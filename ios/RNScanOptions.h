#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/**
 * Typed mirror of the JS-side `ScanReceiptOptions`. Built by
 * `+optionsFromDictionary:` from the raw `NSDictionary` the bridge hands us.
 *
 * Field set must stay in sync with `src/types.ts`. The JS layer fills in
 * defaults via `DEFAULT_SCAN_OPTIONS` *before* dispatch, so missing keys
 * here represent a contract violation rather than a "use the default" signal
 * — `+optionsFromDictionary:` still applies sensible defaults defensively.
 */
@interface RNScanOptions : NSObject

/** Acquisition path: `@"camera"` or `@"gallery"`. */
@property (nonatomic, copy)   NSString  *source;
/** Page / multi-select limit. Coerced to `>= 1`. */
@property (nonatomic, assign) NSInteger  maxPages;
/** JPEG quality target in `[0.0, 1.0]`. */
@property (nonatomic, assign) double     quality;
/** Whether to read and forward source EXIF. */
@property (nonatomic, assign) BOOL       includeExif;
/** Whether to forward the GPS dictionary specifically. Effective only when `includeExif` is `YES`. */
@property (nonatomic, assign) BOOL       includeGpsExif;
/** Whether to run on-device OCR (Vision `VNRecognizeTextRequest`). */
@property (nonatomic, assign) BOOL       ocr;
/**
 * Skip the in-package crop editor when document detection confidence is high
 * enough. Effective only for `source: "gallery"` — the camera path uses
 * VisionKit's own confirm UI.
 */
@property (nonatomic, assign) BOOL       cropAutoConfirm;
/** Whether to bake OCR-detected rotation into the output JPEG pixels. */
@property (nonatomic, assign) BOOL       autoRotate;
/** Whether to attach the flat raw EXIF map under `exif.raw`. */
@property (nonatomic, assign) BOOL       includeRawExif;
/**
 * Vision `minimumTextHeight` as a fraction of image height in `(0, 1]`.
 * `0` (the default) means "use the package default" (1/32). Lowering it can
 * recover smaller receipt line items at the cost of more noise.
 * **iOS only** — Android (ML Kit) has no equivalent and ignores this field.
 */
@property (nonatomic, assign) double     minimumTextHeight;

/**
 * Parses an options dictionary received over the React Native bridge.
 *
 * @param dict The raw options dictionary, or any object (defensively
 *             defaulted when not an `NSDictionary`).
 * @return A fully populated `RNScanOptions`. Never `nil`.
 */
+ (instancetype)optionsFromDictionary:(NSDictionary *)dict;
@end

NS_ASSUME_NONNULL_END
