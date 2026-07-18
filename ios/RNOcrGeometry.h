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

@end

NS_ASSUME_NONNULL_END
