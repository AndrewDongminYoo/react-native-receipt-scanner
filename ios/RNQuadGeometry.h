#import <Foundation/Foundation.h>
#import <CoreGraphics/CoreGraphics.h>

NS_ASSUME_NONNULL_BEGIN

/// Geometric sanity backstop for crop quads. See docs/specs/quad-distortion-backstop.md.
/// Thresholds are PROVISIONAL and must stay in sync with Android QuadGeometry.
@interface RNQuadGeometry : NSObject
/// corners: 4 NSValue-wrapped CGPoint in order TL, TR, BR, BL (any consistent 2D space).
+ (BOOL)isDistorted:(NSArray<NSValue *> *)corners;
@end

NS_ASSUME_NONNULL_END
