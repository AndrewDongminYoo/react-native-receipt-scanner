#import <UIKit/UIKit.h>

NS_ASSUME_NONNULL_BEGIN

/**
 * Modal view controller hosting four draggable corner handles over a fitted
 * source image. Used by the gallery path when document detection confidence
 * is low or `cropAutoConfirm` is disabled.
 *
 * On confirm, applies `CIPerspectiveCorrection` via
 * `+[RNImageProcessor perspectiveCorrectedCGImage:corners:]` on a background
 * thread and invokes the completion block with a retained `CGImageRef`.
 * On cancel, the completion block is invoked with `NULL`.
 *
 * Coordinate space: `corners` are in **CIImage** coordinates (origin at
 * bottom-left, Y increasing upward) — matching what
 * `+[RNImageProcessor perspectiveCorrectedCGImage:corners:]` expects.
 *
 * Order: `topLeft`, `topRight`, `bottomRight`, `bottomLeft`.
 *
 * @see ADR-004 for implementation constraints (UIButton vs UIBarButtonItem,
 *      bottom anchor offset, subview Z-order).
 */
@interface RNCropEditorViewController : UIViewController

/**
 * Designated initializer.
 *
 * @param image Source image (orientation-normalised). The view controller
 *              displays this directly via `UIImageView`.
 * @param corners Optional initial quad in CIImage coordinates. Pass `nil`
 *                or a 4-element array; when `nil` (or any other count), a
 *                10% inset on each side is used as the default.
 * @param completion Called once on confirm or cancel. On confirm the block
 *                   receives a retained `CGImageRef` and **must release it**;
 *                   on cancel it receives `NULL`. Invoked on a background
 *                   queue (confirm) or the main queue (cancel).
 */
- (instancetype)initWithImage:(UIImage *)image
                      corners:(nullable NSArray<NSValue *> *)corners
                   completion:(void (^)(CGImageRef _Nullable cgImage))completion;

@end

NS_ASSUME_NONNULL_END
