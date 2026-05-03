#import "RNGalleryPickerDelegate.h"
#import "RNImageProcessor.h"
#import "RNOcrProcessor.h"
#import <UIKit/UIKit.h>
#import <PhotosUI/PhotosUI.h>
#import <Vision/Vision.h>
#import <CoreImage/CoreImage.h>
#import <UniformTypeIdentifiers/UniformTypeIdentifiers.h>

// Below this confidence the user sees the crop editor; above it we apply the
// detected corners automatically when cropAutoConfirm is enabled.
static const float kCropAutoConfirmMinConfidence = 0.85f;

static CGImagePropertyOrientation CIOrientationFromUIOrientation(UIImageOrientation o) {
    switch (o) {
        case UIImageOrientationUp:            return kCGImagePropertyOrientationUp;
        case UIImageOrientationDown:          return kCGImagePropertyOrientationDown;
        case UIImageOrientationLeft:          return kCGImagePropertyOrientationLeft;
        case UIImageOrientationRight:         return kCGImagePropertyOrientationRight;
        case UIImageOrientationUpMirrored:    return kCGImagePropertyOrientationUpMirrored;
        case UIImageOrientationDownMirrored:  return kCGImagePropertyOrientationDownMirrored;
        case UIImageOrientationLeftMirrored:  return kCGImagePropertyOrientationLeftMirrored;
        case UIImageOrientationRightMirrored: return kCGImagePropertyOrientationRightMirrored;
    }
}

// RNCropEditorViewController has no header — redeclare its interface here
@interface RNCropEditorViewController : UIViewController
- (instancetype)initWithImage:(UIImage *)image
                      corners:(nullable NSArray<NSValue *> *)corners
                   completion:(void (^)(CGImageRef _Nullable cgImage))completion;
@end

@interface RNGalleryPickerDelegate () <PHPickerViewControllerDelegate>
@property (nonatomic, strong) RNScanOptions                      *options;
@property (nonatomic, weak)   UIViewController                   *presentingVC;
@property (nonatomic, copy)   RNResolveBlock                      resolve;
@property (nonatomic, copy)   RNRejectBlock                       reject;
@property (nonatomic, strong) NSMutableArray<NSDictionary *>     *results;
@property (nonatomic, assign) NSInteger                           pendingCount;
@end

@implementation RNGalleryPickerDelegate

- (instancetype)initWithOptions:(RNScanOptions *)options
       presentingViewController:(UIViewController *)presentingVC
                        resolve:(RNResolveBlock)resolve
                         reject:(RNRejectBlock)reject {
    self = [super init];
    if (self) {
        _options      = options;
        _presentingVC = presentingVC;
        _resolve      = resolve;
        _reject       = reject;
        _results      = [NSMutableArray new];
        _pendingCount = 0;
    }
    return self;
}

- (void)picker:(PHPickerViewController *)picker
didFinishPicking:(NSArray<PHPickerResult *> *)results {

    if (results.count == 0) {
        [picker dismissViewControllerAnimated:YES completion:^{
            self.resolve(@{@"status": @"cancelled", @"images": @[]});
        }];
        return;
    }

    [picker dismissViewControllerAnimated:YES completion:^{
        [RNImageProcessor deletePreviousSessionFiles];
        self.pendingCount = results.count;

        for (PHPickerResult *result in results) {
            [result.itemProvider loadDataRepresentationForTypeIdentifier:UTTypeImage.identifier
                                                       completionHandler:^(NSData *data, NSError *err) {
                if (!data || err) {
                    [self didFinishOneItem:nil];
                    return;
                }
                CGImageSourceRef sourceRef = CGImageSourceCreateWithData(
                    (__bridge CFDataRef)data, NULL);
                UIImage *image = [UIImage imageWithData:data];
                if (!image || !sourceRef) {
                    if (sourceRef) CFRelease(sourceRef);
                    [self didFinishOneItem:nil];
                    return;
                }
                [self detectRectangleAndCrop:image sourceRef:sourceRef];
            }];
        }
    }];
}

- (void)detectRectangleAndCrop:(UIImage *)image sourceRef:(CGImageSourceRef)sourceRef {
    // Batch both requests on one handler so Vision processes the image only once.
    VNDetectDocumentSegmentationRequest *docRequest =
        [VNDetectDocumentSegmentationRequest new];
    VNDetectRectanglesRequest *rectRequest = [VNDetectRectanglesRequest new];
    rectRequest.minimumConfidence  = 0.5;
    rectRequest.maximumObservations = 1;
    // More permissive for perspective-distorted receipts (default is 30°).
    rectRequest.quadratureTolerance = 45;

    // Pass the CGImage + explicit orientation so Vision processes pixels in the
    // same oriented space as image.size. initWithCIImage: ignores the orientation
    // transform embedded by [CIImage initWithImage:], returning landscape coords
    // for portrait UIImages — which would mismatch _corners in the crop editor.
    CGImagePropertyOrientation exifOrientation =
        CIOrientationFromUIOrientation(image.imageOrientation);
    VNImageRequestHandler *handler =
        [[VNImageRequestHandler alloc] initWithCGImage:image.CGImage
                                           orientation:exifOrientation
                                               options:@{}];
    [handler performRequests:@[docRequest, rectRequest] error:nil];

    CGFloat W = image.size.width;
    CGFloat H = image.size.height;

    // Preferred path: run rectangle detection on the clean binary document mask.
    // The mask is already in the oriented image coordinate space, so its normalized
    // results multiply directly to W/H without any extra transform.
    // Fallback: use rectangle detection run directly on the original image.
    VNRectangleObservation *obs = nil;
    VNDocumentObservation *docObs = docRequest.results.firstObject;
    if (docObs) {
        obs = [self rectangleInDocumentMask:docObs.pixelBuffer];
    }
    if (!obs) {
        obs = rectRequest.results.firstObject;
    }

    NSArray<NSValue *> *corners = nil;
    if (obs) {
        corners = @[
            [NSValue valueWithCGPoint:CGPointMake(obs.topLeft.x     * W, obs.topLeft.y     * H)],
            [NSValue valueWithCGPoint:CGPointMake(obs.topRight.x    * W, obs.topRight.y    * H)],
            [NSValue valueWithCGPoint:CGPointMake(obs.bottomRight.x * W, obs.bottomRight.y * H)],
            [NSValue valueWithCGPoint:CGPointMake(obs.bottomLeft.x  * W, obs.bottomLeft.y  * H)],
        ];
    }

    // Skip the crop editor when detection is confident and the caller opted in.
    // Called on a background thread (PHPickerResult completion), so we can do
    // the perspective-correction render here without an extra dispatch.
    if (self.options.cropAutoConfirm && obs && obs.confidence >= kCropAutoConfirmMinConfidence) {
        [self applyCropAndFinishImage:image
                              corners:corners
                            sourceRef:sourceRef];
        return;
    }

    dispatch_async(dispatch_get_main_queue(), ^{
        UIViewController *presentingVC = self.presentingVC;
        if (!presentingVC) {
            if (sourceRef) CFRelease(sourceRef);
            [self didFinishOneItem:nil];
            return;
        }
        RNCropEditorViewController *editor =
            [[RNCropEditorViewController alloc] initWithImage:image
                                                      corners:corners
                                                   completion:^(CGImageRef cropped) {
            // Called from the editor's background dispatch after rendering.
            if (!cropped) {
                if (sourceRef) CFRelease(sourceRef);
                [self didFinishOneItem:nil];
                return;
            }
            [self processAndFinishCGImage:cropped sourceRef:sourceRef];
        }];
        editor.modalPresentationStyle = UIModalPresentationFullScreen;
        [presentingVC presentViewController:editor animated:YES completion:nil];
    });
}

// Runs rectangle detection on the binary document segmentation mask returned by
// VNDetectDocumentSegmentationRequest. The mask is in the same oriented coordinate
// space as the original image, so no orientation correction is needed here and the
// resulting normalized coordinates map directly to the original image's W/H.
- (nullable VNRectangleObservation *)rectangleInDocumentMask:(CVPixelBufferRef)maskBuffer {
    CIImage *maskCI = [CIImage imageWithCVPixelBuffer:maskBuffer];
    VNDetectRectanglesRequest *req = [VNDetectRectanglesRequest new];
    // The mask is a clean binary image: lower threshold is reliable here.
    req.minimumConfidence   = 0.3;
    req.maximumObservations = 1;
    req.quadratureTolerance = 45;
    VNImageRequestHandler *maskHandler = [[VNImageRequestHandler alloc]
        initWithCIImage:maskCI options:@{}];
    [maskHandler performRequests:@[req] error:nil];
    return req.results.firstObject;
}

// Must be called from a background thread.
- (void)applyCropAndFinishImage:(UIImage *)image
                        corners:(NSArray<NSValue *> *)corners
                      sourceRef:(CGImageSourceRef)sourceRef {
    CGImageRef cropped = [RNImageProcessor perspectiveCorrectedCGImage:image corners:corners];
    if (!cropped) {
        if (sourceRef) CFRelease(sourceRef);
        [self didFinishOneItem:nil];
        return;
    }
    [self processAndFinishCGImage:cropped sourceRef:sourceRef];
}

// Encodes, optionally runs OCR, and resolves a single image result.
// Must be called from a background thread; takes ownership of cropped and sourceRef.
- (void)processAndFinishCGImage:(CGImageRef)cropped sourceRef:(CGImageSourceRef)sourceRef {
    NSError *err = nil;
    RNProcessedImage *processed =
        [RNImageProcessor processImage:cropped
                               quality:self.options.quality
                             sourceRef:sourceRef
                          includeExif:self.options.includeExif
                       includeGpsExif:self.options.includeGpsExif
                                error:&err];
    if (sourceRef) CFRelease(sourceRef);
    if (!processed) {
        CGImageRelease(cropped);
        [self didFinishOneItem:nil];
        return;
    }
    NSString *ocrText = nil;
    if (self.options.ocr) {
        UIImage *croppedUIImage = [UIImage imageWithCGImage:cropped];
        ocrText = [RNOcrProcessor recognizeTextInImage:croppedUIImage error:NULL];
    }
    CGImageRelease(cropped);
    NSMutableDictionary *img = [@{
        @"uri":      [@"file://" stringByAppendingString:processed.fileURL.path],
        @"width":    @(processed.width),
        @"height":   @(processed.height),
        @"fileName": processed.fileURL.lastPathComponent,
        @"mimeType": @"image/jpeg",
        @"fileSize": @(processed.fileSize),
    } mutableCopy];
    if (ocrText)            img[@"ocrText"] = ocrText;
    if (processed.exifData) img[@"exif"]    = processed.exifData;
    [self didFinishOneItem:[img copy]];
}

- (void)didFinishOneItem:(nullable NSDictionary *)imageResult {
    dispatch_async(dispatch_get_main_queue(), ^{
        if (imageResult) [self.results addObject:imageResult];
        self.pendingCount--;
        if (self.pendingCount == 0) {
            if (self.results.count > 0) {
                self.resolve(@{@"status": @"success", @"images": [self.results copy]});
            } else {
                self.resolve(@{@"status": @"cancelled", @"images": @[]});
            }
        }
    });
}

@end
