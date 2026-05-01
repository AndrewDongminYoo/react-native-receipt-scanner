package com.receiptscanner

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions

class OcrProcessor(
  private val context: Context,
) {
  private val recognizer =
    TextRecognition.getClient(
      KoreanTextRecognizerOptions.Builder().build(),
    )

  /**
   * Perform text recognition on [imageUri].
   * MUST be called on a background thread — uses [Tasks.await] which blocks.
   */
  fun recognize(imageUri: Uri): String {
    val image = InputImage.fromFilePath(context, imageUri)
    val result = Tasks.await(recognizer.process(image))
    return result.text
  }

  fun close() {
    recognizer.close()
  }
}
