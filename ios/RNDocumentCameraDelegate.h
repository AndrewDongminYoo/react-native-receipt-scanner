#import <Foundation/Foundation.h>
#import "RNScanOptions.h"

NS_ASSUME_NONNULL_BEGIN

/**
 * Block used by the package to resolve a JS `Promise` with the
 * `ScanReceiptResult`-shaped payload. Mirrors `RCTPromiseResolveBlock` but
 * is typed as `id` to keep the public delegate API decoupled from the
 * `<React/RCTBridgeModule.h>` import chain.
 */
typedef void (^RNResolveBlock)(id result);

/**
 * Block used to reject the JS `Promise` with an error code, message, and
 * optional `NSError`. Mirrors `RCTPromiseRejectBlock`.
 */
typedef void (^RNRejectBlock)(NSString *code, NSString *message, NSError * _Nullable error);

/**
 * `VNDocumentCameraViewControllerDelegate` adapter for the camera path.
 *
 * Owned (strongly retained) by `ReceiptScanner` while the camera VC is on
 * screen so the delegate isn't deallocated mid-presentation. On finish /
 * cancel / error the wrapped resolve/reject blocks tear down the strong
 * reference.
 *
 * This object is created on the main queue but performs all image
 * post-processing (`RNImageProcessor`, OCR) on a global background queue.
 */
@interface RNDocumentCameraDelegate : NSObject

/**
 * Designated initializer.
 *
 * @param options Parsed scan options.
 * @param resolve Block to call with the success / cancelled payload.
 * @param reject Block to call on failure.
 */
- (instancetype)initWithOptions:(RNScanOptions *)options
                        resolve:(RNResolveBlock)resolve
                         reject:(RNRejectBlock)reject;

@end

NS_ASSUME_NONNULL_END
