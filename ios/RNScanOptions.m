#import "RNScanOptions.h"

@implementation RNScanOptions

+ (instancetype)optionsFromDictionary:(NSDictionary *)dict {
    RNScanOptions *opts = [RNScanOptions new];
    opts.source         = dict[@"source"]         ?: @"camera";
    opts.maxPages       = dict[@"maxPages"]        ? [dict[@"maxPages"] integerValue]  : 1;
    opts.quality        = dict[@"quality"]         ? [dict[@"quality"] doubleValue]    : 0.82;
    opts.includeExif    = dict[@"includeExif"]     ? [dict[@"includeExif"] boolValue]    : YES;
    opts.includeGpsExif = dict[@"includeGpsExif"]  ? [dict[@"includeGpsExif"] boolValue] : NO;
    opts.ocr            = dict[@"ocr"]             ? [dict[@"ocr"] boolValue]            : YES;
    return opts;
}

@end
