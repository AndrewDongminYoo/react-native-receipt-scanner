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

  /**
   * True when [corners] is too distorted to perspective-warp without visibly
   * deforming the content. [corners] = [tlx,tly, trx,try, brx,bry, blx,bly] in any
   * consistent 2D space; only distances and winding sign matter.
   */
  fun isDistorted(corners: FloatArray): Boolean {
    require(corners.size == 8) { "corners must have 8 elements" }

    fun dist(
      i: Int,
      j: Int,
    ): Float {
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
    // All-zero edges (coincident corners) are degenerate. Everything else routes through the
    // convexity + opposite-edge-ratio checks below, which already reject collapsed corners (a
    // zero-length edge makes its opposite-pair ratio diverge past MAX_EDGE_RATIO). A standalone
    // shortest/longest-edge gate was dropped: its only unique effect was flagging legitimate
    // high-aspect-ratio (very long) receipts as distorted. See quad-distortion-backstop.md.
    if (maxEdge <= 0f) return true
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
      val s =
        if (cross > 0f) {
          1
        } else if (cross < 0f) {
          -1
        } else {
          0
        }
      if (s == 0) return false
      if (sign == 0) {
        sign = s
      } else if (s != sign) {
        return false
      }
    }
    return true
  }
}
