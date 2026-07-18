#import <Foundation/Foundation.h>
#import <CoreGraphics/CoreGraphics.h>

@class UIImage;

NS_ASSUME_NONNULL_BEGIN

/**
 * Result of `+[RNOcrProcessor recognizeAndDetectRotationInImage:error:]`.
 * Field shape feeds directly into `ReceiptImage.ocrText` and
 * `ReceiptImage.ocrQuality` in `src/types.ts`.
 */
@interface RNOcrResult : NSObject
/**
 * Recognized text joined by newlines (in the chosen rotation). One line
 * per `VNRecognizedTextObservation`.
 */
@property (nonatomic, copy)   NSString *text;
/**
 * Detected rotation in degrees (one of `0`, `90`, `180`, `270`) — the
 * rotation that maximised OCR confidence. The caller is expected to apply
 * this rotation to the output pixels (see `docs/specs/ocr-orientation-correction.md`).
 */
@property (nonatomic, assign) NSInteger rotationDegrees;
/**
 * Mean per-line confidence in `[0, 1]` for the chosen rotation. Forwarded
 * to JS via `ocrQuality.confidence`.
 */
@property (nonatomic, assign) double meanConfidence;
/**
 * Per-line geometry for the chosen rotation, shaped like the JS `OcrLine`
 * (`text`, `frame`, `confidence`). Coordinates are top-left pixels of
 * `passSize` — i.e. of the *rotated* image whenever `rotationDegrees` is
 * non-zero, since that is the frame Vision measured them on. Callers that do
 * not bake the rotation into their output must undo it via
 * `+[RNOcrGeometry linesByRotating:frameSize:clockwiseDegrees:]`.
 *
 * Always collected; the delegates only forward it when `options.ocrGeometry`
 * is set. Lines with empty text or a degenerate box are omitted, so this does
 * not line up index-wise with the newline-joined `text`.
 */
@property (nonatomic, copy) NSArray<NSDictionary *> *lines;
/** Pixel size of the image the chosen pass ran on — the frame `lines` sit in. */
@property (nonatomic, assign) CGSize passSize;
@end

/**
 * On-device OCR via Vision `VNRecognizeTextRequest`, plus a 4-pass
 * rotation-detection pipeline.
 *
 * Languages: `ko-KR` + `en-US`. The package targets iOS 16+ — Korean
 * recognition was added in iOS 16 and there is no Latin-only fallback
 * (see ADR-006).
 *
 * Threading: blocking on `VNImageRequestHandler performRequests:`. Call
 * from a background queue.
 */
@interface RNOcrProcessor : NSObject

/**
 * Run text recognition with `0° / 90° / 180° / 270°` rotation detection.
 *
 * Algorithm (see `docs/specs/ocr-orientation-correction.md` for the full tree):
 *  - Portrait (aspect ≤ 1): Pass 1 (`0°` accurate) → optional `180°` fast
 *    probe → optional Pass 3 (accurate on rotated). Fast path when `Q0 ≥ 0.80`.
 *  - Landscape (aspect > 1): Pass 1 (`0°` accurate) → `90/180/270` fast
 *    probes → optional Pass 3 on the best rotation. Fast path when
 *    `Q0 ≥ 0.80`, count ≥ 5, and aspect ≤ 1.5 (genuine landscape receipts).
 *
 * @param image Source image. Orientation is baked into the CIImage before
 *              recognition.
 * @param minimumTextHeight Vision `minimumTextHeight` as a fraction of image
 *              height in `(0, 1]`; `0` uses the package default (1/32).
 * @param error Out-error pointer for `VNImageRequestHandler` failures.
 *              The probe passes are best-effort and do not propagate
 *              errors here — only Pass 1 / Pass 3 do.
 * @return An `RNOcrResult` with the chosen rotation's text and confidence,
 *         or `nil` on Pass 1 failure (with `*error` set).
 *
 * @warning Blocks the calling thread — must be called on a background queue.
 */
+ (nullable RNOcrResult *)recognizeAndDetectRotationInImage:(UIImage *)image
                                          minimumTextHeight:(double)minimumTextHeight
                                                       error:(NSError **)error;

@end

NS_ASSUME_NONNULL_END
