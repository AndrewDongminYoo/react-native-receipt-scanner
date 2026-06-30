# OCR Threshold Calibration — Methodology & Harness Spec

**Spec version:** 0.1 (methodology — no thresholds changed)
**Created:** 2026-06-10
**Status:** Active procedure; **execution is data-gated** (needs on-device logs / labeled corpus)
**Related:** [`portrait-rotation-detection.md`](./portrait-rotation-detection.md) (Android v1.3 semantics), [`ios-geometry-rotation-routing.md`](./ios-geometry-rotation-routing.md) (iOS boxAspect), [`ocr-orientation-correction.md`](./ocr-orientation-correction.md)
**Wiki:** `concepts/corpus-calibration-workflow.md`, `concepts/ios-quality-gate-calibration-gap.md`, `concepts/rotation-augmented-corpus-validation.md`

## What this document is, and is not

Every routing and quality-gate threshold this package ships is a **provisional heuristic**.
None has been fitted to a labeled Korean-receipt corpus or to real on-device OCR output.
The wiki names this the _iOS quality gate calibration gap_ and states plainly that closing it "requires measurement infrastructure, not just code changes," and that the gap concept "names the problem but not the procedure."

This document **is** that procedure, instantiated for this codebase:
a per-constant map of what each threshold gates, which diagnostic log field calibrates it, and the condition that unblocks changing it.

This document is **not** a set of new threshold values.
No constant changes here. Every value below stays PROVISIONAL until device data justifies a change, at which point the change lands in the owning source file with the fitted value and a reference to the capture that produced it.

The one piece deliberately **deferred** is the log-analysis script (see [Deferred: analyzer](#deferred-analyzer)):
it cannot be written correctly until the first real device capture reveals the actual log line shape (console prefixes, timestamps, interleaving, device noise).
Writing it against the hand-typed sample lines in these specs would test assumptions, not data.

## Threshold inventory

Each row is one calibration target.
"Calibrating field" is the diagnostic log field whose distribution determines the value; all fields are already emitted by the DEBUG diagnostics (iOS `logDiagnostics:`, Android `logProbe`/`logDecision`).

### iOS — `ios/RNOcrProcessor.m`

| Constant                      | Loc   | Current (provisional) | Gates                                                        | Calibrating field                                 | Unblock condition                                                                                       |
| ----------------------------- | ----- | --------------------- | ------------------------------------------------------------ | ------------------------------------------------- | ------------------------------------------------------------------------------------------------------- |
| `kMinLinesToJudgeOrientation` | `:14` | `3`                   | Below this `count`, trust 0° (too little signal)             | `count` at 0° vs ground-truth orientation         | FN rate for rotated receipts with `count` in [3, kUprightLineCount) is acceptable on held-out set       |
| `kUprightLineCount`           | `:15` | `8`                   | At/above this `count`, skip probing (assume upright)         | `count` at 0° for known-upright vs known-rotated  | upright `count` P5 clearly above rotated `count` P95                                                    |
| `kRotateCommitRatio`          | `:16` | `1.3`                 | Probe must find ≥ ratio × 0° lines to commit a rotation      | probe `count` / 0° `count` ratio, by ground-truth | ratio cleanly separates true-rotation from noise on held-out set                                        |
| `kReceiptMinTextHeight`       | `:10` | `1/32`                | Vision drops text smaller than this fraction of image height | `count` / `textLength` delta at lowered values    | sparse-receipt recall gain outweighs noise at the chosen value                                          |
| iOS `boxAspect` bands         | (TBD) | not yet defined       | Geometry-assisted routing                                    | `boxAspect` by ground-truth orientation           | upright vs rotated `boxAspect` distributions separable — **gates the entire geometry routing decision** |

### iOS / Android — gallery crop detection & geometry

These gate the gallery crop seeding/warp, not OCR. The geometry constants are shared
(must stay identical across `ios/RNQuadGeometry.m` and `com.receiptscanner.QuadGeometry`);
see [`quad-distortion-backstop.md`](./quad-distortion-backstop.md).

| Constant                  | Loc                                    | Current (provisional) | Gates                                                                 | Calibrating field                                                         | Unblock condition                                                                           |
| ------------------------- | -------------------------------------- | --------------------- | --------------------------------------------------------------------- | ------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------- |
| `kDetectionMinConfidence` | `ios/RNGalleryPickerDelegate.m`        | `0.1`                 | doc-seg/rect quad below this is discarded → editor uses inset default | DEBUG `detect confidence doc=/rect=` log, by ground-truth usable vs noise | floor sits clearly above the noise band (≈0.004 observed) and below the real-detection band |
| `MAX_EDGE_RATIO`          | `RNQuadGeometry.m` / `QuadGeometry.kt` | `2.2`                 | opposite-edge ratio above ⇒ distorted ⇒ bbox crop (no warp)           | `wRatio`/`hRatio` for ground-truth legit-angle vs pathological trapezoid  | distributions separable; FP (legit warps skipped) within budget on a labeled set            |

### Android — `android/src/main/java/com/receiptscanner/OcrProcessor.kt`

Semantics live in [`portrait-rotation-detection.md`](./portrait-rotation-detection.md) §임계값; this table only maps each to its calibrating field so all targets sit in one index.

| Constant                    | Loc    | Current (provisional) | Gates                                             | Calibrating field                                 | Unblock condition                                                    |
| --------------------------- | ------ | --------------------- | ------------------------------------------------- | ------------------------------------------------- | -------------------------------------------------------------------- |
| `LINE_HORIZONTAL_THRESHOLD` | `:279` | `1.5f`                | `lineAspect` above ⇒ lines horizontal (upright)   | `lineAspect` for known-upright                    | upright `lineAspect` P5 well above 1.5                               |
| `LINE_VERTICAL_THRESHOLD`   | `:282` | `0.7f`                | `lineAspect` below ⇒ lines vertical (rotated)     | `lineAspect` for known-rotated                    | rotated `lineAspect` P95 well below 0.7                              |
| `MISMATCH_MIN_LINES`        | `:285` | `5`                   | Min lines for the `lineAspect` mean to be trusted | `lineCount` vs `lineAspect` variance              | `lineAspect` stable above this line count                            |
| `ROTATED_DEFAULT_DEGREES`   | `:295` | `270`                 | Direction applied on aspect-mismatch (90 vs 270)  | ground-truth rotation direction of field captures | direction correct on a multi-sample rotated set (currently 1 sample) |
| `TRIM_RATIO`                | `:298` | `0.10f`               | Trim fraction for the `lineAspect` trimmed mean   | `lineAspect` sensitivity to trim                  | mean robust to outlier boxes; low priority                           |

### OCR Floor — `src/types.ts` `DEFAULT_OCR_FLOOR` (`:323`)

| Constant        | Loc    | Current (provisional) | Gates                                | Calibrating field                                      | Unblock condition                                                                    |
| --------------- | ------ | --------------------- | ------------------------------------ | ------------------------------------------------------ | ------------------------------------------------------------------------------------ |
| `minTextLength` | `:324` | `12`                  | Reject below this joined text length | `ocrQuality.textLength` for accept/reject ground-truth | FP (legit sparse rejected) within agreed budget on held-out set                      |
| `minLines`      | `:325` | `2`                   | Reject below this line count         | `ocrQuality.lineCount` for accept/reject ground-truth  | same FP budget                                                                       |
| `minConfidence` | `:326` | `0` (off)             | Reject below this mean confidence    | `ocrQuality.confidence` per platform                   | **kept at 0 until cross-platform comparability is validated** — not a fit target yet |

## Two calibration tracks

The targets split by whether they need real on-device confidence distributions.

### Track A — geometry / orientation (engine-agnostic, partially unblockable now)

Covers: iOS `boxAspect` bands, Android `LINE_*_THRESHOLD` / `MISMATCH_MIN_LINES` / `ROTATED_DEFAULT_DEGREES`, and the count-ratio gates.

Method: **rotation-augmented corpus validation**.
Because the metric is geometric (aspect ratio), it is not subject to on-device-vs-research-model divergence or cross-level confidence non-comparability, so it can run against any OCR engine and even public corpora.

1. Take a labeled corpus of upright Korean receipts (KORIE; AIHub Korean receipt set as a supplement).
2. Apply synthetic rotations 0/90/180/270, recording the applied rotation as ground truth.
3. Run each rotated image through the scanner DEBUG build; collect the per-pass diagnostic line.
4. Plot the geometry field (`boxAspect` / `lineAspect`) against ground-truth orientation; pick bands that minimise FP/FN; validate on a held-out partition.

Caveat: synthetic rotation validates _orientation classification_ but does not reproduce _thermal degradation severity_ or device-specific behavior — those still need real captures.

### Track B — confidence / count / floor (needs production-hardware captures)

Covers: OCR Floor `minTextLength` / `minLines` / (eventually) `minConfidence`, `kUprightLineCount`, `kReceiptMinTextHeight`.

Method: the full **four-step corpus calibration workflow**.

1. Assemble a labeled corpus combining public Korean receipts **and thermal-degraded captures from target production hardware** (Galaxy Z Flip6 class for Android; an equivalent iOS device). The production-hardware component is non-optional — public corpora lack device confidence distributions and degradation-severity labels.
2. Run both `.fast` and `.accurate` passes on device.
3. Record `meanConfidence`, `count`, `boxAspect`, `lineAspect`, `textLength`, `rotationDegrees` per image per pass. **Keep `.fast` and `.accurate` separate** — they are not on a comparable scale.
4. Fit thresholds at real P5/P95 distributions against ground-truth labels; validate on a held-out partition.

## Corpus & label schema

One row per corpus image; the label file is the join key for the collected logs.

```log
image_id        unique id, also used as the log `file=`/label token
source          camera | gallery
platform        ios | android
device          model string (e.g. iPhone15,3 / SM-F741N)
gt_orientation  0 | 90 | 180 | 270   (applied/synthetic or hand-labeled)
gt_quality      accept | reject       (human judgment for floor calibration)
density         dense | sparse
ink             good | faded          (thermal degradation severity)
```

Store the corpus and labels outside the repo (it contains receipt images / PII); reference its location in the calibration run notes, not in git.

## Collection commands

Run a DEBUG build so `logDiagnostics:` / `logProbe` emit.

```bash
# iOS — filter the Xcode device console / log stream
log stream --predicate 'eventMessage CONTAINS "[ReceiptScanner.Ocr]"' --style compact

# Android — logcat tag filter
adb logcat -s ReceiptScanner.Ocr:I
```

Current diagnostic line shapes (the analyzer will parse these once their real form is confirmed on device):

```log
# iOS  (ios/RNOcrProcessor.m logDiagnostics:)
[ReceiptScanner.Ocr] pass1 0deg accurate count=34 meanConf=0.620 candidateSpread=0.180 boxAspect=0.240

# Android  (OcrProcessor.kt logProbe / logDecision)
I/ReceiptScanner.Ocr: probe deg=0 file=…jpg lineCount=47 lineAspect=4.82 textLength=600 imageAspect=0.373
I/ReceiptScanner.Ocr: decision file=…jpg chosen=0 reason=aspect-matched lineCount=47 lineAspect=4.82 textLength=600
```

## Fit method & go/no-go

For each target:

- **Separable → fit.** If the calibrating field's distribution for the two ground-truth classes separates (upright vs rotated, accept vs reject), set the threshold at the crossover that meets the FP/FN budget, then confirm on a held-out partition.
- **Not separable → do not change.** Keep the provisional value and record that the field is not a usable signal for that target (this is itself a result — e.g. it would retire the iOS geometry routing).
- **Direction-only targets** (`ROTATED_DEFAULT_DEGREES`): need ≥ several rotated samples with known physical direction before changing the current single-sample default.

Agreed FP/FN budgets are set with the app team before any floor tightening (consistent with the staged OCR Floor rollout, Candidate 1).

## Deferred: analyzer

A log-parsing + distribution/threshold-recommendation script is **intentionally not built yet**.
It is gated on the first real device capture, which will reveal the actual log line shape.
When that lands: add `tools/calibration/` (Node ESM, matching the repo's `.mjs` tooling) that ingests the label file + collected logs and emits per-bucket P5/P50/P95 and a recommended value per target.
Estimated ~½ day once a real capture exists; building it before that is premature infrastructure.

## Decision log

- **2026-06-10** — Spec opened. Instantiated the wiki calibration-gap procedure as a per-constant inventory tied to `file:line` and calibrating log fields. Split targets into Track A (geometry, engine-agnostic) and Track B (confidence/count, production-hardware). Deferred the analyzer until a real device capture exists. No threshold values changed.
