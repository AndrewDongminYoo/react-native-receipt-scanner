package com.receiptscanner

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageProcessorTest {
  @Test
  fun `sample size bounds the longer image dimension`() {
    assertEquals(4, ImageProcessor.sampleSizeForBounds(12000, 9000, 3072))
  }
}
