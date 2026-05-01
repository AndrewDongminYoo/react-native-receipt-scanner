---
date: 2026-05-01
topic: receipt-scanner
status: approved
---

# Receipt Scanner — Design Doc

## Context

[ReceiptScraper](https://github.com/AndrewDongminYoo/receipt-scraper) pp uploads mart/convenience-store receipts to an Azure OCR backend.
The current flow sends raw images directly. Two failure modes prompted this design:

1. **Geometric distortion** — skewed angles, perspective warp, crumpled receipts degrade OCR accuracy.
2. **Validity issues** — refund receipts, duplicates, foreign receipts, and non-retail receipts reach Azure unfiltered.

The solution is an in-house library (`react-native-receipt-scanner`) that standardises
image acquisition, normalisation, and on-device OCR before the image reaches the server.
Azure's existing pipeline is unchanged.

---

## Architecture

### Layers

```plaintext
App (ReceiptScraper)
  └─ scan(options) ──────────────────────────────── public API
        │
        ├─ [Android] ML Kit Document Scanner
        │    GmsDocumentScannerOptions
        │      setGalleryImportAllowed(true)
        │      setPageLimit(maxPages)
        │      RESULT_FORMAT_JPEG / SCANNER_MODE_FULL
        │
        └─ [iOS] camera: VNDocumentCameraViewController
                  gallery: PHPicker → VNDetectRectanglesRequest → crop editor
        │
        ├─ orientation normalisation
        ├─ JPEG recompress (target quality)
        ├─ EXIF read  (GPS stripped unless includeGpsExif=true)
        ├─ OCR  (Android: ML Kit TextRecognition / iOS: VNRecognizeTextRequest)
        ├─ temp file write
        └─ JS result returned → temp cleanup on next call
```

### Package boundaries

**In scope:** scan/pick, document crop, rotation correction, JPEG compression,
EXIF extraction, on-device OCR text primitive.

**Out of scope:** receipt domain parsing (store name, amount, date), upload policy,
Azure OCR response interpretation, fraud/duplicate detection, point/reward logic.

---

## Public API

```ts
function scan(options?: ScanReceiptOptions): Promise<ScanReceiptResult>;

type ScanReceiptOptions = {
  source?: "camera" | "gallery"; // default: 'camera'
  maxPages?: number; // default: 1
  quality?: number; // default: 0.82  (0.0–1.0)
  includeExif?: boolean; // default: true
  includeGpsExif?: boolean; // default: false
  ocr?: boolean; // default: true
};

type ScanReceiptResult = {
  status: "success" | "cancelled";
  images: ReceiptImage[];
};

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

type ReceiptExif = {
  orientation?: number;
  dateTimeOriginal?: string;
  make?: string;
  model?: string;
  gps?: { latitude: number; longitude: number };
};
```

---

## Implementation Phases

### Phase 1 — JS wrapper & type unification

Goal: stable public interface before any native work.

- Remove `multiply` placeholder from src/, android/, ios/
- Define all types in `src/types.ts`
- Implement `scan()` with default options in `src/index.tsx`
- Stub `NativeReceiptScanner.ts` with `scan` method signature
- Update example app

### Phase 2 — Android (ML Kit)

Goal: full Android implementation.

- ML Kit Document Scanner (gallery import, crop, JPEG)
- ML Kit Text Recognition (Korean + English)
- ExifInterface (GPS stripped by default)
- JPEG recompress at target quality
- ActivityResult API, TurboModule

### Phase 3 — iOS (VisionKit + custom gallery crop)

Goal: iOS parity.

- Camera: `VNDocumentCameraViewController` → JPEG write
- Gallery: `PHPicker` → `VNDetectRectanglesRequest` → crop editor
- OCR: `VNRecognizeTextRequest` (ko-KR + en-US)
- JPEG recompress: `CGImageDestination`
- EXIF via `ImageIO`

---

## Key Decisions

| Decision         | Choice                              | Rationale                                                           |
| ---------------- | ----------------------------------- | ------------------------------------------------------------------- |
| Android scanner  | ML Kit Document Scanner             | Built-in gallery import, no OpenCV from scratch                     |
| iOS gallery crop | Custom Vision/CoreImage crop editor | VisionKit is camera-only; no vendor lock-in                         |
| GPS EXIF default | `false`                             | Privacy risk; irrelevant to OCR quality                             |
| base64           | Disabled                            | OOM risk on low-end devices; file URI sufficient                    |
| OCR location     | Client (preview) + Server (final)   | Client OCR for pre-validation only; authoritative result from Azure |

---

## Definition of Done (Phase 1 complete)

```plaintext
[ ] Camera scan → cropped JPEG returned
[ ] Gallery image → document crop → JPEG returned
[ ] JPEG quality compression applied
[ ] width / height / fileSize populated
[ ] EXIF returned, GPS absent by default
[ ] OCR text returned
[ ] Android low-end device: no OOM
[ ] base64-free: file URI only
```
