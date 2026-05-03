package com.receiptscanner

import android.app.Activity
import android.content.Intent
import android.net.Uri
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
  private val imageProcessor = ImageProcessor(reactContext)
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
    executor.execute { imageProcessor.deletePreviousSessionFiles() }
    pendingPromise = promise
    pendingOptions = scanOptions

    if (scanOptions.source == "gallery") {
      @Suppress("DEPRECATION")
      activity.startActivityForResult(
        Intent(activity, CropEditorActivity::class.java),
        GALLERY_REQUEST_CODE,
      )
      return
    }

    // Camera path: GmsDocumentScanner (gallery import disabled — handled above)
    val scannerOptions =
      GmsDocumentScannerOptions
        .Builder()
        .setGalleryImportAllowed(false)
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
        val p = pendingPromise
        pendingPromise = null
        pendingOptions = null
        p?.reject(
          "SCANNER_INIT_FAILED",
          e.message ?: "Failed to initialize ML Kit scanner",
          e,
        )
      }
  }

  override fun onActivityResult(
    activity: Activity,
    requestCode: Int,
    resultCode: Int,
    data: Intent?,
  ) {
    when (requestCode) {
      SCAN_REQUEST_CODE -> handleCameraResult(resultCode, data)
      GALLERY_REQUEST_CODE -> handleGalleryResult(resultCode, data)
    }
  }

  private fun handleCameraResult(
    resultCode: Int,
    data: Intent?,
  ) {
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

    val pages = GmsDocumentScanningResult.fromActivityResultIntent(data)?.pages ?: emptyList()

    executor.execute {
      try {
        val ocrProcessor =
          if (scanOptions.ocr) OcrProcessor(reactApplicationContext) else null

        val imageResults =
          pages.map { page ->
            val processed =
              imageProcessor.process(
                page.imageUri,
                scanOptions.quality,
                scanOptions.includeExif,
                scanOptions.includeGpsExif,
                synthesizeDeviceInfo = true,
              )
            val ocrText =
              ocrProcessor?.let { ocr ->
                try {
                  ocr.recognize(Uri.fromFile(processed.file))
                } catch (_: Exception) {
                  null
                }
              }
            ResultBuilder.buildImage(
              file = processed.file,
              width = processed.width,
              height = processed.height,
              ocrText = ocrText,
              exifData = processed.exifData,
              imageOrigin = "camera",
            )
          }

        ocrProcessor?.close()
        promise.resolve(ResultBuilder.buildSuccess(imageResults))
      } catch (e: Exception) {
        promise.reject("PROCESSING_FAILED", e.message ?: "Image processing failed", e)
      }
    }
  }

  private fun handleGalleryResult(
    resultCode: Int,
    data: Intent?,
  ) {
    val promise = pendingPromise ?: return
    val scanOptions = pendingOptions ?: return
    pendingPromise = null
    pendingOptions = null

    if (resultCode == Activity.RESULT_CANCELED || data == null) {
      promise.resolve(ResultBuilder.buildCancelled())
      return
    }

    val originalUriStr = data.getStringExtra(CropEditorActivity.EXTRA_ORIGINAL_URI)
    val corners = data.getFloatArrayExtra(CropEditorActivity.EXTRA_CORNERS)
    if (resultCode != Activity.RESULT_OK || originalUriStr == null || corners == null) {
      promise.resolve(ResultBuilder.buildCancelled())
      return
    }

    val originalUri = Uri.parse(originalUriStr)
    executor.execute {
      try {
        val processed =
          imageProcessor.processGallery(
            originalUri,
            corners,
            scanOptions.quality,
            scanOptions.includeExif,
            scanOptions.includeGpsExif,
          )
        val imageOrigin = imageProcessor.inferOrigin(originalUri, processed.exifData)

        val ocrText =
          if (scanOptions.ocr) {
            val ocr = OcrProcessor(reactApplicationContext)
            try {
              ocr.recognize(Uri.fromFile(processed.file))
            } catch (_: Exception) {
              null
            } finally {
              ocr.close()
            }
          } else {
            null
          }

        promise.resolve(
          ResultBuilder.buildSuccess(
            listOf(
              ResultBuilder.buildImage(
                file = processed.file,
                width = processed.width,
                height = processed.height,
                ocrText = ocrText,
                exifData = processed.exifData,
                imageOrigin = imageOrigin,
              ),
            ),
          ),
        )
      } catch (e: Exception) {
        promise.reject("PROCESSING_FAILED", e.message ?: "Gallery processing failed", e)
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
    private const val GALLERY_REQUEST_CODE = 0x9002
  }
}
