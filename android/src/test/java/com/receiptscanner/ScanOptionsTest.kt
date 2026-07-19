package com.receiptscanner

import com.facebook.react.bridge.JavaOnlyMap
import org.junit.Assert.assertEquals
import org.junit.Test

class ScanOptionsTest {
  @Test
  fun `from applies all defaults when map is empty`() {
    val opts = ScanOptions.from(JavaOnlyMap())

    assertEquals("camera", opts.source)
    assertEquals(1, opts.maxPages)
    assertEquals(0.82, opts.quality, 0.001)
    assertEquals(true, opts.includeExif)
    assertEquals(false, opts.includeGpsExif)
    assertEquals(true, opts.ocr)
    assertEquals(false, opts.ocrGeometry)
  }

  @Test
  fun `from reads ocrGeometry true`() {
    val map = JavaOnlyMap().apply { putBoolean("ocrGeometry", true) }
    assertEquals(true, ScanOptions.from(map).ocrGeometry)
  }

  @Test
  fun `from reads source gallery`() {
    val map = JavaOnlyMap().apply { putString("source", "gallery") }
    assertEquals("gallery", ScanOptions.from(map).source)
  }

  @Test
  fun `from reads maxPages`() {
    val map = JavaOnlyMap().apply { putInt("maxPages", 5) }
    assertEquals(5, ScanOptions.from(map).maxPages)
  }

  @Test
  fun `from reads quality`() {
    val map = JavaOnlyMap().apply { putDouble("quality", 0.5) }
    assertEquals(0.5, ScanOptions.from(map).quality, 0.001)
  }

  @Test
  fun `from reads includeExif false`() {
    val map = JavaOnlyMap().apply { putBoolean("includeExif", false) }
    assertEquals(false, ScanOptions.from(map).includeExif)
  }

  @Test
  fun `from reads includeGpsExif true`() {
    val map = JavaOnlyMap().apply { putBoolean("includeGpsExif", true) }
    assertEquals(true, ScanOptions.from(map).includeGpsExif)
  }

  @Test
  fun `from reads ocr false`() {
    val map = JavaOnlyMap().apply { putBoolean("ocr", false) }
    assertEquals(false, ScanOptions.from(map).ocr)
  }

  @Test
  fun `from reads all fields together`() {
    val map =
      JavaOnlyMap().apply {
        putString("source", "gallery")
        putInt("maxPages", 3)
        putDouble("quality", 0.6)
        putBoolean("includeExif", false)
        putBoolean("includeGpsExif", true)
        putBoolean("ocr", false)
      }
    val opts = ScanOptions.from(map)

    assertEquals("gallery", opts.source)
    assertEquals(3, opts.maxPages)
    assertEquals(0.6, opts.quality, 0.001)
    assertEquals(false, opts.includeExif)
    assertEquals(true, opts.includeGpsExif)
    assertEquals(false, opts.ocr)
  }
}
