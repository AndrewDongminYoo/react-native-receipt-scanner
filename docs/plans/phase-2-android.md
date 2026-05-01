# Phase 2 — Android Implementation (ML Kit)

## Goal

Full Android implementation: camera scan, gallery import, document crop,
JPEG compression, EXIF extraction, and on-device OCR — all returned as a
unified `ScanReceiptResult`.

## Dependencies to add

```kotlin
// build.gradle (android/)
implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0-beta1")
implementation("com.google.mlkit:text-recognition-korean:16.0.0")
implementation("androidx.exifinterface:exifinterface:1.3.7")
```

## Tasks

### Scanner integration

- [ ] Launch `GmsDocumentScanner` via `ActivityResult` API:
  ```kotlin
  val options = GmsDocumentScannerOptions.Builder()
    .setGalleryImportAllowed(source == "gallery")
    .setPageLimit(maxPages)
    .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
    .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
    .build()
  ```
- [ ] Handle `GmsDocumentScanningResult.getPages()` → list of URI

### Image processing

- [ ] JPEG recompress each result URI:
  - `BitmapFactory.decodeFile(uri.path)`
  - `bitmap.compress(Bitmap.CompressFormat.JPEG, (quality * 100).toInt(), outputStream)`
  - Write to `context.cacheDir`
- [ ] Read `width` and `height` from `BitmapFactory.Options` (inJustDecodeBounds)
- [ ] Read `fileSize` after write

### EXIF

- [ ] Read EXIF via `ExifInterface(filePath)`:
  - `TAG_ORIENTATION`, `TAG_DATETIME_ORIGINAL`, `TAG_MAKE`, `TAG_MODEL`
  - `TAG_GPS_LATITUDE`, `TAG_GPS_LONGITUDE` — include only if `includeGpsExif=true`
- [ ] Strip GPS tags from output file EXIF when `includeGpsExif=false`

### OCR

- [ ] Use `TextRecognition.getClient(KoreanTextRecognizerOptions())` (covers Korean + Latin)
- [ ] `InputImage.fromFilePath(context, uri)` → `recognizer.process(image)`
- [ ] Extract `.text` from `Text` result
- [ ] Run only when `options.ocr === true`

### Result assembly

- [ ] Build `WritableMap` per image: `uri`, `width`, `height`, `fileName`, `mimeType`, `fileSize`, `ocrText`, `exif`
- [ ] Resolve promise with `WritableArray` of image maps

### Cleanup

- [ ] Delete previous session temp files at the start of each `scan()` call

## Definition of Done

- [ ] Camera scan → cropped JPEG → `ocrText` returned to JS
- [ ] Gallery image → document crop flow → `ocrText` returned to JS
- [ ] `width`, `height`, `fileSize` correct
- [ ] `exif.orientation`, `exif.dateTimeOriginal` populated when available
- [ ] `exif.gps` absent by default; present when `includeGpsExif=true`
- [ ] `uri` is a `file://` path — no base64
- [ ] Tested on a low-end device (≤3 GB RAM): no OOM crash
- [ ] `yarn example android` runs the full flow end-to-end
