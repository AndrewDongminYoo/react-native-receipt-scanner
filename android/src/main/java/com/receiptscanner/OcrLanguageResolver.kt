package com.receiptscanner

import android.icu.util.ULocale
import java.util.IllformedLocaleException
import java.util.Locale

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
          validateLanguageTag(tag)
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

  private fun validateLanguageTag(tag: String) {
    try {
      Locale.Builder().setLanguageTag(tag)
    } catch (error: IllformedLocaleException) {
      throw invalidLanguageTag(tag)
    }

    val subtags = tag.lowercase(Locale.ROOT).split("-")
    if (subtags.first() == "x") return

    val variants = mutableSetOf<String>()
    val extensionSingletons = mutableSetOf<String>()
    var inExtension = false
    subtags.drop(1).forEach { subtag ->
      if (subtag.length == 1) {
        if (subtag == "x") return
        if (!extensionSingletons.add(subtag)) throw invalidLanguageTag(tag)
        inExtension = true
      } else if (
        !inExtension &&
        (subtag.length in 5..8 || (subtag.length == 4 && subtag.first().isDigit()))
      ) {
        if (!variants.add(subtag)) throw invalidLanguageTag(tag)
      }
    }
  }

  private fun invalidLanguageTag(tag: String) =
    OcrLanguageException(
      "INVALID_OCR_LANGUAGE",
      "OCR language tag $tag is invalid",
    )

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
