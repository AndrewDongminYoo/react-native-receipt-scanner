package com.receiptscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OcrLanguageResolverTest {
  @Test
  fun `Korean and Latin resolve to Korean`() {
    assertEquals(
      OcrScript.KOREAN,
      OcrLanguageResolver.resolve(listOf("ko-KR", "en-US"), ::likelySubtagsFor),
    )
  }

  @Test
  fun `Japanese and Latin resolve to Japanese`() {
    assertEquals(
      OcrScript.JAPANESE,
      OcrLanguageResolver.resolve(listOf("ja-JP", "en-US"), ::likelySubtagsFor),
    )
  }

  @Test
  fun `Latin languages resolve to Latin`() {
    assertEquals(
      OcrScript.LATIN,
      OcrLanguageResolver.resolve(listOf("es-ES", "fr-FR"), ::likelySubtagsFor),
    )
  }

  @Test
  fun `Hindi resolves to Devanagari`() {
    assertEquals(
      OcrScript.DEVANAGARI,
      OcrLanguageResolver.resolve(listOf("hi-IN"), ::likelySubtagsFor),
    )
  }

  @Test
  fun `Simplified Chinese resolves to Chinese`() {
    assertEquals(
      OcrScript.CHINESE,
      OcrLanguageResolver.resolve(listOf("zh-Hans"), ::likelySubtagsFor),
    )
  }

  @Test
  fun `Chinese and Japanese reject as multiple non Latin scripts`() {
    val error =
      assertThrows(OcrLanguageException::class.java) {
        OcrLanguageResolver.resolve(listOf("zh-Hant", "ja-JP"), ::likelySubtagsFor)
      }

    assertEquals("OCR_LANGUAGE_COMBINATION_NOT_SUPPORTED", error.code)
  }

  @Test
  fun `unsupported Arabic script rejects explicitly`() {
    val error =
      assertThrows(OcrLanguageException::class.java) {
        OcrLanguageResolver.resolve(listOf("ar"), ::likelySubtagsFor)
      }

    assertEquals("OCR_LANGUAGE_NOT_SUPPORTED", error.code)
  }

  @Test
  fun `invalid tag rejects explicitly`() {
    val error =
      assertThrows(OcrLanguageException::class.java) {
        OcrLanguageResolver.resolve(listOf("invalid"), ::likelySubtagsFor)
      }

    assertEquals("INVALID_OCR_LANGUAGE", error.code)
  }

  @Test
  fun `empty languages reject explicitly`() {
    val error =
      assertThrows(OcrLanguageException::class.java) {
        OcrLanguageResolver.resolve(emptyList(), ::likelySubtagsFor)
      }

    assertEquals("INVALID_OCR_LANGUAGE", error.code)
  }

  private fun likelySubtagsFor(tag: String): LikelySubtags =
    when (tag) {
      "ko-KR" -> LikelySubtags("ko", "Kore")
      "en-US", "es-ES", "fr-FR" -> LikelySubtags("en", "Latn")
      "ja-JP" -> LikelySubtags("ja", "Jpan")
      "hi-IN" -> LikelySubtags("hi", "Deva")
      "zh-Hans" -> LikelySubtags("zh", "Hans")
      "zh-Hant" -> LikelySubtags("zh", "Hant")
      "ar" -> LikelySubtags("ar", "Arab")
      "invalid" -> LikelySubtags("", "")
      else -> error("Unexpected language tag: $tag")
    }
}
