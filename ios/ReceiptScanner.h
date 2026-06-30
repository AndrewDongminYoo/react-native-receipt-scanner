#import <ReceiptScannerSpec/ReceiptScannerSpec.h>

/**
 * iOS TurboModule entry for the `ReceiptScanner` package.
 *
 * Routes `source: "camera"` to `VNDocumentCameraViewController` (via
 * `RNDocumentCameraDelegate`) and `source: "gallery"` to
 * `PHPickerViewController` + `RNCropEditorViewController` (via
 * `RNGalleryPickerDelegate`). Holds strong references to the active
 * delegate so it survives modal presentation; clears them in the wrapped
 * resolve / reject blocks.
 *
 * The module name string `@"ReceiptScanner"` must stay in lockstep with
 * the JS spec (`src/NativeReceiptScanner.ts`) and the Android registration
 * (`ReceiptScannerPackage.kt`).
 *
 * @see RNDocumentCameraDelegate
 * @see RNGalleryPickerDelegate
 */
@interface ReceiptScanner : NSObject <NativeReceiptScannerSpec>

@end
