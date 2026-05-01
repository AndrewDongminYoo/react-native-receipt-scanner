#import <Foundation/Foundation.h>
#import "RNScanOptions.h"

NS_ASSUME_NONNULL_BEGIN

typedef void (^RNResolveBlock)(id result);
typedef void (^RNRejectBlock)(NSString *code, NSString *message, NSError * _Nullable error);

@interface RNDocumentCameraDelegate : NSObject

- (instancetype)initWithOptions:(RNScanOptions *)options
                        resolve:(RNResolveBlock)resolve
                         reject:(RNRejectBlock)reject;

@end

NS_ASSUME_NONNULL_END
