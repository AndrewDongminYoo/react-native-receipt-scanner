#import "RNOcrGeometry.h"

const NSInteger RNOcrGeometryQuarterTurnUnknown = -1;
const NSInteger RNOcrGeometryAngleMinLines = 5;
const double RNOcrGeometryAngleMajority = 0.7;

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
                            clockwiseDegrees:(NSInteger)degrees
                                  outputSize:(CGSize)outputSize {
    NSInteger turn = ((degrees % 360) + 360) % 360;
    BOOL swapsAxes = (turn == 90 || turn == 270);
    CGSize turnedFrame = CGSizeMake(swapsAxes ? frameSize.height : frameSize.width,
                                    swapsAxes ? frameSize.width : frameSize.height);
    if (turnedFrame.width <= 0 || turnedFrame.height <= 0) return @[];
    // Vision measures on the frame it was handed, which is expected to be the
    // one that gets encoded. Rescale rather than trust that: it costs a multiply
    // and keeps the emitted coordinates inside ReceiptImage.width x height even
    // if the two ever diverge.
    CGFloat scaleX = outputSize.width / turnedFrame.width;
    CGFloat scaleY = outputSize.height / turnedFrame.height;
    CGRect bounds = CGRectMake(0, 0, outputSize.width, outputSize.height);

    NSMutableArray<NSDictionary *> *placed = [NSMutableArray arrayWithCapacity:lines.count];
    for (NSDictionary *line in lines) {
        NSDictionary *frame = line[@"frame"];
        CGRect rect = CGRectMake([frame[@"x"] doubleValue],
                                 [frame[@"y"] doubleValue],
                                 [frame[@"width"] doubleValue],
                                 [frame[@"height"] doubleValue]);
        CGRect turned = [self rectByRotating:rect frameSize:frameSize clockwiseDegrees:turn];
        CGRect scaled = CGRectMake(turned.origin.x * scaleX, turned.origin.y * scaleY,
                                   turned.size.width * scaleX, turned.size.height * scaleY);
        // Clamp to the image; a box with nothing left inside carries no drawable
        // geometry, so drop it rather than ship an off-image rectangle.
        CGRect clamped = CGRectIntersection(scaled, bounds);
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

#pragma mark - Text angle rotation detection

+ (CGFloat)clockwiseAngleFromTopLeft:(CGPoint)topLeft topRight:(CGPoint)topRight {
    CGFloat dx = topRight.x - topLeft.x;
    CGFloat dy = topRight.y - topLeft.y;
    // Negate dy: Vision's y grows upward, the canonical top-left frame's grows
    // downward, and in that frame a clockwise angle from +x is atan2(dy, dx).
    // Upright text gives dy == 0 -> 0; text running down the image gives
    // dy < 0 in Vision space -> +90, i.e. the receipt was laid down clockwise.
    return (CGFloat)(atan2(-dy, dx) * 180.0 / M_PI);
}

+ (NSInteger)quantizeQuarterTurn:(CGFloat)degrees {
    NSInteger quarters = (NSInteger)lround(degrees / 90.0);
    return ((quarters * 90) % 360 + 360) % 360;
}

+ (NSInteger)correctionForTextAngle:(NSInteger)quarterTurn {
    return (360 - (((quarterTurn % 360) + 360) % 360)) % 360;
}

+ (NSArray<NSNumber *> *)quarterTurnHistogramFromAngles:(NSArray<NSNumber *> *)angles {
    NSInteger bins[4] = {0, 0, 0, 0};
    for (NSNumber *angle in angles) {
        double value = angle.doubleValue;
        if (!isfinite(value)) continue;
        bins[[self quantizeQuarterTurn:(CGFloat)value] / 90]++;
    }
    return @[@(bins[0]), @(bins[1]), @(bins[2]), @(bins[3])];
}

+ (NSInteger)dominantQuarterTurnFromAngles:(NSArray<NSNumber *> *)angles {
    NSArray<NSNumber *> *bins = [self quarterTurnHistogramFromAngles:angles];
    NSInteger total = 0;
    for (NSNumber *bin in bins) total += bin.integerValue;
    if (total < RNOcrGeometryAngleMinLines) return RNOcrGeometryQuarterTurnUnknown;

    NSUInteger best = 0;
    for (NSUInteger i = 1; i < bins.count; i++) {
        if (bins[i].integerValue > bins[best].integerValue) best = i;
    }
    if ((double)bins[best].integerValue / (double)total < RNOcrGeometryAngleMajority) {
        return RNOcrGeometryQuarterTurnUnknown;
    }
    return (NSInteger)best * 90;
}

@end
