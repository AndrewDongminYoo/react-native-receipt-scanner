package com.receiptscanner

import kotlin.math.roundToInt

/**
 * Maps OCR line boxes into the frame the output JPEG actually ships in, and
 * turns per-line text angles into the rotation that puts them upright.
 * See docs/specs/ocr-line-geometry.md and docs/specs/ocr-angle-rotation-detection.md.
 *
 * Pure Kotlin — no `android.graphics` types — so it stays JVM-unit-testable
 * without Robolectric, matching [QuadGeometry]'s convention. The angle helpers
 * are deliberately platform-agnostic: `RNOcrGeometry` mirrors the same formulas
 * on iOS, and these tests are what pin the shared sign convention.
 */
object OcrGeometry {
  /** Axis-aligned box in top-left-origin pixel space. */
  data class Box(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
  )

  /**
   * Rotates [box] clockwise by [degrees] inside a [frameWidth] x [frameHeight]
   * frame and returns it in the rotated frame's coordinates. For 90 / 270 the
   * rotated frame is [frameHeight] x [frameWidth], so width and height swap.
   *
   * Clockwise matches `android.graphics.Matrix.postRotate`, which is what
   * [ImageProcessor.rotateFileInPlace] applies to the pixels. iOS rotates the
   * *other* way for the same degree value — see docs/notes/platform-asymmetries.md.
   */
  fun rotateClockwise(
    box: Box,
    frameWidth: Int,
    frameHeight: Int,
    degrees: Int,
  ): Box =
    when (((degrees % 360) + 360) % 360) {
      90 -> {
        Box(frameHeight - box.y - box.height, box.x, box.height, box.width)
      }

      180 -> {
        Box(
          frameWidth - box.x - box.width,
          frameHeight - box.y - box.height,
          box.width,
          box.height,
        )
      }

      270 -> {
        Box(box.y, frameWidth - box.x - box.width, box.height, box.width)
      }

      else -> {
        box
      }
    }

  /**
   * Intersects [box] with the frame. Returns null when nothing survives — such
   * a line has no drawable geometry and is dropped from the payload rather than
   * shipped as an off-image or zero-area rectangle.
   */
  fun clamp(
    box: Box,
    frameWidth: Int,
    frameHeight: Int,
  ): Box? {
    val left = box.x.coerceIn(0, frameWidth)
    val top = box.y.coerceIn(0, frameHeight)
    val right = (box.x + box.width).coerceIn(0, frameWidth)
    val bottom = (box.y + box.height).coerceIn(0, frameHeight)
    if (right <= left || bottom <= top) return null
    return Box(left, top, right - left, bottom - top)
  }

  /** Minimum lines carrying a finite angle before the mode is trusted. PROVISIONAL. */
  const val ANGLE_MIN_LINES = 5

  /**
   * Fraction of lines the winning quarter-turn must hold. PROVISIONAL.
   * Lines printed on one piece of paper share an angle, so a real signal sits
   * near 1.0. Kept above 0.5 by construction so a two-way tie can never resolve
   * silently in favour of whichever bin happens to be lower.
   */
  const val ANGLE_MAJORITY = 0.7f

  /**
   * Rounds a clockwise-positive text angle to the nearest quarter turn and
   * normalizes it into `[0, 360)`. ML Kit reports `[-180, 180]`, so -90 lands
   * on 270 rather than staying negative.
   *
   * Caller must pass a finite value; [dominantQuarterTurn] filters NaN.
   */
  fun quantizeQuarterTurn(degrees: Float): Int {
    val quarters = (degrees / 90f).roundToInt()
    return ((quarters * 90) % 360 + 360) % 360
  }

  /**
   * The clockwise rotation that puts text sitting at [quarterTurn] back
   * upright. Undoing a clockwise `d` tilt is a clockwise `360 - d` turn.
   */
  fun correctionForTextAngle(quarterTurn: Int): Int = (360 - (((quarterTurn % 360) + 360) % 360)) % 360

  /**
   * Counts of finite angles per quarter-turn bin, indexed `turn / 90`.
   * Non-finite angles are dropped, so `sum()` is the usable sample size rather
   * than [anglesDegrees].size — ML Kit reports NaN for some lines the same way
   * it does for confidence.
   *
   * Exposed so the diagnostic log can report the same distribution the routing
   * decision saw, without recomputing it.
   */
  fun quarterTurnHistogram(anglesDegrees: List<Float>): IntArray {
    val bins = IntArray(4)
    for (angle in anglesDegrees) {
      if (angle.isFinite()) bins[quantizeQuarterTurn(angle) / 90]++
    }
    return bins
  }

  /**
   * Dominant quarter turn across per-line text angles, or null when the sample
   * is too small or too split to act on — the caller then falls back to the
   * lineAspect path.
   *
   * Angles are binned before counting rather than averaged: a linear mean of
   * -179 and +179 is 0, the exact opposite of the truth. Binning resolves the
   * wrap at the boundary, so no circular mean is needed.
   */
  fun dominantQuarterTurn(
    anglesDegrees: List<Float>,
    minLines: Int = ANGLE_MIN_LINES,
    majority: Float = ANGLE_MAJORITY,
  ): Int? {
    val bins = quarterTurnHistogram(anglesDegrees)
    val total = bins.sum()
    if (total < minLines) return null
    var best = 0
    for (i in 1 until bins.size) if (bins[i] > bins[best]) best = i
    if (bins[best].toFloat() / total < majority) return null
    return best * 90
  }
}
