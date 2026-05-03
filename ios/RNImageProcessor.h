#import <Foundation/Foundation.h>
#import <CoreGraphics/CoreGraphics.h>
#import <ImageIO/ImageIO.h>

@class UIImage;

NS_ASSUME_NONNULL_BEGIN

@interface RNProcessedImage : NSObject
@property (nonatomic, strong) NSURL    *fileURL;
@property (nonatomic, assign) NSInteger width;
@property (nonatomic, assign) NSInteger height;
@property (nonatomic, assign) NSInteger fileSize;
@property (nonatomic, strong, nullable) NSDictionary *exifData;
@end

@interface RNImageProcessor : NSObject

/**
 * Recompress cgImage to JPEG at the given quality, optionally copying EXIF/GPS
 * from sourceRef (pass NULL for camera images that have no original EXIF source).
 * Returns nil and sets error on failure.
 */
+ (nullable RNProcessedImage *)processImage:(CGImageRef)cgImage
                                    quality:(double)quality
                                  sourceRef:(nullable CGImageSourceRef)sourceRef
                               includeExif:(BOOL)includeExif
                            includeGpsExif:(BOOL)includeGpsExif
                                     error:(NSError **)error;

/**
 * Applies CIPerspectiveCorrection to image using four corners in CIImage coordinate space
 * (origin bottom-left, Y up, pixel values relative to image.size). Returns NULL on failure.
 */
+ (nullable CGImageRef)perspectiveCorrectedCGImage:(UIImage *)image
                                           corners:(NSArray<NSValue *> *)corners
    CF_RETURNS_RETAINED;

/** Normalize UIImage orientation to UIImageOrientationUp. */
+ (UIImage *)normalizeOrientation:(UIImage *)image;

/** Delete all receipt_*.jpg files in NSCachesDirectory from a previous session. */
+ (void)deletePreviousSessionFiles;

/** Returns the URL to the app caches directory. */
+ (NSURL *)cacheDirURL;

@end

NS_ASSUME_NONNULL_END
