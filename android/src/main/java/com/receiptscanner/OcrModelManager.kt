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
import java.util.concurrent.atomic.AtomicBoolean

internal data class OcrModelState(
  val script: String,
  val status: String,
)

internal fun interface OcrPreparation {
  fun cancel()
}

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
  ): OcrPreparation
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
  ): OcrPreparation {
    lateinit var listener: InstallStatusListener
    val isTerminal = AtomicBoolean(false)

    fun finishInstalled() {
      if (!isTerminal.compareAndSet(false, true)) return
      client.unregisterListener(listener)
      onInstalled()
    }

    fun finishFailed(error: Exception) {
      if (!isTerminal.compareAndSet(false, true)) return
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

    return OcrPreparation {
      if (isTerminal.compareAndSet(false, true)) {
        client.unregisterListener(listener)
      }
    }
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

      val recognizer =
        try {
          recognizerFactory.create(script)
        } catch (error: Exception) {
          onFailure(error)
          return
        }
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
  ): OcrPreparation {
    val recognizer =
      try {
        recognizerFactory.create(script)
      } catch (error: Exception) {
        onFailure(error)
        return OcrPreparation {}
      }
    val isTerminal = AtomicBoolean(false)
    val installLock = Any()
    var installPreparation: OcrPreparation? = null

    fun cancelInstall() {
      synchronized(installLock) {
        installPreparation?.cancel()
      }
    }

    fun finishReady() {
      if (!isTerminal.compareAndSet(false, true)) return
      cancelInstall()
      // Korean is the only bundled recognizer; the rest arrive through Play
      // services and may not report real confidence. See OcrProcessor.
      onReady(OcrProcessor(recognizer, reportsConfidence = script == OcrScript.KOREAN))
    }

    fun finishFailed(error: Exception) {
      if (!isTerminal.compareAndSet(false, true)) return
      cancelInstall()
      recognizer.close()
      onFailure(error)
    }

    val preparation =
      OcrPreparation {
        if (isTerminal.compareAndSet(false, true)) {
          cancelInstall()
          recognizer.close()
        }
      }

    if (script == OcrScript.KOREAN) {
      finishReady()
      return preparation
    }

    installer.check(
      recognizer,
      onResult = { isReady ->
        if (isReady) {
          finishReady()
        } else {
          synchronized(installLock) {
            if (!isTerminal.get()) {
              installPreparation =
                installer.install(
                  recognizer,
                  onInstalled = ::finishReady,
                  onFailure = ::finishFailed,
                )
              if (isTerminal.get()) {
                installPreparation?.cancel()
              }
            }
          }
        }
      },
      onFailure = ::finishFailed,
    )
    return preparation
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
