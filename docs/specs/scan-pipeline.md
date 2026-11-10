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
  │    (later scan processing attempts prior-session cache cleanup)
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

Returned JPEGs live in `NSCachesDirectory` on iOS and `context.cacheDir` on Android.
They are temporary files, with cleanup timing determined by the following paths.

| Event                                     | Action                                                                                                                                |
| ----------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------- |
| Android scan preparation                  | Schedule `ImageProcessor.deletePreviousSessionFiles()` on the executor after options and activity checks, before image acquisition.   |
| iOS camera scan completed                 | Call `RNImageProcessor.deletePreviousSessionFiles` before processing the captured pages.                                              |
| iOS gallery images selected               | Call `RNImageProcessor.deletePreviousSessionFiles` after picker dismissal, before processing the selection.                           |
| Scan cancelled or rejected before cleanup | Previous session files may remain; cancellation or rejection is not a deletion guarantee.                                             |
| Scan fails after writing files            | JPEGs written before the failure may remain without their URIs being returned.                                                        |
| App restart                               | No restart-triggered cleanup is implemented; cached files may still exist after relaunch.                                             |
| OS cache reclamation                      | The system may remove cached files independently of scan processing; neither retention nor deletion at a specific time is guaranteed. |

Both cleanup helpers attempt to remove cached files whose names start with `receipt_` and end with `.jpg`.
Deletion errors are not surfaced as a cleanup result, so a later scan is not proof that a particular file was removed.
The returned `uri` is therefore neither permanent storage nor evidence of deletion after a restart.

Consuming apps must copy images to appropriate persistent storage if they need to retain them.
When prompt removal is required, the app must explicitly delete sensitive files after use through its own file API and handle deletion failures.
Deleting returned URIs covers only those known files; it does not guarantee cleanup of JPEGs left by a failed scan before their URIs were returned.
Apps with strict cleanup deadlines need a cache-management policy that also accounts for such unreturned files.
The library exposes no public file-deletion API.
Do not rely on an app restart, a future scan, or eventual OS reclamation to meet a deletion deadline.

This follows the platform cache guidance: [Android app-specific cache files](https://developer.android.com/training/data-storage/app-specific#internal-remove-cache) and [Apple file-system guidance](https://developer.apple.com/documentation/foundation/using-the-file-system-effectively).
