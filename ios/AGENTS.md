# ios/ — Native iOS (Obj-C++ TurboModule)

## OVERVIEW

VisionKit camera + PHPicker gallery + custom 4-handle perspective crop editor. Module name `"ReceiptScanner"`. Min iOS 16 (Korean OCR via Vision). Frameworks declared in `ReceiptScanner.podspec`: VisionKit, Vision, PhotosUI, ImageIO, CoreImage, CoreGraphics, UniformTypeIdentifiers.

## STRUCTURE

```plaintext
ios/
├── ReceiptScanner.{h,mm}          Module entry; routes camera vs gallery; retains delegates
├── RNScanOptions.{h,m}            NSDictionary → typed RNScanOptions (clamps + defaults)
├── RNDocumentCameraDelegate.{h,m} VNDocumentCameraViewController callbacks; synthesizes EXIF
├── RNGalleryPickerDelegate.{h,m}  PHPicker → Vision detect → optional crop UI → process
├── RNCropEditorViewController.{h,m}  Custom 4-handle perspective crop editor (ADR-004)
├── RNImageProcessor.{h,m}         Orientation normalize + JPEG + EXIF + perspective correction
└── RNOcrProcessor.{h,m}           VNRecognizeTextRequest with ordered caller BCP 47 hints (iOS 16+)
```

## WHERE TO LOOK

| Task                                                   | File:Lines                                          |
| ------------------------------------------------------ | --------------------------------------------------- |
| Module strong-refs the delegates                       | `ReceiptScanner.mm:11-13, 28-38`                    |
| Camera vs gallery routing on main queue                | `ReceiptScanner.mm:40-58`                           |
| `cropAutoConfirm` threshold                            | `RNGalleryPickerDelegate.m:15, 212`                 |
| Multi-photo serial queue (avoids modal race)           | `RNGalleryPickerDelegate.m:60-67, 116-148, 366-376` |
| Vision rectangle detection (with explicit orientation) | `RNGalleryPickerDelegate.m:240-285`                 |
| Document segmentation mask path (preferred)            | `RNGalleryPickerDelegate.m:243, 263-273`            |
| Crop editor handles + button bar + hit-test order      | `RNCropEditorViewController.m:53-138`               |
| EXIF synthesis on camera scans                         | `RNImageProcessor.m:119-128`                        |
| Perspective correction (orientation baked first)       | `RNImageProcessor.m:163-192`                        |
| Localizable string keys                                | `RNCropEditorViewController.m:94-110`               |
| OCR language validation and Vision availability        | `RNOcrProcessor.m:18-23, 130-180`                   |

## CONVENTIONS

- **Strong refs on the module**: `cameraDelegate`/`galleryDelegate` are retained on `ReceiptScanner` until `wrappedResolve`/`wrappedReject` clears them. Weak references on UIKit views WILL crash mid-flow.
- **Single-scan guard — assign the delegate before clearing `preparingScan`.** `-scan:` rejects `SCAN_IN_PROGRESS` on `(preparingScan || cameraDelegate || galleryDelegate)`, and it reads those off the main queue while the presentation helpers write them on it. Clearing the flag first leaves a window where all three read empty, so a second scan is accepted and overwrites the only strong reference to a live delegate. Order every such transition so at least one of the three stays set.
- **Background thread for image work**: `dispatch_async(dispatch_get_global_queue(...))` in both delegates; resolve/reject hops back to the main queue.
- **Localization**: Read via `NSLocalizedStringWithDefaultValue(..., [NSBundle mainBundle], ...)`. Host apps add `RNReceiptScanner_cropInstruction` / `RNReceiptScanner_cancelButton` / `RNReceiptScanner_confirmButton` to their own `Localizable.strings`. Defaults are `"Drag the corners to frame the document"` / `"Cancel"` / `"Use Photo"`.
- **Output orientation is always `1` (Up)**: `RNImageProcessor.processImage:` writes both `kCGImagePropertyOrientation` and TIFF `Orientation` = `kCGImagePropertyOrientationUp`. JS receives `exif.orientation === 1` always.
- **ARC-managed `CGImageSourceRef`**: `RNCGImageSourceHolder` wraps the source so all early-return paths in the gallery delegate release it. Don't introduce raw `CGImageSourceRef` locals.
- **Per-call `CIContext`**: `RNImageProcessor.perspectiveCorrectedCGImage:` allocates `[CIContext context]` per call because `CIContext` is not thread-safe under `maxPages > 1`.
- **CGImage origin normalization**: After `imageByApplyingOrientation:`, defensively translate to `(0,0)` if extent origin shifted (`RNImageProcessor.m:174-178`).
- **`absoluteString`, not manual concat**: Always return `processed.fileURL.absoluteString` for `uri` — handles spaces and non-ASCII chars correctly. Manual `[@"file://" stringByAppendingString:fileURL.path]` breaks for usernames with special chars.

## ANTI-PATTERNS (ADR-004 — read before touching the crop editor)

- ❌ **`UIBarButtonItem`** for the crop editor toolbar — target-action silently fails in some RN modal paths. Use `UIButton` + `UIControlEventTouchUpInside`.
- ❌ **`safeAreaLayoutGuide.bottomAnchor`** for the button bar — can report `0` under `RCTPresentedViewController()`. Anchor to `view.bottomAnchor constant:-34` (home-indicator zone height).
- ❌ **Adding the button bar before the handles in `viewDidLoad`** — UIKit hit-tests subviews in REVERSE order; handles will absorb button taps. Bar MUST be added LAST.
- ❌ **`VNImageRequestHandler initWithCIImage:`** for orientation-bearing photos — Vision ignores `[CIImage initWithImage:]`'s embedded orientation. Use `initWithCGImage:orientation:` with explicit `CGImagePropertyOrientation`.
- ❌ **`[CIImage initWithImage:]` straight into `CIPerspectiveCorrection`** — orientation is lazy and the filter sees raw landscape pixels. Do `initWithCGImage:` then `imageByApplyingOrientation:` first.
- ❌ **`UIImageJPEGRepresentation`** — strips EXIF/TIFF dictionaries. Use `CGImageDestination` with `kCGImageDestinationLossyCompressionQuality`.
- ❌ **Manual `"file://" + path` concat** for the `uri` field — breaks on spaces / non-ASCII. Use `fileURL.absoluteString`.
- ❌ **Static / shared `CIContext`** — race under `maxPages > 1`. Allocate per call.
- ❌ Using `-34` as a generic offset somewhere else — it's specifically the Face-ID home-indicator zone height; document the source if you reuse it.
- ❌ Removing the `RNCGImageSourceHolder` wrapper — every early-return path then leaks a `CGImageSourceRef`.
- ❌ **Concurrent `presentViewController:` on the same presenter** for `maxPages > 1` gallery scans. After PHPicker dismisses, fanning out N `loadDataRepresentation` → `present` chains in a single for-loop issues N main-queue presentations near-simultaneously; UIKit silently rejects all but the first, and the rejected editors' completion blocks are never invoked → Promise hangs forever. `RNGalleryPickerDelegate` serializes via `queuedItems` + `queueIndex` + `processNextQueuedItem`; chain the next photo from inside `didFinishOneItem:`. Do NOT reintroduce a parallel for-loop here.
