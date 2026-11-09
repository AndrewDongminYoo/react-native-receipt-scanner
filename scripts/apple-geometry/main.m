#import <UIKit/UIKit.h>
#import "RNOcrGeometry.h"
#import "RNQuadGeometry.h"

static NSUInteger failures = 0;
static void check(BOOL matches, NSString *label) {
    if (!matches) {
        fprintf(stderr, "FAIL %s\n", label.UTF8String);
        failures++;
    }
}
static CGRect rect(NSArray<NSNumber *> *a) {
    return CGRectMake(a[0].doubleValue, a[1].doubleValue, a[2].doubleValue, a[3].doubleValue);
}
static BOOL equalRect(CGRect actual, CGRect expected) {
    return fabs(actual.origin.x - expected.origin.x) <= 1e-6 &&
           fabs(actual.origin.y - expected.origin.y) <= 1e-6 &&
           fabs(actual.size.width - expected.size.width) <= 1e-6 &&
           fabs(actual.size.height - expected.size.height) <= 1e-6;
}
static NSNumber *angle(id value) {
    if ([value isKindOfClass:NSNumber.class]) return value;
    if ([value isEqual:@"NaN"]) return @(NAN);
    if ([value isEqual:@"+Infinity"]) return @(INFINITY);
    if ([value isEqual:@"-Infinity"]) return @(-INFINITY);
    [NSException raise:@"InvalidAngle" format:@"Unknown token %@", value];
    return nil;
}
int main(int argc, const char *argv[]) {
    @autoreleasepool {
        if (argc != 2) { fprintf(stderr, "Usage: geometry-reader contract.json\n"); return 2; }
        NSError *error = nil;
        NSData *data = [NSData dataWithContentsOfFile:@(argv[1]) options:0 error:&error];
        NSDictionary *root = data ? [NSJSONSerialization JSONObjectWithData:data options:0 error:&error] : nil;
        NSDictionary *geometry = root[@"geometry"];
        if (!geometry || [root[@"schemaVersion"] integerValue] != 1) {
            fprintf(stderr, "Invalid corpus: %s\n", error.description.UTF8String); return 2;
        }
        NSUInteger cases = 0;
        for (NSDictionary *c in geometry[@"quantizeCases"]) {
            cases++;
            check([RNOcrGeometry quantizeQuarterTurn:[c[@"degrees"] doubleValue]] == [c[@"expected"] integerValue], c[@"id"]);
        }
        for (NSDictionary *c in geometry[@"angleCases"]) {
            cases++;
            NSMutableArray *angles = [NSMutableArray array];
            for (id value in c[@"angles"]) [angles addObject:angle(value)];
            check([[RNOcrGeometry quarterTurnHistogramFromAngles:angles] isEqual:c[@"expectedHistogram"]], [c[@"id"] stringByAppendingString:@" histogram"]);
            NSInteger expected = c[@"expectedDominant"] == NSNull.null ? RNOcrGeometryQuarterTurnUnknown : [c[@"expectedDominant"] integerValue];
            check([RNOcrGeometry dominantQuarterTurnFromAngles:angles] == expected, c[@"id"]);
        }
        for (NSDictionary *c in geometry[@"correctionCases"]) {
            cases++;
            check([RNOcrGeometry correctionForTextAngle:[c[@"quarterTurn"] integerValue]] == [c[@"expected"] integerValue], c[@"id"]);
        }
        for (NSDictionary *c in geometry[@"boxCases"]) {
            cases++;
            NSArray *f = c[@"frame"], *out = c[@"expectedFrame"];
            CGSize frame = CGSizeMake([f[0] doubleValue], [f[1] doubleValue]);
            CGSize output = CGSizeMake([out[0] doubleValue], [out[1] doubleValue]);
            CGRect box = rect(c[@"box"]);
            NSInteger degrees = [c[@"degrees"] integerValue];
            CGRect rotatedFrame = [RNOcrGeometry rectByRotating:CGRectMake(0, 0, frame.width, frame.height) frameSize:frame clockwiseDegrees:degrees];
            check(CGSizeEqualToSize(rotatedFrame.size, output), [c[@"id"] stringByAppendingString:@" output frame"]);
            check(equalRect([RNOcrGeometry rectByRotating:box frameSize:frame clockwiseDegrees:degrees], rect(c[@"expectedRotated"])), [c[@"id"] stringByAppendingString:@" rotation"]);
            NSDictionary *line = @{@"text": @"synthetic", @"confidence": @0.75, @"frame": @{@"x": @(box.origin.x), @"y": @(box.origin.y), @"width": @(box.size.width), @"height": @(box.size.height)}};
            NSArray *placed = [RNOcrGeometry linesByRotating:@[line] frameSize:frame clockwiseDegrees:degrees outputSize:output];
            if (c[@"expectedClamped"] == NSNull.null) check(placed.count == 0, c[@"id"]);
            else {
                check(placed.count == 1, c[@"id"]);
                NSDictionary *actual = [placed firstObject];
                NSDictionary *r = actual[@"frame"];
                CGRect result = CGRectMake([r[@"x"] doubleValue], [r[@"y"] doubleValue], [r[@"width"] doubleValue], [r[@"height"] doubleValue]);
                check(equalRect(result, rect(c[@"expectedClamped"])), [c[@"id"] stringByAppendingString:@" clamp"]);
                check([actual[@"text"] isEqual:line[@"text"]] && [actual[@"confidence"] isEqual:line[@"confidence"]], c[@"id"]);
            }
            // Convert the shared top-left pixel box to Vision's bottom-left normalized input.
            CGRect vision = CGRectMake(box.origin.x / frame.width, 1 - CGRectGetMaxY(box) / frame.height, box.size.width / frame.width, box.size.height / frame.height);
            check(equalRect([RNOcrGeometry rectFromNormalizedBox:vision pixelSize:frame], box), [c[@"id"] stringByAppendingString:@" Vision"]);
        }
        for (NSDictionary *c in geometry[@"quadCases"]) {
            cases++;
            NSArray *a = c[@"corners"];
            NSMutableArray *points = [NSMutableArray array];
            for (NSUInteger i = 0; i < a.count; i += 2) [points addObject:[NSValue valueWithCGPoint:CGPointMake([a[i] doubleValue], [a[i+1] doubleValue])]];
            check([RNQuadGeometry isDistorted:points] == [c[@"expectedDistorted"] boolValue], c[@"id"]);
        }
        // Literal non-square Vision vectors catch axis/sign mistakes that square frames hide.
        check(fabs([RNOcrGeometry clockwiseAngleFromTopLeft:CGPointMake(0.1, 0.8) topRight:CGPointMake(0.3, 0.7) pixelSize:CGSizeMake(200, 400)] - 45) <= 1e-6, @"Vision clockwise pixel angle");
        check(fabs([RNOcrGeometry clockwiseAngleFromTopLeft:CGPointMake(0.1, 0.2) topRight:CGPointMake(0.1, 0.8) pixelSize:CGSizeMake(200, 400)] + 90) <= 1e-6, @"Vision counterclockwise pixel angle");
        check(cases == 64, @"complete geometry corpus (64 cases)");
        printf("Objective-C geometry: %lu cases, %lu failures\n", (unsigned long)cases, (unsigned long)failures);
        return failures ? 1 : 0;
    }
}
