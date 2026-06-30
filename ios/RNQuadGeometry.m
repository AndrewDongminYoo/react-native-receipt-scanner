#import "RNQuadGeometry.h"
#import <UIKit/UIKit.h>  // NSValue (CGPointValue) lives in UIKit's NSValue+UIGeometry

static const CGFloat kMaxEdgeRatio = 2.2;

static CGFloat RNDist(CGPoint a, CGPoint b) { return hypot(a.x - b.x, a.y - b.y); }

@implementation RNQuadGeometry

+ (BOOL)isConvexTL:(CGPoint)tl tr:(CGPoint)tr br:(CGPoint)br bl:(CGPoint)bl {
    CGPoint p[4] = { tl, tr, br, bl };
    int sign = 0;
    for (int i = 0; i < 4; i++) {
        CGPoint a = p[i], b = p[(i + 1) % 4], c = p[(i + 2) % 4];
        CGFloat cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x);
        int s = cross > 0 ? 1 : (cross < 0 ? -1 : 0);
        if (s == 0) return NO;            // colinear / coincident → degenerate
        if (sign == 0) sign = s;
        else if (s != sign) return NO;
    }
    return YES;
}

+ (BOOL)isDistorted:(NSArray<NSValue *> *)corners {
    if (corners.count != 4) return YES;
    CGPoint tl = [corners[0] CGPointValue], tr = [corners[1] CGPointValue];
    CGPoint br = [corners[2] CGPointValue], bl = [corners[3] CGPointValue];

    CGFloat topW = RNDist(tl, tr), botW = RNDist(bl, br);
    CGFloat leftH = RNDist(tl, bl), rightH = RNDist(tr, br);
    CGFloat edges[4] = { topW, botW, leftH, rightH };
    CGFloat maxE = edges[0];
    for (int i = 1; i < 4; i++) {
        if (edges[i] > maxE) maxE = edges[i];
    }
    // All-zero edges (coincident corners) are degenerate. Everything else routes through the
    // convexity + opposite-edge-ratio checks below, which already reject collapsed corners (a
    // zero-length edge makes its opposite-pair ratio diverge past kMaxEdgeRatio). A standalone
    // shortest/longest-edge gate was dropped: its only unique effect was flagging legitimate
    // high-aspect-ratio (very long) receipts as distorted. See quad-distortion-backstop.md.
    if (maxE <= 0) return YES;
    if (![self isConvexTL:tl tr:tr br:br bl:bl]) return YES;

    CGFloat wRatio = MAX(topW, botW) / MIN(topW, botW);
    CGFloat hRatio = MAX(leftH, rightH) / MIN(leftH, rightH);
    return (wRatio > kMaxEdgeRatio || hRatio > kMaxEdgeRatio);
}

@end
