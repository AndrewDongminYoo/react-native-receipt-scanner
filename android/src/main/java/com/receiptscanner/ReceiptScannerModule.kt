package com.receiptscanner

import android.app.Activity
import android.content.Intent
import com.facebook.react.bridge.ActivityEventListener
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReadableMap
import com.google.mlkit.vision.documentscanner.GmsDocumentScanner
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import java.util.concurrent.Executors

class ReceiptScannerModule(
  reactContext: ReactApplicationContext,
) : NativeReceiptScannerSpec(reactContext),
  ActivityEventListener {
  private val executor = Executors.newSingleThreadExecutor()
  private var pendingPromise: Promise? = null
  private var pendingOptions: ScanOptions? = null

  init {
    reactContext.addActivityEventListener(this)
  }

  override fun scan(
    options: ReadableMap,
    promise: Promise,
  ) {
    if (pendingPromise != null) {
      promise.reject("SCAN_IN_PROGRESS", "A scan is already in progress")
      return
    }

    val activity =
      currentActivity ?: run {
        promise.reject("NO_ACTIVITY", "No foreground activity found")
        return
      }

    val scanOptions = ScanOptions.from(options)

    // Clean up files from the previous session on a background thread
    executor.execute {
      ImageProcessor(reactApplicationContext).deletePreviousSessionFiles()
    }

    pendingPromise = promise
    pendingOptions = scanOptions

    val scannerOptions =
      GmsDocumentScannerOptions
        .Builder()
        .setGalleryImportAllowed(scanOptions.source == "gallery")
        .setPageLimit(scanOptions.maxPages)
        .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
        .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
        .build()

    val scanner: GmsDocumentScanner = GmsDocumentScanning.getClient(scannerOptions)
    scanner
      .getStartScanIntent(activity)
      .addOnSuccessListener { intentSender ->
        activity.startIntentSenderForResult(intentSender, SCAN_REQUEST_CODE, null, 0, 0, 0)
      }.addOnFailureListener { e ->
        pendingPromise = null
        pendingOptions = null
        promise.reject("SCANNER_INIT_FAILED", e.message ?: "Failed to initialise ML Kit scanner", e)
      }
  }

  override fun onActivityResult(
    activity: Activity,
    requestCode: Int,
    resultCode: Int,
    data: Intent?,
  ) {
    if (requestCode != SCAN_REQUEST_CODE) return

    val promise = pendingPromise ?: return
    val scanOptions = pendingOptions ?: return
    pendingPromise = null
    pendingOptions = null

    if (resultCode == Activity.RESULT_CANCELED) {
      promise.resolve(ResultBuilder.buildCancelled())
      return
    }

    if (resultCode != Activity.RESULT_OK || data == null) {
      promise.reject("SCAN_FAILED", "Unexpected result code: $resultCode")
      return
    }

    val scanningResult = GmsDocumentScanningResult.fromActivityResultIntent(data)
    val pages = scanningResult?.pages ?: emptyList()

    executor.execute {
      try {
        val imageProcessor = ImageProcessor(reactApplicationContext)
        val ocrProcessor = if (scanOptions.ocr) OcrProcessor(reactApplicationContext) else null

        val imageResults =
          pages.map { page ->
            val processed =
              imageProcessor.process(
                page.imageUri,
                scanOptions.quality,
                scanOptions.includeExif,
                scanOptions.includeGpsExif,
              )

            val ocrText =
              if (ocrProcessor != null) {
                try {
                  ocrProcessor.recognize(android.net.Uri.fromFile(processed.file))
                } catch (e: Exception) {
                  null
                }
              } else {
                null
              }

            ResultBuilder.buildImage(
              file = processed.file,
              width = processed.width,
              height = processed.height,
              ocrText = ocrText,
              exifData = processed.exifData,
            )
          }

        ocrProcessor?.close()
        promise.resolve(ResultBuilder.buildSuccess(imageResults))
      } catch (e: Exception) {
        promise.reject("PROCESSING_FAILED", e.message ?: "Image processing failed", e)
      }
    }
  }

  override fun onNewIntent(intent: Intent) = Unit

  override fun invalidate() {
    super.invalidate()
    executor.shutdown()
    reactApplicationContext.removeActivityEventListener(this)
  }

  companion object {
    const val NAME = NativeReceiptScannerSpec.NAME
    private const val SCAN_REQUEST_CODE = 0x9001
  }
}
