package com.receiptscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

class GalleryCacheCopierTest {
  @Test
  fun `copy rejects oversized streams and removes the partial file`() {
    val destination = File(Files.createTempDirectory("gallery-copy-test").toFile(), "picked.jpg")

    val error =
      assertThrows(GalleryCacheCopier.SizeLimitExceededException::class.java) {
        GalleryCacheCopier.copy(
          ByteArrayInputStream(ByteArray(9)),
          destination,
          maxBytes = 8,
        )
      }

    // The message names the enforced limit, not a hardcoded 50 MB.
    assertEquals("Selected image exceeds the 0 MB limit", error.message)
    assertFalse(destination.exists())
  }
}
