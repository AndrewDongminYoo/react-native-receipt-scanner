#import <UIKit/UIKit.h>

NS_ASSUME_NONNULL_BEGIN

/**
 * Presents the source image with four draggable corner handles.
 * On confirm, applies CIPerspectiveCorrection and returns the cropped CGImage.
 * On cancel, returns nil.
 *
 * Corners are in CIImage pixel coordinates (origin at bottom-left).
 * Order: topLeft, topRight, bottomRight, bottomLeft.
 */
@interface RNCropEditorViewController : UIViewController

/**
 * @param image         The full-resolution source image to display.
 * @param corners       Array of 4 NSValue-wrapped CGPoints in CIImage pixel coords
 *                      (order: topLeft, topRight, bottomRight, bottomLeft).
 *                      Pass nil to default to image corners.
 * @param completion    Called on main thread. cgImage is nil when cancelled.
 */
- (instancetype)initWithImage:(UIImage *)image
                      corners:(nullable NSArray<NSValue *> *)corners
                   completion:(void (^)(CGImageRef _Nullable cgImage))completion;

@end

NS_ASSUME_NONNULL_END
