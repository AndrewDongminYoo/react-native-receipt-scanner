#import "RNScanOptions.h"

static id RNNullToNil(id value) {
    return [value isKindOfClass:[NSNull class]] ? nil : value;
}

// Bridged JS values are untyped at the native boundary (Phase 1 codegen uses
// `Object`). A malformed value (object/array/string where a scalar is expected)
// would crash if sent -boolValue/-integerValue/-doubleValue directly, so every
// scalar extraction below is class-checked and falls back to the documented default.
static BOOL RNBoolFromValue(id value, BOOL defaultValue) {
    return [value isKindOfClass:[NSNumber class]] ? [value boolValue] : defaultValue;
}

static NSInteger RNIntegerFromValue(id value, NSInteger defaultValue) {
    return [value isKindOfClass:[NSNumber class]] ? [value integerValue] : defaultValue;
}

static double RNDoubleFromValue(id value, double defaultValue) {
    return [value isKindOfClass:[NSNumber class]] ? [value doubleValue] : defaultValue;
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

    id src = RNNullToNil(dict[@"source"]);
    opts.source = ([src isKindOfClass:[NSString class]] && [src isEqualToString:@"gallery"]) ? @"gallery" : @"camera";

    // Clamp to 1..10, matching Android's ScanOptions.MAX_PAGES so the same
    // public option yields the same page count on both platforms.
    opts.maxPages = MIN(10, MAX(1, RNIntegerFromValue(dict[@"maxPages"], 1)));
    opts.quality  = MAX(0.0, MIN(1.0, RNDoubleFromValue(dict[@"quality"], 0.82)));

    opts.includeExif      = RNBoolFromValue(dict[@"includeExif"], YES);
    opts.includeGpsExif   = RNBoolFromValue(dict[@"includeGpsExif"], NO);
    opts.ocr              = RNBoolFromValue(dict[@"ocr"], YES);
    opts.cropAutoConfirm  = RNBoolFromValue(dict[@"cropAutoConfirm"], NO);
    opts.autoRotate       = RNBoolFromValue(dict[@"autoRotate"], YES);
    opts.includeRawExif   = RNBoolFromValue(dict[@"includeRawExif"], NO);
    opts.ocrGeometry      = RNBoolFromValue(dict[@"ocrGeometry"], NO);

    // iOS-only Vision tuning knob; absent/0 → use the package default (1/32).
    opts.minimumTextHeight = MAX(0.0, MIN(1.0, RNDoubleFromValue(dict[@"minimumTextHeight"], 0.0)));

    return opts;
}

@end
