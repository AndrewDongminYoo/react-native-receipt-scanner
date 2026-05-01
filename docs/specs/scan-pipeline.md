# Scan Pipeline

Internal processing pipeline for contributors. Describes what happens between the
`scan()` call and the resolved `ScanReceiptResult`.

## Flow

```plaintext
scan(options)
  │
  ├─ apply default options
  │
  ├─ [Android] GmsDocumentScanner
  │    options: setGalleryImportAllowed(source === 'gallery')
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
  │    Android: ML Kit TextRecognition
  │               .getClient(KoreanTextRecognizerOptions())
  │    iOS:     VNRecognizeTextRequest
  │               recognitionLanguages: ["ko-KR", "en-US"]
  │               recognitionLevel: .accurate
  │
  ├─ build ReceiptImage result map per image
  │
  ├─ write final JPEG to app cache directory
  │
  └─ return ScanReceiptResult to JS
       (temp working files cleaned up on next scan() call)
```

## Platform Notes

### Android

- Minimum SDK: 24 (ML Kit Document Scanner requires Play Services)
- `GmsDocumentScannerOptions` handles the entire scan UI and returns result URIs
  via `GmsDocumentScanningResult.getPages()`
- `ExifInterface` from `androidx.exifinterface` for EXIF read/write
- Use `InputImage.fromFilePath()` for ML Kit OCR input
- ActivityResult API (`registerForActivityResult`) — do not use deprecated `startActivityForResult`

### iOS

- Camera path uses `VNDocumentCameraViewController` (VisionKit framework)
- Gallery path uses `PHPickerViewController` (PhotosUI framework, no permission prompt on iOS 14+)
- `VNDetectRectanglesRequest` with `minimumConfidence: 0.7` for rectangle detection
- `VNRecognizeTextRequest` supports `ko-KR` from iOS 16+; fall back to `en-US` on older versions
- All JPEG operations via `ImageIO` / `CoreGraphics` — never UIImageJPEGRepresentation (lossy metadata loss)

## Temp File Policy

| Event           | Action                                                        |
| --------------- | ------------------------------------------------------------- |
| `scan()` called | Delete previous session's temp files                          |
| Scan cancelled  | Delete any partially-written temp files                       |
| App restart     | OS clears `NSCachesDirectory` / `getCacheDir()` automatically |

The `uri` returned in `ReceiptImage` is stable until the next `scan()` call.
Do not assume it persists across app restarts.
