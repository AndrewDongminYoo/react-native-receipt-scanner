# Scan Pipeline

Internal processing pipeline for contributors.
Describes what happens between the `scan()` call and the resolved `ScanReceiptResult`.

## Flow

```plaintext
scan(options)
  │
  ├─ apply default options
  │
  ├─ when OCR is enabled: validate ordered BCP 47 `ocrLanguages` before scanner UI
  │    iOS: canonicalize against the active Vision request's supported identifiers
  │    Android: resolve one script recognizer; Latin may accompany one non-Latin script
  │
  ├─ [Android] prepare the resolved non-default OCR model before acquisition
  │    (may download through ML Kit before scanner UI is presented)
  │
  ├─ [Android] camera: GmsDocumentScanner
  │    options: setGalleryImportAllowed(false)  // camera only; gallery → CropEditorActivity (ADR-005)
  │             setPageLimit(maxPages)
  │             setResultFormats(RESULT_FORMAT_JPEG)
  │             setScannerMode(SCANNER_MODE_FULL)
  │    → ActivityResult → cropped JPEG URIs
  │
  └─ [iOS]
       camera:  VNDocumentCameraViewController
                  → delegate callback → JPEG write to temp dir
       gallery: PHPickerViewController
                  → VNDetectRectanglesRequest (find receipt boundary)
                  → crop editor (confirm / adjust corners)
                  → JPEG write to temp dir
  │
  ├─ orientation normalisation
  │    Android: ExifInterface.getAttributeInt(TAG_ORIENTATION)
  │    iOS:     CGImageSource / kCGImagePropertyOrientation
  │
  ├─ JPEG recompress
  │    Android: BitmapFactory.decodeFile → compress(JPEG, quality*100)
  │    iOS:     CGImageDestination + kCGImageDestinationLossyCompressionQuality
  │
  ├─ EXIF read
  │    Android: ExifInterface
  │    iOS:     CGImageSourceCopyPropertiesAtIndex
  │    GPS stripped unless includeGpsExif=true
  │
  ├─ OCR  (only if options.ocr === true)
  │    Android: ML Kit TextRecognition with the preflight-selected script recognizer
  │    iOS:     VNRecognizeTextRequest
  │               recognitionLanguages: ordered validated identifiers
  │               recognitionLevel: .accurate
  │
  ├─ build ReceiptImage result map per image
  │
  ├─ write final JPEG to app cache directory
  │
  ├─ return ScanReceiptResult to JS
  │    (temp working files cleaned up on next scan() call)
  │
  ├─ [JS] derive OcrQuality per image from ocrText
  │
  ├─ [JS] when mergeOcrPages: snapshot native page order by URI
  │        (must happen before the floor gate, which partitions the array)
  │
  ├─ [JS] OcrFloor gate → images / rejectedImages
  │        no image passes → status becomes "rejected"
  │
  └─ [JS] when mergeOcrPages: restore page order by URI, merge adjacent
           OCR text, attach mergedOcr — including on a "rejected" status
```

## Platform Notes

### Android

- Minimum SDK: 24 (ML Kit Document Scanner requires Play Services)
- `GmsDocumentScannerOptions` handles the entire scan UI and returns result URIs
  via `GmsDocumentScanningResult.getPages()`
- `ExifInterface` from `androidx.exifinterface` for EXIF read/write
- Use `InputImage.fromFilePath()` for ML Kit OCR input
- ActivityResult API (`registerForActivityResult`) — do not use deprecated `startActivityForResult`
- Capability discovery reports installed-model state only. It never starts a model installation.
- Non-default models are prepared before the scanner UI opens, so a required ML Kit download completes or rejects before image acquisition.

### iOS

- Camera path uses `VNDocumentCameraViewController` (VisionKit framework)
- Gallery path uses `PHPickerViewController` (PhotosUI framework, no permission prompt on iOS 14+)
- `VNDetectRectanglesRequest` with `minimumConfidence: 0.7` for rectangle detection
- iOS 16+ is required for the Korean-first OCR baseline; there is no Latin-only compatibility tier.
- OCR passes the caller's validated BCP 47 hints to Vision in caller order.
- All JPEG operations via `ImageIO` / `CoreGraphics` — never UIImageJPEGRepresentation (lossy metadata loss)

## Temp File Policy

| Event           | Action                                                        |
| --------------- | ------------------------------------------------------------- |
| `scan()` called | Delete previous session's temp files                          |
| Scan cancelled  | Delete any partially-written temp files                       |
| App restart     | OS clears `NSCachesDirectory` / `getCacheDir()` automatically |

The `uri` returned in `ReceiptImage` is stable until the next `scan()` call.
Do not assume it persists across app restarts.
