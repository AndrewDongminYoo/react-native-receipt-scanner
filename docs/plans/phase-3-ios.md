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

- [ ] Present `VNDocumentCameraViewController` modally
- [ ] Implement `VNDocumentCameraViewControllerDelegate`:
  - `cameraViewController(_:didFinishWith:)` — iterate `VNDocumentPage`, write each to temp JPEG
  - `cameraViewControllerDidCancel(_:)` — resolve with `{ status: 'cancelled' }`
- [ ] Write cropped pages using `CGImageDestination` with `kCGImageDestinationLossyCompressionQuality`

### Gallery path

- [ ] Present `PHPickerViewController` with `PHPickerFilter.images`, `selectionLimit = maxPages`
- [ ] For each picked item, load `UIImage` via `NSItemProvider`
- [ ] Run `VNDetectRectanglesRequest` with `minimumConfidence: 0.7`
- [ ] Show crop editor UI (custom view) pre-populated with detected corners; user can adjust
- [ ] On confirm, apply perspective correction via `CIFilter(name: "CIPerspectiveCorrection")`
- [ ] Write result to temp JPEG via `CGImageDestination`

### JPEG recompress

- [ ] Use `CGImageDestinationAddImage` with:
  ```swift
  kCGImageDestinationLossyCompressionQuality: quality
  ```
- [ ] Never use `UIImageJPEGRepresentation` (strips EXIF)

### EXIF

- [ ] Read via `CGImageSourceCopyPropertiesAtIndex`:
  - `kCGImagePropertyExifDictionary` → orientation, dateTimeOriginal
  - `kCGImagePropertyTIFFDictionary` → make, model
  - `kCGImagePropertyGPSDictionary` → lat/lon (strip unless `includeGpsExif=true`)
- [ ] Write stripped EXIF back using `CGImageDestinationAddImageFromSource` with modified properties

### OCR

- [ ] `VNRecognizeTextRequest` with:
  ```swift
  request.recognitionLanguages = ["ko-KR", "en-US"]
  request.recognitionLevel = .accurate
  ```
- [ ] iOS 16 guard for `ko-KR`; fall back to `["en-US"]` on older versions
- [ ] Run only when `options.ocr === true`

### Result & cleanup

- [ ] Build result dictionary matching the Android shape: `uri`, `width`, `height`, `fileName`, `mimeType`, `fileSize`, `ocrText?`, `exif?`
- [ ] Resolve JS promise
- [ ] Delete previous session temp files at start of each `scan()` call

## Definition of Done

- [ ] Camera scan → cropped JPEG → `ocrText` returned to JS (iOS)
- [ ] Gallery image → rectangle detection → crop editor → JPEG → `ocrText` returned (iOS)
- [ ] `exif` fields populated; GPS absent by default
- [ ] `uri` is a `file://` path — no base64
- [ ] Korean receipt OCR functional on iOS 16+; graceful fallback on iOS 15
- [ ] `yarn example ios` runs the full flow end-to-end
