#import <Foundation/Foundation.h>
#import <CoreGraphics/CoreGraphics.h>

NS_ASSUME_NONNULL_BEGIN

/// Places OCR line boxes in the frame the output JPEG actually ships in.
/// See docs/specs/ocr-line-geometry.md. Mirrors Android `OcrGeometry`.
@interface RNOcrGeometry : NSObject

/// Converts one Vision bounding box — normalised `[0, 1]`, bottom-left origin —
/// into top-left-origin pixels of a `pixelSize` frame.
+ (CGRect)rectFromNormalizedBox:(CGRect)box pixelSize:(CGSize)pixelSize;

/// Rotates `rect` clockwise by `degrees` (0 / 90 / 180 / 270) inside a
/// `frameSize` frame and returns it in the rotated frame's coordinates.
/// Width and height swap for 90 / 270.
///
/// @note Clockwise here is the *Android* `Matrix.postRotate` convention, which
///       this package uses as the single canonical direction. iOS rotates its
///       pixels the other way for the same degree value — callers pass the
///       degrees that undo their own transform, not the ones they applied.
///       See docs/notes/platform-asymmetries.md.
+ (CGRect)rectByRotating:(CGRect)rect frameSize:(CGSize)frameSize clockwiseDegrees:(NSInteger)degrees;

/// Places every line's `frame` in the output image's coordinate space: rotates
/// by `degrees`, rescales onto `outputSize`, clamps, and drops lines whose
/// frame clamps to nothing.
///
/// The rescale exists so the emitted coordinates satisfy the JS contract
/// (`frame` lives in `ReceiptImage.width` x `height`) by construction rather
/// than by assuming Vision measured on exactly the frame that got encoded. It
/// is a no-op in the expected case, where the two agree.
///
/// @param lines Line dictionaries shaped like the JS `OcrLine` — `text`,
///              `frame` (`x` / `y` / `width` / `height`), optional `confidence`.
/// @param frameSize Pixel size of the frame `lines` are currently expressed in.
/// @param degrees Clockwise rotation to apply; `0` rescales without rotating.
/// @param outputSize Pixel size of the encoded output image.
+ (NSArray<NSDictionary *> *)linesByRotating:(NSArray<NSDictionary *> *)lines
                                   frameSize:(CGSize)frameSize
                            clockwiseDegrees:(NSInteger)degrees
                                  outputSize:(CGSize)outputSize;

#pragma mark - Text angle rotation detection

/// Returned by `dominantQuarterTurnFromAngles:` when the sample is too small or
/// too split to judge; the caller falls back to its own routing.
extern const NSInteger RNOcrGeometryQuarterTurnUnknown;

/// Minimum lines carrying a finite angle before the mode is trusted.
/// PROVISIONAL. Mirrors Android `OcrGeometry.ANGLE_MIN_LINES`.
extern const NSInteger RNOcrGeometryAngleMinLines;

/// Fraction of lines the winning quarter turn must hold. PROVISIONAL.
/// Mirrors Android `OcrGeometry.ANGLE_MAJORITY`.
extern const double RNOcrGeometryAngleMajority;

/// Clockwise angle in degrees of the text running from `topLeft` to `topRight`.
///
/// Both points come from `VNRectangleObservation`: normalised, **bottom-left**
/// origin. Moving to the top-left origin the rest of this package uses flips the
/// sign of the y component, and that flip is what turns Vision's convention into
/// the clockwise one Android's `Text.Line.getAngle` already reports. Getting it
/// wrong silently swaps 90 and 270 — the exact bug this redesign exists to fix.
/// See docs/specs/ocr-angle-rotation-detection.md.
+ (CGFloat)clockwiseAngleFromTopLeft:(CGPoint)topLeft topRight:(CGPoint)topRight;

/// Rounds a clockwise text angle to the nearest quarter turn, normalised into
/// `[0, 360)`. Mirrors Android `OcrGeometry.quantizeQuarterTurn`.
+ (NSInteger)quantizeQuarterTurn:(CGFloat)degrees;

/// The clockwise rotation that puts text sitting at `quarterTurn` back upright.
/// Mirrors Android `OcrGeometry.correctionForTextAngle`.
+ (NSInteger)correctionForTextAngle:(NSInteger)quarterTurn;

/// Counts of finite angles per quarter-turn bin, indexed `turn / 90`. Non-finite
/// entries are dropped, so the sum is the usable sample size rather than
/// `angles.count`. Mirrors Android `OcrGeometry.quarterTurnHistogram`.
+ (NSArray<NSNumber *> *)quarterTurnHistogramFromAngles:(NSArray<NSNumber *> *)angles;

/// Dominant quarter turn across per-line text angles, or
/// `RNOcrGeometryQuarterTurnUnknown`. Angles are binned before counting rather
/// than averaged: a linear mean of -179 and +179 is 0, the opposite of the
/// truth. Mirrors Android `OcrGeometry.dominantQuarterTurn`.
+ (NSInteger)dominantQuarterTurnFromAngles:(NSArray<NSNumber *> *)angles;

@end

NS_ASSUME_NONNULL_END
