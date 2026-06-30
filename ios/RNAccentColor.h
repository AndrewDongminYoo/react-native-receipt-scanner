#import <UIKit/UIKit.h>

NS_ASSUME_NONNULL_BEGIN

/**
 * Brand accent color for the in-package crop editor UI (handle borders,
 * overlay fill / stroke).
 *
 * The color resolves dynamically against `UIUserInterfaceStyle` so the
 * accent contrasts correctly in light and dark mode without the consumer
 * having to ship asset catalog overrides.
 */
@interface RNAccentColor : NSObject

/**
 * Returns the dynamic crop-editor accent color. Cached behind
 * `dispatch_once` — safe to call from any queue.
 *
 * @return A `UIColor` resolved per the current `UITraitCollection` at draw time.
 */
+ (UIColor *)cropAccent;

@end

NS_ASSUME_NONNULL_END
