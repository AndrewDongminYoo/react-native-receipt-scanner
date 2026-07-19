#import "RNGalleryPickerDelegate.h"
#import "RNImageProcessor.h"
#import "RNOcrGeometry.h"
#import "RNOcrProcessor.h"
#import "RNQuadGeometry.h"
#import <UIKit/UIKit.h>
#import <PhotosUI/PhotosUI.h>
#import <Photos/Photos.h>
#import <Vision/Vision.h>
#import <CoreImage/CoreImage.h>
#import <UniformTypeIdentifiers/UniformTypeIdentifiers.h>

#import "RNCropEditorViewController.h"

// Below this confidence the user sees the crop editor; above it we apply the
// detected corners automatically when cropAutoConfirm is enabled.
static const float kCropAutoConfirmMinConfidence = 0.85f;

// A detected quadrilateral below this confidence is discarded rather than seeded
// into the crop editor. VNDetectDocumentSegmentationRequest can return a
// near-zero-confidence quad for hard inputs — e.g. a receipt embedded in a
// screenshot's UI chrome, where the detector latches onto the thin gap above the
// receipt (observed confidence ≈ 0.004). Such a quad can look geometrically benign
// (mild trapezoid), so RNQuadGeometry does NOT catch it — only this floor does.
// Discarding it lets the editor fall back to its 10% inset default (mirrors Android
// quadFromTextBlocks → applyDefaultInsetCorners).
//
// PROVISIONAL (see docs/specs/threshold-calibration.md). Set low — the observed
// noise sits at ≈0.004, while real (even imperfect/angled) detections measured far
// higher — so borderline-but-usable detections still seed the editor; degenerate
// shapes are caught separately by RNQuadGeometry regardless of confidence.
static const float kDetectionMinConfidence = 0.1f;

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

@interface RNGalleryPickerDelegate () <PHPickerViewControllerDelegate>
@property (nonatomic, strong) RNScanOptions                      *options;
@property (nonatomic, weak)   UIViewController                   *presentingVC;
@property (nonatomic, assign) BOOL                                hasLibraryAccess;
@property (nonatomic, copy)   RNResolveBlock                      resolve;
@property (nonatomic, copy)   RNRejectBlock                       reject;
@property (nonatomic, strong) NSMutableArray<NSDictionary *>     *results;
// Per-photo pipeline is serialized: only one crop editor presented at a time.
// See `processNextQueuedItem` and the AGENTS.md anti-pattern entry on
// concurrent `presentViewController:` calls.
@property (nonatomic, strong) NSArray<PHPickerResult *>          *queuedItems;
@property (nonatomic, assign) NSInteger                           queueIndex;
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
        _queuedItems       = @[];
        _queueIndex        = 0;
    }
    return self;
}

- (void)picker:(PHPickerViewController *)picker
didFinishPicking:(NSArray<PHPickerResult *> *)results {

    if (results.count == 0) {
        [picker dismissViewControllerAnimated:YES completion:^{
            self.resolve(@{ @"status": @"cancelled", @"images": @[] });
        }];
        return;
    }

    [picker dismissViewControllerAnimated:YES completion:^{
        [RNImageProcessor deletePreviousSessionFiles];
        // Serial queue — see AGENTS.md anti-pattern: concurrent presentViewController:
        // on the same presenter is silently rejected by UIKit.
        self.queuedItems = results;
        self.queueIndex  = 0;
        [self processNextQueuedItem];
    }];
}

// FUTURE: `loadDataRepresentation` calls are independent and could be
// prefetched in parallel; only the editor presentation must serialize.
// On 6-photo iCloud Photos batches this would save 200-500 ms wall-clock.
- (void)processNextQueuedItem {
    if (self.queueIndex >= (NSInteger)self.queuedItems.count) {
        if (self.results.count > 0) {
            self.resolve(@{ @"status": @"success",   @"images": [self.results copy] });
        } else {
            self.resolve(@{ @"status": @"cancelled", @"images": @[] });
        }
        return;
    }

    PHPickerResult *item = self.queuedItems[self.queueIndex];
    self.queueIndex++;

    // PHAsset fetch is synchronous for local identifiers — safe on main thread.
    NSString *earlyOrigin = [self originForPickerResult:item];

    [item.itemProvider loadDataRepresentationForTypeIdentifier:UTTypeImage.identifier
                                             completionHandler:^(NSData *data, NSError *err) {
        if (!data || err) {
            [self didFinishOneItem:nil];
            return;
        }
        CGImageSourceRef rawRef = CGImageSourceCreateWithData((__bridge CFDataRef)data, NULL);
        // Wrap immediately so ARC handles release even on the early-return paths below.
        RNCGImageSourceHolder *sourceHolder = rawRef ? [[RNCGImageSourceHolder alloc] initWithRef:rawRef] : nil;
        UIImage *image = [UIImage imageWithData:data];
        if (!image || !sourceHolder) {
            [self didFinishOneItem:nil];
            return;
        }
        [self detectRectangleAndCrop:image sourceHolder:sourceHolder earlyOrigin:earlyOrigin];
    }];
}

// Returns a definitive imageOrigin from the Photos library if library access is available,
// or nil if origin cannot be determined at this stage (EXIF heuristics run later).
- (nullable NSString *)originForPickerResult:(PHPickerResult *)result {
    if (!self.hasLibraryAccess || !result.assetIdentifier) return nil;

    PHFetchResult<PHAsset *> *fetchResult = [PHAsset fetchAssetsWithLocalIdentifiers:@[result.assetIdentifier] options:nil];
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
    NSDictionary *props = (__bridge_transfer NSDictionary *) CGImageSourceCopyPropertiesAtIndex(sourceRef, 0, NULL);
    NSDictionary *tiff = props[(NSString *)kCGImagePropertyTIFFDictionary];
    NSDictionary *exif = props[(NSString *)kCGImagePropertyExifDictionary];
    return OriginFromExifFields(
        tiff[(NSString *)kCGImagePropertyTIFFMake],
        tiff[(NSString *)kCGImagePropertyTIFFModel],
        exif[(NSString *)kCGImagePropertyExifDateTimeOriginal]
    );
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
        RNCropEditorViewController *editor = [[RNCropEditorViewController alloc] initWithImage:image
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
    CGImagePropertyOrientation exifOrientation = CIOrientationFromUIOrientation(image.imageOrientation);
    VNImageRequestHandler *handler = [[VNImageRequestHandler alloc] initWithCGImage:image.CGImage
                                                                        orientation:exifOrientation
                                                                            options:@{}];
    [handler performRequests:@[docRequest, rectRequest] error:error];

    CGFloat W = image.size.width;
    CGFloat H = image.size.height;

    // Preferred: VNDetectDocumentSegmentationRequest is trained for documents and returns
    // VNRectangleObservation corners that are more confident on receipts than the generic
    // rectangle detector. isKindOfClass: guards against future SDK revisions changing the
    // result subclass.
    // Fallback: VNDetectRectanglesRequest on the original image.
    VNRectangleObservation *obs = nil;
    float detectedConfidence = 0;

    // kDetectionMinConfidence gates only the doc-segmentation candidate:
    // VNDetectDocumentSegmentationRequest exposes no minimumConfidence and can return a
    // sub-floor quad (e.g. the gap above a receipt in a screenshot) that would otherwise be
    // seeded into the editor unconditionally. The rectangle detector already enforces its own
    // floor (MakeReceiptRectangleRequest sets minimumConfidence = 0.5), so a non-nil rectObs
    // is always above the floor and needs no second check here. When both miss we fall through
    // to the editor's 10% inset default.
    id docResult = docRequest.results.firstObject;
    VNRectangleObservation *docObs =
        [docResult isKindOfClass:[VNRectangleObservation class]] ? (VNRectangleObservation *)docResult : nil;
    VNRectangleObservation *rectObs = rectRequest.results.firstObject;

    if (docObs && docObs.confidence >= kDetectionMinConfidence) {
        obs = docObs;
        detectedConfidence = obs.confidence;
    }
    if (!obs && rectObs) {
        obs = rectObs;
        detectedConfidence = rectObs.confidence;
    }

#if DEBUG
    // Calibration diagnostic for kDetectionMinConfidence (docs/specs/threshold-calibration.md).
    // Logs both detectors' confidence and the decision so the floor can be fitted to the
    // real-device distribution in the borderline band instead of a guessed value.
    NSLog(@"[ReceiptScanner] detect confidence doc=%.3f rect=%.3f floor=%.2f -> %@",
          docObs ? docObs.confidence : -1.0,
          rectObs ? rectObs.confidence : -1.0,
          kDetectionMinConfidence,
          obs ? (obs == docObs ? @"doc" : @"rect") : @"none(inset fallback)");
#endif

    if (confidence) *confidence = detectedConfidence;
    if (!obs) return nil;

    NSArray<NSValue *> *detected = @[
        [NSValue valueWithCGPoint:CGPointMake(obs.topLeft.x     * W, obs.topLeft.y     * H)],
        [NSValue valueWithCGPoint:CGPointMake(obs.topRight.x    * W, obs.topRight.y    * H)],
        [NSValue valueWithCGPoint:CGPointMake(obs.bottomRight.x * W, obs.bottomRight.y * H)],
        [NSValue valueWithCGPoint:CGPointMake(obs.bottomLeft.x  * W, obs.bottomLeft.y  * H)],
    ];
    // Distorted/degenerate detected quad → discard so the editor uses its inset default.
    // Reset *confidence to honor the "0 on failure" contract — a discarded quad is a failure.
    if ([RNQuadGeometry isDistorted:detected]) {
        if (confidence) *confidence = 0;
        return nil;
    }
    return detected;
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
    // OCR + rotation detection runs *before* JPEG encoding so the chosen
    // rotation can be baked into the output pixels (autoRotate path).
    NSString *ocrText = nil;
    NSInteger rotationDegrees = 0;
    double ocrMeanConfidence = 0.0;
    NSArray<NSDictionary *> *ocrLines = nil;
    CGSize ocrPassSize = CGSizeZero;
    if (self.options.ocr) {
        UIImage *croppedUIImage = [UIImage imageWithCGImage:cropped];
        RNOcrResult *ocr =
            [RNOcrProcessor recognizeAndDetectRotationInImage:croppedUIImage
                                            minimumTextHeight:self.options.minimumTextHeight
                                                        error:NULL];
        if (ocr) {
            ocrText = ocr.text;
            rotationDegrees = ocr.rotationDegrees;
            ocrMeanConfidence = ocr.meanConfidence;
            ocrLines = ocr.lines;
            ocrPassSize = ocr.passSize;
        }
    }

    CGImageRef encodeCG = cropped;
    CGImageRef rotatedCG = NULL;
    if (self.options.autoRotate && rotationDegrees != 0) {
        rotatedCG = [RNImageProcessor cgImageByRotating:cropped degrees:rotationDegrees];
        if (rotatedCG) encodeCG = rotatedCG;
    }
    BOOL rotationBaked = (rotatedCG != NULL);

    NSError *err = nil;
    RNProcessedImage *processed = [RNImageProcessor processImage:encodeCG
                                                         quality:self.options.quality
                                                       sourceRef:sourceHolder.ref
                                                    includeExif:self.options.includeExif
                                                 includeGpsExif:self.options.includeGpsExif
                                                 includeRawExif:self.options.includeRawExif
                                                          error:&err];
    if (rotatedCG) CGImageRelease(rotatedCG);
    CGImageRelease(cropped);
    if (!processed) {
        [self didFinishOneItem:nil];
        return;
    }

    // Vision measured the boxes on the frame the winning pass ran on. When that
    // same rotation gets baked into the output the two frames agree; otherwise
    // the output stays un-rotated and the boxes need it undone — which is the
    // equal clockwise turn, since the pixel rotation is CCW.
    if (self.options.ocrGeometry && ocrLines.count > 0) {
        ocrLines = [RNOcrGeometry linesByRotating:ocrLines
                                        frameSize:ocrPassSize
                                 clockwiseDegrees:(rotationBaked ? 0 : rotationDegrees)
                                       outputSize:CGSizeMake(processed.width, processed.height)];
    } else {
        ocrLines = nil;
    }

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
    if (ocrText.length > 0) {
        img[@"ocrText"] = ocrText;
        // Surface the iOS-computed mean per-line confidence so the JS layer
        // exposes ocrQuality.confidence on both platforms. Reporting only.
        img[@"ocrQuality"] = @{@"confidence": @(ocrMeanConfidence)};
    }
    if (ocrLines.count > 0) img[@"ocrLines"] = ocrLines;
    if (processed.exifData)  img[@"exif"]    = processed.exifData;
    [self didFinishOneItem:[img copy]];
}

// Called once per queued photo. `imageResult == nil` means the user cancelled
// that photo's crop editor — skip it and continue the batch, matching the
// Android CropEditorActivity flow.
- (void)didFinishOneItem:(nullable NSDictionary *)imageResult {
    dispatch_async(dispatch_get_main_queue(), ^{
        if (imageResult) [self.results addObject:imageResult];
        [self processNextQueuedItem];
    });
}

@end

