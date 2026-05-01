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

| Field            | Type                    | Default    | Description                                                                                             |
| ---------------- | ----------------------- | ---------- | ------------------------------------------------------------------------------------------------------- |
| `source`         | `'camera' \| 'gallery'` | `'camera'` | Whether to open the document scanner camera or the image picker.                                        |
| `maxPages`       | `number`                | `1`        | Maximum pages per scan session.                                                                         |
| `quality`        | `number` (0.0–1.0)      | `0.82`     | JPEG compression quality applied after crop.                                                            |
| `includeExif`    | `boolean`               | `true`     | Attach EXIF metadata to each image result.                                                              |
| `includeGpsExif` | `boolean`               | `false`    | Include GPS coordinates in EXIF. **Leave false** — GPS is a privacy risk and irrelevant to OCR quality. |
| `ocr`            | `boolean`               | `true`     | Run on-device OCR and return `ocrText`.                                                                 |

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
};
```

| Field     | Present when                   |
| --------- | ------------------------------ |
| `ocrText` | `options.ocr === true`         |
| `exif`    | `options.includeExif === true` |

`uri` is a `file://` path. Never a `data:` URI — base64 is disabled to prevent OOM on low-end devices.

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
