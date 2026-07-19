# iOS Geometry-First Rotation Routing — Design (Data-Gated)

**Spec version:** 0.1 (design proposal — not yet implemented)
**Created:** 2026-06-10
**Parent decision:** ADR-006 D7 (iOS confidence multi-pass), phase-6 Candidate 3 "future work"
**Related:** [`portrait-rotation-detection.md`](./portrait-rotation-detection.md) (Android v1.3), [`../notes/platform-asymmetries.md`](../notes/platform-asymmetries.md) §2.1
**Platform:** iOS only

## Status: Closed (2026-07-18) — superseded by a stronger signal

This design's step-1/step-2 structure was adopted, but its **signal was replaced**.
It proposed measuring the _bounding-box aspect_ — a proxy for line shape.
[`ocr-angle-rotation-detection.md`](./ocr-angle-rotation-detection.md) v1.0 instead reads the _text angle_ off the observation quad, which strictly dominates: aspect is identical at 90° and 270° (shape carries no direction), while the angle separates them and detects a plain 180° flip as well.

Two of this document's conclusions survived and were carried into the replacement:

- **"Match Android" is not a design goal** — correct, and the replacement does not port Android's thresholds. What it _does_ share is a platform-independent quantity (the angle), not an algorithm tuned to one engine's behaviour.
- **Thresholds cannot be guessed** — carried over verbatim; the new gates are marked PROVISIONAL and gated on the same on-device measurement this document called for.

The step-1 `boxAspect` diagnostic shipped here is **kept, as diagnostics only**. No routing code reads it. It sits next to `angleBins` in the same DEBUG log line, so a reader comparing the two can tell whether the angle signal is alive — an `[n,0,0,0]` histogram beside a `boxAspect` that says "sideways" is the fingerprint of the angle being reported in Vision's own reading frame. That is a human calibration aid, not a branch.

The regression guard in the replacement spec is a different mechanism and does **not** consult `boxAspect`: on iOS it simply never acts on `turn == 0`, and the only guard that reads an aspect signal is Android's, which uses `lineAspect`.

The original proposal follows as historical record.

## Original status: Proposed — blocked on on-device data

This document does **not** propose shippable routing logic yet.
The core hypothesis it rests on cannot be validated in CI or from source inspection — it needs Vision bounding-box geometry measured on real Korean receipts.
The only code change this design ships now is a **diagnostics extension** (step 1), which is reporting-only and produces exactly the distribution needed to decide whether the routing change is viable at all.

## The constraint that overrides the phase-6 framing

phase-6 Candidate 3 lists, under "future work":

> Add geometry-first iOS routing using Vision observation bounding boxes, **matching Android's line-aspect direction**.

That phrasing is aspirational, and the project's own cross-cutting reference contradicts it.
`platform-asymmetries.md` §2.1 records, from 2026-05-10 Galaxy Z Flip6 field data:

| Platform                              | Rotation behavior                                                                            |
| ------------------------------------- | -------------------------------------------------------------------------------------------- |
| iOS Vision (`VNRecognizeTextRequest`) | recognition **varies** with input pixel rotation → multi-pass probing is meaningful          |
| Android ML Kit Korean                 | **rotation-invariant** — identical `lineCount` / `lineAspect` / `textLength` at 0/90/180/270 |

Android's single-pass geometry routing (v1.3) works **because** ML Kit is rotation-invariant: it reads sideways text correctly and returns bounding boxes that already reflect the rotated layout (a rotated portrait receipt yields `lineAspect ≈ 0.23`).
The geometry signal is reliable on Android precisely because OCR does not degrade on rotated input.

On iOS the premise is inverted.
Vision is rotation-_variant_; that is the entire reason the current code probes multiple rotations.
"Match Android" is therefore **not a design goal** — it is a phrasing the platform evidence rules out.
This contradiction is the center of this design, not a footnote: a literal port of Android's algorithm (one 0° pass, geometry decides, no probes) is the reading most likely to regress on-device, because a single 0° pass on iOS cannot recover the geometry of text it failed to read while sideways.

## What geometry-first would actually buy on iOS

"Matching Android" is not a user value. The two candidate values are:

1. **Latency** — eliminate some of the up-to-four probe OCR passes the landscape path runs today (`RNOcrProcessor.m`, `probeDegrees = @[@90, @180, @270]`).
2. **Accuracy** — disambiguate 90° vs 270° better than the count-only signal can (count tells you _that_ a rotation reads more lines, not _which way_ the receipt is oriented).

Latency (1) is the value the asymmetry undercuts: iOS needs multiple passes _because_ it is not rotation-invariant, so geometry from a single 0° pass cannot safely replace probing.
The honest role for geometry on iOS is therefore **a disambiguation / probe-reduction signal layered on the existing count routing — not a single-pass replacement.**

Concretely, geometry could:

- **Prune the landscape probe set.** If the 0° bounding-box aspect of a landscape image indicates vertical lines, probe `90°` and `270°` (the two ways a portrait receipt lies sideways) and skip `180°`. This keeps probing but cuts one pass.
- **Break the count tie.** When `90°` and `270°` probes return similar line counts, the 0° box geometry (which edge the lines run along) can pick the direction count alone cannot.

What it must **not** do in v1: replace the count-based commit gate, or introduce a confidence comparison. Count-only routing stays the spine.

## The unvalidated hypothesis

The design rests on one claim that is currently a hypothesis, not a fact:

> Vision's `VNRecognizedTextObservation.boundingBox` aspect gives a usable rotation signal for **rotated** Korean receipts, the way ML Kit's `Line.boundingBox` does on Android.

What is actually known (`platform-asymmetries.md` §2.1) is only that _confidence_ varies with rotation — nothing is recorded about box **geometry** for sideways text.
The plausible failure mode: Vision returns few or garbled observations for sideways Korean text, so the boxes it does return at 0° on a rotated receipt are too sparse or too noisy to yield a stable aspect.
If that is what the data shows, geometry-first is not viable on iOS and this design is abandoned — the count-only routing already in place remains the answer.

Thresholds cannot be guessed either.
Android's `LINE_HORIZONTAL_THRESHOLD = 1.5`, `LINE_VERTICAL_THRESHOLD = 0.7`, and the observed `0.23` rotated value all came from Galaxy Z Flip6 field captures.
Vision's `boundingBox` is normalized `[0, 1]` with a bottom-left origin; its aspect distribution for upright vs. rotated Korean receipts is simply not in our possession and must be measured before any threshold is written.

## Step 1 (shippable now): diagnostics extension

Mirror Android's `lineAspectOf` as an iOS DEBUG-only measurement, layered into the existing `logDiagnostics:` (already `#if DEBUG`).
For each pass already logged, also emit the **trimmed-mean bounding-box aspect** of the observations.

- `VNRecognizedTextObservation.boundingBox` is normalized; `aspect = width / height` is scale-free, so no pixel conversion is needed.
- Reporting-only: it must not touch the routing decision path. Same discipline — it is computed inside the `#if DEBUG` block and never read by `recognizeAndDetectRotationInImage:`.
- Log it next to the existing `count` / `meanConf` / `candidateSpread` line so each probe pass already in the loop gains an `boxAspect` field.

This ships in the next QA build, alongside the native changes already queued for device QA, and produces the iOS counterpart of the Android v1.3 distribution table:

```log
[ReceiptScanner.Ocr] pass1 0deg accurate count=34 meanConf=0.62 candidateSpread=0.18 boxAspect=0.24
[ReceiptScanner.Ocr] probe 90deg fast count=41 meanConf=0.71 candidateSpread=0.21 boxAspect=4.90
```

If, across a labeled set, upright receipts cluster at high `boxAspect` and rotated receipts at low `boxAspect` (as on Android), the hypothesis holds and step 2 proceeds.
If the rotated cluster is indistinguishable from the upright cluster (Vision failed to read sideways text), the design stops here.

## Step 2 (gated on step-1 data): geometry-assisted routing

Only attempted if step-1 data confirms a separable distribution.
Sketch, to be made concrete with measured thresholds:

1. Run the existing 0° `.accurate` pass; compute `boxAspect0` alongside `count0`.
2. Landscape path: if `boxAspect0` indicates vertical lines, probe `{90, 270}` only (skip `180`). Otherwise keep the current `{90, 180, 270}`.
3. Commit gate (count ratio `kRotateCommitRatio`); geometry only **selects which rotations to probe** and **breaks 90-vs-270 count ties**.
4. Thresholds (`iOS boxAspect` horizontal/vertical bands) are constants calibrated **only** from on-device Vision output — never ported from Android's values.

Acceptance criteria for step 2 (unchanged in spirit from Candidate 3):

- Routing never compares `.fast`/`.accurate` confidence as a primary decision.
- Sparse receipts are no more likely to be rotated or rejected than under count-only routing.
- `ocrQuality.confidence` remains available for diagnostics.
- Measured latency drops (fewer probe passes) without an accuracy regression on the labeled set.

## Validation plan

- **Corpus:** KORIE and internal captures for separability analysis; on-device Vision output from production-like devices for any threshold value.
- **Method:** the same path that produced Android v1.3 — collect step-1 diagnostics across normal / sparse / blurred / rotated Korean receipts, plot `boxAspect` vs. ground-truth orientation, decide go/no-go, then calibrate.
- **Regression check:** compare pre/post accepted, rejected, and rotation decisions on the labeled fixture set before making geometry routing a default path.

## Verification

- Package JS surface (unaffected by step 1): `yarn typecheck && yarn test`
- Native lint: `trunk check` (no Obj-C compiler in CI — device build is QA)
- Manual: `yarn example ios` with normal, sparse, blurred, and rotated Korean receipts; collect `adb`-equivalent device console logs for `boxAspect`.

## Decision log

- **2026-06-10** — Design opened. Established that "match Android" is ruled out by §2.1; reframed geometry as a disambiguation/probe-reduction layer over count-only routing; scoped step 1 (diagnostics) as the only shippable change and gated step 2 on its data.
