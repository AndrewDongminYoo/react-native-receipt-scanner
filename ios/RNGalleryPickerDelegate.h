#import <Foundation/Foundation.h>
#import "RNScanOptions.h"
#import "RNDocumentCameraDelegate.h"

NS_ASSUME_NONNULL_BEGIN

@class UIViewController;

@interface RNGalleryPickerDelegate : NSObject

- (instancetype)initWithOptions:(RNScanOptions *)options
       presentingViewController:(UIViewController *)presentingVC
                        resolve:(RNResolveBlock)resolve
                         reject:(RNRejectBlock)reject;

@end

NS_ASSUME_NONNULL_END
