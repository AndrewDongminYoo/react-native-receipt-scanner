#import "RNDocumentCameraDelegate.h"
#import "RNImageProcessor.h"
#import "RNOcrGeometry.h"
#import "RNOcrProcessor.h"
#import <UIKit/UIKit.h>
#import <VisionKit/VisionKit.h>

@interface RNDocumentCameraDelegate () <VNDocumentCameraViewControllerDelegate>
@property (nonatomic, strong) RNScanOptions  *options;
@property (nonatomic, copy)   RNResolveBlock  resolve;
@property (nonatomic, copy)   RNRejectBlock   reject;
@end

@implementation RNDocumentCameraDelegate

- (instancetype)initWithOptions:(RNScanOptions *)options
                        resolve:(RNResolveBlock)resolve
                         reject:(RNRejectBlock)reject {
    self = [super init];
    if (self) {
        _options = options;
        _resolve = resolve;
        _reject  = reject;
    }
    return self;
}

- (void)documentCameraViewController:(VNDocumentCameraViewController *)controller
                  didFinishWithScan:(VNDocumentCameraScan *)scan {
    [controller dismissViewControllerAnimated:YES completion:nil];

    __weak typeof(self) weakSelf = self;
    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
        __strong typeof(weakSelf) strongSelf = weakSelf;
        if (!strongSelf) return;

        [RNImageProcessor deletePreviousSessionFiles];

        NSMutableArray<NSDictionary *> *images = [NSMutableArray new];
        NSInteger limit = MIN((NSInteger)scan.pageCount, strongSelf.options.maxPages);

        for (NSInteger i = 0; i < limit; i++) {
            UIImage *page       = [scan imageOfPageAtIndex:i];
            UIImage *normalized = [RNImageProcessor normalizeOrientation:page];

            // OCR + rotation detection runs *before* JPEG encoding so we can
            // bake the chosen rotation into the output pixels (autoRotate path).
            NSString *ocrText = nil;
            NSInteger rotationDegrees = 0;
            double ocrMeanConfidence = 0.0;
            NSArray<NSDictionary *> *ocrLines = nil;
            CGSize ocrPassSize = CGSizeZero;
            if (strongSelf.options.ocr) {
                NSError *ocrErr = nil;
                RNOcrResult *ocr =
                    [RNOcrProcessor recognizeAndDetectRotationInImage:normalized
                                                    minimumTextHeight:strongSelf.options.minimumTextHeight
                                                                error:&ocrErr];
                if (ocr) {
                    ocrText = ocr.text;
                    rotationDegrees = ocr.rotationDegrees;
                    ocrMeanConfidence = ocr.meanConfidence;
                    ocrLines = ocr.lines;
                    ocrPassSize = ocr.passSize;
                }
            }

            CGImageRef sourceCG = normalized.CGImage;
            CGImageRef rotatedCG = NULL;
            if (strongSelf.options.autoRotate && rotationDegrees != 0) {
                rotatedCG = [RNImageProcessor cgImageByRotating:sourceCG
                                                        degrees:rotationDegrees];
            }
            CGImageRef encodeCG = rotatedCG ?: sourceCG;

            // Vision measured the boxes on the frame the winning pass ran on. When
            // that same rotation gets baked into the output the two frames agree;
            // otherwise the output stays un-rotated and the boxes need it undone —
            // which is the equal clockwise turn, since the pixel rotation is CCW.
            if (strongSelf.options.ocrGeometry && ocrLines.count > 0) {
                ocrLines = [RNOcrGeometry linesByRotating:ocrLines
                                                frameSize:ocrPassSize
                                         clockwiseDegrees:(rotatedCG ? 0 : rotationDegrees)];
            } else {
                ocrLines = nil;
            }

            NSError *err = nil;
            // sourceRef is NULL — VisionKit does not expose the original shutter EXIF.
            // RNImageProcessor synthesizes make/model/dateTime from UIDevice in this case.
            RNProcessedImage *processed =
                [RNImageProcessor processImage:encodeCG
                                       quality:strongSelf.options.quality
                                     sourceRef:NULL
                                  includeExif:strongSelf.options.includeExif
                               includeGpsExif:NO
                               includeRawExif:strongSelf.options.includeRawExif
                                        error:&err];
            if (rotatedCG) CGImageRelease(rotatedCG);
            if (!processed) continue;

            NSMutableDictionary *img = [@{
                // absoluteString gives a properly percent-encoded file:// URI; manually
                // appending .path breaks on paths with spaces or non-ASCII (e.g. usernames
                // with special characters in the cache directory).
                @"uri":         processed.fileURL.absoluteString,
                @"width":       @(processed.width),
                @"height":      @(processed.height),
                @"fileName":    processed.fileURL.lastPathComponent,
                @"mimeType":    @"image/jpeg",
                @"fileSize":    @(processed.fileSize),
                @"imageOrigin": @"camera",
            } mutableCopy];
            if (ocrText) {
                img[@"ocrText"] = ocrText;
                // Surface the iOS-computed mean per-line confidence so the JS
                // layer exposes ocrQuality.confidence on both platforms
                // Reporting only — not used for routing.
                img[@"ocrQuality"] = @{@"confidence": @(ocrMeanConfidence)};
            }
            if (ocrLines.count > 0) img[@"ocrLines"] = ocrLines;
            if (processed.exifData)  img[@"exif"]    = processed.exifData;
            [images addObject:[img copy]];
        }

        dispatch_async(dispatch_get_main_queue(), ^{
            if (images.count == 0 && limit > 0) {
                strongSelf.reject(@"PROCESSING_FAILED", @"All scanned pages failed to process", nil);
            } else {
                strongSelf.resolve(@{@"status": @"success", @"images": images});
            }
        });
    });
}

- (void)documentCameraViewControllerDidCancel:(VNDocumentCameraViewController *)controller {
    [controller dismissViewControllerAnimated:YES completion:nil];
    self.resolve(@{@"status": @"cancelled", @"images": @[]});
}

- (void)documentCameraViewController:(VNDocumentCameraViewController *)controller
                  didFailWithError:(NSError *)error {
    [controller dismissViewControllerAnimated:YES completion:nil];
    self.reject(@"CAMERA_FAILED", error.localizedDescription, error);
}

@end
