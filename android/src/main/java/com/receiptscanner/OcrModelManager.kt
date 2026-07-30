package com.receiptscanner

import android.content.Context
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate.InstallState.STATE_CANCELED
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate.InstallState.STATE_COMPLETED
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate.InstallState.STATE_FAILED
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.CancellationException

internal data class OcrModelState(
  val script: String,
  val status: String,
)

internal fun interface OcrRecognizerFactory {
  fun create(script: OcrScript): TextRecognizer
}

internal object DefaultOcrRecognizerFactory : OcrRecognizerFactory {
  override fun create(script: OcrScript): TextRecognizer =
    when (script) {
      OcrScript.LATIN -> {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
      }

      OcrScript.KOREAN -> {
        TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
      }

      OcrScript.JAPANESE -> {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
      }

      OcrScript.CHINESE -> {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
      }

      OcrScript.DEVANAGARI -> {
        TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
      }
    }
}

internal interface OcrModuleInstaller {
  fun check(
    recognizer: TextRecognizer,
    onResult: (Boolean) -> Unit,
    onFailure: (Exception) -> Unit,
  )

  fun install(
    recognizer: TextRecognizer,
    onInstalled: () -> Unit,
    onFailure: (Exception) -> Unit,
  )
}

private class PlayServicesOcrModuleInstaller(
  context: Context,
) : OcrModuleInstaller {
  private val client = ModuleInstall.getClient(context)

  override fun check(
    recognizer: TextRecognizer,
    onResult: (Boolean) -> Unit,
    onFailure: (Exception) -> Unit,
  ) {
    client
      .areModulesAvailable(recognizer)
      .addOnSuccessListener { response -> onResult(response.areModulesAvailable()) }
      .addOnFailureListener(onFailure)
  }

  override fun install(
    recognizer: TextRecognizer,
    onInstalled: () -> Unit,
    onFailure: (Exception) -> Unit,
  ) {
    lateinit var listener: InstallStatusListener
    var isTerminal = false

    fun finishInstalled() {
      if (isTerminal) return
      isTerminal = true
      client.unregisterListener(listener)
      onInstalled()
    }

    fun finishFailed(error: Exception) {
      if (isTerminal) return
      isTerminal = true
      client.unregisterListener(listener)
      onFailure(error)
    }

    listener =
      InstallStatusListener { update ->
        when (update.installState) {
          STATE_COMPLETED -> {
            finishInstalled()
          }

          STATE_FAILED -> {
            finishFailed(
              IllegalStateException("OCR model installation failed (${update.errorCode})"),
            )
          }

          STATE_CANCELED -> {
            finishFailed(CancellationException("OCR model installation canceled"))
          }
        }
      }

    val request =
      ModuleInstallRequest
        .newBuilder()
        .addApi(recognizer)
        .setListener(listener)
        .build()

    client
      .installModules(request)
      .addOnSuccessListener { response ->
        if (response.areModulesAlreadyInstalled()) {
          finishInstalled()
        }
      }.addOnFailureListener(::finishFailed)
  }
}

internal class OcrModelManager(
  private val installer: OcrModuleInstaller,
  private val recognizerFactory: OcrRecognizerFactory,
) {
  constructor(context: Context) : this(
    PlayServicesOcrModuleInstaller(context),
    DefaultOcrRecognizerFactory,
  )

  fun capabilities(
    onResult: (List<OcrModelState>) -> Unit,
    onFailure: (Exception) -> Unit,
  ) {
    val scripts = OcrScript.entries
    val states = mutableListOf<OcrModelState>()

    fun checkNext(index: Int) {
      if (index == scripts.size) {
        onResult(states)
        return
      }

      val script = scripts[index]
      if (script == OcrScript.KOREAN) {
        states += script.toStates(isReady = true)
        checkNext(index + 1)
        return
      }

      val recognizer = recognizerFactory.create(script)
      installer.check(
        recognizer,
        onResult = { isReady ->
          recognizer.close()
          states += script.toStates(isReady)
          checkNext(index + 1)
        },
        onFailure = { error ->
          recognizer.close()
          onFailure(error)
        },
      )
    }

    checkNext(0)
  }

  fun prepare(
    script: OcrScript,
    onReady: (OcrProcessor) -> Unit,
    onFailure: (Exception) -> Unit,
  ) {
    val recognizer = recognizerFactory.create(script)
    if (script == OcrScript.KOREAN) {
      onReady(OcrProcessor(recognizer))
      return
    }

    installer.check(
      recognizer,
      onResult = { isReady ->
        if (isReady) {
          onReady(OcrProcessor(recognizer))
        } else {
          installer.install(
            recognizer,
            onInstalled = { onReady(OcrProcessor(recognizer)) },
            onFailure = { error ->
              recognizer.close()
              onFailure(error)
            },
          )
        }
      },
      onFailure = { error ->
        recognizer.close()
        onFailure(error)
      },
    )
  }

  private fun OcrScript.toStates(isReady: Boolean): List<OcrModelState> {
    val publicScripts =
      when (this) {
        OcrScript.LATIN -> listOf("Latn")
        OcrScript.KOREAN -> listOf("Kore")
        OcrScript.JAPANESE -> listOf("Jpan")
        OcrScript.CHINESE -> listOf("Hans", "Hant")
        OcrScript.DEVANAGARI -> listOf("Deva")
      }
    val status = if (isReady) "ready" else "download-required"
    return publicScripts.map { script -> OcrModelState(script, status) }
  }
}
