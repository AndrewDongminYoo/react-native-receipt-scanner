#import "RNDocumentCameraDelegate.h"
#import "RNImageProcessor.h"
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
            CGImageRef cgImage  = normalized.CGImage;

            NSError *err = nil;
            // Camera images have no original EXIF source; pass NULL for sourceRef
            RNProcessedImage *processed =
                [RNImageProcessor processImage:cgImage
                                       quality:strongSelf.options.quality
                                     sourceRef:NULL
                                  includeExif:NO
                               includeGpsExif:NO
                                        error:&err];
            if (!processed) continue;

            NSString *ocrText = nil;
            if (strongSelf.options.ocr) {
                NSError *ocrErr = nil;
                ocrText = [RNOcrProcessor recognizeTextInImage:normalized error:&ocrErr];
            }

            NSMutableDictionary *img = [@{
                @"uri":      [@"file://" stringByAppendingString:processed.fileURL.path],
                @"width":    @(processed.width),
                @"height":   @(processed.height),
                @"fileName": processed.fileURL.lastPathComponent,
                @"mimeType": @"image/jpeg",
                @"fileSize": @(processed.fileSize),
            } mutableCopy];
            if (ocrText) img[@"ocrText"] = ocrText;
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
