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

> **Note:** `includeGpsExif: true` does **not** require `NSLocationWhenInUseUsageDescription`. The library only copies the GPS dictionary already embedded in the source image's EXIF — there is no `CLLocationManager` call.

### Android — `AndroidManifest.xml`

No extra entries are required. The ML Kit scanner handles its own camera permission prompt internally, and the gallery flow uses the system photo picker — neither requires a declared storage or media permission.

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

### Gallery import with perspective-crop

With `source: "gallery"`, the user selects a photo and is presented with an interactive crop editor. The document corners are detected automatically; the user can adjust them before confirming.

```ts
const result = await scan({ source: "gallery" });
```

### Localizing the crop editor

The crop editor shows an instruction plus two buttons: a cancel button (left) and a confirm button (right).

On iOS, add the following keys to each locale's `Localizable.strings` in your Xcode project:

```plaintext
/* ios/en.lproj/Localizable.strings */
"RNReceiptScanner_cropInstruction" = "Drag the corners to frame the document";
"RNReceiptScanner_cancelButton"    = "Cancel";
"RNReceiptScanner_confirmButton"   = "Use Photo";

/* ios/ko.lproj/Localizable.strings */
"RNReceiptScanner_cropInstruction" = "문서의 네 모서리를 맞춰 주세요";
"RNReceiptScanner_cancelButton"    = "취소";
"RNReceiptScanner_confirmButton"   = "사진 사용";
```

On Android, override the same resource names in the host app's `strings.xml` files.

The camera scanner uses the platform scanner UI and is not affected.

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

### Multilingual OCR and capability discovery

Choose native OCR with ordered BCP 47 hints, and inspect the active platform without opening scanner UI:

```ts
import { getOcrCapabilities, scan } from "react-native-receipt-scanner";

const capabilities = await getOcrCapabilities();
const result = await scan({
  ocrLanguages: ["es-ES", "en-US"],
  ocrFloor: false,
});
```

`ocrLanguages` defaults to `["ko-KR", "en-US"]`. These hints select native OCR only. They do not infer a receipt country or parse receipt / invoice fields.

On iOS, `capabilities` contains the ordered Vision identifiers currently supported by the active request. On Android, it contains script model states (`"ready"` or `"download-required"`); querying it never starts a download. The web fallback returns `{ platform: "web", defaultLanguages: ["ko-KR", "en-US"], supported: false }`.

Non-Korean and non-English scripts exposed by a provider are provider-supported but uncalibrated until fixture coverage exists.

### EXIF metadata

```ts
const result = await scan({ includeExif: true });

if (result.status === "success") {
  const { exif } = result.images[0];
  console.log(exif?.orientation); // always 1 — pixels are pre-rotated; do not re-rotate
  console.log(exif?.dateTimeOriginal); // "2024:12:25 10:30:00"
  console.log(exif?.make); // "Apple"
  console.log(exif?.model); // "iPhone 15 Pro"
  console.log(exif?.software); // iOS camera: OS version ("26.4.2"); editors / generators: tool name; usually empty on Android camera and screenshots
}
```

> **Note:** Output JPEG pixels are always written orientation-normalized, so `exif.orientation` is always `1`. Callers must not re-rotate.

> **Note:** On the gallery path EXIF is read from the original source image. On the camera path (VNDocumentCameraViewController on iOS, GmsDocumentScanner on Android) the source EXIF is stripped during the scan, so the library synthesizes `make` / `model` / `dateTimeOriginal` from the device — `software` and `gps` are absent unless the source carried them.

### Cancelled state

When the user dismisses the scanner or picker without scanning, `status` is `"cancelled"` and `images` is an empty array:

```ts
const result = await scan();

if (result.status === "cancelled") {
  console.log("User cancelled");
}
```

### Auto-rotate — handling sideways captures

A receipt photo that comes in rotated 90° / 180° / 270° (typically from a gallery import where the user or an editor rotated the image) is detected during OCR and the **output JPEG is rotated upright before being returned**. `width` / `height` reflect the post-rotation dimensions; `exif.orientation` stays `1` because the rotation is baked into the pixels.

```ts
// Default: rotation detected → output is upright
const result = await scan({ source: "gallery" });
console.log(result.images[0].width, result.images[0].height);
// → e.g. 1176 × 3530 (upright) even if the source was 3530 × 1176

// Disable to keep the original pixel orientation
const result = await scan({ source: "gallery", autoRotate: false });
```

The detector runs only when `ocr: true` (OCR is the rotation signal). Portrait inputs pay near-zero cost; landscape inputs trigger a few extra fast OCR probes (~300–450 ms total).

### Rejected state — non-receipt images

When `ocr: true` (default), the library applies a conservative OCR floor (12 characters / 2 lines) and treats captures that fail the threshold as rejected. Use this to prompt the user when they shoot a wall, the floor, or a blank page instead of a receipt:

```ts
const result = await scan();

if (result.status === "rejected") {
  // Every image failed the OCR floor — likely not a receipt
  alert("영수증 텍스트를 인식하지 못했습니다. 다시 촬영해 주세요.");
  console.log(result.rejectedImages[0]?.ocrQuality);
  // → { textLength: 3, lineCount: 1 }
}

// Tighten or relax:
await scan({ ocrFloor: { minLines: 3 } }); // require store + date + total
await scan({ ocrFloor: false }); // disable the floor entirely
```

In multi-image mode, partial rejects are returned via `rejectedImages` alongside `status: "success"`:

```ts
const result = await scan({ source: "gallery", maxPages: 5 });
if (result.status === "success") {
  console.log(`accepted: ${result.images.length}`);
  console.log(`rejected: ${result.rejectedImages.length}`);
}
```

---

## API reference

### `scan(options?): Promise<ScanReceiptResult>`

| Option              | Type                    | Default                                   | Description                                                                                                                                                                                                                                 |
| ------------------- | ----------------------- | ----------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `source`            | `'camera' \| 'gallery'` | `'camera'`                                | Open document camera or system image picker                                                                                                                                                                                                 |
| `maxPages`          | `number`                | `1`                                       | Maximum pages per scan session                                                                                                                                                                                                              |
| `quality`           | `number` (0.0–1.0)      | `0.82`                                    | JPEG compression quality after crop                                                                                                                                                                                                         |
| `includeExif`       | `boolean`               | `true`                                    | Attach EXIF metadata to each result image                                                                                                                                                                                                   |
| `includeGpsExif`    | `boolean`               | `false`                                   | Include GPS coordinates in EXIF (off by default — privacy risk)                                                                                                                                                                             |
| `ocr`               | `boolean`               | `true`                                    | Run on-device text recognition and return `ocrText`                                                                                                                                                                                         |
| `ocrLanguages`      | `readonly string[]`     | `["ko-KR", "en-US"]`                      | Ordered BCP 47 hints that select native OCR. Empty arrays or entries reject while OCR is enabled. These hints do not infer a receipt country or parse receipt / invoice fields.                                                             |
| `cropAutoConfirm`   | `boolean`               | `false`                                   | iOS gallery only: skip the crop editor when document detection confidence is ≥ 0.85 and apply the detected corners automatically                                                                                                            |
| `ocrFloor`          | `OcrFloor \| false`     | conservative default (12 chars / 2 lines) | Reject blank / non-text captures (e.g. landscape photo of a wall). Pass `false` to disable. Only applies when `ocr: true`.                                                                                                                  |
| `autoRotate`        | `boolean`               | `true`                                    | Detect 90° / 180° / 270° content rotation via OCR confidence and rotate the output JPEG to upright. Only applies when `ocr: true`.                                                                                                          |
| `includeRawExif`    | `boolean`               | `false`                                   | Include the full raw EXIF / TIFF / GPS dictionary on `exif.raw`. Off by default to keep IPC payloads small. GPS keys excluded when `includeGpsExif: false`.                                                                                 |
| `minimumTextHeight` | `number` (0.0–1.0)      | `0`                                       | iOS only: minimum text height as a fraction of image height for Vision OCR (`0` = platform default ≈ 1/32). Lower it to recover small receipt line items; Android (ML Kit) has no equivalent and ignores it. Only applies when `ocr: true`. |
| `ocrGeometry`       | `boolean`               | `false`                                   | Attach per-line text boxes to `ocrLines`, in output-image pixels. Enable it to draw text-region overlays. Off by default because a long receipt can carry hundreds of lines. Only applies when `ocr: true`.                                 |

### Result types

```ts
type ScanReceiptResult = {
  status: "success" | "cancelled" | "rejected";
  images: ReceiptImage[]; // always an array — empty when none
  rejectedImages: ReceiptImage[]; // always an array — empty when none
};

type ReceiptImage = {
  uri: string; // file:// path — never base64
  width: number;
  height: number;
  fileName: string;
  mimeType: "image/jpeg";
  fileSize: number;
  imageOrigin: ImageOrigin; // always present; classifies how the source image was produced
  ocrText?: string; // present when options.ocr === true
  ocrQuality?: OcrQuality; // present when options.ocr === true
  ocrLines?: OcrLine[]; // present when options.ocrGeometry === true
  exif?: ReceiptExif; // present when options.includeExif === true
};

type OcrLine = {
  text: string; // this line's recognized text
  frame: { x: number; y: number; width: number; height: number }; // output-image pixels, top-left origin
  confidence?: number; // 0–1, when the platform reports one
};

type ImageOrigin = "camera" | "screenshot" | "download" | "unknown";

type OcrFloor = {
  minTextLength?: number; // default 12
  minLines?: number; // default 2
  minConfidence?: number; // default 0 — iOS only
};

type OcrQuality = {
  textLength: number; // trimmed character count
  lineCount: number; // non-empty line count
  confidence?: number; // mean recognition confidence (iOS only)
};

type ReceiptExif = {
  // image metadata
  orientation?: number; // always 1 — pixels are written orientation-normalized; raw.Orientation preserves the source value
  colorSpace?: number;
  lightSource?: number;
  exifVersion?: string;

  // device + software
  make?: string;
  model?: string;
  software?: string; // TIFF Software tag — iOS camera writes OS version; Samsung etc. may write firmware ID; editors/generators write their name

  // timestamps
  dateTime?: string;
  dateTimeOriginal?: string;
  dateTimeDigitized?: string;

  // camera settings (numeric, normalized across platforms)
  exposureTime?: number; // seconds
  fNumber?: number;
  iso?: number; // single value, not array
  focalLength?: number; // mm
  flash?: number;
  whiteBalance?: number;
  exposureMode?: number;
  exposureProgram?: number;
  meteringMode?: number;

  // gps (when includeGpsExif: true)
  gps?: {
    latitude: number;
    longitude: number;
    altitude?: number;
    timestamp?: string;
    speed?: number;
    heading?: number;
  };

  // raw passthrough (when includeRawExif: true)
  raw?: Record<string, string | number | Array<string | number>>;
};
```

```ts
// Pull every available EXIF tag (e.g. for migrations from @lodev09/react-native-exify)
const result = await scan({ source: "gallery", includeRawExif: true });
console.log(result.images[0].exif?.raw);
// → { Make: "samsung", Software: "F741NKSS3CZCS", Orientation: "6",
//     DateTimeOriginal: "...", FNumber: "1.8", ISOSpeedRatings: "100", ... }
```

### Error codes

| Code                                     | Trigger                                                             |
| ---------------------------------------- | ------------------------------------------------------------------- |
| `SCAN_IN_PROGRESS`                       | `scan()` called while a previous call has not yet resolved          |
| `NO_ACTIVITY`                            | No foreground activity / view controller found (Android)            |
| `NOT_SUPPORTED`                          | `VNDocumentCameraViewController` not supported on this device (iOS) |
| `SCANNER_INIT_FAILED`                    | ML Kit scanner failed to initialize (Android)                       |
| `SCAN_FAILED`                            | Unexpected activity result code (Android)                           |
| `PROCESSING_FAILED`                      | Image processing or OCR failed                                      |
| `CAMERA_FAILED`                          | `VNDocumentCameraViewController` reported an error (iOS)            |
| `INVALID_OCR_LANGUAGE`                   | OCR is enabled and a hint is empty or not a valid BCP 47 tag        |
| `OCR_LANGUAGE_NOT_SUPPORTED`             | A requested language or script is not supported by the provider     |
| `OCR_LANGUAGE_COMBINATION_NOT_SUPPORTED` | Android receives more than one non-Latin script                     |
| `OCR_MODEL_INSTALL_FAILED`               | Android cannot prepare the selected OCR model before scanner UI     |

---

## Architecture docs

- [API contract](docs/specs/api-contract.md) — full type reference, platform requirements, `imageOrigin` rules, fraud-filter notes
- [Scan pipeline](docs/specs/scan-pipeline.md) — internal processing flow (contributors)
- [Multilingual OCR](docs/specs/multilingual-ocr.md) — `ocrLanguages`, `getOcrCapabilities()`, per-platform language resolution
- [OCR line geometry](docs/specs/ocr-line-geometry.md) — `ocrLines` coordinate contract
- [OCR 180° orientation correction](docs/specs/ocr-orientation-correction.md) — confidence-based 2-pass detection (iOS)
- [Platform asymmetries](docs/notes/platform-asymmetries.md) — living record of every iOS/Android behavioural difference
- Phase plans: [1 JS wrapper](docs/plans/phase-1-js-wrapper.md) · [2 Android](docs/plans/phase-2-android.md) · [3 iOS](docs/plans/phase-3-ios.md) · [4 OCR orientation](docs/plans/phase-4-ocr-orientation-correction.md) · [5 autoflip/mirror](docs/plans/phase-5-autoflip-mirror-detection.md) · [6 data quality](docs/plans/phase-6-receipt-data-quality-hardening.md) · [7 line geometry](docs/plans/phase-7-ocr-line-geometry.md) · [8 multilingual OCR](docs/plans/phase-8-multilingual-ocr.md)
- ADRs: [001 Android ML Kit](docs/notes/adr-001-android-mlkit.md) · [002 iOS gallery crop](docs/notes/adr-002-ios-gallery-crop.md) · [003 package boundaries](docs/notes/adr-003-package-boundaries.md) · [004 iOS crop editor real-device fixes](docs/notes/adr-004-ios-crop-editor-realdevice-fixes.md) · [005 Android gallery strategy](docs/notes/adr-005-android-gallery-strategy.md) · [006 design audit + iOS 16 baseline](docs/notes/adr-006-design-audit-and-ios16-baseline.md) · [007 v0.4.2→v0.4.3 diff](docs/notes/adr-007-v042-v043-code-diff.md)

---

## Planned

| Feature                                   | Notes                                                                                                                                                                                                                                                                                                                                                                                       |
| ----------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **PDF export with searchable text layer** | Android: `GmsDocumentScannerOptions.RESULT_FORMAT_PDF` already produces a multi-page PDF — cost is near-zero. iOS: compose pages with `PDFKit` and overlay the OCR result as an invisible text layer so the file is copy-pasteable and full-text-searchable in any PDF viewer. New `ScanReceiptOptions.outputFormat: "jpeg" \| "pdf"` option; result carries a `pdfUri` alongside `images`. |

---

## Contributing

- [Development workflow](CONTRIBUTING.md#development-workflow)
- [Verification checklist before opening a PR](CONTRIBUTING.md#verification-checklist-before-opening-a-pr) — run `yarn typecheck && yarn lint && yarn test && trunk fmt && trunk check` and confirm it is clean.
- [Sending a pull request](CONTRIBUTING.md#sending-a-pull-request)
- [Code of conduct](CODE_OF_CONDUCT.md)

## License

MIT
