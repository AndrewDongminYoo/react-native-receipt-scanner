# API Contract

Public interface for `react-native-receipt-scanner`. This is what app developers read.

## `scan(options?)`

```ts
function scan(options?: ScanReceiptOptions): Promise<ScanReceiptResult>;
```

Launches the receipt scan flow (camera or gallery picker). Resolves when the user
completes or cancels. Rejects only on unrecoverable errors (e.g. permissions denied
and the system cannot show the rationale dialog).

## `ScanReceiptOptions`

| Field             | Type                    | Default    | Description                                                                                             |
| ----------------- | ----------------------- | ---------- | ------------------------------------------------------------------------------------------------------- |
| `source`          | `'camera' \| 'gallery'` | `'camera'` | Whether to open the document scanner camera or the image picker.                                        |
| `maxPages`        | `number`                | `1`        | Maximum pages per scan session.                                                                         |
| `quality`         | `number` (0.0–1.0)      | `0.82`     | JPEG compression quality applied after crop.                                                            |
| `includeExif`     | `boolean`               | `true`     | Attach EXIF metadata to each image result.                                                              |
| `includeGpsExif`  | `boolean`               | `false`    | Include GPS coordinates in EXIF. **Leave false** — GPS is a privacy risk and irrelevant to OCR quality. |
| `ocr`             | `boolean`               | `true`     | Run on-device OCR and return `ocrText`.                                                                 |
| `cropAutoConfirm` | `boolean`               | `false`    | (iOS gallery only) Skip the crop editor when detection confidence is high (≥ 0.85).                     |

## Localization

The iOS gallery crop editor (`source: "gallery"`) displays two buttons whose labels can be translated via `Localizable.strings` in the host app's Xcode project. Add the following keys to each locale's strings file:

```plaintext
/* ios/en.lproj/Localizable.strings */
"RNReceiptScanner_cancelButton"  = "Cancel";
"RNReceiptScanner_confirmButton" = "Use Photo";

/* ios/ko.lproj/Localizable.strings */
"RNReceiptScanner_cancelButton"  = "취소";
"RNReceiptScanner_confirmButton" = "사진 사용";
```

The library uses `NSLocalizedStringWithDefaultValue` against `[NSBundle mainBundle]`, so the host app's strings file is picked up automatically when the device language matches. If a key is absent, the English defaults (`"Cancel"` / `"Use Photo"`) are used.

This has no effect on the camera scanner or on Android.

## `ScanReceiptResult`

```ts
type ScanReceiptResult = {
  status: "success" | "cancelled";
  images: ReceiptImage[];
};
```

`images` is empty when `status === 'cancelled'`.

## `ReceiptImage`

```ts
type ReceiptImage = {
  uri: string;
  width: number;
  height: number;
  fileName: string;
  mimeType: "image/jpeg";
  fileSize: number;
  ocrText?: string;
  exif?: ReceiptExif;
  imageOrigin: "camera" | "screenshot" | "download" | "unknown";
};
```

| Field         | Present when                              |
| ------------- | ----------------------------------------- |
| `ocrText`     | `options.ocr === true`                    |
| `exif`        | `options.includeExif === true`            |
| `imageOrigin` | Always present (see platform notes below) |

`uri` is a `file://` path. Never a `data:` URI — base64 is disabled to prevent OOM on low-end devices.

### `imageOrigin` platform behavior

| Source            | iOS                                                                     | Android                                                           |
| ----------------- | ----------------------------------------------------------------------- | ----------------------------------------------------------------- |
| `source: camera`  | `"camera"` (always)                                                     | `"camera"` (always)                                               |
| `source: gallery` | PHAsset-based: `"screenshot"`, `"camera"`, `"download"`, or `"unknown"` | `"unknown"` (GmsDocumentScanner does not expose the original URI) |

**iOS gallery detection logic (in priority order):**

1. `PHAssetMediaSubtypePhotoScreenshot` → `"screenshot"` (definitive, requires `NSPhotoLibraryUsageDescription` in `Info.plist`)
2. EXIF has `make` + `model` + `dateTimeOriginal` → `"camera"`
3. EXIF has none of those fields → `"download"`
4. Partial or missing EXIF → `"unknown"`

If the user denies photo library permission, PHAsset detection is skipped and only EXIF heuristics (steps 2–4) are used.

## `ReceiptExif`

```ts
type ReceiptExif = {
  orientation?: number;
  dateTimeOriginal?: string;
  make?: string;
  model?: string;
  gps?: {
    latitude: number;
    longitude: number;
  };
};
```

`gps` is only populated when `options.includeGpsExif === true`.

## Package Responsibilities

**In scope:**

- Image acquisition (camera scan, gallery pick)
- Document crop and perspective correction
- Rotation / orientation normalisation
- JPEG recompression at target quality
- EXIF extraction and GPS stripping
- On-device OCR (text primitive)
- Temp file management

**Out of scope** (belongs in app or server):

- Receipt domain parsing (store name, amount, date)
- Upload policy and network transport
- Azure OCR / Document Intelligence response handling
- Fraud detection, duplicate checks
- Point / reward business logic
