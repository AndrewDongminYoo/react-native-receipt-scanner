#import "RNOcrGeometry.h"

@implementation RNOcrGeometry

+ (CGRect)rectFromNormalizedBox:(CGRect)box pixelSize:(CGSize)pixelSize {
    // Vision reports [0, 1] with a bottom-left origin; the JS contract is
    // top-left pixels, so the y axis flips around the box's *upper* edge.
    return CGRectMake(CGRectGetMinX(box) * pixelSize.width,
                      (1.0 - CGRectGetMaxY(box)) * pixelSize.height,
                      box.size.width * pixelSize.width,
                      box.size.height * pixelSize.height);
}

+ (CGRect)rectByRotating:(CGRect)rect frameSize:(CGSize)frameSize clockwiseDegrees:(NSInteger)degrees {
    CGFloat frameW = frameSize.width, frameH = frameSize.height;
    CGFloat x = rect.origin.x, y = rect.origin.y;
    CGFloat w = rect.size.width, h = rect.size.height;
    switch (((degrees % 360) + 360) % 360) {
        case 90:  return CGRectMake(frameH - y - h, x, h, w);
        case 180: return CGRectMake(frameW - x - w, frameH - y - h, w, h);
        case 270: return CGRectMake(y, frameW - x - w, h, w);
        default:  return rect;
    }
}

+ (NSArray<NSDictionary *> *)linesByRotating:(NSArray<NSDictionary *> *)lines
                                   frameSize:(CGSize)frameSize
                            clockwiseDegrees:(NSInteger)degrees {
    NSInteger turn = ((degrees % 360) + 360) % 360;
    BOOL swapsAxes = (turn == 90 || turn == 270);
    CGRect bounds = CGRectMake(0, 0,
                               swapsAxes ? frameSize.height : frameSize.width,
                               swapsAxes ? frameSize.width : frameSize.height);

    NSMutableArray<NSDictionary *> *placed = [NSMutableArray arrayWithCapacity:lines.count];
    for (NSDictionary *line in lines) {
        NSDictionary *frame = line[@"frame"];
        CGRect rect = CGRectMake([frame[@"x"] doubleValue],
                                 [frame[@"y"] doubleValue],
                                 [frame[@"width"] doubleValue],
                                 [frame[@"height"] doubleValue]);
        CGRect turned = [self rectByRotating:rect frameSize:frameSize clockwiseDegrees:turn];
        // Clamp to the image; a box with nothing left inside carries no drawable
        // geometry, so drop it rather than ship an off-image rectangle.
        CGRect clamped = CGRectIntersection(turned, bounds);
        if (CGRectIsNull(clamped) || CGRectIsEmpty(clamped)) continue;

        NSMutableDictionary *out = [line mutableCopy];
        out[@"frame"] = @{
            @"x":      @(clamped.origin.x),
            @"y":      @(clamped.origin.y),
            @"width":  @(clamped.size.width),
            @"height": @(clamped.size.height),
        };
        [placed addObject:[out copy]];
    }
    return [placed copy];
}

@end
