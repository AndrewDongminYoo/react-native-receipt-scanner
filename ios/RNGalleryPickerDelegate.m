#import "RNGalleryPickerDelegate.h"
#import "RNImageProcessor.h"
#import "RNOcrProcessor.h"
#import <UIKit/UIKit.h>
#import <PhotosUI/PhotosUI.h>
#import <Photos/Photos.h>
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

static VNDetectRectanglesRequest *MakeReceiptRectangleRequest(float minimumConfidence) {
    VNDetectRectanglesRequest *req = [VNDetectRectanglesRequest new];
    req.minimumConfidence   = minimumConfidence;
    req.maximumObservations = 1;
    // More permissive for perspective-distorted receipts (default is 30°).
    req.quadratureTolerance = 45;
    return req;
}

// Wraps a CGImageSourceRef so ARC manages its lifetime instead of manual CFRelease calls.
// Without this, every early-return path must remember to call CFRelease — a leak hazard.
@interface RNCGImageSourceHolder : NSObject
@property (nonatomic, readonly) CGImageSourceRef ref;
- (instancetype)initWithRef:(CGImageSourceRef)ref NS_DESIGNATED_INITIALIZER;
- (instancetype)init NS_UNAVAILABLE;
@end

@implementation RNCGImageSourceHolder
- (instancetype)initWithRef:(CGImageSourceRef)ref {
    self = [super init];
    _ref = ref;
    return self;
}
- (void)dealloc { if (_ref) CFRelease(_ref); }
@end

#import "RNCropEditorViewController.h"

@interface RNGalleryPickerDelegate () <PHPickerViewControllerDelegate>
@property (nonatomic, strong) RNScanOptions                      *options;
@property (nonatomic, weak)   UIViewController                   *presentingVC;
@property (nonatomic, assign) BOOL                                hasLibraryAccess;
@property (nonatomic, copy)   RNResolveBlock                      resolve;
@property (nonatomic, copy)   RNRejectBlock                       reject;
@property (nonatomic, strong) NSMutableArray<NSDictionary *>     *results;
@property (nonatomic, assign) NSInteger                           pendingCount;
@end

@implementation RNGalleryPickerDelegate

- (instancetype)initWithOptions:(RNScanOptions *)options
       presentingViewController:(UIViewController *)presentingVC
               hasLibraryAccess:(BOOL)hasLibraryAccess
                        resolve:(RNResolveBlock)resolve
                         reject:(RNRejectBlock)reject {
    self = [super init];
    if (self) {
        _options           = options;
        _presentingVC      = presentingVC;
        _hasLibraryAccess  = hasLibraryAccess;
        _resolve           = resolve;
        _reject            = reject;
        _results           = [NSMutableArray new];
        _pendingCount      = 0;
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
            // PHAsset fetch is synchronous for local identifiers — safe to call on main thread.
            NSString *earlyOrigin = [self originForPickerResult:result];

            [result.itemProvider loadDataRepresentationForTypeIdentifier:UTTypeImage.identifier
                                                       completionHandler:^(NSData *data, NSError *err) {
                if (!data || err) {
                    [self didFinishOneItem:nil];
                    return;
                }
                CGImageSourceRef rawRef = CGImageSourceCreateWithData(
                    (__bridge CFDataRef)data, NULL);
                // Wrap immediately so ARC handles release even on the early-return paths below.
                RNCGImageSourceHolder *sourceHolder = rawRef
                    ? [[RNCGImageSourceHolder alloc] initWithRef:rawRef] : nil;
                UIImage *image = [UIImage imageWithData:data];
                if (!image || !sourceHolder) {
                    [self didFinishOneItem:nil];
                    return;
                }
                [self detectRectangleAndCrop:image sourceHolder:sourceHolder earlyOrigin:earlyOrigin];
            }];
        }
    }];
}

// Returns a definitive imageOrigin from the Photos library if library access is available,
// or nil if origin cannot be determined at this stage (EXIF heuristics run later).
- (nullable NSString *)originForPickerResult:(PHPickerResult *)result {
    if (!self.hasLibraryAccess || !result.assetIdentifier) return nil;

    PHFetchResult<PHAsset *> *fetchResult =
        [PHAsset fetchAssetsWithLocalIdentifiers:@[result.assetIdentifier] options:nil];
    PHAsset *asset = fetchResult.firstObject;
    if (!asset) return nil;

    if (asset.mediaSubtypes & PHAssetMediaSubtypePhotoScreenshot) {
        return @"screenshot";
    }
    // No dedicated "download" subtype in Photos framework.
    // Fall through to EXIF heuristics in processAndFinishCGImage:.
    return nil;
}

// Classifies imageOrigin from three EXIF indicators.
// dateTimeOriginal is the strongest camera signal — it records the shutter moment and
// is absent from screenshots and most web-downloaded images.
// make+model together (without dateTime) also indicates a camera-originated image.
// Complete absence of all three → "download".
// One field present but not the others → ambiguous (nil).
static NSString * _Nullable OriginFromExifFields(NSString *make, NSString *model, NSString *dateTime) {
    if (dateTime)          return @"camera";   // shutter timestamp — strong camera signal
    if (make && model)     return @"camera";   // device IDs without timestamp — still camera-like
    if (!make && !model)   return @"download"; // no camera metadata at all
    return nil;                                // make XOR model, no dateTime — ambiguous
}

// Uses already-extracted EXIF dict (avoids re-reading CGImageSourceRef when includeExif:YES).
- (nullable NSString *)detectOriginFromExifData:(nullable NSDictionary *)exifData {
    if (!exifData) return nil;
    return OriginFromExifFields(exifData[@"make"], exifData[@"model"], exifData[@"dateTimeOriginal"]);
}

// Fallback used when includeExif:NO left processed.exifData nil.
// Reads source properties directly so origin detection is independent of the includeExif option.
- (nullable NSString *)detectOriginFromSourceRef:(CGImageSourceRef)sourceRef {
    if (!sourceRef) return nil;
    NSDictionary *props = (__bridge_transfer NSDictionary *)
        CGImageSourceCopyPropertiesAtIndex(sourceRef, 0, NULL);
    NSDictionary *tiff = props[(NSString *)kCGImagePropertyTIFFDictionary];
    NSDictionary *exif = props[(NSString *)kCGImagePropertyExifDictionary];
    return OriginFromExifFields(
        tiff[(NSString *)kCGImagePropertyTIFFMake],
        tiff[(NSString *)kCGImagePropertyTIFFModel],
        exif[(NSString *)kCGImagePropertyExifDateTimeOriginal]);
}

- (void)detectRectangleAndCrop:(UIImage *)image
                  sourceHolder:(RNCGImageSourceHolder *)sourceHolder
                   earlyOrigin:(nullable NSString *)earlyOrigin {
    NSError *visionError = nil;
    float confidence = 0;
    NSArray<NSValue *> *corners = [self detectCornersForImage:image
                                                   confidence:&confidence
                                                        error:&visionError];
    if (visionError) {
        NSLog(@"[ReceiptScanner] Vision request failed: %@", visionError);
    }

    if (self.options.cropAutoConfirm && corners && confidence >= kCropAutoConfirmMinConfidence) {
        [self applyCropAndFinishImage:image corners:corners sourceHolder:sourceHolder earlyOrigin:earlyOrigin];
        return;
    }

    dispatch_async(dispatch_get_main_queue(), ^{
        UIViewController *presentingVC = self.presentingVC;
        if (!presentingVC) {
            [self didFinishOneItem:nil];
            return;
        }
        RNCropEditorViewController *editor =
            [[RNCropEditorViewController alloc] initWithImage:image
                                                      corners:corners
                                                   completion:^(CGImageRef cropped) {
            // Called from the editor's background dispatch after rendering.
            if (!cropped) {
                [self didFinishOneItem:nil];
                return;
            }
            [self processAndFinishCGImage:cropped sourceHolder:sourceHolder earlyOrigin:earlyOrigin];
        }];
        editor.modalPresentationStyle = UIModalPresentationFullScreen;
        [presentingVC presentViewController:editor animated:YES completion:nil];
    });
}

// Returns corners in CIImage coordinate space (origin bottom-left, Y upward), or nil if
// nothing was detected. Sets *confidence to the detection confidence, or 0 on failure.
- (nullable NSArray<NSValue *> *)detectCornersForImage:(UIImage *)image
                                            confidence:(float *)confidence
                                                 error:(NSError **)error {
    VNDetectDocumentSegmentationRequest *docRequest = [VNDetectDocumentSegmentationRequest new];
    VNDetectRectanglesRequest *rectRequest = MakeReceiptRectangleRequest(0.5f);

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
    [handler performRequests:@[docRequest, rectRequest] error:error];

    CGFloat W = image.size.width;
    CGFloat H = image.size.height;

    // Preferred: run rectangle detection on the clean binary document mask.
    // The mask is already in the oriented image coordinate space, so its normalized
    // results multiply directly to W/H without any extra transform.
    // Fallback: rectangle detection on the original image.
    VNRectangleObservation *obs = nil;
    float detectedConfidence = 0;

    VNDocumentObservation *docObs = docRequest.results.firstObject;
    if (docObs) {
        obs = [self rectangleInDocumentMask:docObs.pixelBuffer];
        if (obs) {
            // Use document segmentation confidence — more meaningful than the mask
            // rect confidence since the mask is a clean binary image.
            detectedConfidence = docObs.confidence;
        }
    }
    if (!obs) {
        obs = rectRequest.results.firstObject;
        if (obs) detectedConfidence = obs.confidence;
    }

    if (confidence) *confidence = detectedConfidence;
    if (!obs) return nil;

    return @[
        [NSValue valueWithCGPoint:CGPointMake(obs.topLeft.x     * W, obs.topLeft.y     * H)],
        [NSValue valueWithCGPoint:CGPointMake(obs.topRight.x    * W, obs.topRight.y    * H)],
        [NSValue valueWithCGPoint:CGPointMake(obs.bottomRight.x * W, obs.bottomRight.y * H)],
        [NSValue valueWithCGPoint:CGPointMake(obs.bottomLeft.x  * W, obs.bottomLeft.y  * H)],
    ];
}

// Runs rectangle detection on the binary document segmentation mask returned by
// VNDetectDocumentSegmentationRequest. The mask is in the same oriented coordinate
// space as the original image, so no orientation correction is needed here and the
// resulting normalized coordinates map directly to the original image's W/H.
- (nullable VNRectangleObservation *)rectangleInDocumentMask:(CVPixelBufferRef)maskBuffer {
    CIImage *maskCI = [CIImage imageWithCVPixelBuffer:maskBuffer];
    // Lower threshold: the mask is a clean binary image, so even 0.3-confidence
    // rectangles reliably correspond to real document boundaries.
    VNDetectRectanglesRequest *req = MakeReceiptRectangleRequest(0.3f);
    VNImageRequestHandler *maskHandler = [[VNImageRequestHandler alloc]
        initWithCIImage:maskCI options:@{}];
    [maskHandler performRequests:@[req] error:nil];
    return req.results.firstObject;
}

// Must be called from a background thread.
- (void)applyCropAndFinishImage:(UIImage *)image
                        corners:(NSArray<NSValue *> *)corners
                   sourceHolder:(RNCGImageSourceHolder *)sourceHolder
                    earlyOrigin:(nullable NSString *)earlyOrigin {
    CGImageRef cropped = [RNImageProcessor perspectiveCorrectedCGImage:image corners:corners];
    if (!cropped) {
        [self didFinishOneItem:nil];
        return;
    }
    [self processAndFinishCGImage:cropped sourceHolder:sourceHolder earlyOrigin:earlyOrigin];
}

// Encodes, optionally runs OCR, and resolves a single image result.
// Must be called from a background thread; takes ownership of cropped.
- (void)processAndFinishCGImage:(CGImageRef)cropped
                   sourceHolder:(RNCGImageSourceHolder *)sourceHolder
                    earlyOrigin:(nullable NSString *)earlyOrigin {
    NSError *err = nil;
    RNProcessedImage *processed =
        [RNImageProcessor processImage:cropped
                               quality:self.options.quality
                             sourceRef:sourceHolder.ref
                          includeExif:self.options.includeExif
                       includeGpsExif:self.options.includeGpsExif
                                error:&err];
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

    // Priority: PHAsset subtype → extracted exifData → raw source properties → "unknown".
    // The source-ref read is gated on exifData being nil so we don't decode the same TIFF/EXIF
    // dictionaries twice when extraction already ran (includeExif:YES).
    NSString *imageOrigin = earlyOrigin
        ?: [self detectOriginFromExifData:processed.exifData]
        ?: (processed.exifData == nil ? [self detectOriginFromSourceRef:sourceHolder.ref] : nil)
        ?: @"unknown";

    NSMutableDictionary *img = [@{
        // absoluteString gives a properly percent-encoded file:// URI, unlike
        // manually appending .path which breaks on paths containing spaces or
        // non-ASCII characters (e.g. usernames with special characters).
        @"uri":         processed.fileURL.absoluteString,
        @"width":       @(processed.width),
        @"height":      @(processed.height),
        @"fileName":    processed.fileURL.lastPathComponent,
        @"mimeType":    @"image/jpeg",
        @"fileSize":    @(processed.fileSize),
        @"imageOrigin": imageOrigin,
    } mutableCopy];
    // length > 0 distinguishes "OCR ran, found text" from "OCR ran, empty result".
    if (ocrText.length > 0)  img[@"ocrText"] = ocrText;
    if (processed.exifData)  img[@"exif"]    = processed.exifData;
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
