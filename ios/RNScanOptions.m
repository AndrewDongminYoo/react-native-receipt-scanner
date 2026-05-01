#import "RNScanOptions.h"

static id RNNullToNil(id value) {
    return [value isKindOfClass:[NSNull class]] ? nil : value;
}

@implementation RNScanOptions

+ (instancetype)optionsFromDictionary:(NSDictionary *)dict {
    RNScanOptions *opts = [RNScanOptions new];

    if (![dict isKindOfClass:[NSDictionary class]]) {
        opts.source         = @"camera";
        opts.maxPages       = 1;
        opts.quality        = 0.82;
        opts.includeExif    = YES;
        opts.includeGpsExif = NO;
        opts.ocr            = YES;
        return opts;
    }

    NSString *src = RNNullToNil(dict[@"source"]);
    opts.source = [src isEqualToString:@"gallery"] ? @"gallery" : @"camera";

    NSNumber *maxPagesNum = RNNullToNil(dict[@"maxPages"]) ?: @1;
    opts.maxPages = MAX(1, maxPagesNum.integerValue);

    NSNumber *qualityNum = RNNullToNil(dict[@"quality"]) ?: @0.82;
    opts.quality = MAX(0.0, MIN(1.0, qualityNum.doubleValue));

    NSNumber *includeExifNum    = RNNullToNil(dict[@"includeExif"])     ?: @YES;
    NSNumber *includeGpsExifNum = RNNullToNil(dict[@"includeGpsExif"])  ?: @NO;
    NSNumber *ocrNum            = RNNullToNil(dict[@"ocr"])             ?: @YES;
    opts.includeExif    = includeExifNum.boolValue;
    opts.includeGpsExif = includeGpsExifNum.boolValue;
    opts.ocr            = ocrNum.boolValue;

    return opts;
}

@end
