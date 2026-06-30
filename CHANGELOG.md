# Changelog

## [0.6.0](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/compare/v0.5.0...v0.6.0) (2026-06-22)

Gallery crop robustness. Adds a cross-platform quad-distortion backstop so a bad document detection no longer warps a receipt into a distorted image, and discards low-confidence gallery detections that previously latched onto a screenshot's UI chrome. No public API changes — drop-in upgrade from 0.5.0.

### Added

- **ios/android:** quad-distortion backstop on the gallery crop path. Before perspective-warping the detected or user-confirmed quad, an egregiously distorted (opposite-edge ratio > `MAX_EDGE_RATIO` = 2.2) or degenerate (non-convex / coincident-corner) quad is rejected: the warp is skipped in favor of an axis-aligned bounding-box crop (no reshape — soft fail), and a distorted _detected_ quad is discarded at seeding so the editor falls back to its 10% inset default. It is a backstop, not a classifier — geometry cannot separate a legitimate angled receipt from a pathological one, so only the egregious tail is rejected. New `RNQuadGeometry` (iOS) / `QuadGeometry` (Android) predicates with thresholds kept in sync ([#3](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/pull/3), [3e1a0b7](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/3e1a0b7), [d5a182b](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/d5a182b), [0630907](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/0630907), [151e9f6](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/151e9f6)).

### Fixed

- **ios:** low-confidence gallery detections are now discarded. `VNDetectDocumentSegmentationRequest` could return a ~0.004-confidence quad latching onto the thin gap above a receipt embedded in a screenshot, which was seeded into the crop editor unconditionally. A `kDetectionMinConfidence` floor (0.1) drops it so the editor falls back to its 10% inset default ([2345438](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/2345438), [13791ce](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/13791ce)).
- **android:** the distorted-quad bounding-box fallback could return the _same_ bitmap instance as its input when an immutable source is cropped in full, so the caller recycled it and then crashed on `compress()` with "Can't compress a recycled bitmap". The fallback now copies in that case ([464f20c](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/464f20c)).

### Internal

- **ios/android:** dropped the redundant `MIN_EDGE_FRACTION` degeneracy gate — a collapsed corner is already caught by the convexity and opposite-edge-ratio checks, and the gate's only distinct effect was misflagging legitimate very-long (aspect ratio above ~20:1) receipts as distorted. iOS confidence-handling cleanup: the rectangle candidate's floor check was dead (the detector enforces its own `minimumConfidence = 0.5`), and `*confidence` now honors its documented "0 on failure" contract on the distorted-reject path ([b38ab57](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/b38ab57), [e6e7716](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/e6e7716)).
- **docs:** quad-distortion backstop design spec, implementation plan, threshold-calibration inventory, and the platform-asymmetry note ([534076e](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/534076e), [b9445bb](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/b9445bb), [bce69db](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/bce69db), [0959c42](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/0959c42)).

---

## [0.5.0](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/compare/v0.4.5...v0.5.0) (2026-06-11)

OCR tunability and cross-platform metadata parity. Adds the iOS `minimumTextHeight` option, populates `ocrQuality.confidence` on both platforms, and writes file-level EXIF onto the Android output JPEG. **API note:** one additive, backwards-compatible option (`minimumTextHeight`) — drop-in upgrade from 0.4.5.

### Added

- **ios:** new `ScanReceiptOptions.minimumTextHeight` option — the minimum text height Vision will recognize, as a fraction of image height (`0` = the platform default ≈ 1/32). Lower it to recover small receipt line items. iOS only; Android (ML Kit) has no equivalent and ignores it. Default behavior is unchanged ([7e278b8](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/7e278b8)).
- **android:** the output JPEG now carries file-level EXIF. Previously EXIF was emitted only in the JS payload, so server-side file readers saw an empty block (asymmetric with iOS). Structured tags (make/model/software/date-times/GPS) are written onto the file after auto-rotate, only when `includeExif`; the JS `exif` payload is unchanged ([9c24e71](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/9c24e71)).

### Fixed

- **ios/android:** `ocrQuality.confidence` is now populated on both platforms. It was computed on iOS but dropped at the delegate boundary, and never computed on Android, so the field was always `undefined`. iOS forwards `meanConfidence`; Android computes mean per-line ML Kit confidence (NaN-guarded). Reporting-only ([25cc546](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/25cc546)).

### Internal

- **ios:** added DEBUG-only per-observation `boundingBox` aspect diagnostics (trimmed mean, mirroring Android `lineAspectOf`) and recorded the geometry-routing design — iOS Vision is rotation-variant, so geometry is a probe-reduction/tie-break over count routing, not a single-pass replacement. Reporting-only; no routing change ([e4780fa](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/e4780fa)).
- **docs:** added the OCR threshold calibration methodology spec and the `imageOrigin` enforcement re-design conditions ([eeefac6](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/eeefac6), [1a8a830](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/1a8a830)).
- **ci:** extended the release workflow and release skill with GitHub Release + Package completion checks ([4fbc9fe](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/4fbc9fe), [b7038aa](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/b7038aa), [08f21be](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/08f21be)).

---

## [0.4.5](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/compare/v0.4.4...v0.4.5) (2026-06-10)

Receipt OCR quality-hardening pass (phase 6). No public API changes. **Behavior note:** the Android camera scanner no longer offers in-camera "import from gallery" — route non-camera input through `source: "gallery"`.

### Changed

#### iOS

- **ios:** OCR rotation routing no longer compares `.fast`-probe against `.accurate`-baseline confidence (a cross-level, structurally-invalid comparison). Rotation is now decided from the count of non-empty recognized lines; `meanConfidence` is kept for `ocrQuality.confidence` reporting only. Thresholds are conservative and biased against rotating, pending on-device calibration ([341530a](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/341530a), [0a8055d](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/0a8055d)).

#### Android

- **android:** disabled the in-camera "import from gallery" affordance (`setGalleryImportAllowed(false)`) so all non-camera input flows through `source: "gallery"` → `CropEditorActivity`, preserving the original `content://` URI and EXIF. Closes the previously-accepted two-gallery-path leak (EXIF loss, `imageOrigin` collapsing to `"unknown"`) ([c820bc8](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/c820bc8)).

### Fixed

#### iOS

- **ios:** disabled Vision `usesLanguageCorrection` for receipt OCR. Prices, product codes, and short tokens are not dictionary words, so language correction over-corrected and distorted them; recognized receipt text is now more faithful ([3e98189](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/3e98189)).

### Internal

- **ios:** extracted `minimumTextHeight` to a tunable constant set to the platform default — behavior-preserving, lets small-line recall be tuned later ([c954eb9](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/c954eb9)).
- **ios:** added DEBUG-only OCR diagnostics (observation count, mean confidence, candidate-spread); compiled out of release builds, no routing change ([8c2c9a3](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/8c2c9a3)).
- **android:** pinned and guarded the ML Kit Korean recognizer version against a rotation-invariance regression — a `build.gradle` guard comment plus a `docs/notes/ml-kit-korean-rotation-invariance.md` re-validation procedure for version bumps ([3842f4a](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/3842f4a), [56bec2f](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/56bec2f)).
- **docs:** corrected the inaccurate "Android cannot report OCR confidence" comments in `src/scan.native.tsx` / `src/types.ts` — ML Kit v2 exposes `Text.Element.getConfidence()`; the field is unwired, not unavailable ([8c2c9a3](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/8c2c9a3)).
- **docs:** added the phase-6 receipt-data-quality-hardening plan and synced ADR-005 / `scan-pipeline.md` / `build.gradle` comments to the disabled in-camera gallery import ([f933893](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/f933893), [6342047](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/6342047)).

---

## [0.4.4](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/compare/v0.4.3...v0.4.4) (2026-05-29)

Improves the gallery crop editor's initial selection and guidance on both platforms. No public API changes — drop-in upgrade from 0.4.3.

### Fixed

#### Android

- **android:** the gallery crop editor now expands text-block-derived document corners before showing the initial crop selection, so receipts that are detected too tightly around OCR text include more document/background area by default. The crop screen also shows a localized instruction telling the user to align the document corners ([a8591f7](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/a8591f7)).

#### iOS

- **ios:** the gallery crop editor now expands Vision-detected document corners before placing the handles, reducing overly narrow initial crop areas while still clamping the selection to the image bounds. The crop screen also shows the same localized corner-selection instruction ([a8591f7](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/a8591f7)).

### Internal

- **docs:** documented the cross-platform crop-editor localization keys and host-app override paths in the README and API contract ([a8591f7](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/a8591f7)).
- **docs:** added ADR-007 documenting why v0.4.2 and v0.4.3 were separate Android gallery-flow patch releases ([2301069](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/2301069)).
- **ci:** updated the GitHub Packages publish workflow command to use Yarn's npm publish command ([add7c88](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/add7c88)).

---

## [0.4.3](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/compare/v0.4.2...v0.4.3) (2026-05-19)

Fixes the remaining Android gallery silent-cancel path seen after selecting images from the system Photo Picker. No public API changes — drop-in upgrade from 0.4.2.

### Fixed

#### Android

- **android:** `CropEditorActivity` now launches the system Photo Picker through `registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia)` instead of nesting a picker `startActivityForResult` inside the activity that is itself launched by `ReceiptScannerModule`. On Android 14+ devices, the nested result path could deliver a cancelled `GALLERY_REQUEST_CODE` result (`resultCode=0`, `dataNull=true`) to the module immediately after the picker returned selected URIs, causing JS to receive `{ status: "cancelled", images: [] }` even though the crop flow had started. The picker is registered during `onCreate` and launched once from `onPostResume`, so selected URIs stay inside `CropEditorActivity` until the final crop result is returned ([4aaa379](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/4aaa379)).

### Internal

- **android:** removed a redundant deprecation suppression from the gallery back-button cancel path ([fcd37ee](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/fcd37ee)).

---

## [0.4.2](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/compare/v0.4.1...v0.4.2) (2026-05-18)

Fixes a production silent-cancel in the Android gallery flow that affected v13.2.0 of the consumer app on multiple device vendors (Samsung Galaxy S24 Ultra / Nothing Phone, Android 14 and 16). No public API changes — drop-in upgrade from 0.4.1.

### Fixed

#### Android

- **android:** `ImageProcessor.decodeBitmapSampled` was throwing `IllegalArgumentException: Failed to open content stream` whenever `ContentResolver.openInputStream` returned `null` for a Photo Picker URI (`content://media/picker/...`). The exception was caught by `CropEditorActivity.loadAndDisplayImage` and turned into a silent `RESULT_CANCELED`, leaving the JS layer with `status: 'cancelled'` and no visible feedback. Cross-vendor reproduction on Samsung (Android 16) and Nothing (Android 14) confirmed the picker provider's `openAssetFile` can graceful-null even when the underlying file is readable through the FD-based path. Adds an `openContentStream` helper that falls back to `ContentResolver.openFileDescriptor` + `ParcelFileDescriptor.AutoCloseInputStream` when `openInputStream` returns `null`; only throws `IOException` (with the resolved MIME type) when both paths fail. Both call sites in `decodeBitmapSampled` route through the helper ([dc250ae](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/dc250ae))

### Internal

- **android:** `CropEditorActivity` no longer swallows exceptions in `loadAndDisplayImage`, `readExifOrientation`, or `onConfirmTapped`'s cache-copy `Thread`. Each `catch (_: Exception)` becomes `catch (e: Exception)` with a matching `Log.e` / `Log.w` carrying the failing URI and the exception cause. The behavior is unchanged (graceful fallback for EXIF; `RESULT_CANCELED` for the two cancel paths), but silent-cancel investigations no longer require patching the library to learn the exception type ([5bc928e](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/5bc928e))

---

## [0.4.1](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/compare/v0.4.0...v0.4.1) (2026-05-14)

Real-device hardening pass over the gallery batch path on both platforms. No public API changes — drop-in upgrade from 0.4.0.

### Fixed

#### Android

- **android:** OOM crash on multi-photo gallery batches — `ImageProcessor.processGallery` decoded the original URI at full resolution, then `applyExifRotation` allocated a second bitmap of the same size for any non-`NORMAL` EXIF orientation. On 50–200 MP camera output this peaked at 200–600 MB per image and batches of 6 images crashed the executor as the native heap fragmented. Introduces `decodeBitmapSampled(uri, maxDim)` using `inSampleSize` so the longer side fits within `GALLERY_MAX_DIM` (3072); corners are scaled by `1/sample` before perspective correction. `ReceiptScannerModule.handleGalleryResult` now catches `OutOfMemoryError` explicitly (Error, not Exception — slips through the existing catch) and moves `ocrProcessor.close()` to `finally` so a thrown iteration no longer leaks the ML Kit recognizer client ([10360b7](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/10360b7))

#### iOS

- **ios:** PHPicker multi-select Promise hang — the picker fanned out N concurrent `presentViewController:` calls on the same presenter after dismissing itself. UIKit silently rejects all but the first; the rejected crop editors never invoke their completion blocks, so the `pendingCount` fan-in counter stalled and the JS Promise hung forever. Replaced `pendingCount` + parallel for-loop with `queuedItems` + `queueIndex` + `processNextQueuedItem` — one photo end-to-end at a time, the next item chained from inside `didFinishOneItem:`. Cancel-mid-batch semantics (skip this photo, continue to the next) preserved to match the Android `CropEditorActivity` flow ([9d3bdee](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/9d3bdee))

### Internal

- **android:** `decodeBitmapSampled` hoisted to `ImageProcessor` companion object and reused by `CropEditorActivity` preview decode (`PREVIEW_MAX_DIM = 2048`) instead of duplicating the `inSampleSize` loop inline ([3b1027c](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/3b1027c))
- **android:** always-on diagnostic logcat at the gallery picker boundary — `Log.i("ReceiptScanner.Gallery", …)` at every decision point in `CropEditorActivity.onCreate` / `onActivityResult` / `loadNextImage` / `returnAllResults`, plus matching boundary logs in `ReceiptScannerModule.handleGalleryResult`. Read with `adb logcat -s ReceiptScanner.Gallery:I` to diagnose silent `status: 'cancelled'` returns ([b884537](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/b884537))
- **example:** OCR fixture infrastructure for Phase 5 — fixture dump UI on the result page, PII redaction gate before file write, consolidated EXIF rendering. Real-device captures are now collected as Jest fixtures for downstream autoFlip work ([52b38c2](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/52b38c2), [d6245b0](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/d6245b0), [3c472b2](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/3c472b2), [36e4b9c](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/36e4b9c), [121949f](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/121949f))

---

## [0.4.0](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/compare/v0.3.5...v0.4.0) (2026-05-10)

Wraps the 0.3.x audit line. Lands ADR-006 D14 after four iterations driven by Galaxy Z Flip6 field data.

### Changed

#### Android

- **android:** content-rotation detection rewritten to single-pass aspect mismatch (ADR-006 D14 v1.3) — Galaxy Z Flip6 field data showed ML Kit Korean's results are _rotation-invariant_ (every probe in a 4-pass loop returned identical `lineCount` / `lineAspect` / `textLength`), so multi-pass detection cannot work. Replaced with a single OCR pass plus an `imageAspect` vs `lineAspect` direction comparison: when the image is landscape but lines are vertical (`lineAspect < 0.7`), the content is a rotated portrait receipt and a default 270° CW rotation is applied. Cost: every Android scan is now bounded at **one OCR pass** — v0.3.x's weak-signal probes (-150 to -450 ms) are gone ([7454ae8](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/7454ae8))
- **android:** always-on diagnostic logcat — `Log.i("ReceiptScanner.Ocr", …)` emits `lineCount` / `lineAspect` / `textLength` / `imageAspect` per probe and the final decision so future field reports can drive threshold tuning. Text content is excluded for PII safety. Read with `adb logcat -s ReceiptScanner.Ocr:I` ([9959ce6](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/9959ce6))

### Internal

- docs: `docs/notes/platform-asymmetries.md` — new living document tracking every iOS / Android semantic difference (`rotationDegrees` direction (iOS CCW vs Android CW), OCR rotation invariance, EXIF output asymmetry, Vision per-line confidence vs ML Kit absent, etc.). Read before designing any cross-platform feature ([7454ae8](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/7454ae8))
- docs: `docs/specs/portrait-rotation-detection.md` — Android-only spec recording the v1.0 → v1.1 → v1.2 → v1.3 iteration history so a future re-open has the prior dead ends documented ([bd1c15e](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/bd1c15e))

### Cumulative summary of 0.3.0 → 0.4.0

| Tag    | Theme                                                             |
| ------ | ----------------------------------------------------------------- |
| v0.3.1 | Pre-audit features (multi-image, iOS 180° OCR, crop UX)           |
| v0.3.2 | Audit baseline (iOS 16, exif.software, AndroidCameraOptions drop) |
| v0.3.3 | OCR floor + rejected status + always-array result                 |
| v0.3.4 | autoRotate (4-pass OCR-based content rotation)                    |
| v0.3.5 | ReceiptExif white-list expansion + raw passthrough                |
| v0.4.0 | Android single-pass aspect-mismatch rotation (D14 v1.3)           |

Breaking changes vs 0.3.0 (0.x minor allowed): iOS 16.0 minimum · `ScanReceiptResult.status` gains `"rejected"` · `rejectedImages` now always-array · `ocrFloor` defaults ON · `autoRotate` defaults ON · `AndroidCameraOptions` removed.

---

## [0.3.5](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/compare/v0.3.4...v0.3.5) (2026-05-10)

Surfaces enough EXIF for consumers to migrate from `@lodev09/react-native-exify` with minimal code change. Implements ADR-006 D8.

### Added

#### JS / Cross-platform

- **types:** `ReceiptExif` white-list adds 13 fields that are stable on both iOS ImageIO and Android `ExifInterface` — `dateTime`, `dateTimeDigitized`, `exposureTime`, `fNumber`, `iso`, `focalLength`, `flash`, `whiteBalance`, `exposureMode`, `exposureProgram`, `meteringMode`, `colorSpace`, `lightSource`, `exifVersion`. ISO is normalized from iOS's `ISOSpeedRatings` array to a single number to match Android ([f52ee34](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/f52ee34))
- **types:** `ReceiptExif.gps` adds `altitude`, `timestamp`, `speed`, `heading` ([f52ee34](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/f52ee34))
- **api:** `ScanReceiptOptions.includeRawExif` (default `false`) — when `true`, `ReceiptExif.raw` carries the full TIFF/EXIF/GPS dictionary flattened by standard EXIF tag name. Binary fields (`Thumbnail*`, `MakerNote`, `UserComment`, `JPEGInterchangeFormat`) are excluded. GPS-prefixed keys are excluded from `raw` whenever `includeGpsExif === false`, matching the white-list `gps` policy ([f52ee34](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/f52ee34))
- **types:** `raw.Orientation` preserves the _original_ EXIF value (1–8); the white-list `orientation` stays `1` (output pixels are normalized) ([f52ee34](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/f52ee34))

### Internal

- **android:** `ImageProcessor` iterates `ExifInterface.TAG_*` static fields via reflection — no hand-maintained list ([f52ee34](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/f52ee34))
- **ios:** `RNImageProcessor` flattens TIFF / EXIF / GPS dicts with a `GPS` prefix on GPS keys for cross-platform key alignment ([f52ee34](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/f52ee34))

---

## [0.3.4](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/compare/v0.3.3...v0.3.4) (2026-05-09)

Implements ADR-006 D7 — landscape gallery captures (CCW-rotated iPhone photos, sideways PDFs) get their output JPEG pixels rotated upright before being returned.

### Added

#### JS / Cross-platform

- **api:** `ScanReceiptOptions.autoRotate` (default `true`) — detects 90° / 180° / 270° content rotation via OCR confidence and bakes the chosen rotation into the output JPEG. Effective only when `ocr === true`. Output `width` / `height` swap accordingly; `exif.orientation` stays `1`. Pass `false` for v1.0 behaviour (180° text correction in OCR but no pixel rotation) ([dd48b9b](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/dd48b9b))

### Changed

#### iOS

- **ios:** `RNOcrProcessor.recognizeTextInImage:` replaced with `recognizeAndDetectRotationInImage:` returning `RNOcrResult { text, rotationDegrees, meanConfidence }`. `RNDocumentCameraDelegate` and `RNGalleryPickerDelegate` run OCR before encoding so the chosen rotation can be applied via `RNImageProcessor.cgImageByRotating` without a re-decode ([dd48b9b](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/dd48b9b))

#### Android

- **android:** `OcrProcessor.recognizeWithRotationDetection` runs the 4-pass with `InputImage.fromBitmap(bitmap, rotationDegrees)` hints. Aspect-ratio gate keeps portrait inputs cheap (180° probe only); landscape inputs add 90° / 270° fast probes. Genuine landscape receipts (high signal + ≥ 5 lines + aspect ≤ 1.5) skip the probes. `ImageProcessor.rotateFileInPlace` re-encodes the JPEG ([dd48b9b](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/dd48b9b))

### Internal

- **spec:** `docs/specs/ocr-orientation-correction.md` rewritten as v2.0 — 180°-only detection (v1.0) extended to a 4-rotation algorithm with an aspect-ratio gate. Phase-4 plan updated to point at v2.0 ([dd48b9b](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/dd48b9b))

---

## [0.3.3](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/compare/v0.3.2...v0.3.3) (2026-05-09)

Implements ADR-006 D6 — the package gates obviously bad captures (blank pages, landscape-of-the-floor, etc.) before returning them. Also tightens result symmetry.

### Added

#### JS / Cross-platform

- **api:** `ScanReceiptOptions.ocrFloor` (default `{ minTextLength: 12, minLines: 2, minConfidence: 0 }`) — captures with shorter OCR are routed to `rejectedImages` and `status: "rejected"`. Pass `false` to disable the check, or an `OcrFloor` object to override per call ([b8034bf](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/b8034bf))
- **types:** `ReceiptImage.ocrQuality { textLength, lineCount, confidence? }` — derived in JS from `ocrText` so threshold tuning doesn't require a native rebuild. `confidence` is iOS-only (Android ML Kit Korean does not expose per-line confidence) ([b8034bf](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/b8034bf))
- **types:** `OcrFloor`, `OcrQuality`, `DEFAULT_OCR_FLOOR` exported from `src/index.tsx` ([b8034bf](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/b8034bf))

### Changed

#### JS / Cross-platform

- **types:** `ScanReceiptResult.status` union gains `"rejected"`. Consumers using exhaustive switch see a compile error; simple `=== "success"` checks are unaffected ([b8034bf](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/b8034bf))
- **types:** `rejectedImages: ReceiptImage[]` is now always an array (was `rejectedImages?:`) — symmetric with `images` for caller ergonomics. Consumers can read `result.rejectedImages.length` without a null-check ([197af2b](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/197af2b))

### Fixed

- **docs:** corrected `exif.software` guidance — iOS camera writes the OS version (e.g. `"17.0"`, `"26.4.2"`), not "always empty on native cameras" as the audit originally said. Added a Software-tag patterns table to `api-contract.md` so consumers match by _value pattern_ rather than presence/absence ([b029257](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/b029257))

---

## [0.3.2](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/compare/v0.3.1...v0.3.2) (2026-05-09)

Outcomes of the 2026-05-09 design audit (ADR-006 D1–D5).

### Changed

#### iOS

- **ios:** **BREAKING** (0.x minor allowed) — pin iOS deployment target to **16.0** in `ReceiptScanner.podspec`. The package no longer ships an en-US fallback for older iOS — Korean OCR via `VNRecognizeTextRequest` requires iOS 16. `RNOcrProcessor.m`'s `@available(iOS 16, *)` guard is removed; the language list is unconditionally `["ko-KR", "en-US"]`. `RNImageProcessor.m`'s `@available(iOS 14, *)` UTI guard is also removed ([5481ab0](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/5481ab0))

#### JS / Cross-platform

- **types:** `AndroidCameraOptions` removed from `src/types.ts` — was never exported from `src/index.tsx`, never read by `ScanOptions.kt`, and never used by the consumer. The internal knobs (`scannerMode`, `setGalleryImportAllowed`) are pinned by deliberate decision (ADR-001, ADR-005) and shouldn't be exposed as user options ([0351817](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/0351817))

### Added

#### JS / Cross-platform

- **types:** `ReceiptExif.software` — TIFF Software tag, surfaced on both platforms. iOS camera writes the OS version (e.g. `"17.0"`, `"26.4.2"`); Android camera writes a vendor / firmware identifier (e.g. `"MIUI Camera"`, `"F741NKSS3CZCS"` on Galaxy Z Flip6); editors / generators write their own name. Use the _value pattern_ — not mere presence — for fraud filtering ([0351817](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/0351817))

### Internal

- **docs:** ADR-005 (`docs/notes/adr-005-android-gallery-strategy.md`) — documents why Android `source: "gallery"` routes to `CropEditorActivity` rather than GMS gallery import (EXIF preservation, `imageOrigin` classification, multi-image flow) ([5b73235](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/5b73235))
- **docs:** ADR-006 (`docs/notes/adr-006-design-audit-and-ios16-baseline.md`) — captures all audit decisions: iOS 16 baseline, EXIF `software` field, dropped `AndroidCameraOptions`, `ocrText` intent (raw primitive for consumer-side keyword classification), and deferred items ([5b73235](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/5b73235))
- **docs:** README aligned with the audited spec — `software` field, orientation invariant, camera-path EXIF synthesis ([a61ced3](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/a61ced3))
- **android:** added a quiet debug log on the module's `onActivityResult` path for field diagnostics ([3a1d9e1](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/3a1d9e1))

---

## [0.3.1](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/compare/v0.3.0...v0.3.1) (2026-05-09)

Pre-audit feature additions and bug fixes shipped on top of 0.3.0.

### Added

#### iOS

- **ios:** confidence-based 2-pass OCR with optional 3rd pass that detects and corrects 180°-rotated receipt captures before returning `ocrText`. `RNOcrProcessor` now scores the primary recognition pass and probes the 180°-rotated image when confidence is low; the rotated result is committed only when it clears a 15% margin over the original. See `docs/specs/ocr-orientation-correction.md` v1.0 ([af91075](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/af91075))

#### Android

- **android:** multi-image selection and per-image cropping in the gallery flow — `source: "gallery"` with `maxPages > 1` now lets the user select multiple images via `MediaStore.ACTION_PICK_IMAGES` (API 33+) or `Intent.ACTION_GET_CONTENT + EXTRA_ALLOW_MULTIPLE` (older), then sequentially walks each through the perspective-crop editor ([b69ca66](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/b69ca66))

### Fixed

#### Android

- **android:** reset `QuadCropView.userHasAdjusted` and apply the default 10% inset between crop sessions so the previous image's selection doesn't leak into the next page ([d8779b3](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/d8779b3))

### Internal

- **docs:** `docs/specs/ocr-orientation-correction.md` v1.0 + `docs/plans/phase-4-ocr-orientation-correction.md` — algorithm spec and implementation plan for the 180° detection ([f5de178](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/f5de178))
- **chore:** updated author information ([c020fba](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/c020fba))
- **chore:** miscellaneous example app and documentation updates ([a49758e](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/a49758e))

---

## [0.3.0](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/compare/v0.2.0...v0.3.0) (2026-05-06)

### Added

#### Android

- **android:** interactive quad-crop gallery editor — `source: "gallery"` now presents a 4-handle perspective-crop editor before image processing, matching the iOS gallery flow. Powered by `CropEditorActivity` and `QuadCropView`.
- **android:** synthesized EXIF for camera images — camera-scanned pages now include `make`, `model`, and `dateTimeOriginal` fields derived from device information; previously these fields were absent on the camera path.

#### iOS

- **ios:** automatic document corner detection via `VNDetectRectanglesRequest` and document segmentation masks — detected corners are pre-populated in the crop editor, reducing the need for manual adjustment.

#### JS / Cross-platform

- **types:** `imageOrigin: ImageOrigin` — new required field on `ReceiptImage` classifying how the source image was produced. Values: `"camera"`, `"screenshot"`, `"download"`, or `"unknown"`. Determined via `PHAsset` metadata on iOS and EXIF heuristics on both platforms.
- **api:** `cropAutoConfirm` option — `scan({ cropAutoConfirm: true })` skips the iOS gallery crop editor when document-detection confidence is ≥ 0.85 and applies the detected corners automatically (iOS only; no-op on Android).

### Fixed

#### Android

- **android:** `imageOrigin` for gallery images is now derived from EXIF heuristics instead of being hardcoded to `"unknown"` ([7dfdd68](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/7dfdd68))
- **android:** harmonized image output format and crop editor behavior across the gallery processing pipeline ([75f8bc7](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/75f8bc7))
- **android:** prevented photo picker URI expiration errors; corrected inset calculations for the crop editor button bar ([3e8e935](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/3e8e935))

#### iOS

- **ios:** reduced `imageOrigin: "unknown"` rate by relaxing the EXIF heuristic and adding a `PHAsset sourceRef` fallback for picker results without full library access ([02c1f47](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/02c1f47))

### Changed

#### Android

- **android:** gallery import now routes through the custom `CropEditorActivity` — `source: "gallery"` no longer uses the GMS Document Scanner built-in gallery picker, giving users full control over the perspective-crop step.

### Internal

- Package published as `react-native-receipt-scanner` on GitHub Packages.
- Android dependencies bumped: Kotlin 2.0.21, ML Kit Document Scanner 16.0.0, Text Recognition Korean 16.0.1, `androidx.exifinterface` 1.4.2.
- React Native pod versions updated.
- CI: separated CI check and release publish workflows.

---

## [0.2.0](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/compare/v0.1.0...v0.2.0) (2026-05-02)

### Added

#### iOS

- **ios:** crop editor button labels are now localizable via the host app's `Localizable.strings` — add `RNReceiptScanner_cancelButton` and `RNReceiptScanner_confirmButton` keys to each `.lproj` bundle to override the English defaults (`"Cancel"` / `"Use Photo"`)

### Fixed

#### iOS

- **ios:** scanned images now always output in upright (`UIImageOrientationUp`) orientation regardless of source EXIF rotation ([9db8e31](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/9db8e31))
- **ios:** corrected image orientation handling in CoreImage and Vision processing pipeline — `CIImage` was previously processed without applying the EXIF-embedded orientation transform, causing perspective-correction coordinates to mismatch on non-up images ([a0b52ce](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/a0b52ce))
- **ios:** resolved unreliable crop editor button interaction — replaced `UIToolbar` / `UIBarButtonItem` with a plain `UIView` / `UIButton` bar that fires `TouchUpInside` directly, avoiding silent target-action routing failures in some RN modal presentation paths ([aee2426](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/aee2426))

### Changed

#### iOS

- **ios:** improved crop editor responsiveness — the button bar is now added last in the view hierarchy so `hitTest:withEvent:` checks it before drag handles, preventing handle circles near the bottom of the image from absorbing toolbar button taps ([9415819](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/9415819))

### Internal

- example app: added full-featured UI demonstrating camera and gallery scan flows ([480ad6d](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/480ad6d))
- example app: enabled React Native New Architecture (TurboModule) on iOS ([72e8b64](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/72e8b64))
- docs: added ADR-004 documenting iOS crop editor real-device fixes ([8230ce8](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/8230ce8))

---

## [0.1.0](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/compare/347aaf4...v0.1.0) (2026-05-01)

### Features

#### iOS

- **ios:** implement `scan()` — dispatches to VisionKit camera or PHPickerViewController gallery path ([5a545cd](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/5a545cd))
- **ios:** add `RNGalleryPickerDelegate` — PHPicker, `VNDetectRectanglesRequest`, interactive perspective-crop editor ([b33be13](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/b33be13))
- **ios:** add `RNCropEditorViewController` — 4-corner drag-handle overlay with `CIPerspectiveCorrection` ([07f6e1c](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/07f6e1c))
- **ios:** add `RNDocumentCameraDelegate` — `VNDocumentCameraViewController` camera scan delegate ([ec2bc2f](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/ec2bc2f))
- **ios:** add `RNOcrProcessor` — `VNRecognizeTextRequest` with `ko-KR` / `en-US` language support ([8c3d0d7](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/8c3d0d7))
- **ios:** add `RNImageProcessor` — JPEG recompression, EXIF extraction, session cache cleanup ([9ce7514](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/9ce7514))
- **ios:** add `RNScanOptions` — options parsing with `NSNull` guards and clamped defaults ([b1fc9a3](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/b1fc9a3))

#### Android

- **android:** integrate ML Kit Document Scanner (`GmsDocumentScannerOptions`) for camera and gallery import ([1001caf](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/1001caf))
- **android:** add `OcrProcessor` — ML Kit Korean Text Recognition (Hangul + Latin characters) ([38bf937](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/38bf937))
- **android:** add `ImageProcessor` — JPEG recompression, `ExifInterface` extraction, session cache cleanup ([7aa71e2](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/7aa71e2))
- **android:** add `ResultBuilder` — serializes processing results to `WritableMap` / `WritableArray` ([a32a6c9](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/a32a6c9))
- **android:** add `ScanOptions` data class — `ReadableMap` parsing with typed defaults ([c367ddf](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/c367ddf))

#### JS / Cross-platform

- implement `scan()` with `ScanReceiptOptions` defaults merging and native delegation ([83ef32f](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/83ef32f))
- add TypeScript types: `ScanReceiptOptions`, `ScanReceiptResult`, `ReceiptImage`, `ReceiptExif` ([2a5a0fa](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/2a5a0fa))
- add TurboModule spec `NativeReceiptScanner` — New Architecture (JSI) on both platforms ([8f6ff4d](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/8f6ff4d))
- add web / JS stub returning `{ status: "cancelled", images: [] }` ([83ef32f](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/83ef32f))

### Bug Fixes

#### iOS

- **ios:** fix `CGImageRef` use-after-free in gallery crop path ([702ee73](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/702ee73))
- **ios:** use `__typeof__` instead of `typeof` in `.mm` files — `typeof` is not valid in C++ mode ([bae0871](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/bae0871))
- **ios:** cast ternary result to `NSNumber *` before property access — resolves ObjC/C++ ambiguity ([453693d](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/453693d))
- **ios:** fix build errors — ObjC `?:` inside `[]` subscript ambiguity and missing `RCTUtils` header ([1518df3](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/1518df3))
- **ios:** eliminate duplicate `CGImageSourceCopyPropertiesAtIndex` call; add UUID suffix to output filenames to prevent collisions ([053d1ae](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/053d1ae))
- **ios:** address code quality findings — weak reference handling, retain cycle prevention ([021cce2](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/021cce2))

#### Android

- **android:** fix `onNewIntent` signature — `Intent` parameter must be non-nullable per `ActivityEventListener` spec ([3f7fe7c](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/3f7fe7c))

### Documentation

- rewrite README — add comparison table against competing libraries, full iOS / Android setup guide, error code reference ([d85f56c](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/d85f56c))
- add API contract and scan pipeline specs ([c9d628f](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/c9d628f))
- add Phase 1 (JS), Phase 2 (Android), Phase 3 (iOS) implementation plans ([78272cf](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/78272cf))
