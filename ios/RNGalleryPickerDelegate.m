#import "RNGalleryPickerDelegate.h"
#import "RNImageProcessor.h"
#import "RNOcrProcessor.h"
#import <UIKit/UIKit.h>
#import <PhotosUI/PhotosUI.h>
#import <Vision/Vision.h>
#import <UniformTypeIdentifiers/UniformTypeIdentifiers.h>

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
            NSString *typeIdentifier;
            if (@available(iOS 14, *)) {
                typeIdentifier = UTTypeImage.identifier;
            } else {
                typeIdentifier = @"public.image";
            }

            [result.itemProvider loadDataRepresentationForTypeIdentifier:typeIdentifier
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
    CIImage *ciImage = [[CIImage alloc] initWithImage:image];

    VNDetectRectanglesRequest *request = [[VNDetectRectanglesRequest alloc] init];
    request.minimumConfidence = 0.7;
    request.maximumObservations = 1;

    VNImageRequestHandler *handler =
        [[VNImageRequestHandler alloc] initWithCIImage:ciImage options:@{}];
    [handler performRequests:@[request] error:nil];

    CGFloat W = image.size.width;
    CGFloat H = image.size.height;

    NSArray<NSValue *> *corners = nil;
    VNRectangleObservation *obs = request.results.firstObject;
    if (obs) {
        corners = @[
            [NSValue valueWithCGPoint:CGPointMake(obs.topLeft.x     * W, obs.topLeft.y     * H)],
            [NSValue valueWithCGPoint:CGPointMake(obs.topRight.x    * W, obs.topRight.y    * H)],
            [NSValue valueWithCGPoint:CGPointMake(obs.bottomRight.x * W, obs.bottomRight.y * H)],
            [NSValue valueWithCGPoint:CGPointMake(obs.bottomLeft.x  * W, obs.bottomLeft.y  * H)],
        ];
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
            if (!cropped) {
                if (sourceRef) CFRelease(sourceRef);
                [self didFinishOneItem:nil];
                return;
            }
            dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
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
                    NSError *ocrErr = nil;
                    ocrText = [RNOcrProcessor recognizeTextInImage:croppedUIImage error:&ocrErr];
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
            });
        }];
        editor.modalPresentationStyle = UIModalPresentationFullScreen;
        [presentingVC presentViewController:editor animated:YES completion:nil];
    });
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
