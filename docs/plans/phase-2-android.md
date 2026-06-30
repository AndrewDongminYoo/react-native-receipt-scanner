# Phase 2 — Android Implementation (ML Kit)

## Goal

Full Android implementation: camera scan, gallery import, document crop,
JPEG compression, EXIF extraction, and on-device OCR — all returned as a unified `ScanReceiptResult`.

## Dependencies to add

```kotlin
// build.gradle (android/)
implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0-beta1")
implementation("com.google.mlkit:text-recognition-korean:16.0.0")
implementation("androidx.exifinterface:exifinterface:1.3.7")
```

## Tasks

### Scanner integration

- [x] Launch `GmsDocumentScanner` via `ActivityResult` API:
  ```kotlin
  val options = GmsDocumentScannerOptions.Builder()
    .setGalleryImportAllowed(source == "gallery")
    .setPageLimit(maxPages)
    .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
    .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
    .build()
  ```
- [x] Handle `GmsDocumentScanningResult.getPages()` → list of URI

### Image processing

- [x] JPEG recompress each result URI:
  - `BitmapFactory.decodeFile(uri.path)`
  - `bitmap.compress(Bitmap.CompressFormat.JPEG, (quality * 100).toInt(), outputStream)`
  - Write to `context.cacheDir`
- [x] Read `width` and `height` from `BitmapFactory.Options` (inJustDecodeBounds)
- [x] Read `fileSize` after write

### EXIF

- [x] Read EXIF via `ExifInterface(filePath)`:
  - `TAG_ORIENTATION`, `TAG_DATETIME_ORIGINAL`, `TAG_MAKE`, `TAG_MODEL`, `TAG_SOFTWARE`
  - `TAG_GPS_LATITUDE`, `TAG_GPS_LONGITUDE` — include only if `includeGpsExif=true`
- [x] Surface read EXIF via the JS `exif` field.
      **Note**:
      the output JPEG is written via `Bitmap.compress(JPEG, …)` and carries no EXIF.
      GPS is therefore never present in the output file regardless of `includeGpsExif`.
      This is asymmetric to iOS, which preserves EXIF on the file.
      See ADR-005 ("Output JPEG carries no EXIF") and `api-contract.md` "Output JPEG metadata asymmetry".

### OCR

- [x] Use `TextRecognition.getClient(KoreanTextRecognizerOptions())` (covers Korean + Latin)
- [x] `InputImage.fromFilePath(context, uri)` → `recognizer.process(image)`
- [x] Extract `.text` from `Text` result
- [x] Run only when `options.ocr === true`

### Result assembly

- [x] Build `WritableMap` per image: `uri`, `width`, `height`, `fileName`, `mimeType`, `fileSize`, `ocrText`, `exif`
- [x] Resolve promise with `WritableArray` of image maps

### Cleanup

- [x] Delete previous session temp files at the start of each `scan()` call

## Definition of Done

- [x] Camera scan → cropped JPEG → `ocrText` returned to JS
- [x] Gallery image → document crop flow → `ocrText` returned to JS
- [x] `width`, `height`, `fileSize` correct
- [x] `exif.orientation`, `exif.dateTimeOriginal`, `exif.software` populated when available (from source EXIF)
- [x] `exif.gps` absent by default; present in JS response when `includeGpsExif=true` (output file never carries GPS — see note above)
- [x] `uri` is a `file://` path — no base64
- [ ] Tested on a low-end device (≤3 GB RAM): no OOM crash — requires physical device
- [x] `yarn example android` runs the full flow end-to-end
