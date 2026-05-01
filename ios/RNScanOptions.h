#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

@interface RNScanOptions : NSObject
@property (nonatomic, copy)   NSString  *source;         // "camera" | "gallery"
@property (nonatomic, assign) NSInteger  maxPages;
@property (nonatomic, assign) double     quality;         // 0.0–1.0
@property (nonatomic, assign) BOOL       includeExif;
@property (nonatomic, assign) BOOL       includeGpsExif;
@property (nonatomic, assign) BOOL       ocr;

+ (instancetype)optionsFromDictionary:(NSDictionary *)dict;
@end

NS_ASSUME_NONNULL_END
