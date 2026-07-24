package com.receiptscanner

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

internal object GalleryCacheCopier {
  const val MAX_BYTES = 50L * 1024L * 1024L

  // ponytail: fixed session-wide ceiling across all images in one gallery batch,
  // so N selections can't sum to N × MAX_BYTES and exhaust storage. Bump if
  // multi-page receipts legitimately need more than this.
  const val SESSION_MAX_BYTES = 250L * 1024L * 1024L

  class SizeLimitExceededException : Exception("Selected image exceeds the 50 MB limit")

  fun copy(
    input: InputStream,
    destination: File,
    maxBytes: Long = MAX_BYTES,
  ) {
    try {
      FileOutputStream(destination).use { output ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copied = 0L
        while (true) {
          val count = input.read(buffer)
          if (count == -1) break
          copied += count
          if (copied > maxBytes) throw SizeLimitExceededException()
          output.write(buffer, 0, count)
        }
      }
    } catch (error: Throwable) {
      destination.delete()
      throw error
    }
  }
}
