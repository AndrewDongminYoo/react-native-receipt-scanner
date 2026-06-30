# Quad Distortion Backstop — Crop Geometry Sanity Guard

**Spec version:** 0.1 (design; thresholds provisional, data-gated)
**Created:** 2026-06-22
**Status:** Design approved; implementation pending. Thresholds PROVISIONAL.
**Related:** [`adr-002-ios-gallery-crop.md`](../notes/adr-002-ios-gallery-crop.md), [`adr-005-android-gallery-strategy.md`](../notes/adr-005-android-gallery-strategy.md), [`threshold-calibration.md`](./threshold-calibration.md), [`platform-asymmetries.md`](../notes/platform-asymmetries.md)

## What this document is, and is not

This is a **backstop**, not a classifier.

Geometry alone **cannot** distinguish a legitimate perspective trapezoid (a receipt photographed at an angle — the use case the warp exists to serve) from a pathological one (a UI element such as a close button caught as a corner).
Both can produce the same edge ratios; intent is not encoded in the coordinates.
The guard therefore only rejects the **egregious** tail, and the root improvement remains in detection — not seeding UI chrome as a corner in the first place.
On iOS, the detection-confidence floor (`kDetectionMinConfidence`, `RNGalleryPickerDelegate.m`) already removes the low-confidence noise case; this guard is complementary, catching degenerate/over-distorted **shapes** that confidence does not gate.

This document is **not** a final set of threshold values.
The values below are fitted to a 10-image sample that is **mostly normal receipts** and contains **no captured instance** of the target failure (a high-confidence pathological trapezoid).
They are deliberately permissive (reject only well past the observed-normal envelope) and stay PROVISIONAL until a labeled distortion corpus justifies a change, per the calibration culture in [`threshold-calibration.md`](./threshold-calibration.md).

## Problem

The gallery flow estimates a document quad, then warps it to a rectangle — `CIPerspectiveCorrection` (iOS, `RNImageProcessor.m`) / `Matrix.setPolyToPoly` (Android, `ImageProcessor.kt`).
When the estimated or user-confirmed quad is a strong trapezoid, the warp stretches one side relative to the other and the receipt comes out visibly distorted (the thread's "정상 이미지를 찌그러뜨림").
Two triggers:

1. **Detection** seeds a bad quad (Android `quadFromTextBlocks` can grab a non-receipt text region; iOS doc-seg can return a degenerate quad).
2. **User** confirms a bad quad — misunderstood the 4-corner UX, or dragged a corner wrong.

## Mechanism & placement

The guard is a pure geometric predicate `isQuadDistorted(corners) → bool` plus a degenerate check, wired at two points:

| Point                 | Site (iOS)                                              | Site (Android)                              | Action when distorted                                                                                                      |
| --------------------- | ------------------------------------------------------- | ------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------- |
| **Warp chokepoint**   | `RNImageProcessor perspectiveCorrectedCGImage:corners:` | `ImageProcessor.perspectiveCorrectedBitmap` | Skip the warp; crop the **axis-aligned bounding box** of the corners (clamped to image bounds). No reshape, no distortion. |
| **Detection seeding** | return of `detectCornersForImage:`                      | return of `quadFromTextBlocks`              | Discard the detected quad → editor uses its **10% inset default**.                                                         |

The warp chokepoint is the single funnel for **both** the editor-confirm path and the `cropAutoConfirm` auto-apply path (`applyCropAndFinishImage`, which bypasses the editor). Wiring the guard there — not at confirm — closes the auto-confirm gap. Verified call sites: iOS `RNCropEditorViewController.m:309` and `RNGalleryPickerDelegate.m:308` both route through `perspectiveCorrectedCGImage:`; Android routes through `processGallery` → `perspectiveCorrectedBitmap`.

Seeding-stage rejection composes with the existing iOS confidence floor: low-confidence → already nil; high-confidence-but-degenerate → newly nil.

## Metric (lean; data-validated)

Computed on the four corners in image-pixel space (`tl, tr, br, bl`), all scale-invariant:

1. **Opposite-edge ratio** (primary — the thread's "네 변의 길이 비율"):
   - `widthRatio  = max(topW, botW) / min(topW, botW)`
   - `heightRatio = max(leftH, rightH) / min(leftH, rightH)`
   - Distorted if either exceeds `MAX_EDGE_RATIO`.
2. **Degeneracy guard** (cheap sanity):
   - Non-convex (cross-product sign not consistent around `tl→tr→br→bl`), **or**
   - all four edges zero-length (coincident corners).

**Rejected metric:** "shortest-edge / longest-edge as a distortion signal." The sample shows normal tall receipts sit at `minEdgeFrac` 0.22–0.43 — the ratio conflates a receipt's natural aspect ratio with distortion. It was initially kept as a near-zero degeneracy gate (`< 0.05`), but later **dropped entirely**: a collapsed corner is already rejected by the convexity check and the opposite-edge-ratio check (a zero-length edge makes its opposite-pair ratio diverge past `MAX_EDGE_RATIO`), so the gate caught nothing unique — its one distinct effect was falsely flagging legitimate very-long receipts (aspect ratio above ~20:1, i.e. `minEdgeFrac < 0.05`) as distorted and stripping their perspective correction. Adding bbox-fill or interior-angle checks is deferred until data shows a case the two checks above miss (every extra check multiplies false-positive surface).

## Provisional thresholds

| Constant         | Provisional value | Basis                                                                                                              | Unblock condition                                                                                                                                                     |
| ---------------- | ----------------- | ------------------------------------------------------------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `MAX_EDGE_RATIO` | `2.2`             | ~1.6× the max observed on normal receipts (`widthRatio` ≤ 1.35). Leaves headroom for legitimately angled receipts. | A labeled set separates pathological-trapezoid `widthRatio`/`heightRatio` from legitimate-angle ones; pick the value where FP (legit warps skipped) is within budget. |

`MAX_EDGE_RATIO` lives as a named constant in each platform's source and **must stay in sync** across iOS/Android (recorded in `platform-asymmetries.md`). (`MIN_EDGE_FRACTION` was removed — see the Rejected-metric note above.)

## Empirical data (calibration harness, 10 samples)

Vision detection run mirroring the app (`initWithCGImage:orientation:`, doc-seg + rect, confidence floor 0.5), metric computed on the chosen quad. The 10-image corpus was a private one-time calibration set (real receipts contain PII) and is **not committed** to the repo; only the derived measurements are recorded here:

| Sample | Kind           | Detected quad     | wRatio | hRatio | minEdgeFrac | convex     |
| ------ | -------------- | ----------------- | ------ | ------ | ----------- | ---------- |
| S1     | normal receipt | doc 0.94          | 1.28   | 1.10   | 0.22        | Y          |
| S2     | normal receipt | doc 0.99          | 1.25   | 1.03   | 0.41        | Y          |
| S3     | normal receipt | doc 0.93          | 1.35   | 1.02   | 0.34        | Y          |
| S4     | normal receipt | doc 0.99          | 1.13   | 1.01   | 0.43        | Y          |
| S5     | normal receipt | doc 0.97          | 1.05   | 1.01   | 0.36        | Y          |
| S6     | normal receipt | doc 0.98          | 1.12   | 1.01   | 0.42        | Y          |
| S7     | normal receipt | doc 0.99          | 1.00   | 1.02   | 0.41        | Y          |
| S8     | normal receipt | doc 0.99          | 1.01   | 1.01   | 0.42        | Y          |
| S9     | UI screenshot  | none ≥ 0.5        | —      | —      | —           | —          |
| S10    | angled receipt | doc 0.497 (BR≡BL) | —      | —      | ≈0.0        | degenerate |

Key findings:

- Normal-receipt envelope: `widthRatio` ≤ 1.35, `heightRatio` ≤ 1.10, all convex. `MAX_EDGE_RATIO = 2.2` clears all eight with margin.
- The angled-receipt sample (S10): doc-seg returned a **degenerate** quad (bottom-right and bottom-left corners coincide) at confidence 0.497 — caught today only because it sits just under the floor. The degeneracy guard catches it on **shape**, independent of confidence.
- The screenshot (S9) and angled-receipt (S10) samples already fall back to the inset default → manual adjust. Acceptable; improving detection on these is out of scope (see below).
- The sample contains **no** high-confidence pathological trapezoid, so `MAX_EDGE_RATIO` is set as a conservative envelope, not a fitted separator. PROVISIONAL.

## Platform asymmetry

Detection sources differ (iOS Vision doc-seg/rectangle; Android ML Kit text-block corners), so the **nature** of distortion differs even though the guard acts on the final quad:

- **iOS** doc-seg quads in the sample are clean (convex, low ratio); the guard mostly defends the confirm/auto-confirm path.
- **Android** `quadFromTextBlocks` (sector-furthest-point) can emit non-convex or skewed quads from stray text — the convexity check earns its keep there.

To record in `platform-asymmetries.md`.

## False-positive risk & graceful degradation

A steeply-angled receipt the user correctly framed could exceed `MAX_EDGE_RATIO` and have its (wanted) warp skipped. This **fails soft**: the fallback is an axis-aligned bounding-box crop — undistorted, with some extra background — never a worse distorted warp. This is exactly the behavior chosen in design ("왜곡 시 wrap 생략"), so a false positive degrades to "slightly loose crop," not "broken image."

## Out of scope

- **Better detection** (not catching UI chrome; segmenting low-confidence/angled receipts). Tracked separately; partly addressed by the iOS confidence floor and 라미's planned detection-level tuning.
- **Multi-page detection consistency** (테오: 2nd/3rd image in a batch detected worse than the 1st).
- **The "don't reshape at all" product question** (네이버/리멤버 keep the image, detect text only). A larger product decision, not this guard.
- Any receipt **domain** logic (ADR-003 boundary).

## Implementation outline

- **iOS** — add `RNQuadGeometry` helper (or static functions in `RNImageProcessor`) exposing `isQuadDistorted`/`isQuadDegenerate` + axis-aligned bbox crop. Guard inside `perspectiveCorrectedCGImage:corners:` before building the `CIPerspectiveCorrection` filter; bbox path uses `CGImageCreateWithImageInRect` on the orientation-baked image. Guard at `detectCornersForImage:` return → nil.
- **Android** — add a `QuadGeometry` object (Kotlin) with the same predicate + constants; guard inside `perspectiveCorrectedBitmap` (it already computes `topW/botW/leftH/rightH`); bbox path uses `Bitmap.createBitmap(src, l, t, w, h)` clamped. Guard at `quadFromTextBlocks` return → null (→ `applyDefaultInsetCorners`).

## Test plan

1. **Regression (harness):** re-run the 10-sample metric harness; assert no NORMAL receipt (the 8) is classified DISTORTED at the chosen thresholds.
2. **Unit (predicate):** table-driven cases — perfect rectangle (pass), mild trapezoid wRatio 1.4 (pass), egregious trapezoid wRatio 3.0 (distorted), collapsed corner (degenerate), non-convex quad (degenerate).
3. **Manual:** gallery-pick a normal receipt (warp unchanged), an angled receipt (warp still applied), and an image that yields a bad drag (bbox crop, not distorted warp).
