#import <Foundation/Foundation.h>
#import <CoreGraphics/CoreGraphics.h>
#import <ImageIO/ImageIO.h>

@class UIImage;

NS_ASSUME_NONNULL_BEGIN

/**
 * Result of `+[RNImageProcessor processImage:...]`. Owns a JPEG written to
 * `NSCachesDirectory`; callers do not need to delete it explicitly —
 * `+deletePreviousSessionFiles` sweeps the directory at the start of the
 * next scan.
 *
 * Field shape mirrors `ReceiptImage` in `src/types.ts` for direct
 * forwarding over the bridge.
 */
@interface RNProcessedImage : NSObject
/** `file://` URL to the cached JPEG. Use `absoluteString` to produce the bridge URI. */
@property (nonatomic, strong) NSURL    *fileURL;
/** Output pixel width. */
@property (nonatomic, assign) NSInteger width;
/** Output pixel height. */
@property (nonatomic, assign) NSInteger height;
/** File size in bytes (read post-encoding). */
@property (nonatomic, assign) NSInteger fileSize;
/** EXIF white-list dictionary, or `nil` when `includeExif` was `NO`. */
@property (nonatomic, strong, nullable) NSDictionary *exifData;
@end

/**
 * Image post-processing utilities used by both the camera
 * (`RNDocumentCameraDelegate`) and gallery (`RNGalleryPickerDelegate`)
 * paths.
 *
 * Responsibilities: JPEG encoding, EXIF read / strip / synthesize,
 * perspective correction, in-place rotation, and temp-file housekeeping.
 *
 * All methods are thread-safe in the sense that they don't share mutable
 * state, but they are blocking — callers should dispatch onto a background
 * queue for any non-trivial input.
 */
@interface RNImageProcessor : NSObject

/**
 * Recompresses `cgImage` to JPEG at the given quality and writes it to
 * `NSCachesDirectory`. Optionally copies EXIF / TIFF / GPS dictionaries
 * from `sourceRef`, or synthesizes a minimal device dict when no source is
 * available.
 *
 * @param cgImage Pre-oriented pixels to encode. Must already match the
 *                desired output orientation; orientation is always written
 *                as `kCGImagePropertyOrientationUp` (1).
 * @param quality JPEG quality in `[0.0, 1.0]`.
 * @param sourceRef Source image whose EXIF should be preserved. Pass `NULL`
 *                  for the camera path (VisionKit strips EXIF) — the
 *                  processor synthesizes `make` / `model` / `dateTimeOriginal`
 *                  from `UIDevice` instead. Held only for the duration of
 *                  the call.
 * @param includeExif When `YES`, populates `RNProcessedImage.exifData`.
 * @param includeGpsExif When `YES`, includes the GPS dictionary specifically.
 * @param includeRawExif When `YES`, attaches a flat raw EXIF map under `exif.raw`.
 * @param error Out-error pointer for write failures.
 * @return The cached `RNProcessedImage`, or `nil` on failure (with `*error` set).
 */
+ (nullable RNProcessedImage *)processImage:(CGImageRef)cgImage
                                    quality:(double)quality
                                  sourceRef:(nullable CGImageSourceRef)sourceRef
                               includeExif:(BOOL)includeExif
                            includeGpsExif:(BOOL)includeGpsExif
                            includeRawExif:(BOOL)includeRawExif
                                     error:(NSError **)error;

/**
 * Applies `CIPerspectiveCorrection` using four corners in CIImage
 * coordinate space (origin bottom-left, Y up, in pixels relative to
 * `image.size`). The image's orientation is baked into the CIImage before
 * filtering — see ADR-004 for why this matters.
 *
 * @param image Source image (any orientation).
 * @param corners 4-element array of `NSValue` boxes wrapping `CGPoint`,
 *                in order `topLeft`, `topRight`, `bottomRight`, `bottomLeft`.
 * @return A retained `CGImageRef` (caller owns and must `CGImageRelease`),
 *         or `NULL` on failure.
 */
+ (nullable CGImageRef)perspectiveCorrectedCGImage:(UIImage *)image
                                           corners:(NSArray<NSValue *> *)corners
    CF_RETURNS_RETAINED;

/**
 * Bakes any non-`Up` orientation into raw pixels by redrawing through
 * `UIGraphicsImageRenderer`. Returns the input unchanged when orientation
 * is already `UIImageOrientationUp`.
 */
+ (UIImage *)normalizeOrientation:(UIImage *)image;

/**
 * Rotates `cgImage` by 0 / 90 / 180 / 270 degrees CCW.
 *
 * @param cgImage Source pixels. Not retained beyond the call.
 * @param degrees One of `0`, `90`, `180`, `270`. Other values return the
 *                input retained (no rotation applied).
 * @return Retained `CGImageRef` for non-zero rotations, or the input
 *         (retained) when `degrees == 0`. Caller owns the result and must
 *         `CGImageRelease` it.
 */
+ (nullable CGImageRef)cgImageByRotating:(CGImageRef)cgImage
                                 degrees:(NSInteger)degrees
    CF_RETURNS_RETAINED;

/**
 * Deletes all `receipt_*.jpg` files in `NSCachesDirectory` from a previous
 * `scan()` session. Called at the start of each new scan to keep the cache
 * bounded.
 */
+ (void)deletePreviousSessionFiles;

/** @return The app's caches directory URL (`NSCachesDirectory`, user domain). */
+ (NSURL *)cacheDirURL;

@end

NS_ASSUME_NONNULL_END
