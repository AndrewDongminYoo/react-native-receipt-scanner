# Phase 3 — iOS Implementation (VisionKit + Custom Gallery Crop)

## Goal

iOS parity with Phase 2. Camera scanning uses `VNDocumentCameraViewController`.
Gallery import uses `PHPickerViewController` with a custom rectangle-detection
crop editor, because VisionKit has no built-in gallery import.

## Key Constraints

- `VNDocumentCameraViewController` is camera-only — no gallery import path exists in VisionKit.
- `PHPickerViewController` requires no user permission prompt on iOS 14+.
- `VNRecognizeTextRequest` supports `ko-KR` from iOS 16 onwards.
- All JPEG operations must go through `ImageIO` / `CoreGraphics` to preserve EXIF.
  Do not use `UIImageJPEGRepresentation` — it discards metadata.

## Tasks

### Camera path

- [x] Present `VNDocumentCameraViewController` modally
- [x] Implement `VNDocumentCameraViewControllerDelegate`:
  - `cameraViewController(_:didFinishWith:)` — iterate `VNDocumentPage`, write each to temp JPEG
  - `cameraViewControllerDidCancel(_:)` — resolve with `{ status: 'cancelled' }`
- [x] Write cropped pages using `CGImageDestination` with `kCGImageDestinationLossyCompressionQuality`

### Gallery path

- [x] Present `PHPickerViewController` with `PHPickerFilter.images`, `selectionLimit = maxPages`
- [x] For each picked item, load `UIImage` via `NSItemProvider`
- [x] Run `VNDetectRectanglesRequest` with `minimumConfidence: 0.7`
- [x] Show crop editor UI (`RNCropEditorViewController`) pre-populated with detected corners; user can adjust
- [x] On confirm, apply perspective correction via `CIFilter(name: "CIPerspectiveCorrection")`
- [x] Write result to temp JPEG via `CGImageDestination`

### JPEG recompress

- [x] Use `CGImageDestinationAddImage` with:
  ```swift
  kCGImageDestinationLossyCompressionQuality: quality
  ```
- [x] Never use `UIImageJPEGRepresentation` (strips EXIF)

### EXIF

- [x] Read via `CGImageSourceCopyPropertiesAtIndex`:
  - `kCGImagePropertyExifDictionary` → orientation, dateTimeOriginal
  - `kCGImagePropertyTIFFDictionary` → make, model
  - `kCGImagePropertyGPSDictionary` → lat/lon (strip unless `includeGpsExif=true`)
- [x] Write stripped EXIF back using `CGImageDestinationAddImageFromSource` with modified properties

### OCR

- [x] `VNRecognizeTextRequest` with:
  ```swift
  request.recognitionLanguages = ["ko-KR", "en-US"]
  request.recognitionLevel = .accurate
  ```
- [x] iOS 16 guard for `ko-KR`; fall back to `["en-US"]` on older versions
- [x] Run only when `options.ocr === true`

### Result & cleanup

- [x] Build result dictionary matching the Android shape: `uri`, `width`, `height`, `fileName`, `mimeType`, `fileSize`, `ocrText?`, `exif?`
- [x] Resolve JS promise
- [x] Delete previous session temp files at start of each `scan()` call

## Definition of Done

- [ ] Camera scan → cropped JPEG → `ocrText` returned to JS (iOS)
- [ ] Gallery image → rectangle detection → crop editor → JPEG → `ocrText` returned (iOS)
- [ ] `exif` fields populated; GPS absent by default
- [ ] `uri` is a `file://` path — no base64
- [ ] Korean receipt OCR functional on iOS 16+; graceful fallback on iOS 15
- [ ] `yarn example ios` runs the full flow end-to-end
