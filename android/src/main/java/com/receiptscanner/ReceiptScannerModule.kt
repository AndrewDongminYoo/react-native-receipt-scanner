package com.receiptscanner

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import com.facebook.react.bridge.ActivityEventListener
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReadableMap
import com.google.mlkit.vision.documentscanner.GmsDocumentScanner
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import java.io.File
import java.util.concurrent.Executors

/**
 * Android TurboModule entry for the `ReceiptScanner` package.
 *
 * Routes `source: "camera"` to the GMS Document Scanner via
 * `startIntentSenderForResult`, and `source: "gallery"` to
 * [CropEditorActivity]. Both paths funnel through [ImageProcessor] for
 * recompression / EXIF handling and optionally [OcrProcessor] for text
 * recognition + rotation detection. All heavy work runs on a single-thread
 * [Executors.newSingleThreadExecutor]; the React thread only holds the
 * `pendingPromise` while the native UI is on screen.
 *
 * @see com.receiptscanner.ImageProcessor
 * @see com.receiptscanner.OcrProcessor
 * @see com.receiptscanner.CropEditorActivity
 */
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
      reactApplicationContext.getCurrentActivity() ?: run {
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
        Intent(activity, CropEditorActivity::class.java).apply {
          putExtra(CropEditorActivity.EXTRA_MAX_IMAGES, scanOptions.maxPages)
        },
        GALLERY_REQUEST_CODE,
      )
      return
    }

    // Camera path: GmsDocumentScanner
    val scannerOptions =
      GmsDocumentScannerOptions
        .Builder()
        // Disable the in-camera "import from gallery" affordance: those imports
        // go through GMS (EXIF stripped, imageOrigin collapses to "unknown").
        // The explicit source:"gallery" -> CropEditorActivity path is the only
        // gallery route. See ADR-005.
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

        val message = e.message ?: "Failed to initialize ML Kit scanner"
        val userFriendlyMessage =
          if (message.contains("GmsNetworkStack") || message.contains("AuthPII")) {
            "Google Play Services network error. Please check your internet connection, Google account, and device date/time settings."
          } else {
            message
          }

        p?.reject(
          "SCANNER_INIT_FAILED",
          userFriendlyMessage,
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

    val scanningResult =
      try {
        GmsDocumentScanningResult.fromActivityResultIntent(data)
      } catch (e: Exception) {
        promise.reject("SCAN_RESULT_ERROR", "Failed to parse scanning result: ${e.message}", e)
        return
      }

    val pages = scanningResult?.pages ?: emptyList()

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
                includeRawExif = scanOptions.includeRawExif,
                synthesizeDeviceInfo = true,
              )
            val ocr = runOcr(ocrProcessor, processed.file)
            val rotatedDims =
              applyAutoRotateIfNeeded(
                processed.file,
                ocr?.rotationDegrees ?: 0,
                scanOptions.autoRotate,
                scanOptions.quality,
              )
            val finalDims = rotatedDims ?: Pair(processed.width, processed.height)
            // Write the parsed EXIF back onto the final JPEG (after any
            // autoRotate re-compress) so server-side file readers see it on
            // Android too — parity with iOS.
            processed.exifData?.let { imageProcessor.writeExifToFile(processed.file, it) }
            ResultBuilder.buildImage(
              file = processed.file,
              width = finalDims.first,
              height = finalDims.second,
              ocrText = ocr?.text,
              exifData = processed.exifData,
              imageOrigin = "camera",
              confidence = ocr?.confidence?.toDouble(),
              ocrLines =
                ocrLinesFor(scanOptions, ocr, processed.width, processed.height, rotatedDims),
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

    Log.i(
      "ReceiptScanner.Gallery",
      "handleGalleryResult resultCode=$resultCode dataNull=${data == null}",
    )

    if (resultCode == Activity.RESULT_CANCELED || data == null) {
      promise.resolve(ResultBuilder.buildCancelled())
      return
    }

    val originalUriStrs = data.getStringArrayExtra(CropEditorActivity.EXTRA_ORIGINAL_URIS)
    val allCorners = data.getFloatArrayExtra(CropEditorActivity.EXTRA_ALL_CORNERS)
    Log.i(
      "ReceiptScanner.Gallery",
      "handleGalleryResult uris=${originalUriStrs?.size} corners=${allCorners?.size}",
    )
    if (resultCode != Activity.RESULT_OK || originalUriStrs.isNullOrEmpty() || allCorners == null) {
      promise.resolve(ResultBuilder.buildCancelled())
      return
    }

    executor.execute {
      val ocrProcessor = if (scanOptions.ocr) OcrProcessor(reactApplicationContext) else null
      try {
        val imageResults =
          originalUriStrs.mapIndexed { i, uriStr ->
            val originalUri = uriStr.toUri()
            val corners = allCorners.copyOfRange(i * CropEditorActivity.CORNERS_PER_IMAGE, (i + 1) * CropEditorActivity.CORNERS_PER_IMAGE)
            val processed =
              imageProcessor.processGallery(
                originalUri,
                corners,
                scanOptions.quality,
                scanOptions.includeExif,
                scanOptions.includeGpsExif,
                includeRawExif = scanOptions.includeRawExif,
              )
            val imageOrigin = imageProcessor.inferOrigin(originalUri, processed.exifData)
            val ocr = runOcr(ocrProcessor, processed.file)
            val rotatedDims =
              applyAutoRotateIfNeeded(
                processed.file,
                ocr?.rotationDegrees ?: 0,
                scanOptions.autoRotate,
                scanOptions.quality,
              )
            val finalDims = rotatedDims ?: Pair(processed.width, processed.height)
            // Write the parsed EXIF back onto the final JPEG (after any
            // autoRotate re-compress) so server-side file readers see it on
            // Android too — parity with iOS.
            processed.exifData?.let { imageProcessor.writeExifToFile(processed.file, it) }
            ResultBuilder.buildImage(
              file = processed.file,
              width = finalDims.first,
              height = finalDims.second,
              ocrText = ocr?.text,
              exifData = processed.exifData,
              imageOrigin = imageOrigin,
              confidence = ocr?.confidence?.toDouble(),
              ocrLines =
                ocrLinesFor(scanOptions, ocr, processed.width, processed.height, rotatedDims),
            )
          }

        promise.resolve(ResultBuilder.buildSuccess(imageResults))
      } catch (e: OutOfMemoryError) {
        // OOM is Error, not Exception — convert to reject instead of killing the executor.
        promise.reject(
          "OUT_OF_MEMORY",
          "Image too large to process: ${e.message ?: "out of memory"}",
          e,
        )
      } catch (e: Exception) {
        promise.reject("PROCESSING_FAILED", e.message ?: "Gallery processing failed", e)
      } finally {
        ocrProcessor?.close()
      }
    }
  }

  private fun runOcr(
    processor: OcrProcessor?,
    file: File,
  ): OcrProcessor.OcrResult? =
    processor?.let {
      try {
        it.recognizeWithRotationDetection(file)
      } catch (e: Exception) {
        Log.w(NAME, "OCR failed for ${file.name}", e)
        null
      }
    }

  /**
   * When auto-rotate is enabled and OCR detected a non-zero rotation, rotate the
   * output JPEG file in place and return the new `(width, height)`.
   *
   * @return The new pixel dimensions after rotation, or `null` to signal
   *         "no rotation applied; caller should keep the original dimensions".
   *         A `null` return also covers rotation failures (logged, non-fatal).
   */

  /**
   * OCR line boxes expressed in the *output* JPEG's frame, or null when the
   * caller didn't ask for geometry.
   *
   * OCR measures its boxes on the pre-autoRotate JPEG, so whenever the pixels
   * were subsequently turned each box needs the same clockwise turn. A non-null
   * [rotatedDims] is exactly the signal that they were: [applyAutoRotateIfNeeded]
   * returns null for "autoRotate off", "nothing to rotate", and "rotate failed"
   * alike, and in all three the boxes already match the output.
   *
   * Boxes that fall outside the output frame are clamped, and any that clamp to
   * nothing are dropped — see docs/specs/ocr-line-geometry.md.
   */
  private fun ocrLinesFor(
    options: ScanOptions,
    ocr: OcrProcessor.OcrResult?,
    sourceWidth: Int,
    sourceHeight: Int,
    rotatedDims: Pair<Int, Int>?,
  ): List<OcrProcessor.Line>? {
    if (!options.ocrGeometry || ocr == null) return null
    val degrees = if (rotatedDims != null) ocr.rotationDegrees else 0
    val (frameWidth, frameHeight) = rotatedDims ?: Pair(sourceWidth, sourceHeight)
    return ocr.lines.mapNotNull { line ->
      val turned = OcrGeometry.rotateClockwise(line.box, sourceWidth, sourceHeight, degrees)
      OcrGeometry.clamp(turned, frameWidth, frameHeight)?.let { line.copy(box = it) }
    }
  }

  private fun applyAutoRotateIfNeeded(
    file: File,
    rotationDegrees: Int,
    autoRotate: Boolean,
    quality: Double,
  ): Pair<Int, Int>? {
    if (!autoRotate || rotationDegrees == 0) return null
    return try {
      imageProcessor.rotateFileInPlace(file, rotationDegrees, quality)
    } catch (e: Exception) {
      Log.w(NAME, "Auto-rotate failed for ${file.name}", e)
      null
    }
  }

  override fun onNewIntent(intent: Intent) = Unit

  override fun invalidate() {
    super.invalidate()
    executor.shutdown()
    reactApplicationContext.removeActivityEventListener(this)
  }

  companion object {
    /** Module identifier exposed to React Native; must match the JS spec name. */
    const val NAME = NativeReceiptScannerSpec.NAME

    /** `requestCode` for [Activity.startIntentSenderForResult] calls into the GMS scanner. */
    private const val SCAN_REQUEST_CODE = 0x9001

    /** `requestCode` for the [CropEditorActivity] (gallery path) round-trip. */
    private const val GALLERY_REQUEST_CODE = 0x9002
  }
}
