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
  fun `negative and over-360 degrees normalise`() {
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
}
