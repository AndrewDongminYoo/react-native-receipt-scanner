# ADR-006: Design Audit Outcomes — iOS 16 Baseline, EXIF `software`, Surface Trim

## Status

Accepted

## Context

A design audit (2026-05-09) reviewed the package against four axes
— implementation complexity, OS version support, EXIF preservation for abuse prevention, and rotated
/ flipped image OCR quality

This ADR records the decisions that came out of the audit and the rationale.
The findings that resulted in code or doc changes are below;
the findings that were deferred (parallel OCR, mirror-flip handling, server-side EXIF parity) are listed in the "Deferred" section so they are not lost.

## Decisions

### D1. Pin iOS minimum to 16.0

**Decision.** `ReceiptScanner.podspec` declares `s.platforms = { :ios => "16.0" }` explicitly, instead of inheriting React Native's `min_ios_version_supported`.

**Why.**

- Korean OCR via `VNRecognizeTextRequest` is iOS 16+ only.
- The package provides no en-US-only fallback for receipts:
  a Korean receipt recognized as Latin text yields empty / nonsense `ocrText` and breaks the confidence-based 180° detection (`Q1 < 0.80` fires unconditionally on Korean-only content under en-US, triggering Pass 2/3 every time without ever improving the result — see `ocr-orientation-correction.md`).
- The consumer app already pins `platform :ios, '16.0'` in its Podfile, so there is no real-world iOS 15 user to protect.

**Consequences.**

- `RNOcrProcessor.m`'s `@available(iOS 16, *)` guard is removed; the language list is unconditionally `["ko-KR", "en-US"]`.
- `RNImageProcessor.m`'s `@available(iOS 14, *)` UTI guard is removed.
- `phase-3-ios.md` and `ocr-orientation-correction.md` no longer claim iOS 15 fallback behaviour.

### D2. Add `software` to `ReceiptExif`

**Decision.** Surface the TIFF `Software` tag as `exif.software?: string` on both platforms.

**Why.** The tag is high-signal once interpreted by **value pattern**:

- iOS camera apps populate it with the OS version (`"17.0"`, `"26.4.2"`). Combined with `make = "Apple"` this is a positive signal of an unedited iOS capture.
- Android cameras usually leave it empty; some OEMs write a ROM / app identifier (`"MIUI Camera"`, `"OnePlus Camera"`).
- Editors and image generators write their own name (`"Adobe Photoshop"`, `"GIMP"`, `"Stable Diffusion"`, `"Midjourney"`) — strong negative signal.
- Screenshots are usually empty.

The original audit note said "native cameras leave this empty" — that's wrong for iOS. The corrected guidance is: do not key off presence/absence; key off the value pattern. See `api-contract.md` "Software tag patterns" for the recommended allow / deny lists.

**Consequences.**

- Android: `ImageProcessor.ExifData` gains a `software` field, populated from `ExifInterface.TAG_SOFTWARE`; `ResultBuilder` writes it under `exif.software`.
- iOS: `RNImageProcessor.buildExifDict` reads `kCGImagePropertyTIFFSoftware` from the source's TIFF dictionary.
  `kCGImagePropertyExifSoftware` does not exist in `ImageIO`; the EXIF dictionary has no Software counterpart.
- The package forwards the raw tag value without interpretation; pattern matching is the consumer's responsibility (per ADR-003 boundary).

**Follow-up (2026-05-10).** A real-device test on Galaxy Z Flip6 surfaced `software: "F741NKSS3CZCS"` (model + carrier + firmware build ID), which doesn't match the OS-version pattern iOS uses. This reinforces the value-pattern guidance and motivated D8 below: rather than asking the package to interpret OEM-specific values, expose the entire EXIF dictionary so the consumer can write their own allow / deny lists.

### D8. Expose the full EXIF dictionary as `exif.raw`

**Decision.** Add `ReceiptExif.raw?: Record<string, string | number | Array<string | number>>` (a flat map keyed by standard EXIF tag name) and `ScanReceiptOptions.includeRawExif` (default `false`).

**Why.** A user comparing fraud-detection coverage with `@lodev09/react-native-exify` reported that the white-list missed many fields they already use (`exposureTime`, `fNumber`, `iso`, `flash`, `whiteBalance`, `colorSpace`, GPS `altitude` / `heading`, etc.) and asked for raw passthrough. The minimal cross-platform white-list is good for type safety but cannot anticipate every signal a consumer will need. Exposing the full dictionary as opt-in passthrough lets consumers migrate from existing EXIF libraries with zero code change.

**Consequences.**

- The white-list grows to cover every field that's stable on both iOS ImageIO and Android `ExifInterface`: timestamps (`dateTime`, `dateTimeOriginal`, `dateTimeDigitized`), camera settings (`exposureTime`, `fNumber`, `iso`, `focalLength`, `flash`, `whiteBalance`, `exposureMode`, `exposureProgram`, `meteringMode`), image metadata (`colorSpace`, `lightSource`, `exifVersion`), and richer GPS (`altitude`, `timestamp`, `speed`, `heading`). ISO is normalized from iOS's array form to a single number to match Android.
- `raw` is opt-in (`includeRawExif: false` by default) to avoid bloating IPC payloads — typical raw maps are 30–60 fields.
- Binary fields (`Thumbnail*`, `MakerNote`, `UserComment`, `JPEGInterchangeFormat`) are excluded.
- GPS-prefixed keys are excluded from `raw` whenever `includeGpsExif === false`, matching the white-list `gps` policy.
- White-list `orientation` stays `1` (output pixels are normalized); `raw.Orientation` preserves the _original_ EXIF value (1–8) for transparency.
- Android iterates `ExifInterface.TAG_*` static fields via reflection — no hand-maintained list. iOS flattens TIFF / EXIF / GPS dicts with `GPS` prefix on the GPS keys for cross-platform key alignment.

### D3. Drop `AndroidCameraOptions` from the public surface

**Decision.** Delete the `AndroidCameraOptions` type.
It was defined in `src/types.ts` but never exported from `src/index.tsx`, never read by `ScanOptions.kt`, and never used by the consumer.

**Why.** Dead code that implied API guarantees the package wasn't keeping.
The `scannerMode` and `allowGalleryImport` knobs are pinned internally (`SCANNER_MODE_FULL`, `setGalleryImportAllowed(true)` for the camera path) by deliberate decision (see ADR-001 and ADR-005).
Exposing them as user options suggests they are tunable when they aren't.

**Consequences.** None for callers — nobody used the type.
CLAUDE.md is updated to remove the reference.

### D4. `imageOrigin = "unknown"` policy is a consumer concern

**Decision.** The package keeps the honest `"unknown"` bucket for images whose origin the OS cannot attribute. `api-contract.md` documents the trade-off explicitly so consumers don't mis-design their fraud filter.

**Why.** A package-side decision to remap `"unknown"` → `"download"` would silently reject legitimate sideloaded images (cloud-synced files, custom folders, third-party gallery apps).
The right place to make that policy call is the consumer, where the business rules live.

The package documents the contract; the consumer chooses the policy.

### D5. OCR text is a primitive for keyword-based classification

**Decision.** Reaffirm ADR-003: `ocrText` is the raw recognized string, not an authoritative transcription.
The consumer is expected to use it for keyword matching to classify receipts (mart vs. convenience store vs. other), gate obviously bad images before upload, and let Azure OCR handle authoritative transcription server-side.

**Why.** This is the existing intent in ADR-003 ("OCR is a primitive"), but the recent 180°-rotation work added enough sophistication (2-pass + confidence scoring) that it could be misread as a signal that the package now owns receipt-content intelligence.
It does not. The orientation correction exists so keyword matching _can work_; the matching itself stays in the consumer.

**Consequences.** `api-contract.md` "Package Responsibilities" lists "Mart / convenience-store classification (built on `ocrText` keyword matching at the consumer)" under Out of scope. PRs that add store-name parsers to this package should be rejected, per ADR-003.

### D6. OCR floor for non-receipt captures

**Decision.** Add `ScanReceiptOptions.ocrFloor` (default `{ minTextLength: 12, minLines: 2, minConfidence: 0 }`) and a new `ScanReceiptResult.status === "rejected"` branch. The package now gates blank pages, landscape photos, and other non-text captures, per ADR-003's "gate obviously bad images before upload" responsibility.

**Why.** A user-reported case showed a landscape photo (OCR text `"226"`, 3 chars / 1 line) returning `status: "success"` — failing the consumer's fraud filter via `imageOrigin === "unknown"` slip-through. The OCR primitive was already extracting the data needed to detect this at the boundary; not surfacing it was a gap.

**Consequences.** `ReceiptImage` gains `ocrQuality?: { textLength; lineCount; confidence? }`, derived in JS from `ocrText`. Floor evaluation lives in `scan.native.tsx` so threshold changes don't require a native rebuild. Android gets `lineCount` only (ML Kit Korean does not expose per-line confidence). `rejectedImages` is always an array for symmetry with `images` (added 2026-05-09).

### D7. 90° / 180° / 270° auto-rotate

**Decision.** OCR-based rotation detection now covers 90° / 180° / 270° on both platforms, gated by aspect ratio. New option `autoRotate: boolean` (default `true`) bakes the detected rotation into the output JPEG pixels.

**Why.** A gallery import of a CCW-90°-rotated iPhone receipt photo (3530 × 1176, EXIF orientation 1) produced an upright `ocrText` but a landscape JPEG and landscape `width × height` — i.e. the OCR algorithm corrected the text but left pixels alone. The user's preview showed the receipt sideways. The earlier "90° / 270° non-goal" stance was wrong for the gallery path.

**Algorithm** (see `docs/specs/ocr-orientation-correction.md`):

- Aspect-ratio gate: portrait inputs keep the v1.0 0° / 180° fast-path; landscape inputs add 90° / 270° fast probes.
- iOS uses Vision's per-line confidence; Android uses ML Kit Korean's line count (no confidence available).
- The chosen rotation is applied to the output pixels before JPEG encoding (iOS) or via in-place re-encode (Android).
- Output `width` / `height` reflect post-rotation dimensions; `exif.orientation` stays `1`.

**Consequences.** Native ordering changed: OCR now runs _before_ JPEG encoding on iOS so the rotation can be baked in without a re-decode. Android adds a one-time decode + rotate + re-encode after OCR. `RNOcrProcessor.recognizeTextInImage:` is replaced by `recognizeAndDetectRotationInImage:` returning `RNOcrResult { text, rotationDegrees, meanConfidence }`. The cost increases for landscape inputs (~300–450 ms) but is acceptable since landscape inputs are rare and the alternative is wrong output.

### D14. Android content-rotation detection — single-pass aspect mismatch

**Status.** Active (v1.3, 2026-05-10).

**Iteration history.** This decision went through four iterations before landing:

- **v1.0** — augmented the portrait fast-path with a `lineAspect` gate. Misfired in field data: ML Kit Korean tokenizes rotated text into short near-square boxes (lineAspect ≈ 1) instead of the predicted 0.1–0.25.
- **v1.1** — made portrait inputs always probe 90/180/270. Cost ~300 ms on every natural-orientation scan, not justified.
- **v1.2** — deferred. Restored v2.0 fast-path and added always-on diagnostic logs.
- **v1.3 (current)** — see "Decision" below.

**Decision (v1.3).** Drop the multi-pass probe loop entirely on Android. Replace it with a single Pass 0 that runs OCR once, plus a comparison of `imageAspect` (image width/height) against `lineAspect` (mean trimmed line bbox width/height). When an image is landscape but lines are vertical (lineAspect < 0.7), the content is a rotated portrait receipt — apply a default 270° CW rotation.

**Why this works where v1.0–v1.2 didn't.** A Galaxy Z Flip6 second field round captured the decisive evidence: four `InputImage.fromBitmap(bitmap, rotationDegrees)` calls with rotation hints 0/90/180/270 returned **identical** lineCount (34), lineAspect (0.23), and textLength (403). ML Kit Korean is rotation-invariant — multi-pass detection cannot work because each pass returns the same data. The signal we _do_ have is the single-pass `lineAspect`, which cleanly separates upright (4–10) from rotated (0.23 in the rotated case).

**Algorithm.** Full details in `docs/specs/portrait-rotation-detection.md` v1.3. Summary:

```log
pass0 = recognize(0)
if pass0.lineCount < 5 → return 0°  (insufficient signal)
if imageAspect > 1 AND lineAspect < 0.7 → return 270°  (rotated portrait held sideways)
otherwise → return 0°
```

**Consequences.**

- Cost: portrait natural-orientation scans unchanged (single OCR ≈ 150 ms). Previous v2.0 portrait _weak-signal_ probe (180°, +150 ms) and landscape weak-signal probes (90/180/270, +300–450 ms) are gone. Net: every Android scan is now bounded at one OCR pass.
- Default rotation is 270° CW (= 90° CCW). Field data is too small to learn the 90 vs 270 split definitively; subject to revision per `portrait-rotation-detection.md` v1.3.
- iOS is **not** modified. Vision's `VNRecognizeTextRequest` is _not_ rotation-invariant — its confidence signal works as v2.0 designed. Cross-platform algorithm asymmetry is now intentional; tracked in `docs/notes/platform-asymmetries.md`.
- Diagnostic logs (`adb logcat -s ReceiptScanner.Ocr:I`) remain so a wrong default rotation or new edge case is immediately observable.

## Deferred (not addressed in this round)

- **D11. Android output JPEG file-level EXIF — RESOLVED.** Originally `Bitmap.compress(JPEG, …)` wrote a bare JPEG and metadata was surfaced via the JS response only.
  `ImageProcessor.writeExifToFile` now writes the structured tags (make / model / software / dateTimes / GPS, `orientation = NORMAL`) back onto the output file after the final compression, so server-side file readers see EXIF on both platforms.
- **D12. Mirror-flip (EXIF orientation 2/4/5/7).** Pixels are normalized before
  Vision and ML Kit see them, so detection is correct in normal flows;
  there is no test case proving robustness against pathological mirror flips.
  Add manual test fixture if it becomes a real-world issue.
- **D13. Parallel multi-image OCR.** Camera and gallery paths both run OCR sequentially per image.
  ML Kit `TextRecognizer.process()` is thread-safe and could fan out 2–3 images at a time;
  iOS `VNImageRequestHandler` likewise.
  Not the bottleneck for current 1–7 image batches; with autoRotate landscape probes added on top, latency may push this onto the priority list later.

## Related

- ADR-001 — Android ML Kit Document Scanner for the camera path.
- ADR-002 — iOS custom gallery crop editor; iOS 16+ Korean OCR note now reflected here.
- ADR-003 — Package responsibility boundaries; D5 reaffirms.
- ADR-004 — iOS crop editor real-device fixes.
- ADR-005 — Android gallery uses CropEditorActivity, not GMS gallery import.
