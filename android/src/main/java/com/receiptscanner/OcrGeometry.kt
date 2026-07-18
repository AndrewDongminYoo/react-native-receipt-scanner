package com.receiptscanner

/**
 * Maps OCR line boxes into the frame the output JPEG actually ships in.
 * See docs/specs/ocr-line-geometry.md.
 *
 * Pure Kotlin — no `android.graphics` types — so it stays JVM-unit-testable
 * without Robolectric, matching [QuadGeometry]'s convention.
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
}
