package com.receiptscanner

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class GalleryCacheCopierTest {
  @Test
  fun `copy rejects oversized streams and removes the partial file`() {
    val destination = File(Files.createTempDirectory("gallery-copy-test").toFile(), "picked.jpg")

    assertThrows(GalleryCacheCopier.SizeLimitExceededException::class.java) {
      GalleryCacheCopier.copy(
        ByteArrayInputStream(ByteArray(9)),
        destination,
        maxBytes = 8,
      )
    }

    assertFalse(destination.exists())
  }
}
