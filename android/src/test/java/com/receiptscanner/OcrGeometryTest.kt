package com.receiptscanner

import com.receiptscanner.OcrGeometry.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OcrGeometryTest {
  // Deliberately asymmetric in every dimension — frame 100x200, box 80x10 at
  // (10, 5) — so a swapped axis or a 90/270 mix-up cannot pass by coincidence.
  private val frameWidth = 100
  private val frameHeight = 200
  private val box = Box(x = 10, y = 5, width = 80, height = 10)

  @Test
  fun `zero degrees is identity`() {
    assertEquals(box, OcrGeometry.rotateClockwise(box, frameWidth, frameHeight, 0))
  }

  @Test
  fun `90 degrees clockwise sends the top edge to the right edge`() {
    // x' = H - y - h = 185 (near the right edge of the 200-wide rotated frame)
    assertEquals(
      Box(x = 185, y = 10, width = 10, height = 80),
      OcrGeometry.rotateClockwise(box, frameWidth, frameHeight, 90),
    )
  }

  @Test
  fun `180 degrees mirrors both axes and keeps the frame shape`() {
    assertEquals(
      Box(x = 10, y = 185, width = 80, height = 10),
      OcrGeometry.rotateClockwise(box, frameWidth, frameHeight, 180),
    )
  }

  @Test
  fun `270 degrees clockwise sends the top edge to the left edge`() {
    assertEquals(
      Box(x = 5, y = 10, width = 10, height = 80),
      OcrGeometry.rotateClockwise(box, frameWidth, frameHeight, 270),
    )
  }

  @Test
  fun `rotating 90 then 270 in the rotated frame restores the original`() {
    // This round trip is the identity the iOS inverse remap relies on: undoing a
    // rotation is the complementary clockwise rotation in the rotated frame.
    val rotated = OcrGeometry.rotateClockwise(box, frameWidth, frameHeight, 90)
    val restored = OcrGeometry.rotateClockwise(rotated, frameHeight, frameWidth, 270)
    assertEquals(box, restored)
  }

  @Test
  fun `negative and over-360 degrees normalize`() {
    assertEquals(
      OcrGeometry.rotateClockwise(box, frameWidth, frameHeight, 90),
      OcrGeometry.rotateClockwise(box, frameWidth, frameHeight, -270),
    )
    assertEquals(
      OcrGeometry.rotateClockwise(box, frameWidth, frameHeight, 180),
      OcrGeometry.rotateClockwise(box, frameWidth, frameHeight, 540),
    )
  }

  @Test
  fun `clamp leaves an in-bounds box untouched`() {
    assertEquals(box, OcrGeometry.clamp(box, frameWidth, frameHeight))
  }

  @Test
  fun `clamp trims a box that overhangs the frame`() {
    val overhanging = Box(x = -10, y = 190, width = 130, height = 40)
    assertEquals(
      Box(x = 0, y = 190, width = 100, height = 10),
      OcrGeometry.clamp(overhanging, frameWidth, frameHeight),
    )
  }

  @Test
  fun `clamp drops a box that lies entirely outside the frame`() {
    assertNull(OcrGeometry.clamp(Box(x = 120, y = 5, width = 30, height = 10), frameWidth, frameHeight))
  }

  @Test
  fun `clamp drops a zero-area box`() {
    assertNull(OcrGeometry.clamp(Box(x = 10, y = 5, width = 0, height = 10), frameWidth, frameHeight))
  }

  @Test
  fun `quantize rounds to the nearest quarter turn`() {
    assertEquals(0, OcrGeometry.quantizeQuarterTurn(0f))
    assertEquals(0, OcrGeometry.quantizeQuarterTurn(44.9f))
    assertEquals(90, OcrGeometry.quantizeQuarterTurn(45.1f))
    assertEquals(90, OcrGeometry.quantizeQuarterTurn(88.3f))
    assertEquals(180, OcrGeometry.quantizeQuarterTurn(179f))
  }

  @Test
  fun `quantize normalizes the negative half of ML Kit's range`() {
    // getAngle() reports [-180, 180]; the routing code only ever compares
    // against 0/90/180/270, so -90 must land on 270, not stay negative.
    assertEquals(270, OcrGeometry.quantizeQuarterTurn(-90f))
    assertEquals(180, OcrGeometry.quantizeQuarterTurn(-179f))
    assertEquals(180, OcrGeometry.quantizeQuarterTurn(-180f))
    assertEquals(0, OcrGeometry.quantizeQuarterTurn(-1f))
  }

  @Test
  fun `correction undoes the tilt`() {
    assertEquals(0, OcrGeometry.correctionForTextAngle(0))
    assertEquals(270, OcrGeometry.correctionForTextAngle(90))
    assertEquals(180, OcrGeometry.correctionForTextAngle(180))
    assertEquals(90, OcrGeometry.correctionForTextAngle(270))
  }

  @Test
  fun `correction reproduces the 2026-07-18 field data`() {
    // The whole point of the redesign. Both receipts were the same document
    // laid down two different ways; v1.3's fixed ROTATED_DEFAULT_DEGREES = 270
    // got the first right and flipped the second by 180.
    // See docs/specs/ocr-angle-rotation-detection.md "실측 교차검증".
    val laidDownClockwise = OcrGeometry.quantizeQuarterTurn(90f)
    assertEquals(270, OcrGeometry.correctionForTextAngle(laidDownClockwise))

    val laidDownCounterClockwise = OcrGeometry.quantizeQuarterTurn(-90f)
    assertEquals(90, OcrGeometry.correctionForTextAngle(laidDownCounterClockwise))
  }

  @Test
  fun `dominant turn wins on a clear majority`() {
    val angles = listOf(89f, 91f, 90f, 88f, 92f, 0f)
    assertEquals(90, OcrGeometry.dominantQuarterTurn(angles))
  }

  @Test
  fun `dominant turn confirms upright text`() {
    // A confirmed 0 is a real answer, not "no answer" — it is what suppresses
    // the lineAspect fallback's false positives.
    assertEquals(0, OcrGeometry.dominantQuarterTurn(listOf(1f, -2f, 0f, 3f, -1f)))
  }

  @Test
  fun `dominant turn abstains below the line floor`() {
    assertNull(OcrGeometry.dominantQuarterTurn(listOf(90f, 90f, 90f, 90f)))
  }

  @Test
  fun `dominant turn abstains when the sample is split`() {
    // 4 of 8 is under ANGLE_MAJORITY, so no bin wins and the caller falls back.
    val split = listOf(90f, 90f, 90f, 90f, 0f, 0f, 0f, 0f)
    assertNull(OcrGeometry.dominantQuarterTurn(split))
  }

  @Test
  fun `dominant turn ignores non-finite angles`() {
    // ML Kit reports NaN for some lines the same way it does for confidence.
    val withNaN = listOf(90f, Float.NaN, 91f, 89f, Float.NaN, 90f, 92f)
    assertEquals(90, OcrGeometry.dominantQuarterTurn(withNaN))
  }

  @Test
  fun `dominant turn abstains when non-finite angles leave too few samples`() {
    val mostlyNaN = listOf(90f, Float.NaN, Float.NaN, Float.NaN, Float.NaN, 90f)
    assertNull(OcrGeometry.dominantQuarterTurn(mostlyNaN))
  }

  @Test
  fun `dominant turn resolves the wrap at 180 instead of averaging it away`() {
    // The reason angles are binned and not averaged: mean(-179, 179) = 0,
    // which would report "upright" for an upside-down receipt.
    val flipped = listOf(179f, -179f, 180f, -178f, 177f)
    assertEquals(180, OcrGeometry.dominantQuarterTurn(flipped))
  }
}
