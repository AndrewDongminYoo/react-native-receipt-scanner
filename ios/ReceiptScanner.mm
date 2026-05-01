#import "ReceiptScanner.h"

@implementation ReceiptScanner

- (void)scan:(NSDictionary *)options
     resolve:(RCTPromiseResolveBlock)resolve
      reject:(RCTPromiseRejectBlock)reject
{
    resolve(nil);
}

- (std::shared_ptr<facebook::react::TurboModule>)getTurboModule:
    (const facebook::react::ObjCTurboModule::InitParams &)params
{
    return std::make_shared<facebook::react::NativeReceiptScannerSpecJSI>(params);
}

+ (NSString *)moduleName
{
    return @"ReceiptScanner";
}

@end
