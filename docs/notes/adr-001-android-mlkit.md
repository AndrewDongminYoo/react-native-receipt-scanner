# ADR-001: Use ML Kit Document Scanner for Android

## Status

Accepted

## Context

Android needs four capabilities in one flow: gallery image import, automatic document crop (perspective correction), JPEG output, and an activity-based UI with no custom camera implementation.

Options considered:

| Option                                              | Notes                                                                          |
| --------------------------------------------------- | ------------------------------------------------------------------------------ |
| ML Kit Document Scanner (GmsDocumentScannerOptions) | Handles full UI, gallery import, crop, JPEG result                             |
| `react-native-document-scanner-plugin`              | Wraps ML Kit but exposes limited options; gallery import issues open on GitHub |
| OpenCV from scratch                                 | Full control, but weeks of native camera + contour detection work              |
| `@dariyd/react-native-document-scanner`             | Closer fit, but OCR not included; still requires assembling at app layer       |

## Decision

Use **`com.google.android.gms:play-services-mlkit-document-scanner`** directly inside the library's `ReceiptScannerModule.kt`.

Configuration:

```kotlin
GmsDocumentScannerOptions.Builder()
  .setGalleryImportAllowed(true)
  .setPageLimit(maxPages)
  .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
  .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
  .build()
```

For OCR: **`com.google.mlkit:text-recognition-korean`** (covers Korean + Latin in one client).

## Consequences

- **Play Services required** — devices without Google Play Services cannot use this library.
  Acceptable for the Korean market.
- **No OpenCV complexity** — avoids camera permission handling, contour detection, and homography transforms from scratch.
- **Gallery import native** — `setGalleryImportAllowed(true)` keeps the gallery affordance inside the ML Kit camera UI for users who reach the gallery from the camera screen.
  Note: explicit `source: "gallery"` calls do **not** use GMS; they are routed to the package's own `CropEditorActivity` to preserve EXIF and surface `imageOrigin` classification (see ADR-005).
- **Model download on first use** — ML Kit may download the document scanner model on the first run (~10 MB).
  Subsequent runs use the cached model.
