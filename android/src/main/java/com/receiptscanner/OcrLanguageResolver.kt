package com.receiptscanner

import android.icu.util.ULocale

internal enum class OcrScript {
  LATIN,
  KOREAN,
  JAPANESE,
  CHINESE,
  DEVANAGARI,
}

internal class OcrLanguageException(
  val code: String,
  message: String,
) : Exception(message)

internal data class LikelySubtags(
  val language: String,
  val script: String,
)

internal object OcrLanguageResolver {
  fun resolve(tags: List<String>): OcrScript = resolve(tags, ::likelySubtagsFor)

  internal fun resolve(
    tags: List<String>,
    likelySubtagsFor: (String) -> LikelySubtags,
  ): OcrScript {
    if (tags.isEmpty()) {
      throw OcrLanguageException(
        "INVALID_OCR_LANGUAGE",
        "At least one OCR language is required",
      )
    }

    val models =
      tags
        .map { tag ->
          val likelySubtags = likelySubtagsFor(tag)
          if (likelySubtags.language.isBlank()) {
            throw OcrLanguageException(
              "INVALID_OCR_LANGUAGE",
              "OCR language tag $tag is invalid",
            )
          }
          modelForScript(likelySubtags.script)
        }.toSet()
    val nonLatinModels = models - OcrScript.LATIN

    return when (nonLatinModels.size) {
      0 -> {
        OcrScript.LATIN
      }

      1 -> {
        nonLatinModels.single()
      }

      else -> {
        throw OcrLanguageException(
          "OCR_LANGUAGE_COMBINATION_NOT_SUPPORTED",
          "OCR languages require multiple non-Latin models on Android",
        )
      }
    }
  }

  private fun likelySubtagsFor(tag: String): LikelySubtags {
    val locale = ULocale.forLanguageTag(tag)
    val likelySubtags = ULocale.addLikelySubtags(locale)
    return LikelySubtags(locale.language, likelySubtags.script)
  }

  private fun modelForScript(script: String): OcrScript =
    when (script) {
      "Latn" -> {
        OcrScript.LATIN
      }

      "Kore" -> {
        OcrScript.KOREAN
      }

      "Jpan", "Hira", "Kana" -> {
        OcrScript.JAPANESE
      }

      "Hans", "Hant", "Hani" -> {
        OcrScript.CHINESE
      }

      "Deva" -> {
        OcrScript.DEVANAGARI
      }

      else -> {
        throw OcrLanguageException(
          "OCR_LANGUAGE_NOT_SUPPORTED",
          "OCR script $script is not supported on Android",
        )
      }
    }
}
