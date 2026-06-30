# Quad Distortion Backstop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Skip perspective warp (→ axis-aligned bounding-box crop) when the crop quad is an egregious trapezoid or degenerate, and discard such quads at detection seeding (→ inset default), on both iOS and Android.

**Architecture:** A pure geometric predicate `isDistorted(corners)` per platform, wired at two points: the warp chokepoint (covers editor-confirm + `cropAutoConfirm` auto-apply) and the detection-seeding return (composes with the existing iOS confidence floor). Distorted → bbox crop / inset, never a distorted warp.

**Tech Stack:** Objective-C + Vision/CoreImage (iOS), Kotlin + Android graphics (Android), JUnit 4 (Android unit tests), Swift harness (iOS verification).

**Spec:** `docs/specs/quad-distortion-backstop.md`

## Global Constraints

- Thresholds are PROVISIONAL: `MAX_EDGE_RATIO = 2.2`, `MIN_EDGE_FRACTION = 0.05`. Same values on both platforms; if one changes, change both and note in `platform-asymmetries.md`.
- Predicate operates on 4 corners in order TL, TR, BR, BL, in any consistent 2D space (pixel or CIImage). Only edge distances and winding-sign consistency matter — no coordinate-origin assumption.
- Behavior on distorted: warp chokepoint → axis-aligned bbox crop clamped to image bounds; seeding → return nil/null so the editor uses its 10% inset default.
- Package scope (ADR-003): image primitives only. No receipt domain logic.
- Branch: `fix/ios-gallery-low-confidence-detection` (continues the related confidence-floor work). Conventional commits.
- Verification gate before done: `yarn typecheck && yarn lint && yarn test && trunk fmt && trunk check`, plus the Android unit test and the iOS Swift harness.

---

### Task 1: Android `QuadGeometry` predicate (pure, unit-tested)

**Files:**

- Create: `android/src/main/java/com/receiptscanner/QuadGeometry.kt`
- Test: `android/src/test/java/com/receiptscanner/QuadGeometryTest.kt`

**Interfaces:**

- Produces: `QuadGeometry.isDistorted(corners: FloatArray): Boolean` where `corners = [tlx,tly, trx,try, brx,bry, blx,bly]`; constants `QuadGeometry.MAX_EDGE_RATIO = 2.2f`, `QuadGeometry.MIN_EDGE_FRACTION = 0.05f`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.receiptscanner

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuadGeometryTest {
  // corners: [tlx,tly, trx,try, brx,bry, blx,bly]
  @Test
  fun `perfect rectangle is not distorted`() {
    val q = floatArrayOf(0f, 0f, 100f, 0f, 100f, 200f, 0f, 200f)
    assertFalse(QuadGeometry.isDistorted(q))
  }

  @Test
  fun `mild perspective trapezoid is not distorted`() {
    // top width 80, bottom width 100 -> ratio 1.25
    val q = floatArrayOf(10f, 0f, 90f, 0f, 100f, 200f, 0f, 200f)
    assertFalse(QuadGeometry.isDistorted(q))
  }

  @Test
  fun `egregious trapezoid is distorted`() {
    // top width 30, bottom width 100 -> ratio 3.33
    val q = floatArrayOf(35f, 0f, 65f, 0f, 100f, 200f, 0f, 200f)
    assertTrue(QuadGeometry.isDistorted(q))
  }

  @Test
  fun `collapsed corner is distorted`() {
    // br == bl -> a zero-length bottom edge
    val q = floatArrayOf(0f, 0f, 100f, 0f, 50f, 200f, 50f, 200f)
    assertTrue(QuadGeometry.isDistorted(q))
  }

  @Test
  fun `non-convex quad is distorted`() {
    // bl pulled inward toward the right edge -> reflex vertex
    val q = floatArrayOf(0f, 0f, 100f, 0f, 100f, 200f, 90f, 100f)
    assertTrue(QuadGeometry.isDistorted(q))
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd example/android && ./gradlew :react-native-receipt-scanner:testDebugUnitTest --tests '*QuadGeometryTest*'`
Expected: FAIL — `Unresolved reference: QuadGeometry`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.receiptscanner

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Geometric sanity backstop for crop quads. See docs/specs/quad-distortion-backstop.md.
 * Acts on the final quad regardless of detection source. Thresholds are PROVISIONAL
 * and must stay in sync with iOS RNQuadGeometry.
 */
internal object QuadGeometry {
  /** Opposite-edge length ratio above which a quad is an egregious trapezoid. */
  const val MAX_EDGE_RATIO = 2.2f

  /** Shortest/longest edge ratio below which a quad is degenerate (a collapsed corner). */
  const val MIN_EDGE_FRACTION = 0.05f

  /**
   * True when [corners] is too distorted to perspective-warp without visibly
   * deforming the content. [corners] = [tlx,tly, trx,try, brx,bry, blx,bly] in any
   * consistent 2D space; only distances and winding sign matter.
   */
  fun isDistorted(corners: FloatArray): Boolean {
    require(corners.size == 8) { "corners must have 8 elements" }
    fun dist(i: Int, j: Int): Float {
      val dx = corners[j] - corners[i]
      val dy = corners[j + 1] - corners[i + 1]
      return sqrt(dx * dx + dy * dy)
    }
    val topW = dist(0, 2)
    val botW = dist(6, 4)
    val leftH = dist(0, 6)
    val rightH = dist(2, 4)
    val edges = floatArrayOf(topW, botW, leftH, rightH)
    val maxEdge = edges.max()
    val minEdge = edges.min()
    if (maxEdge <= 0f) return true
    if (minEdge / maxEdge < MIN_EDGE_FRACTION) return true
    if (!isConvex(corners)) return true
    val widthRatio = max(topW, botW) / min(topW, botW)
    val heightRatio = max(leftH, rightH) / min(leftH, rightH)
    return widthRatio > MAX_EDGE_RATIO || heightRatio > MAX_EDGE_RATIO
  }

  // Convex iff the cross product at every consecutive triplet has the same sign.
  // A zero cross (colinear / coincident corner) counts as non-convex (degenerate).
  private fun isConvex(c: FloatArray): Boolean {
    var sign = 0
    for (i in 0 until 4) {
      val ax = c[(i * 2) % 8]
      val ay = c[(i * 2 + 1) % 8]
      val bx = c[(i * 2 + 2) % 8]
      val by = c[(i * 2 + 3) % 8]
      val cx = c[(i * 2 + 4) % 8]
      val cy = c[(i * 2 + 5) % 8]
      val cross = (bx - ax) * (cy - by) - (by - ay) * (cx - bx)
      val s = if (cross > 0f) 1 else if (cross < 0f) -1 else 0
      if (s == 0) return false
      if (sign == 0) sign = s else if (s != sign) return false
    }
    return true
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd example/android && ./gradlew :react-native-receipt-scanner:testDebugUnitTest --tests '*QuadGeometryTest*'`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add android/src/main/java/com/receiptscanner/QuadGeometry.kt android/src/test/java/com/receiptscanner/QuadGeometryTest.kt
git commit -m "feat(android): add QuadGeometry distortion predicate"
```

---

### Task 2: Wire Android — bbox fallback + seeding guard

**Files:**

- Modify: `android/src/main/java/com/receiptscanner/ImageProcessor.kt` (`perspectiveCorrectedBitmap`, ~`:531`)
- Modify: `android/src/main/java/com/receiptscanner/CropEditorActivity.kt` (`quadFromTextBlocks`, ~`:468`)

**Interfaces:**

- Consumes: `QuadGeometry.isDistorted(FloatArray)` from Task 1.

- [ ] **Step 1: Add the bbox fallback at the warp chokepoint**

In `ImageProcessor.kt`, at the top of `perspectiveCorrectedBitmap`, right after the `require(corners.size == 8)` line, insert:

```kotlin
    if (QuadGeometry.isDistorted(corners)) {
      return boundingBoxCrop(bitmap, corners)
    }
```

Then add this private helper directly below `perspectiveCorrectedBitmap` (same class):

```kotlin
  // Distorted quad → crop the axis-aligned bounding box of the corners instead of
  // warping. Undistorted, with some extra background. See quad-distortion-backstop.md.
  private fun boundingBoxCrop(
    bitmap: Bitmap,
    corners: FloatArray,
  ): Bitmap {
    val xs = floatArrayOf(corners[0], corners[2], corners[4], corners[6])
    val ys = floatArrayOf(corners[1], corners[3], corners[5], corners[7])
    val left = xs.min().toInt().coerceIn(0, bitmap.width - 1)
    val top = ys.min().toInt().coerceIn(0, bitmap.height - 1)
    val right = xs.max().toInt().coerceIn(left + 1, bitmap.width)
    val bottom = ys.max().toInt().coerceIn(top + 1, bitmap.height)
    return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
  }
```

- [ ] **Step 2: Add the seeding guard**

In `CropEditorActivity.kt` `quadFromTextBlocks`, replace the final return:

```kotlin
    return expandedDetectedCorners(
      arrayOf(toDisplay(resolved[0]), toDisplay(resolved[1]), toDisplay(resolved[2]), toDisplay(resolved[3])),
    )
```

with:

```kotlin
    val quad =
      expandedDetectedCorners(
        arrayOf(toDisplay(resolved[0]), toDisplay(resolved[1]), toDisplay(resolved[2]), toDisplay(resolved[3])),
      )
    val flat =
      floatArrayOf(
        quad[0].x, quad[0].y, quad[1].x, quad[1].y,
        quad[2].x, quad[2].y, quad[3].x, quad[3].y,
      )
    // Distorted detected quad → discard so the editor keeps its 10% inset default.
    if (QuadGeometry.isDistorted(flat)) return null
    return quad
```

- [ ] **Step 3: Compile-check via the unit test task (compiles main + test sources)**

Run: `cd example/android && ./gradlew :react-native-receipt-scanner:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass (no Kotlin compile errors in the modified files).

- [ ] **Step 4: Commit**

```bash
git add android/src/main/java/com/receiptscanner/ImageProcessor.kt android/src/main/java/com/receiptscanner/CropEditorActivity.kt
git commit -m "feat(android): bbox fallback + seeding guard for distorted quads"
```

---

### Task 3: iOS `RNQuadGeometry` predicate + harness verification

**Files:**

- Create: `ios/RNQuadGeometry.h`, `ios/RNQuadGeometry.m`
- Verify: `scratchpad/quad_predicate_check.swift` (Swift mirror — verification only, not committed)

**Interfaces:**

- Produces: `+[RNQuadGeometry isDistorted:(NSArray<NSValue *> *)corners]` — corners are 4 `NSValue`-wrapped `CGPoint` in order TL, TR, BR, BL.

- [ ] **Step 1: Write the header**

```objc
// ios/RNQuadGeometry.h
#import <Foundation/Foundation.h>
#import <CoreGraphics/CoreGraphics.h>

NS_ASSUME_NONNULL_BEGIN

/// Geometric sanity backstop for crop quads. See docs/specs/quad-distortion-backstop.md.
/// Thresholds are PROVISIONAL and must stay in sync with Android QuadGeometry.
@interface RNQuadGeometry : NSObject
/// corners: 4 NSValue-wrapped CGPoint in order TL, TR, BR, BL (any consistent 2D space).
+ (BOOL)isDistorted:(NSArray<NSValue *> *)corners;
@end

NS_ASSUME_NONNULL_END
```

- [ ] **Step 2: Write the implementation**

```objc
// ios/RNQuadGeometry.m
#import "RNQuadGeometry.h"

static const CGFloat kMaxEdgeRatio    = 2.2;
static const CGFloat kMinEdgeFraction = 0.05;

static CGFloat RNDist(CGPoint a, CGPoint b) { return hypot(a.x - b.x, a.y - b.y); }

@implementation RNQuadGeometry

+ (BOOL)isConvexTL:(CGPoint)tl tr:(CGPoint)tr br:(CGPoint)br bl:(CGPoint)bl {
    CGPoint p[4] = { tl, tr, br, bl };
    int sign = 0;
    for (int i = 0; i < 4; i++) {
        CGPoint a = p[i], b = p[(i + 1) % 4], c = p[(i + 2) % 4];
        CGFloat cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x);
        int s = cross > 0 ? 1 : (cross < 0 ? -1 : 0);
        if (s == 0) return NO;            // colinear / coincident → degenerate
        if (sign == 0) sign = s;
        else if (s != sign) return NO;
    }
    return YES;
}

+ (BOOL)isDistorted:(NSArray<NSValue *> *)corners {
    if (corners.count != 4) return YES;
    CGPoint tl = [corners[0] CGPointValue], tr = [corners[1] CGPointValue];
    CGPoint br = [corners[2] CGPointValue], bl = [corners[3] CGPointValue];

    CGFloat topW = RNDist(tl, tr), botW = RNDist(bl, br);
    CGFloat leftH = RNDist(tl, bl), rightH = RNDist(tr, br);
    CGFloat edges[4] = { topW, botW, leftH, rightH };
    CGFloat maxE = edges[0], minE = edges[0];
    for (int i = 1; i < 4; i++) {
        if (edges[i] > maxE) maxE = edges[i];
        if (edges[i] < minE) minE = edges[i];
    }
    if (maxE <= 0) return YES;
    if (minE / maxE < kMinEdgeFraction) return YES;
    if (![self isConvexTL:tl tr:tr br:br bl:bl]) return YES;

    CGFloat wRatio = MAX(topW, botW) / MIN(topW, botW);
    CGFloat hRatio = MAX(leftH, rightH) / MIN(leftH, rightH);
    return (wRatio > kMaxEdgeRatio || hRatio > kMaxEdgeRatio);
}

@end
```

- [ ] **Step 3: Verify the predicate logic with a Swift mirror**

Create `scratchpad/quad_predicate_check.swift` mirroring `isDistorted` exactly, then assert the same 5 cases as the Android test plus the harness corner-sets. Run:

```bash
swift scratchpad/quad_predicate_check.swift
```

Expected output: `rect=ok mild=ok egregious=ok collapsed=ok nonconvex=ok` (all 5 match: false,false,true,true,true).

- [ ] **Step 4: Commit**

```bash
git add ios/RNQuadGeometry.h ios/RNQuadGeometry.m
git commit -m "feat(ios): add RNQuadGeometry distortion predicate"
```

---

### Task 4: Wire iOS — bbox fallback + seeding guard

**Files:**

- Modify: `ios/RNImageProcessor.m` (`perspectiveCorrectedCGImage:corners:`, `:289`)
- Modify: `ios/RNGalleryPickerDelegate.m` (`detectCornersForImage:`, return block ~`:277`)

**Interfaces:**

- Consumes: `+[RNQuadGeometry isDistorted:]` from Task 3.

- [ ] **Step 1: bbox fallback at the warp chokepoint**

In `ios/RNImageProcessor.m`, add the import near the top:

```objc
#import "RNQuadGeometry.h"
```

In `perspectiveCorrectedCGImage:corners:`, after the block that builds `ciInput` and applies the origin-translation (immediately before `CIFilter *filter = ...`), insert:

```objc
    if ([RNQuadGeometry isDistorted:corners]) {
        // Distorted quad → crop the axis-aligned bbox in ciInput space, no warp.
        CGFloat minX = MIN(MIN(tl.x, tr.x), MIN(br.x, bl.x));
        CGFloat maxX = MAX(MAX(tl.x, tr.x), MAX(br.x, bl.x));
        CGFloat minY = MIN(MIN(tl.y, tr.y), MIN(br.y, bl.y));
        CGFloat maxY = MAX(MAX(tl.y, tr.y), MAX(br.y, bl.y));
        CGRect bbox = CGRectIntersection(ciInput.extent,
                                         CGRectMake(minX, minY, maxX - minX, maxY - minY));
        if (CGRectIsNull(bbox) || bbox.size.width < 1 || bbox.size.height < 1) return NULL;
        CIImage *croppedCI = [[ciInput imageByCroppingToRect:bbox]
            imageByApplyingTransform:CGAffineTransformMakeTranslation(-bbox.origin.x, -bbox.origin.y)];
        CIContext *bboxCtx = [CIContext context];
        return [bboxCtx createCGImage:croppedCI fromRect:croppedCI.extent];
    }
```

- [ ] **Step 2: seeding guard**

In `ios/RNGalleryPickerDelegate.m`, add the import near the top:

```objc
#import "RNQuadGeometry.h"
```

In `detectCornersForImage:confidence:error:`, replace the final return:

```objc
    return @[
        [NSValue valueWithCGPoint:CGPointMake(obs.topLeft.x     * W, obs.topLeft.y     * H)],
        [NSValue valueWithCGPoint:CGPointMake(obs.topRight.x    * W, obs.topRight.y    * H)],
        [NSValue valueWithCGPoint:CGPointMake(obs.bottomRight.x * W, obs.bottomRight.y * H)],
        [NSValue valueWithCGPoint:CGPointMake(obs.bottomLeft.x  * W, obs.bottomLeft.y  * H)],
    ];
```

with:

```objc
    NSArray<NSValue *> *detected = @[
        [NSValue valueWithCGPoint:CGPointMake(obs.topLeft.x     * W, obs.topLeft.y     * H)],
        [NSValue valueWithCGPoint:CGPointMake(obs.topRight.x    * W, obs.topRight.y    * H)],
        [NSValue valueWithCGPoint:CGPointMake(obs.bottomRight.x * W, obs.bottomRight.y * H)],
        [NSValue valueWithCGPoint:CGPointMake(obs.bottomLeft.x  * W, obs.bottomLeft.y  * H)],
    ];
    // Distorted/degenerate detected quad → discard so the editor uses its inset default.
    if ([RNQuadGeometry isDistorted:detected]) return nil;
    return detected;
```

- [ ] **Step 3: Build the iOS example app to verify it compiles and codegen picks up the new files**

Run: `yarn example ios --no-packager` (or open `example/ios` in Xcode and build).
Expected: build succeeds; `RNQuadGeometry.{h,m}` are compiled (podspec `source_files` glob includes `ios/**/*.{h,m,mm}`).

- [ ] **Step 4: Commit**

```bash
git add ios/RNImageProcessor.m ios/RNGalleryPickerDelegate.m
git commit -m "feat(ios): bbox fallback + seeding guard for distorted quads"
```

---

### Task 5: Regression, docs, and full verification gate

**Files:**

- Modify: `docs/notes/platform-asymmetries.md`

**Interfaces:**

- Consumes: all prior tasks.

- [ ] **Step 1: Regression — run the calibration harness on the sample corpus**

The 10-image corpus is a private one-time calibration set (real receipts contain PII) and is **not committed**. Run the Swift metric harness over whatever local samples are available:
Run: `swift scratchpad/quad_metrics.swift <local-sample-images>`
Expected: every normal receipt shows `wRatio ≤ 1.35`, `convex=Y` (→ NOT distorted at thresholds 2.2 / 0.05); UI-screenshot / degenerate captures show `NO quad ≥ 0.5` (inset fallback). No normal receipt crosses a threshold. Recorded results: `docs/specs/quad-distortion-backstop.md` § Empirical data.

- [ ] **Step 2: Record the platform asymmetry**

Add an entry to `docs/notes/platform-asymmetries.md` (under its existing list/table) capturing:

```markdown
## Quad distortion backstop (2026-06)

The crop-quad distortion guard (`docs/specs/quad-distortion-backstop.md`) acts on the
final quad on both platforms, but the **detection source differs**, so the distortion it
guards against differs:

- **iOS** — Vision `VNDetectDocumentSegmentationRequest`/`VNDetectRectanglesRequest`; sample
  quads are clean (convex, low edge ratio). The guard mostly defends the confirm /
  `cropAutoConfirm` path. Predicate: `ios/RNQuadGeometry`.
- **Android** — ML Kit text-block corners via `quadFromTextBlocks` (sector-furthest-point),
  which can emit non-convex/skewed quads; the convexity check earns its keep here.
  Predicate: `com.receiptscanner.QuadGeometry`.

Thresholds (`MAX_EDGE_RATIO = 2.2`, `MIN_EDGE_FRACTION = 0.05`) are PROVISIONAL and must
stay identical across the two predicates.
```

- [ ] **Step 3: Run the full verification gate**

Run: `yarn typecheck && yarn lint && yarn test && trunk fmt && trunk check`
Expected: all pass. (JS surface is unchanged, but the gate is the project standard.)

Run: `cd example/android && ./gradlew :react-native-receipt-scanner:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add docs/notes/platform-asymmetries.md
git commit -m "docs: record quad distortion backstop platform asymmetry"
```

- [ ] **Step 5: Push**

```bash
git push
```
