package com.receiptscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingScanLifecycleTest {
  @Test
  fun `pending scan stays active through one terminal callback`() {
    // Given
    val lifecycle = PendingScanLifecycle()
    val token = lifecycle.tryBegin()
    assertNotNull(token)
    assertNull(lifecycle.tryBegin())
    var activeDuringCompletion = false
    var completionCount = 0

    // When
    val firstCompletion =
      lifecycle.complete(checkNotNull(token)) {
        activeDuringCompletion = lifecycle.isActive
        completionCount += 1
      }
    val duplicateCompletion =
      lifecycle.complete(token) {
        completionCount += 1
      }

    // Then
    assertTrue(firstCompletion)
    assertTrue(activeDuringCompletion)
    assertFalse(duplicateCompletion)
    assertFalse(lifecycle.isActive)
    assertEquals(1, completionCount)
  }
}
