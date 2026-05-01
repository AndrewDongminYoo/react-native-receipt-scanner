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

    opts.maxPages = MAX(1, (NSInteger)((RNNullToNil(dict[@"maxPages"]) ?: @1).integerValue));

    double q = (RNNullToNil(dict[@"quality"]) ?: @0.82).doubleValue;
    opts.quality = MAX(0.0, MIN(1.0, q));

    opts.includeExif    = (RNNullToNil(dict[@"includeExif"])     ?: @YES).boolValue;
    opts.includeGpsExif = (RNNullToNil(dict[@"includeGpsExif"])  ?: @NO).boolValue;
    opts.ocr            = (RNNullToNil(dict[@"ocr"])             ?: @YES).boolValue;

    return opts;
}

@end
