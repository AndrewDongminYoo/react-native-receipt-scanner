# API Contract

Public interface for `react-native-receipt-scanner`. This is what app developers read.

## Platform requirements

| Platform | Minimum                                                                                           |
| -------- | ------------------------------------------------------------------------------------------------- |
| iOS      | **16.0+** (Korean OCR via `VNRecognizeTextRequest` requires iOS 16; no Latin-only fallback ships) |
| Android  | minSdk 24, compileSdk 36, Google Play Services (ML Kit Document Scanner)                          |

## `scan(options?)`

```ts
function scan(options?: ScanReceiptOptions): Promise<ScanReceiptResult>;
```

Launches the receipt scan flow (camera or gallery picker). Resolves when the user
completes or cancels. Rejects only on unrecoverable errors (e.g. permissions denied
and the system cannot show the rationale dialog).

## `ScanReceiptOptions`

| Field               | Type                    | Default              | Description                                                                                                                                                                                                                                                                                                               |
| ------------------- | ----------------------- | -------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `source`            | `'camera' \| 'gallery'` | `'camera'`           | Whether to open the document scanner camera or the image picker.                                                                                                                                                                                                                                                          |
| `maxPages`          | `number`                | `1`                  | Maximum pages per scan session.                                                                                                                                                                                                                                                                                           |
| `quality`           | `number` (0.0–1.0)      | `0.82`               | JPEG compression quality applied after crop.                                                                                                                                                                                                                                                                              |
| `includeExif`       | `boolean`               | `true`               | Attach EXIF metadata to each image result.                                                                                                                                                                                                                                                                                |
| `includeGpsExif`    | `boolean`               | `false`              | Include GPS coordinates in EXIF. **Leave false** — GPS is a privacy risk and irrelevant to OCR quality.                                                                                                                                                                                                                   |
| `ocr`               | `boolean`               | `true`               | Run on-device OCR and return `ocrText`.                                                                                                                                                                                                                                                                                   |
| `ocrLanguages`      | `readonly string[]`     | `["ko-KR", "en-US"]` | Ordered BCP 47 hints that select native OCR. Empty arrays or entries reject while OCR is enabled. These hints do not infer a receipt country or parse receipt / invoice fields.                                                                                                                                           |
| `cropAutoConfirm`   | `boolean`               | `false`              | (iOS gallery only) Skip the crop editor when detection confidence is high (≥ 0.85).                                                                                                                                                                                                                                       |
| `ocrFloor`          | `OcrFloor \| false`     | conservative default | Reject images whose OCR result falls below the floor (see "OCR floor" below). Pass `false` to disable. Only applies when `ocr === true`.                                                                                                                                                                                  |
| `autoRotate`        | `boolean`               | `true`               | Detect 90° / 180° / 270° content rotation via OCR confidence and rotate the output JPEG to the upright orientation (see "Auto-rotate" below). Effective only when `ocr === true`.                                                                                                                                         |
| `includeRawExif`    | `boolean`               | `false`              | Include the full raw EXIF / TIFF / GPS dictionary on `exif.raw`. Off by default to keep IPC payloads small (raw maps are typically 30–60 fields). Effective only when `includeExif === true`. GPS keys are excluded from `raw` whenever `includeGpsExif === false`.                                                       |
| `minimumTextHeight` | `number` (0.0–1.0)      | `0`                  | **iOS only.** Vision `minimumTextHeight` as a fraction of image height; text shorter than this is skipped during recognition. Lowering it (e.g. `0.02`) can recover small receipt line items at the cost of more noise. `0` uses the package default (≈ 1/32). Android (ML Kit) has no equivalent and ignores this field. |
| `ocrGeometry`       | `boolean`               | `false`              | Attach per-line OCR boxes to `ReceiptImage.ocrLines` (see "OCR line geometry" below). Effective only when `ocr === true`.                                                                                                                                                                                                 |
| `mergeOcrPages`     | `boolean`               | `false`              | Assemble the pages' OCR text into one ordered string on `ScanReceiptResult.mergedOcr` (see "Long receipt OCR merge" below). Requires `ocr: true` and an integer `maxPages >= 2`; page JPEGs are never combined.                                                                                                           |

### Multilingual OCR and capabilities

Use BCP 47 hints to select the native OCR configuration, then query the active platform without opening scanner UI:

```ts
const capabilities = await getOcrCapabilities();
const result = await scan({
  ocrLanguages: ["es-ES", "en-US"],
  ocrFloor: false,
});
```

`ocrLanguages` defaults to `["ko-KR", "en-US"]`. The hints select native OCR only. They do not infer a receipt country and do not parse receipt or invoice fields.

```ts
type OcrCapabilities =
  | {
      platform: "ios";
      defaultLanguages: ["ko-KR", "en-US"];
      supportedLanguages: string[];
    }
  | {
      platform: "android";
      defaultLanguages: ["ko-KR", "en-US"];
      models: Array<{ script: string; status: "ready" | "download-required" }>;
    }
  | {
      platform: "web";
      defaultLanguages: ["ko-KR", "en-US"];
      supported: false;
    };
```

The web fallback always returns the `platform: "web"` shape above and does not provide native OCR. Android capability discovery reports state without installing a model. Provider-supported non-Korean and non-English scripts remain uncalibrated until fixture coverage exists.

### Multilingual OCR errors

| Code                                     | Trigger                                                                        |
| ---------------------------------------- | ------------------------------------------------------------------------------ |
| `INVALID_OCR_LANGUAGE`                   | OCR is enabled and a hint is empty or not a valid BCP 47 tag.                  |
| `OCR_LANGUAGE_NOT_SUPPORTED`             | A requested language or script is not supported by the active native provider. |
| `OCR_LANGUAGE_COMBINATION_NOT_SUPPORTED` | Android receives more than one non-Latin script.                               |
| `OCR_MODEL_INSTALL_FAILED`               | Android cannot prepare the selected OCR model before scanner UI is presented.  |

### OCR line geometry

With `ocrGeometry: true`, each image carries `ocrLines` — one entry per recognized line that the platform could place:

```ts
type OcrLine = {
  text: string;
  frame: { x: number; y: number; width: number; height: number };
  confidence?: number; // 0–1
};
```

`frame` is in the **output JPEG's pixel space** — the same space as `ReceiptImage.width` / `height` — with a top-left origin, and is clamped to the image bounds. A consumer drawing an overlay only needs the displayed-to-actual scale factor.

Lines the platform could not place are omitted: Android's ML Kit `boundingBox` is nullable, and any box that clamps to zero area is dropped. `ocrLines` therefore does **not** correspond index-wise with the newline-joined `ocrText` — read `OcrLine.text` per box instead.

Coordinates are integers on Android (ML Kit reports a pixel `Rect`) and fractional on iOS (Vision reports normalized values scaled by the pixel size). Both satisfy the `number` contract. See `docs/specs/ocr-line-geometry.md` for the coordinate derivation and `docs/notes/platform-asymmetries.md` §2.3 / §3.1 for the underlying platform differences.

### OCR floor

The package gates obviously bad captures (blank pages, landscape photos, text-free images) before returning them, per the "gate obviously bad images" responsibility in ADR-003. The floor runs in JS after the native OCR pass and uses `ocrText` to derive `OcrQuality`.

```ts
type OcrFloor = {
  minTextLength?: number; // default 12
  minLines?: number; // default 2
  minConfidence?: number; // default 0 — populated on both platforms when OCR runs; absent confidence is treated as satisfied
};

type OcrQuality = {
  textLength: number; // trimmed character count of ocrText
  lineCount: number; // non-empty line count
  confidence?: number; // mean recognition confidence — populated on both platforms when OCR runs
};
```

The default — `{ minTextLength: 12, minLines: 2, minConfidence: 0 }` — is calibrated for Korean retail receipts: store name + total = at least two lines and roughly twelve characters of trimmed content. Override with `ocrFloor: { minLines: 3 }` to tighten or `ocrFloor: false` to disable.

Floor evaluation rules:

- When `ocr: false` the floor is a no-op (there is nothing to measure). Result `status` is `"success"` with `ocrQuality` absent.
- When `ocr: true` and `ocrFloor: false` the floor is a no-op but `ocrQuality` is still derived and exposed for the consumer to inspect.
- When the floor is active, every image has `ocrQuality` populated. Images that fail the thresholds are moved to `rejectedImages`. If no image passes, `status` becomes `"rejected"`.

### Long receipt OCR merge

A receipt too long for one frame is captured across several overlapping pages — by camera, or as a set of screenshots picked from the gallery. With `mergeOcrPages: true`, the JS layer assembles those pages' OCR text into one ordered string and removes the text duplicated at each seam.

Page JPEGs are never combined. A single tall image would lose text resolution on both platforms — see [`../notes/adr-008-long-receipt-merge-boundary.md`](../notes/adr-008-long-receipt-merge-boundary.md) for the measurements.

```ts
type MergedOcrResult = {
  text: string; // merged text, proven overlap removed once
  isComplete: boolean; // only covers the pages that came back — see below
  pageUris: string[]; // native capture order; the index space below
  unmatchedBoundaryIndexes: number[]; // i = the seam between pageUris[i] and pageUris[i + 1]
  rejectedPageIndexes: number[]; // no usable text, or rejected by the floor
};
```

Rules:

- Requires `ocr: true` and `maxPages` set to an integer of at least 2. Anything else throws `INVALID_MERGE_OPTION` before any scanner UI opens — `NaN`, `Infinity`, and `2.5` are all rejected.
- `mergedOcr` is attached whenever the native scan captured something — including a `"rejected"` result, where the diagnostics are what tell a consumer to prompt for a re-shoot. A `"cancelled"` scan has no `mergedOcr`.
- Pages are merged in native capture order and never reordered. Only adjacent pages are compared, and repeated lines away from a seam (totals, headers) are always preserved.
- A seam whose overlap cannot be proven contributes both pages' lines in full and records its boundary index. Enabling the merge never loses text.
- Per-page `ocrLines` geometry is **not** merged. Boxes from different pages live in different pixel spaces, so `mergedOcr.text` has no corresponding geometry.
- `isComplete` cannot see a page the native layer dropped before returning, so it means "everything returned joined up", not "the whole receipt is here".

The algorithm and its thresholds are normative in [`long-receipt-ocr-merge.md`](./long-receipt-ocr-merge.md).

### Auto-rotate

When `ocr: true` and `autoRotate: true` (default), the package detects 90° / 180° / 270° content rotation during OCR and rotates the output JPEG pixels to the upright orientation. The OCR text is also returned in upright form. `exif.orientation` remains `1` regardless — the rotation is baked into the pixels, not the metadata.

The detector is gated on the input aspect ratio:

- Portrait inputs (`width <= height`): only a 180° probe runs. Cost ≈ 0 ms when OCR confidence is high.
- Landscape inputs (`width > height`): 90°, 180°, and 270° probes run. Cost ≈ 300–450 ms when probes are needed; near-zero when the input is a genuine landscape receipt (high OCR confidence + ≥ 5 lines + aspect ≤ 1.5).

Disable with `autoRotate: false` to keep the v1.0 behaviour (180° text correction in OCR but no pixel rotation). See `docs/specs/ocr-orientation-correction.md` for the full algorithm.

## Localization

The gallery crop editor (`source: "gallery"`) displays an instruction and two
buttons whose labels can be translated by the host app.

For iOS, add the following keys to each `Localizable.strings` file:

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

For Android, override the same resource names in the host app's `strings.xml` files.

The iOS library uses `NSLocalizedStringWithDefaultValue` against `[NSBundle mainBundle]`, so the host app's strings file is picked up automatically when the device language matches.
If a key is absent, the English defaults (`"Drag the corners to frame the document"` /
`"Cancel"` / `"Use Photo"`) are used.
Android ships Korean defaults in `values/strings.xml` and English defaults in
`values-en/strings.xml`; host app resources with the same names override them.

This has no effect on the camera scanner.

## `ScanReceiptResult`

```ts
type ScanReceiptResult = {
  status: "success" | "cancelled" | "rejected";
  images: ReceiptImage[];
  rejectedImages: ReceiptImage[];
  mergedOcr?: MergedOcrResult; // only with mergeOcrPages: true
};
```

`images` and `rejectedImages` are **always arrays** — empty when there are no items. Consumers can read `.length` on both without a null-check.

| `status`      | `images`                            | `rejectedImages`                                             | `mergedOcr`                        |
| ------------- | ----------------------------------- | ------------------------------------------------------------ | ---------------------------------- |
| `"success"`   | At least one image passed the floor | Empty when nothing was rejected; populated on partial reject | Present with `mergeOcrPages: true` |
| `"cancelled"` | `[]` (user dismissed the scanner)   | `[]`                                                         | Absent — nothing was captured      |
| `"rejected"`  | `[]` (every image failed the floor) | All captured images so the consumer can prompt for a re-take | Present with `mergeOcrPages: true` |

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
  ocrQuality?: OcrQuality;
  ocrLines?: OcrLine[];
  exif?: ReceiptExif;
  imageOrigin: "camera" | "screenshot" | "download" | "unknown";
};
```

| Field         | Present when                                                                 |
| ------------- | ---------------------------------------------------------------------------- |
| `ocrText`     | `options.ocr === true`                                                       |
| `ocrQuality`  | `options.ocr === true` (derived in JS from `ocrText`; see "OCR floor" above) |
| `exif`        | `options.includeExif === true`                                               |
| `imageOrigin` | Always present (see platform notes below)                                    |

`uri` is a `file://` path. Never a `data:` URI — base64 is disabled to prevent OOM on low-end devices.

### `imageOrigin` platform behavior

| Source            | iOS                                                  | Android                                                                           |
| ----------------- | ---------------------------------------------------- | --------------------------------------------------------------------------------- |
| `source: camera`  | `"camera"` (always)                                  | `"camera"` (always)                                                               |
| `source: gallery` | EXIF-based: `"camera"`, `"download"`, or `"unknown"` | MediaStore bucket-based: `"screenshot"`, `"camera"`, `"download"`, or `"unknown"` |

**iOS gallery detection logic (in priority order):**

1. EXIF has `dateTimeOriginal`, or both `make` + `model` → `"camera"`
2. EXIF has none of those fields → `"download"`
3. Partial EXIF → `"unknown"`

The gallery picker does not request Photos library authorization. In particular, iOS screenshots
without camera EXIF are classified as `"download"` rather than `"screenshot"`.

**Android gallery detection logic (in priority order):**

1. `MediaStore.Images.Media.BUCKET_DISPLAY_NAME` matches `"Screenshots"` → `"screenshot"`
2. Bucket matches `"Download"` / `"Downloads"` → `"download"`
3. Bucket matches `"Camera"` / `"DCIM"`, **or** EXIF has `dateTimeOriginal`, **or** EXIF has both `make` and `model` → `"camera"`
4. Anything else (custom folders, cloud-synced files, files without identifiable bucket) → `"unknown"`

### Note for fraud filters

`"unknown"` is a permissive bucket — it covers any image whose origin the OS cannot attribute.
A consumer that rejects only `"screenshot"` and `"download"` will let sideloaded images through.
Either widen the reject set to include `"unknown"` (strict) or supplement with `exif.software`, `exif.dateTimeOriginal`, and `exif.make/model` heuristics (lenient with stronger evidence).

#### Software tag patterns

`exif.software` is a high-signal field but only when interpreted by **value pattern**, not by presence:

| Pattern                                                                      | Likely origin                | Risk      |
| ---------------------------------------------------------------------------- | ---------------------------- | --------- |
| `/^\d+(\.\d+)+$/` (pure version, e.g. `17.0`, `26.4.2`) and `make = "Apple"` | Native iOS camera capture    | low       |
| Empty / absent and `make/model` set                                          | Most Android cameras         | low       |
| Empty / absent and `make/model` also empty                                   | Screenshot or stripped image | medium    |
| Known editor name (e.g. `"Adobe Photoshop"`, `"GIMP"`)                       | Manually edited image        | high      |
| ML / generator name (e.g. `"Stable Diffusion"`, `"Midjourney"`)              | Synthetic image              | very high |
| Vendor camera-app identifier (e.g. `"MIUI Camera"`)                          | Android OEM camera app       | low       |

The library does not interpret `software` itself — it forwards the raw value. Consumers should write a small allowlist / denylist that fits their tolerance.

## `ReceiptExif`

```ts
type ReceiptExif = {
  // Image metadata
  orientation?: number;
  colorSpace?: number;
  lightSource?: number;
  exifVersion?: string;

  // Device + software
  make?: string;
  model?: string;
  software?: string;

  // Timestamps
  dateTime?: string;
  dateTimeOriginal?: string;
  dateTimeDigitized?: string;

  // Camera settings (numeric, normalized across platforms)
  exposureTime?: number; // seconds, e.g. 0.04
  fNumber?: number; // f-stop, e.g. 1.8
  iso?: number; // single value, not array
  focalLength?: number; // millimeters
  flash?: number;
  whiteBalance?: number;
  exposureMode?: number;
  exposureProgram?: number;
  meteringMode?: number;

  // GPS (when includeGpsExif === true)
  gps?: {
    latitude: number;
    longitude: number;
    altitude?: number; // meters
    timestamp?: string;
    speed?: number;
    heading?: number; // degrees
  };

  // Raw passthrough (when includeRawExif === true)
  raw?: Record<string, string | number | Array<string | number>>;
};
```

| Field                                      | Notes                                                                                                                                                                                                                                                                                                                                                                                                                             |
| ------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `orientation`                              | Always reported as `1` (kCGImagePropertyOrientationUp / `ORIENTATION_NORMAL`); pixels are normalized at write time. Callers must not re-rotate.                                                                                                                                                                                                                                                                                   |
| `dateTimeOriginal`                         | EXIF `DateTimeOriginal`. Absent in screenshots and most edited / generated images.                                                                                                                                                                                                                                                                                                                                                |
| `make`, `model`                            | TIFF `Make` / `Model`. Absent in screenshots and downloaded images. iOS camera path synthesizes `Apple` / device model when EXIF is missing.                                                                                                                                                                                                                                                                                      |
| `software`                                 | TIFF `Software` tag. iOS camera apps populate it with the OS version (e.g. `"17.0"`, `"26.4.2"`). Android cameras usually leave it empty or write a ROM / app identifier (e.g. `"MIUI Camera"`). Editors / generators write their own name (`"Photoshop 24.0"`, `"Stable Diffusion v1.5"`). Screenshots are usually empty. **Use the value pattern, not mere presence**, for fraud filtering — see "Software tag patterns" below. |
| `gps`                                      | Populated only when `options.includeGpsExif === true`. `latitude` / `longitude` are decimal degrees with sign applied. `altitude` is meters (negative below sea level). `heading` falls back from `GPSImgDirection` to `GPSDestBearing`.                                                                                                                                                                                          |
| Camera settings                            | `exposureTime`, `fNumber`, `iso`, `focalLength`, `flash`, `whiteBalance`, `exposureMode`, `exposureProgram`, `meteringMode`. iOS exposes `ISOSpeedRatings` as an array; the package normalizes to a single number to match Android.                                                                                                                                                                                               |
| `colorSpace`, `lightSource`, `exifVersion` | Image / EXIF metadata. `exifVersion` is reported as a string (e.g. `"0220"`).                                                                                                                                                                                                                                                                                                                                                     |
| `raw`                                      | Populated only when `options.includeRawExif === true`. Flat map keyed by standard EXIF tag name. See "Raw EXIF passthrough" below.                                                                                                                                                                                                                                                                                                |

### Raw EXIF passthrough

Set `includeRawExif: true` to receive the full set of EXIF / TIFF / GPS attributes the platform exposes, flattened into one map:

```ts
exif.raw ===
  {
    Make: "samsung",
    Model: "Galaxy Z Flip6",
    Software: "F741NKSS3CZCS",
    Orientation: "6", // ← original EXIF value, NOT the normalized 1
    DateTimeOriginal: "2026:05:09 17:33:23",
    ExposureTime: "1/30",
    FNumber: "1.8",
    ISOSpeedRatings: "100",
    // ... 30–60 fields total ...
    GPSLatitude: "37/1,30/1,0/1", // ← only when includeGpsExif === true
    GPSLatitudeRef: "N",
  };
```

- Keys are **standard EXIF tag names** so iOS and Android keys match in most cases.
- Values are forwarded **as-is** (typically string or number, occasionally arrays).
- The white-list `orientation` is always `1` because output pixels are normalized; `raw.Orientation` is the **original** EXIF value (1–8) for transparency.
- Binary fields (`Thumbnail*`, `MakerNote`, `UserComment`, `JPEGInterchangeFormat`, etc.) are excluded.
- GPS-prefixed keys are excluded from `raw` whenever `includeGpsExif === false`, matching the white-list `gps` policy.

### Output JPEG metadata asymmetry (known limitation)

- **iOS** preserves the full EXIF / TIFF / GPS dictionaries on the output JPEG via `CGImageDestinationAddImage`.
  GPS is stripped only when `includeGpsExif === false`.
- **Android** writes the JPEG via `Bitmap.compress(JPEG, …)`, then `ImageProcessor.writeExifToFile`
  copies the parsed structured tags back onto the output file after the final compression.
  Written tags: `make`, `model`, `software`, `dateTime` / `dateTimeOriginal` /
  `dateTimeDigitized`, GPS `lat` / `lng` / `altitude`, and `orientation = NORMAL`
  (output pixels are upright, matching iOS). Runs only when `includeExif` is true.
  Not written back: the flat `raw` map and GPS speed / heading / timestamp.

Server-side validators that re-read EXIF from the uploaded file now see metadata on
both iOS and Android uploads. The JS-side `exif` field remains the richer source
(it also carries `raw` and the source `orientation` value).

## Package Responsibilities

**In scope:**

- Image acquisition (camera scan, gallery pick)
- Document crop and perspective correction
- Rotation / orientation normalisation (EXIF + 180° content-rotation correction; see [ocr-orientation-correction.md](./ocr-orientation-correction.md))
- JPEG recompression at target quality
- EXIF extraction (read-time) and GPS stripping
- On-device OCR (raw text primitive — used at the consumer layer for store-keyword
  classification, not authoritative transcription)
- Temp file management

**Out of scope** (belongs in app or server):

- Receipt domain parsing (store name, amount, date)
- Mart / convenience-store classification (built on `ocrText` keyword matching at the consumer)
- Upload policy and network transport
- Azure OCR / Document Intelligence response handling
- Fraud detection (consumer combines `imageOrigin`, `exif.software`, `exif.dateTimeOriginal`)
- Duplicate receipt detection
- Point / reward business logic
