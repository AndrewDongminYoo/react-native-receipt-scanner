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

  @Test
  fun `long high-aspect receipt is not distorted`() {
    // 25:1 portrait receipt (width 100, height 2500): a normal long thermal receipt, not a
    // degenerate quad. Regression guard for the dropped shortest/longest-edge gate, which
    // used to reject this (minEdgeFrac 0.04 < 0.05). Caught nothing unique — collapsed
    // corners are still rejected by convexity + the opposite-edge-ratio check.
    val q = floatArrayOf(0f, 0f, 100f, 0f, 100f, 2500f, 0f, 2500f)
    assertFalse(QuadGeometry.isDistorted(q))
  }
}
