# react-native-receipt-scanner

Receipt document scanner for React Native. Wraps **ML Kit** (Android) and **VisionKit + Vision** (iOS) with a unified API for scanning, interactive perspective-crop, JPEG compression, EXIF extraction, and on-device OCR.

Built exclusively for the [React Native New Architecture](https://reactnative.dev/docs/the-new-architecture/landing-page) — no legacy bridge, no base64 blobs.

## Why this library?

|                                | **react-native-receipt-scanner** | @dariyd/react-native-document-scanner |   react-native-document-scanner-plugin    |
| ------------------------------ | :------------------------------: | :-----------------------------------: | :---------------------------------------: |
| New Architecture (TurboModule) |         ✅ iOS + Android         |              ✅ iOS only              | ⚠️ JS only — native still uses old bridge |
| Camera scan                    |                ✅                |                  ✅                   |                    ✅                     |
| Gallery import + crop editor   | ✅ Interactive perspective-crop  |                  ✅                   |                    ✅                     |
| On-device OCR                  |        ✅ Korean + Latin         |                  ❌                   |                    ❌                     |
| EXIF metadata                  |                ✅                |                  ✅                   |                    ❌                     |
| GPS EXIF                       |            ✅ opt-in             |               ✅ opt-in               |                    ❌                     |
| Result format                  |        `file://` URI only        |             URI or Base64             |               URI or Base64               |
| Min iOS                        |               16.0               |                 13.0                  |                   13.0                    |
| Min Android                    |           API 24 (7.0)           |             API 21 (5.0)              |               API 21 (5.0)                |
| Google Play Services required  |         ✅ Android only          |            ✅ Android only            |              ✅ Android only              |

### What makes this library different

**On-device OCR.** Neither competitor extracts text from scanned documents. This library runs the Vision framework (iOS) and ML Kit Korean Text Recognition (Android) on-device — no network call, no external API key. The `ocrText` field on each image is the plain-text content of that page.

**Korean language support.** The Android OCR model (`text-recognition-korean`) covers Korean script (Hangul) and Latin characters simultaneously. The iOS Vision recognizer targets `ko-KR` and `en-US` on iOS 16+.

**Interactive perspective-crop in gallery mode.** When `source: "gallery"` is used on iOS, VNDetectRectangles automatically locates the document corners. A drag-handle overlay (`RNCropEditorViewController`) lets the user correct the crop before the image is processed. The result is a perspective-corrected JPEG — not a raw photo.

**TurboModule on both platforms.** The module calls go through JSI on iOS and the codegen-generated `NativeReceiptScannerSpec` on Android. There is no asynchronous bridge serialization for the call path.

**`file://` URIs only.** Images are written to the app cache directory and returned as `file://` paths. Base64 is never used — it doubles memory usage and is slower to transfer across the bridge.

---

## Requirements

| Platform     | Minimum version      | Notes                                                                        |
| ------------ | -------------------- | ---------------------------------------------------------------------------- |
| iOS          | 16.0                 | VNDocumentCameraViewController requires iOS 13+; Korean OCR requires iOS 16+ |
| Android      | API 24 (Android 7.0) | ML Kit Document Scanner requires Google Play Services                        |
| React Native | 0.77+                | New Architecture must be enabled                                             |

---

## Installation

```sh
npm install react-native-receipt-scanner
# or
yarn add react-native-receipt-scanner
```

### iOS — CocoaPods

```sh
cd ios && pod install
```

### Android

No extra steps. The ML Kit Document Scanner dependency is included automatically via Gradle.

> **Note:** The ML Kit Document Scanner downloads its model from Google Play Services on first use. This requires an active internet connection on first launch and does not work on AOSP devices without Google Play Services (e.g., most Android emulators without Play Store).

---

## Setup

### iOS — `Info.plist`

The camera scanner (`source: "camera"`) requires camera access. The gallery picker (`source: "gallery"`) uses the system photo picker (PHPickerViewController) and does **not** require a photo library permission. Add the following key to `ios/<YourApp>/Info.plist`:

```xml
<key>NSCameraUsageDescription</key>
<string>Used to scan receipt documents.</string>
```

If you enable `includeGpsExif: true`, also add:

```xml
<key>NSLocationWhenInUseUsageDescription</key>
<string>Used to attach location to scanned receipt images.</string>
```

> **Note:** GPS EXIF is off by default (`includeGpsExif: false`). You do not need the location permission unless you explicitly opt in.

### Android — `AndroidManifest.xml`

The ML Kit scanner handles its own camera permission prompt internally. No extra entries are required in `AndroidManifest.xml`.

If you target Android 13+ (API 33+) and plan to read images from the gallery via a custom path, add:

```xml
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
```

For API 32 and below:

```xml
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
```

> **Note:** The default gallery path uses the system `PHPickerViewController` (iOS) and the ML Kit built-in gallery import (Android), neither of which requires a declared storage permission in most cases.

---

## Usage

### Basic camera scan

```ts
import { scan } from "react-native-receipt-scanner";

const result = await scan();

if (result.status === "success") {
  for (const image of result.images) {
    console.log(image.uri); // file:///...receipt_123.jpg
    console.log(image.ocrText); // extracted text (when ocr: true)
  }
}
```

### Gallery import with perspective-crop (iOS)

On iOS, the user selects a photo and is presented with an interactive crop editor. The document corners are detected automatically via `VNDetectRectanglesRequest`; the user can adjust them before confirming.

```ts
const result = await scan({ source: "gallery" });
```

### Localizing the crop editor buttons (iOS)

The crop editor shows two buttons: a cancel button (left) and a confirm button (right). Their labels default to `"Cancel"` and `"Use Photo"`.

To translate them, add the following keys to each locale's `Localizable.strings` in your Xcode project:

```plaintext
/* ios/en.lproj/Localizable.strings */
"RNReceiptScanner_cancelButton"  = "Cancel";
"RNReceiptScanner_confirmButton" = "Use Photo";

/* ios/ko.lproj/Localizable.strings */
"RNReceiptScanner_cancelButton"  = "취소";
"RNReceiptScanner_confirmButton" = "사진 사용";
```

The library reads `[NSBundle mainBundle]`, so strings placed in your app's bundle are picked up automatically when the device language matches. If a key is absent, the English default is used.

> **Note:** This applies only to the iOS gallery crop editor (`source: "gallery"`). The camera scanner and Android are not affected.

### Multi-page scan

```ts
const result = await scan({ source: "camera", maxPages: 5 });

if (result.status === "success") {
  console.log(`Scanned ${result.images.length} pages`);
}
```

### OCR — extract text from the document

OCR runs on-device and is enabled by default. Set `ocr: false` to skip it (faster, smaller result):

```ts
// With OCR (default)
const result = await scan({ ocr: true });

// Without OCR
const result = await scan({ ocr: false });
```

### EXIF metadata

```ts
const result = await scan({ includeExif: true });

if (result.status === "success") {
  const { exif } = result.images[0];
  console.log(exif?.orientation); // EXIF orientation tag (1–8)
  console.log(exif?.dateTimeOriginal); // "2024:12:25 10:30:00"
  console.log(exif?.make); // "Apple"
  console.log(exif?.model); // "iPhone 15 Pro"
}
```

> **Note:** EXIF is sourced from the original image on the gallery path. On the camera path (VNDocumentCameraViewController), the scanned page is a synthetic UIImage with no source EXIF, so EXIF fields will be absent even when `includeExif: true`.

### Cancelled state

When the user dismisses the scanner or picker without scanning, `status` is `"cancelled"` and `images` is an empty array:

```ts
const result = await scan();

if (result.status === "cancelled") {
  console.log("User cancelled");
}
```

---

## API reference

### `scan(options?): Promise<ScanReceiptResult>`

| Option           | Type                    | Default    | Description                                                     |
| ---------------- | ----------------------- | ---------- | --------------------------------------------------------------- |
| `source`         | `'camera' \| 'gallery'` | `'camera'` | Open document camera or system image picker                     |
| `maxPages`       | `number`                | `1`        | Maximum pages per scan session                                  |
| `quality`        | `number` (0.0–1.0)      | `0.82`     | JPEG compression quality after crop                             |
| `includeExif`    | `boolean`               | `true`     | Attach EXIF metadata to each result image                       |
| `includeGpsExif` | `boolean`               | `false`    | Include GPS coordinates in EXIF (off by default — privacy risk) |
| `ocr`            | `boolean`               | `true`     | Run on-device text recognition and return `ocrText`             |

### Result types

```ts
type ScanReceiptResult = {
  status: "success" | "cancelled";
  images: ReceiptImage[];
};

type ReceiptImage = {
  uri: string; // file:// path — never base64
  width: number;
  height: number;
  fileName: string;
  mimeType: "image/jpeg";
  fileSize: number;
  ocrText?: string; // present when options.ocr === true
  exif?: ReceiptExif; // present when options.includeExif === true
};

type ReceiptExif = {
  orientation?: number;
  dateTimeOriginal?: string;
  make?: string;
  model?: string;
  gps?: { latitude: number; longitude: number };
};
```

### Error codes

| Code                  | Trigger                                                             |
| --------------------- | ------------------------------------------------------------------- |
| `SCAN_IN_PROGRESS`    | `scan()` called while a previous call has not yet resolved          |
| `NO_ACTIVITY`         | No foreground activity / view controller found (Android)            |
| `NOT_SUPPORTED`       | `VNDocumentCameraViewController` not supported on this device (iOS) |
| `SCANNER_INIT_FAILED` | ML Kit scanner failed to initialize (Android)                       |
| `SCAN_FAILED`         | Unexpected activity result code (Android)                           |
| `PROCESSING_FAILED`   | Image processing or OCR failed                                      |
| `CAMERA_FAILED`       | `VNDocumentCameraViewController` reported an error (iOS)            |

---

## Architecture docs

- [API contract](docs/specs/api-contract.md) — full type reference and package boundaries
- [Scan pipeline](docs/specs/scan-pipeline.md) — internal processing flow (contributors)
- [Phase 1 — JS wrapper](docs/plans/phase-1-js-wrapper.md)
- [Phase 2 — Android](docs/plans/phase-2-android.md)
- [Phase 3 — iOS](docs/plans/phase-3-ios.md)

---

## Planned

| Feature                                   | Notes                                                                                                                                                                                                                                                                                                                                                                                       |
| ----------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **PDF export with searchable text layer** | Android: `GmsDocumentScannerOptions.RESULT_FORMAT_PDF` already produces a multi-page PDF — cost is near-zero. iOS: compose pages with `PDFKit` and overlay the OCR result as an invisible text layer so the file is copy-pasteable and full-text-searchable in any PDF viewer. New `ScanReceiptOptions.outputFormat: "jpeg" \| "pdf"` option; result carries a `pdfUri` alongside `images`. |

---

## Contributing

- [Development workflow](CONTRIBUTING.md#development-workflow)
- [Sending a pull request](CONTRIBUTING.md#sending-a-pull-request)
- [Code of conduct](CODE_OF_CONDUCT.md)

## License

MIT
