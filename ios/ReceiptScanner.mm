#import "ReceiptScanner.h"
#import "RNScanOptions.h"
#import "RNDocumentCameraDelegate.h"
#import "RNGalleryPickerDelegate.h"
#import "RNOcrProcessor.h"
#import <UIKit/UIKit.h>
#import <VisionKit/VisionKit.h>
#import <PhotosUI/PhotosUI.h>
#import <React/RCTUtils.h>

static NSString *RNOcrErrorCode(NSError *error) {
    id code = error.userInfo[@"code"];
    return [code isKindOfClass:[NSString class]] ? code : @"OCR_LANGUAGE_NOT_SUPPORTED";
}

@interface ReceiptScanner ()
@property (nonatomic, strong, nullable) id cameraDelegate;
@property (nonatomic, strong, nullable) id galleryDelegate;
@property (nonatomic, assign) BOOL preparingScan;
@end

@implementation ReceiptScanner

- (void)scan:(NSDictionary *)options
     resolve:(RCTPromiseResolveBlock)resolve
      reject:(RCTPromiseRejectBlock)reject {

    if (self.preparingScan || self.cameraDelegate || self.galleryDelegate) {
        reject(@"SCAN_IN_PROGRESS", @"A scan is already in progress", nil);
        return;
    }

    self.preparingScan = YES;
    RNScanOptions *scanOptions = [RNScanOptions optionsFromDictionary:options];

    __weak __typeof__(self) weakSelf = self;
    RNResolveBlock wrappedResolve = ^(id result) {
        weakSelf.preparingScan = NO;
        weakSelf.cameraDelegate  = nil;
        weakSelf.galleryDelegate = nil;
        resolve(result);
    };
    RNRejectBlock wrappedReject = ^(NSString *code, NSString *message, NSError *error) {
        weakSelf.preparingScan = NO;
        weakSelf.cameraDelegate  = nil;
        weakSelf.galleryDelegate = nil;
        reject(code, message, error);
    };

    if (scanOptions.ocr) {
        NSError *languageError = nil;
        NSArray<NSString *> *languages =
            [RNOcrProcessor validateRecognitionLanguages:scanOptions.ocrLanguages error:&languageError];
        if (!languages) {
            self.preparingScan = NO;
            reject(RNOcrErrorCode(languageError), languageError.localizedDescription, languageError);
            return;
        }
        scanOptions.ocrLanguages = languages;
    }

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
    // Take delegate ownership BEFORE dropping the preparation flag: the -scan:
    // guard reads (preparingScan || cameraDelegate || galleryDelegate) off this
    // queue, so clearing first leaves a window where all three read empty and a
    // second scan is accepted — overwriting the only strong ref to `delegate`
    // and tearing down a live modal. This order keeps one of them always set.
    self.cameraDelegate = delegate;
    self.preparingScan = NO;

    VNDocumentCameraViewController *vc = [VNDocumentCameraViewController new];
    vc.delegate = delegate;
    vc.modalPresentationStyle = UIModalPresentationFullScreen;
    [presentingVC presentViewController:vc animated:YES completion:nil];
}

- (void)presentGalleryPickerWithOptions:(RNScanOptions *)options
                           presentingVC:(UIViewController *)presentingVC
                                resolve:(RNResolveBlock)resolve
                                 reject:(RNRejectBlock)reject {
    PHPickerConfiguration *config = [[PHPickerConfiguration alloc] init];
    config.filter         = [PHPickerFilter imagesFilter];
    config.selectionLimit = options.maxPages;

    RNGalleryPickerDelegate *delegate =
        [[RNGalleryPickerDelegate alloc] initWithOptions:options
                                presentingViewController:presentingVC
                                                 resolve:resolve
                                                  reject:reject];
    // Ownership before the flag — see -presentCameraScannerWithOptions:.
    self.galleryDelegate = delegate;
    self.preparingScan = NO;

    PHPickerViewController *picker =
        [[PHPickerViewController alloc] initWithConfiguration:config];
    picker.delegate = delegate;
    picker.modalPresentationStyle = UIModalPresentationFullScreen;
    [presentingVC presentViewController:picker animated:YES completion:nil];
}

- (void)getOcrCapabilities:(RCTPromiseResolveBlock)resolve
                    reject:(RCTPromiseRejectBlock)reject {
    NSError *error = nil;
    NSArray<NSString *> *supportedLanguages = [RNOcrProcessor supportedRecognitionLanguages:&error];
    if (!supportedLanguages) {
        reject(@"OCR_LANGUAGE_NOT_SUPPORTED", error.localizedDescription, error);
        return;
    }

    resolve(@{
        @"platform": @"ios",
        @"defaultLanguages": @[@"ko-KR", @"en-US"],
        @"supportedLanguages": supportedLanguages,
    });
}

- (std::shared_ptr<facebook::react::TurboModule>)getTurboModule:
    (const facebook::react::ObjCTurboModule::InitParams &)params {
    return std::make_shared<facebook::react::NativeReceiptScannerSpecJSI>(params);
}

+ (NSString *)moduleName {
    return @"ReceiptScanner";
}

@end
