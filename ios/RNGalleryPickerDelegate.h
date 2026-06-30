#import <Foundation/Foundation.h>
#import "RNScanOptions.h"
#import "RNDocumentCameraDelegate.h"

NS_ASSUME_NONNULL_BEGIN

@class UIViewController;

/**
 * `PHPickerViewControllerDelegate` adapter for the gallery path.
 *
 * Pipeline: `PHPicker` selection → `PHAsset` origin lookup (when library
 * authorization is granted) → `VNDetectDocumentSegmentationRequest` (with
 * a `VNDetectRectanglesRequest` fallback) → `RNCropEditorViewController`
 * (skipped when `cropAutoConfirm` is `YES` and detection confidence is high)
 * → `RNImageProcessor` for perspective correction, EXIF read, and JPEG
 * encoding.
 *
 * Owned (strongly retained) by `ReceiptScanner` while the picker is on
 * screen. All image work runs off the main queue; `presentingVC` is a weak
 * reference because the host VC may dismiss before processing finishes.
 */
@interface RNGalleryPickerDelegate : NSObject

/**
 * Designated initializer.
 *
 * @param options Parsed scan options.
 * @param presentingVC The view controller that will present the picker /
 *                     crop editor. Held weakly.
 * @param hasLibraryAccess `YES` when the user has granted Photos
 *        authorization. Determines whether `PHPickerResult.assetIdentifier`
 *        is populated, enabling definitive origin classification (screenshot
 *        subtype, etc.).
 * @param resolve Block to call with the success / cancelled payload.
 * @param reject Block to call on failure.
 */
- (instancetype)initWithOptions:(RNScanOptions *)options
       presentingViewController:(UIViewController *)presentingVC
               hasLibraryAccess:(BOOL)hasLibraryAccess
                        resolve:(RNResolveBlock)resolve
                         reject:(RNRejectBlock)reject;

@end

NS_ASSUME_NONNULL_END
