#import <UIKit/UIKit.h>

NS_ASSUME_NONNULL_BEGIN

/**
 * Presents the source image with four draggable corner handles.
 * On confirm, applies CIPerspectiveCorrection and returns the cropped CGImage via RNImageProcessor.
 * On cancel, calls completion with NULL.
 *
 * Corners are in CIImage pixel coordinates (origin at bottom-left, Y-up).
 * Order: topLeft, topRight, bottomRight, bottomLeft.
 */
@interface RNCropEditorViewController : UIViewController

- (instancetype)initWithImage:(UIImage *)image
                      corners:(nullable NSArray<NSValue *> *)corners
                   completion:(void (^)(CGImageRef _Nullable cgImage))completion;

@end

NS_ASSUME_NONNULL_END
