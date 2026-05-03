#import "ReceiptScanner.h"
#import "RNScanOptions.h"
#import "RNDocumentCameraDelegate.h"
#import "RNGalleryPickerDelegate.h"
#import <UIKit/UIKit.h>
#import <VisionKit/VisionKit.h>
#import <Photos/Photos.h>
#import <PhotosUI/PhotosUI.h>
#import <React/RCTUtils.h>

@interface ReceiptScanner ()
@property (nonatomic, strong, nullable) id cameraDelegate;
@property (nonatomic, strong, nullable) id galleryDelegate;
@end

@implementation ReceiptScanner

- (void)scan:(NSDictionary *)options
     resolve:(RCTPromiseResolveBlock)resolve
      reject:(RCTPromiseRejectBlock)reject {

    if (self.cameraDelegate || self.galleryDelegate) {
        reject(@"SCAN_IN_PROGRESS", @"A scan is already in progress", nil);
        return;
    }

    RNScanOptions *scanOptions = [RNScanOptions optionsFromDictionary:options];

    __weak __typeof__(self) weakSelf = self;
    RNResolveBlock wrappedResolve = ^(id result) {
        weakSelf.cameraDelegate  = nil;
        weakSelf.galleryDelegate = nil;
        resolve(result);
    };
    RNRejectBlock wrappedReject = ^(NSString *code, NSString *message, NSError *error) {
        weakSelf.cameraDelegate  = nil;
        weakSelf.galleryDelegate = nil;
        reject(code, message, error);
    };

    dispatch_async(dispatch_get_main_queue(), ^{
        UIViewController *rootVC = RCTPresentedViewController();
        if (!rootVC) {
            wrappedReject(@"NO_ACTIVITY", @"No presented view controller found", nil);
            return;
        }

        if ([scanOptions.source isEqualToString:@"gallery"]) {
            [self presentGalleryPickerWithOptions:scanOptions
                                    presentingVC:rootVC
                                         resolve:wrappedResolve
                                          reject:wrappedReject];
        } else {
            [self presentCameraScannerWithOptions:scanOptions
                                    presentingVC:rootVC
                                         resolve:wrappedResolve
                                          reject:wrappedReject];
        }
    });
}

- (void)presentCameraScannerWithOptions:(RNScanOptions *)options
                           presentingVC:(UIViewController *)presentingVC
                                resolve:(RNResolveBlock)resolve
                                 reject:(RNRejectBlock)reject {
    if (![VNDocumentCameraViewController isSupported]) {
        reject(@"NOT_SUPPORTED", @"VNDocumentCameraViewController is not supported on this device", nil);
        return;
    }

    RNDocumentCameraDelegate *delegate =
        [[RNDocumentCameraDelegate alloc] initWithOptions:options
                                                  resolve:resolve
                                                   reject:reject];
    self.cameraDelegate = delegate;

    VNDocumentCameraViewController *vc = [VNDocumentCameraViewController new];
    vc.delegate = delegate;
    vc.modalPresentationStyle = UIModalPresentationFullScreen;
    [presentingVC presentViewController:vc animated:YES completion:nil];
}

- (void)presentGalleryPickerWithOptions:(RNScanOptions *)options
                           presentingVC:(UIViewController *)presentingVC
                                resolve:(RNResolveBlock)resolve
                                 reject:(RNRejectBlock)reject {
    // We need library authorization so PHPickerResult.assetIdentifier is populated,
    // enabling definitive imageOrigin detection (screenshot subtype, etc.).
    // The picker itself works without authorization — we only need it for metadata.
    // Only show the system prompt when status is undecided; otherwise present immediately
    // so we don't add an async hop to every gallery scan.
    PHAuthorizationStatus status = [PHPhotoLibrary authorizationStatus];
    if (status == PHAuthorizationStatusNotDetermined) {
        [PHPhotoLibrary requestAuthorization:^(PHAuthorizationStatus newStatus) {
            BOOL hasAccess = (newStatus == PHAuthorizationStatusAuthorized ||
                              newStatus == PHAuthorizationStatusLimited);
            dispatch_async(dispatch_get_main_queue(), ^{
                [self showPickerWithOptions:options
                               presentingVC:presentingVC
                            hasLibraryAccess:hasAccess
                                    resolve:resolve
                                     reject:reject];
            });
        }];
        return;
    }

    BOOL hasAccess = (status == PHAuthorizationStatusAuthorized ||
                      status == PHAuthorizationStatusLimited);
    [self showPickerWithOptions:options
                   presentingVC:presentingVC
                hasLibraryAccess:hasAccess
                        resolve:resolve
                         reject:reject];
}

- (void)showPickerWithOptions:(RNScanOptions *)options
                 presentingVC:(UIViewController *)presentingVC
             hasLibraryAccess:(BOOL)hasLibraryAccess
                      resolve:(RNResolveBlock)resolve
                       reject:(RNRejectBlock)reject {
    PHPickerConfiguration *config;
    if (hasLibraryAccess) {
        // Library-backed config: assetIdentifier is populated in PHPickerResult,
        // allowing PHAsset metadata queries (screenshot subtype, etc.).
        config = [[PHPickerConfiguration alloc]
                    initWithPhotoLibrary:[PHPhotoLibrary sharedPhotoLibrary]];
    } else {
        config = [[PHPickerConfiguration alloc] init];
    }
    config.filter         = [PHPickerFilter imagesFilter];
    config.selectionLimit = options.maxPages;

    RNGalleryPickerDelegate *delegate =
        [[RNGalleryPickerDelegate alloc] initWithOptions:options
                                presentingViewController:presentingVC
                                        hasLibraryAccess:hasLibraryAccess
                                                 resolve:resolve
                                                  reject:reject];
    self.galleryDelegate = delegate;

    PHPickerViewController *picker =
        [[PHPickerViewController alloc] initWithConfiguration:config];
    picker.delegate = delegate;
    picker.modalPresentationStyle = UIModalPresentationFullScreen;
    [presentingVC presentViewController:picker animated:YES completion:nil];
}

- (std::shared_ptr<facebook::react::TurboModule>)getTurboModule:
    (const facebook::react::ObjCTurboModule::InitParams &)params {
    return std::make_shared<facebook::react::NativeReceiptScannerSpecJSI>(params);
}

+ (NSString *)moduleName {
    return @"ReceiptScanner";
}

@end
