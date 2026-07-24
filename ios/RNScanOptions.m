#import "RNScanOptions.h"

static id RNNullToNil(id value) {
    return [value isKindOfClass:[NSNull class]] ? nil : value;
}

@implementation RNScanOptions

+ (instancetype)optionsFromDictionary:(NSDictionary *)dict {
    RNScanOptions *opts = [RNScanOptions new];

    if (![dict isKindOfClass:[NSDictionary class]]) {
        opts.source           = @"camera";
        opts.maxPages         = 1;
        opts.quality          = 0.82;
        opts.includeExif      = YES;
        opts.includeGpsExif   = NO;
        opts.ocr              = YES;
        opts.cropAutoConfirm  = NO;
        opts.autoRotate       = YES;
        opts.includeRawExif   = NO;
        opts.minimumTextHeight = 0;
        opts.ocrGeometry      = NO;
        return opts;
    }

    NSString *src = RNNullToNil(dict[@"source"]);
    opts.source = [src isEqualToString:@"gallery"] ? @"gallery" : @"camera";

    // Clamp to 1..10, matching Android's ScanOptions.MAX_PAGES so the same
    // public option yields the same page count on both platforms.
    NSNumber *maxPagesNum = RNNullToNil(dict[@"maxPages"]) ?: @1;
    opts.maxPages = MIN(10, MAX(1, maxPagesNum.integerValue));

    NSNumber *qualityNum = RNNullToNil(dict[@"quality"]) ?: @0.82;
    opts.quality = MAX(0.0, MIN(1.0, qualityNum.doubleValue));

    NSNumber *includeExifNum     = RNNullToNil(dict[@"includeExif"])      ?: @YES;
    NSNumber *includeGpsExifNum  = RNNullToNil(dict[@"includeGpsExif"])   ?: @NO;
    NSNumber *ocrNum             = RNNullToNil(dict[@"ocr"])              ?: @YES;
    NSNumber *cropAutoConfirmNum = RNNullToNil(dict[@"cropAutoConfirm"])  ?: @NO;
    NSNumber *autoRotateNum      = RNNullToNil(dict[@"autoRotate"])       ?: @YES;
    NSNumber *includeRawExifNum  = RNNullToNil(dict[@"includeRawExif"])   ?: @NO;
    NSNumber *ocrGeometryNum     = RNNullToNil(dict[@"ocrGeometry"])      ?: @NO;
    opts.ocrGeometry      = ocrGeometryNum.boolValue;
    opts.includeExif      = includeExifNum.boolValue;
    opts.includeGpsExif   = includeGpsExifNum.boolValue;
    opts.ocr              = ocrNum.boolValue;
    opts.cropAutoConfirm  = cropAutoConfirmNum.boolValue;
    opts.autoRotate       = autoRotateNum.boolValue;
    opts.includeRawExif   = includeRawExifNum.boolValue;

    // iOS-only Vision tuning knob; absent/0 → use the package default (1/32).
    NSNumber *minTextHeightNum = RNNullToNil(dict[@"minimumTextHeight"]);
    opts.minimumTextHeight = minTextHeightNum ? MAX(0.0, MIN(1.0, minTextHeightNum.doubleValue)) : 0.0;

    return opts;
}

@end
