# Phase 6 — Receipt Data Quality Hardening

## Goal

Raise receipt-upload quality in the Receipt Scanner app without blocking users on high-false-positive signals such as origin inference.
The core of this phase is to demote `imageOrigin` to an observation-only signal, and to roll out higher-accuracy, more explainable signals first — OCR Floor and restricting the Android camera's gallery-import path.

## Context

The package already returns `ocrText`, `ocrQuality`, `ocrFloor`, `exif`, and `imageOrigin`.

The earlier app-migration design assumed a flow that filtered when `imageOrigin` was `"screenshot"` or `"download"`.
In production this policy produced many false positives, and the app has since moved away from blocking uploads on `imageOrigin` alone.
Phase 6 therefore treats origin classification as quality-analysis telemetry only — not as a security or anti-fraud blocking policy.

`ocrFloor` is also explicitly `false` in the app today.
The option can screen out inputs that don't look like a receipt — blank pages, text-less images, severely blurred images — but it risks false positives on sparse receipts and degraded thermal paper.
So the first rollout goes observe → partial-apply → block, not immediate blocking.

## Research Summary

| Topic                             | Finding                                                                                                                                                                                             | Design implication                                                                                                                                             |
| --------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Android ML Kit Document Scanner   | Google's official ML Kit docs show `GmsDocumentScannerOptions.Builder().setGalleryImportAllowed(false)` as a scanner-config example; it removes the gallery-import affordance inside the camera UI. | Turn off the camera's internal gallery import; route gallery input only through the package's explicit `source: "gallery"` path.                               |
| Android Photo Picker              | The Photo Picker grants access only to user-selected images and offers a multi-select API (`PickMultipleVisualMedia` + selected-URI callbacks).                                                     | Keep the current custom crop path that holds the original `content://` URI — better for EXIF and observation.                                                  |
| Apple Vision OCR                  | `VNRecognizeTextRequest` returns `VNRecognizedTextObservation`; `topCandidates(_:)` returns a confidence-ordered candidate array.                                                                   | iOS can derive non-empty candidate count, mean confidence, and bounding-box signals.                                                                           |
| OCR gate rollout                  | OCR Quality Gating is an operational gate that must combine a golden dataset, Sentry/Amplitude observation, and staged rollout.                                                                     | OCR Floor is staged via feature flag and telemetry, not an immediate full block.                                                                               |
| OCR boundary                      | On-device OCR is a preview/prevalidation helper; receipt validity, amount, duplicate, and fraud decisions belong to the app/server.                                                                 | OCR Floor handles only "obviously bad images" and does not expand into receipt-authenticity judgement.                                                         |
| KORIE receipt benchmark           | KORIE provides Korean retail-receipt OCR/detection/IE labels but cannot substitute for per-device Vision/ML Kit confidence distributions.                                                           | Public corpora help geometry/rotation validation, but OCR Floor thresholds must be calibrated on real-device samples.                                          |
| Apple Vision: language correction | `usesLanguageCorrection` corrects results toward dictionary words; receipt prices, codes, and short tokens aren't in the dictionary, so it over-corrects and distorts them (wiki, Apple docs).      | Receipt OCR should set `usesLanguageCorrection = NO`. `RNOcrProcessor.m` currently sets `YES` — Candidate 4.                                                   |
| Apple Vision: minimumTextHeight   | `minimumTextHeight` defaults to 1/32 of image height; smaller text is excluded (Apple docs), and receipt line items can be small.                                                                   | Lowering it can raise small-line recall at the cost of noise — make it configurable and test on-device. Candidate 5.                                           |
| Android ML Kit: confidence/angle  | Text Recognition v2 exposes `Text.Element.getConfidence()` ([0,1]) and `getAngle()`; available on the bundled Korean recognizer, returning 0 only on the unbundled lib with Play Services < 22.30.  | "Android cannot report confidence" is inaccurate — the capability exists on both platforms. Enforcement use is gated on comparability validation (Decision 2). |
| ML Kit Korean version sensitivity | Rotation invariance was field-validated only on `text-recognition-korean:16.0.0`, but the build is one patch ahead at `16.0.1` (wiki `ml-kit-korean-rotation-invariance`).                          | Add a re-validation gate on version bumps — the Android single-pass algorithm depends on this assumption. Candidate 6.                                         |

External references:

- Google ML Kit Document Scanner on Android:
  https://developers.google.com/ml-kit/vision/doc-scanner/android
- Android Photo Picker:
  https://developer.android.com/training/data-storage/shared/photo-picker
- Apple `VNRecognizeTextRequest`:
  https://developer.apple.com/documentation/vision/vnrecognizetextrequest
- Apple `VNRecognizedTextObservation.topCandidates(_:)`:
  https://developer.apple.com/documentation/vision/vnrecognizedtextobservation/topcandidates%28_%3A%29
- Apple `usesLanguageCorrection`:
  https://developer.apple.com/documentation/vision/vnrecognizetextrequest/useslanguagecorrection
- Apple `minimumTextHeight`:
  https://developer.apple.com/documentation/vision/vnrecognizetextrequest/minimumtextheight
- Google ML Kit `Text.Element` (getConfidence / getAngle):
  https://developers.google.com/android/reference/com/google/mlkit/vision/text/Text.Element
- KORIE paper:
  https://www.mdpi.com/2227-7390/14/1/187

Package references:

- `docs/specs/api-contract.md`
- `docs/notes/adr-003-package-boundaries.md`
- `docs/notes/adr-005-android-gallery-strategy.md`
- `android/src/main/java/com/receiptscanner/ReceiptScannerModule.kt`
- `ios/RNOcrProcessor.m`

## Decision

1. `imageOrigin` is telemetry-only for now.
   - Do not block uploads because `imageOrigin` is `"screenshot"`, `"download"`, or `"unknown"`.
   - Keep recording origin distribution so future classifier work can be evaluated against production data.
   - Do not present `imageOrigin` rejection UX until there is measured precision high enough for user-facing enforcement.

2. OCR Floor becomes the primary near-term client quality gate.
   - Start with telemetry and QA-only rollout.
   - Move to warning or partial reject only after accepted/rejected distributions are visible.
   - Use the package's conservative default as the baseline: `minTextLength: 12`, `minLines: 2`, `minConfidence: 0`.
   - Capability correction: `confidence` is **not** iOS-only — ML Kit Text Recognition v2 exposes `Text.Element.getConfidence()` ([0,1]) on the bundled Korean recognizer (returns 0 only on the unbundled lib with Play Services < 22.30), so Android can also populate `ocrQuality.confidence`; the "iOS only" comments in `src/scan.native.tsx` and `src/types.ts` are inaccurate as a capability claim (follow-up in Candidate 7).
   - Policy (unchanged): do **not** use `confidence` as a cross-platform enforcement signal until cross-device comparability is validated; keep `minConfidence: 0` as the default.

3. Android camera gallery import should be disabled.
   - Change the Android camera path from `setGalleryImportAllowed(true)` to `false`.
   - Keep explicit `source: "gallery"` as the only gallery route.
   - This removes the backdoor path where GMS re-encodes imported images and hides the original `content://` URI / EXIF context.

4. iOS OCR quality routing should move away from confidence comparisons.
   - Near term: count only observations with non-empty `topCandidates(1).first`.
   - Medium term: prefer geometry-first routing using text bounding boxes, matching Android's line-aspect direction.
   - Keep `meanConfidence` as `ocrQuality.confidence` reporting only.

## Candidate Work

### 1. Enable OCR Floor as a staged app rollout

**Why first:** It directly addresses low-quality receipt inputs with signals already returned by the package, and it avoids the known false-positive class from `imageOrigin`.

**Package work:**

- No API change required for MVP.
- Ensure `ocrQuality` remains populated even when `ocrFloor: false`, because the app needs telemetry before enforcement.
- Keep rejected images in `rejectedImages` for partial-reject UX.

**App work:**

- Replace hard-coded `ocrFloor: false` with a remote-config or build-config controlled value.
- Add telemetry fields without raw OCR text:
  - scan source: `camera` / `gallery`
  - platform
  - status: `success` / `cancelled` / `rejected`
  - image count and rejected image count
  - `ocrQuality.textLength` bucket
  - `ocrQuality.lineCount` bucket
  - `ocrQuality.confidence` bucket (iOS today; Android after Candidate 7)
  - `imageOrigin` distribution as observation only
- In partial reject cases, continue with accepted images and show a short notice that blurry or unreadable photos were removed.
- In total reject cases, ask for a clearer retake or clearer gallery photo.

**Acceptance criteria:**

- No upload is blocked by `imageOrigin`.
- OCR Floor can be switched off remotely or by release config.
- Production telemetry can answer: "How many images would OCR Floor reject by source/platform?"
- Raw OCR text is not sent to analytics.

**Verification:**

- Package: `yarn test -- src/__tests__/index.test.tsx`
- App: targeted typecheck/test around `UploadButton` once implemented.
- Manual: camera/galleries on one Android device and one iOS device with normal, blurred, blank, and sparse receipts.

### 2. Disable Android camera's internal gallery import

**Why second:** It is a small package change with low product ambiguity, and it removes a confusing second gallery path while preserving the explicit gallery flow that owns EXIF/origin handling.

**Package work:**

- In `android/src/main/java/com/receiptscanner/ReceiptScannerModule.kt`, set `setGalleryImportAllowed(false)` on the GMS camera scanner options.
- Update `docs/notes/adr-005-android-gallery-strategy.md` to mark the previous "accepted leak" as closed.
- Update `docs/specs/scan-pipeline.md` if it still describes the camera UI import affordance.

**App work:**

- No app code change required if camera and gallery already call `scan({ source })` separately.
- Confirm the UI still exposes a separate gallery button.

**Acceptance criteria:**

- Android camera scanner no longer shows the GMS gallery import affordance.
- Android gallery upload still works through `source: "gallery"`.
- Existing camera multi-page scanning still respects `maxPages`.

**Verification:**

- Package: `yarn test`
- Native manual: `yarn example android`, then verify camera scanner UI and explicit gallery flow.

### 3. Replace iOS confidence-heavy OCR routing with count/geometry signals

**Why third:** The wiki research identifies iOS confidence comparison as the riskiest heuristic; it can improve scan quality, but it is native behavior and needs fixture-backed validation before app rollout.

**Package work:**

- Audit `ios/RNOcrProcessor.m` to count only observations whose first top candidate exists and has non-empty text.
- For near-term routing, prefer count-only gates over confidence-plus-count gates.
- Keep `meanConfidenceFromResults` only for reporting in `ocrQuality.confidence`.
- Add unit-level fixture tests where possible for the JS-visible result shape, and manual iOS scanner validation for native routing behavior.

**Future work:**

- Add geometry-first iOS routing using Vision observation bounding boxes.
- Validate with rotation-augmented receipt samples before turning it into a default path.
- Use KORIE and internal captures for geometry validation, but calibrate threshold values only with on-device Vision output from production-like devices.

**Acceptance criteria:**

- iOS routing no longer compares `.fast` and `.accurate` confidence values as the primary decision.
- Sparse receipts are not more likely to be rotated or rejected than before.
- `ocrQuality.confidence` remains available for diagnostics.

**Verification:**

- Package JS surface: `yarn typecheck && yarn test`
- Native manual: `yarn example ios` with normal, sparse, blurred, and rotated Korean receipts.
- Data check: compare pre/post accepted, rejected, and rotation decisions on a small labeled fixture set.

### 4. Disable iOS Vision language correction

**Why:** `usesLanguageCorrection` corrects recognized text toward dictionary words, but receipt prices, product codes, and short tokens aren't in the dictionary and get distorted.
The wiki `fast-vs-accurate-ocr-modes` and Apple docs agree, and this is a low-risk, high-value one-line fix.
This is a corrected fact from the wiki audit: the current code is set opposite to the requirement.

**Package work:**

- In `ios/RNOcrProcessor.m`'s `runOcrOnCIImage:`, change `request.usesLanguageCorrection` from `YES` to `NO` (currently line 139).
- Apply to both the `.fast` and `.accurate` passes.
- Leave routing logic unchanged — only OCR text-content accuracy is affected.

**Acceptance criteria:**

- Numeric tokens such as prices and business-registration numbers are no longer altered by dictionary correction.
- General Korean receipt text quality does not regress.

**Verification:**

- Package: `yarn typecheck && yarn test`
- Native manual: `yarn example ios`, compare OCR text before and after on number-heavy receipts.

### 5. Make iOS `minimumTextHeight` configurable for small-line recall

**Why:** `minimumTextHeight` defaults to 1/32 of image height, so smaller text is excluded from recognition (Apple docs), and receipt line items can be small enough to drop.
Lowering it raises recall but also noise, so it needs on-device experimentation.

**Package work:**

- Set `request.minimumTextHeight` explicitly in `ios/RNOcrProcessor.m`, initially equal to the current default.
- Keep it as a code constant and expose it via `ScanReceiptOptions` only after the effect is confirmed (default preserved, so behavior is unchanged).

**Acceptance criteria:**

- With the default preserved, behavior is identical to today.
- With a lowered value, it is measurable whether sparse receipts' `ocrQuality.lineCount` / `textLength` increase.

**Verification:**

- Native manual: `yarn example ios`, compare receipts with many small line items at the default vs a lowered value.

### 6. Pin and guard the ML Kit Korean recognizer version

**Why:** Android single-pass rotation routing depends entirely on ML Kit Korean's rotation invariance, which was field-validated only on `text-recognition-korean:16.0.0`.
The build is one patch ahead at `16.0.1`, so there is no regression gate to catch a behavior change (wiki `ml-kit-korean-rotation-invariance`).

**Package work:**

- Document the validated version (16.0.0) and an upgrade constraint as a comment in `android/build.gradle`.
- Re-validate rotation invariance on `16.0.1` on a real device — identical lineCount / lineAspect / textLength at 0 / 90 / 180 / 270.
- Document a regression procedure in `docs/notes/` that runs the rotation-invariance probe on version bumps.

**Acceptance criteria:**

- The validated version is documented in code.
- Rotation invariance is reconfirmed on `16.0.1` (or any future version), or its algorithmic impact is identified if broken.

**Verification:**

- Native manual: rotate one receipt through all four orientations and confirm `OcrProcessor`'s probe logs (lineCount / lineAspect / textLength) are identical.

### 7. Add quantization-aware confidence diagnostics and Android confidence parity

**Why:** Vision confidence effectively quantizes to three values (0.3 / 0.5 / 1.0) on sparse receipts, making it a weak rotation/quality signal (wiki `ios-confidence-threshold-calibration`, plus the fast/accurate tokenization difference).
Calibrating thresholds on real devices (tied to Candidates 1 and 3) needs richer diagnostic signals.
Android can also populate confidence, so the "iOS only" claim on `ocrQuality.confidence` must be corrected.

**Package work:**

- Add debug-only logging in `ios/RNOcrProcessor.m`: per-pass `meanConfidence`, observation count, candidate-spread (top-1 minus top-2 confidence from `topCandidates(2)`), and a ground-truth label during testing.
- Correct the "Android cannot report confidence" comments in `src/scan.native.tsx` and `src/types.ts`, and record a follow-up to populate `ocrQuality.confidence` on Android via `Text.Element.getConfidence()`.
- Leave production routing and blocking unchanged — diagnostics and calibration only; confidence is not used for enforcement until calibrated (consistent with Decision 2).

**Acceptance criteria:**

- Confidence distribution and candidate-spread can be measured per orientation / density / ink-quality bucket on a labeled corpus.
- Production routing and blocking behavior do not change.

**Verification:**

- Package: `yarn test`
- Native manual: collect debug logs on a labeled receipt set and review the distribution.

## Deferred Candidates

### File-level Android EXIF writeback

ADR-005 notes that Android output JPEG files do not carry EXIF after `Bitmap.compress`; EXIF is returned only in the JS payload.
This can matter if a backend validator reads EXIF from uploaded files instead of the multipart `exif_${i}` field.
It is useful but not the first quality win, because the app already sends the metadata payload explicitly.

Defer until the server contract requires file-level EXIF parity.

### `includeRawExif` rollout

Raw EXIF can expose stronger forensic signals, but it increases payload size and privacy surface.
Do not enable it by default.
If used, start with `includeGpsExif: false`, store only allowlisted keys, and avoid using raw tags as a user-facing blocker until precision is measured.

### `imageOrigin` enforcement

Do not re-enable `imageOrigin` filtering in this phase.
A future version can revisit it only after a measured classifier combines origin, EXIF, OCR, server validation, and user-outcome labels with acceptable false-positive rates.

## Rollout Plan

1. Land Android camera import hardening in the package and release it.
2. Bump the app to the new package version without changing upload policy.
3. Add OCR Floor telemetry in the app while keeping enforcement off.
4. Run QA and limited production observation for at least one release window.
5. Enable OCR Floor for a small cohort or internal users.
6. Expand only if total rejects and support complaints stay within the agreed threshold.
7. Separately evaluate iOS count/geometry routing with fixtures before making it a default behavior change.

## Risks

- OCR Floor can reject legitimate sparse receipts if the threshold is tightened too early.
- iOS `confidence` is not a stable cross-platform signal and should not be used as the main enforcement threshold.
- Android users may notice the removed import button inside the camera scanner, but the app's separate gallery entry point remains the intended path.
- Public datasets cannot fully model production camera hardware, thermal degradation, lighting, and Korean retail layout variance.

## Success Metrics

- Lower server-side first-validation failure rate for blank, unreadable, or non-receipt images.
- No measurable increase in user complaints caused by client-side false positives.
- OCR Floor reject rate is explainable by source/platform/quality buckets.
- Android gallery-origin ambiguity through the GMS camera path disappears.
- `imageOrigin` remains available for analysis but does not block users.
