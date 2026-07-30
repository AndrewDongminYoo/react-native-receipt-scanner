package com.receiptscanner

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import com.facebook.react.bridge.ActivityEventListener
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReadableMap
import com.google.mlkit.vision.documentscanner.GmsDocumentScanner
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

internal class PendingScanLifecycle {
  internal class Token

  private var activeToken: Token? = null

  fun tryBegin(): Token? =
    synchronized(this) {
      if (activeToken != null) return@synchronized null
      Token().also { activeToken = it }
    }

  fun isCurrent(token: Token): Boolean = synchronized(this) { activeToken === token }

  val isActive: Boolean
    get() = synchronized(this) { activeToken != null }

  fun current(): Token? = synchronized(this) { activeToken }

  fun complete(
    token: Token,
    terminalAction: () -> Unit,
  ): Boolean =
    synchronized(this) {
      if (activeToken !== token) return@synchronized false
      try {
        terminalAction()
      } finally {
        activeToken = null
      }
      true
    }
}

/**
 * Android TurboModule entry for the `ReceiptScanner` package.
 *
 * Routes `source: "camera"` to the GMS Document Scanner via
 * `startIntentSenderForResult`, and `source: "gallery"` to
 * [CropEditorActivity]. Both paths funnel through [ImageProcessor] for
 * recompression / EXIF handling and optionally [OcrProcessor] for text
 * recognition + rotation detection. All heavy work runs on a single-thread
 * [Executors.newSingleThreadExecutor]; [PendingScanLifecycle] keeps the
 * single-scan guard through native UI, image processing, and Promise settlement.
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
  private val ocrModelManager = OcrModelManager(reactContext)
  private val pendingScanLifecycle = PendingScanLifecycle()

  // Written on the caller's thread in scan(), read from the UI thread in
  // onActivityResult and from the executor in finishPendingScan/invalidate.
  // PendingScanLifecycle's token owns the hand-off; @Volatile only publishes the
  // writes, which happen outside its monitor.
  @Volatile private var pendingPromise: Promise? = null

  @Volatile private var pendingOptions: ScanOptions? = null

  @Volatile private var pendingOcrPreparation: OcrPreparation? = null

  @Volatile private var pendingOcrProcessor: OcrProcessor? = null

  init {
    reactContext.addActivityEventListener(this)
  }

  override fun scan(
    options: ReadableMap,
    promise: Promise,
  ) {
    val token =
      pendingScanLifecycle.tryBegin() ?: run {
        promise.reject("SCAN_IN_PROGRESS", "A scan is already in progress")
        return
      }

    pendingPromise = promise
    val scanOptions =
      try {
        ScanOptions.from(options)
      } catch (e: Exception) {
        rejectPendingScan(token, "SCAN_FAILED", e.message ?: "Invalid scan options", e)
        return
      }
    pendingOptions = scanOptions

    val activity =
      reactApplicationContext.getCurrentActivity() ?: run {
        rejectPendingScan(token, "NO_ACTIVITY", "No foreground activity found")
        return
      }

    executor.execute { imageProcessor.deletePreviousSessionFiles() }

    if (!scanOptions.ocr) {
      launchScan(activity, scanOptions, token)
      return
    }

    val script =
      try {
        OcrLanguageResolver.resolve(scanOptions.ocrLanguages)
      } catch (e: OcrLanguageException) {
        rejectPendingScan(token, e.code, e.message, e)
        return
      }

    val preparation =
      ocrModelManager.prepare(
        script,
        onReady = { processor ->
          if (!pendingScanLifecycle.isCurrent(token)) {
            processor.close()
            return@prepare
          }
          pendingOcrPreparation = null
          pendingOcrProcessor = processor
          launchScan(activity, scanOptions, token)
        },
        onFailure = { error ->
          if (!pendingScanLifecycle.isCurrent(token)) return@prepare
          pendingOcrPreparation = null
          rejectPendingScan(
            token,
            "OCR_MODEL_INSTALL_FAILED",
            error.message ?: "Failed to prepare OCR model",
            error,
          )
        },
      )
    if (pendingScanLifecycle.isCurrent(token) && pendingOcrProcessor == null) {
      pendingOcrPreparation = preparation
    } else {
      preparation.cancel()
    }
  }

  private fun launchScan(
    activity: Activity,
    scanOptions: ScanOptions,
    token: PendingScanLifecycle.Token,
  ) {
    if (scanOptions.source == "gallery") {
      try {
        @Suppress("DEPRECATION")
        activity.startActivityForResult(
          Intent(activity, CropEditorActivity::class.java).apply {
            putExtra(CropEditorActivity.EXTRA_MAX_IMAGES, scanOptions.maxPages)
          },
          GALLERY_REQUEST_CODE,
        )
      } catch (e: Exception) {
        rejectScannerInitialization(token, e)
      }
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

    val scanner: GmsDocumentScanner =
      try {
        GmsDocumentScanning.getClient(scannerOptions)
      } catch (e: Exception) {
        rejectScannerInitialization(token, e)
        return
      }
    scanner
      .getStartScanIntent(activity)
      .addOnSuccessListener { intentSender ->
        if (!pendingScanLifecycle.isCurrent(token)) return@addOnSuccessListener
        try {
          activity.startIntentSenderForResult(intentSender, SCAN_REQUEST_CODE, null, 0, 0, 0)
        } catch (e: Exception) {
          rejectScannerInitialization(token, e)
        }
      }.addOnFailureListener { e ->
        rejectScannerInitialization(token, e)
      }
  }

  private fun rejectScannerInitialization(
    token: PendingScanLifecycle.Token,
    error: Exception,
  ) {
    val message = error.message ?: "Failed to initialize ML Kit scanner"
    val userFriendlyMessage =
      if (message.contains("GmsNetworkStack") || message.contains("AuthPII")) {
        "Google Play Services network error. Please check your internet connection, Google account, and device date/time settings."
      } else {
        message
      }

    rejectPendingScan(token, "SCANNER_INIT_FAILED", userFriendlyMessage, error)
  }

  override fun getOcrCapabilities(promise: Promise) {
    ocrModelManager.capabilities(
      onResult = { states ->
        val modelStateArray =
          Arguments.createArray().apply {
            states.forEach { state ->
              pushMap(
                Arguments.createMap().apply {
                  putString("script", state.script)
                  putString("status", state.status)
                },
              )
            }
          }
        promise.resolve(
          Arguments.createMap().apply {
            putString("platform", "android")
            putArray("defaultLanguages", Arguments.fromList(listOf("ko-KR", "en-US")))
            putArray("models", modelStateArray)
          },
        )
      },
      onFailure = { error ->
        promise.reject(
          "OCR_MODEL_INSTALL_FAILED",
          error.message ?: "Failed to query OCR model availability",
          error,
        )
      },
    )
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
    val token = pendingScanLifecycle.current() ?: return
    if (pendingPromise == null) return
    val scanOptions =
      pendingOptions ?: run {
        finishPendingScan(token)
        return
      }
    val ocrProcessor = takePendingOcrProcessor()

    if (resultCode == Activity.RESULT_CANCELED) {
      resolvePendingScan(token, ResultBuilder.buildCancelled(), ocrProcessor)
      return
    }
    if (resultCode != Activity.RESULT_OK || data == null) {
      rejectPendingScan(token, "SCAN_FAILED", "Unexpected result code: $resultCode", processor = ocrProcessor)
      return
    }

    val scanningResult =
      try {
        GmsDocumentScanningResult.fromActivityResultIntent(data)
      } catch (e: Exception) {
        rejectPendingScan(
          token,
          "SCAN_RESULT_ERROR",
          "Failed to parse scanning result: ${e.message}",
          e,
          ocrProcessor,
        )
        return
      }

    // Do not rely solely on ML Kit honoring its configured page limit.
    val pages = scanningResult?.pages?.take(scanOptions.maxPages) ?: emptyList()

    val processingTask =
      Runnable {
        try {
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
              val outcome = runOcrAndAutoRotate(ocrProcessor, processed.file, scanOptions)
              val ocr = outcome.result
              val finalDims = outcome.rotatedDims ?: Pair(processed.width, processed.height)
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
                  ocrLinesFor(
                    scanOptions,
                    ocr,
                    processed.width,
                    processed.height,
                    outcome.rotatedDims,
                    outcome.remapDegrees,
                  ),
              )
            }

          resolvePendingScan(token, ResultBuilder.buildSuccess(imageResults))
        } catch (e: OutOfMemoryError) {
          // Report under the documented PROCESSING_FAILED code (the public error
          // contract has no OUT_OF_MEMORY); the message still names the cause.
          rejectPendingScan(
            token,
            "PROCESSING_FAILED",
            "Image too large to process: ${e.message ?: "out of memory"}",
            e,
          )
        } catch (e: Exception) {
          rejectPendingScan(token, "PROCESSING_FAILED", e.message ?: "Image processing failed", e)
        } finally {
          ocrProcessor?.close()
        }
      }
    try {
      executor.execute(processingTask)
    } catch (e: RejectedExecutionException) {
      rejectPendingScan(
        token,
        "PROCESSING_FAILED",
        e.message ?: "Image processing was interrupted",
        e,
        ocrProcessor,
      )
    }
  }

  private fun handleGalleryResult(
    resultCode: Int,
    data: Intent?,
  ) {
    val token = pendingScanLifecycle.current() ?: return
    if (pendingPromise == null) return
    val scanOptions =
      pendingOptions ?: run {
        finishPendingScan(token)
        return
      }
    val ocrProcessor = takePendingOcrProcessor()

    Log.i(
      "ReceiptScanner.Gallery",
      "handleGalleryResult resultCode=$resultCode dataNull=${data == null}",
    )

    if (resultCode == Activity.RESULT_CANCELED || data == null) {
      resolvePendingScan(token, ResultBuilder.buildCancelled(), ocrProcessor)
      return
    }

    // The crop editor reports an enforced-limit failure (e.g. oversized image)
    // as RESULT_OK + EXTRA_ERROR so it is not mistaken for a user cancel.
    data.getStringExtra(CropEditorActivity.EXTRA_ERROR)?.let { error ->
      rejectPendingScan(token, "PROCESSING_FAILED", error, processor = ocrProcessor)
      return
    }

    val originalUriStrs = data.getStringArrayExtra(CropEditorActivity.EXTRA_ORIGINAL_URIS)
    val allCorners = data.getFloatArrayExtra(CropEditorActivity.EXTRA_ALL_CORNERS)
    Log.i(
      "ReceiptScanner.Gallery",
      "handleGalleryResult uris=${originalUriStrs?.size} corners=${allCorners?.size}",
    )
    if (resultCode != Activity.RESULT_OK || originalUriStrs.isNullOrEmpty() || allCorners == null) {
      resolvePendingScan(token, ResultBuilder.buildCancelled(), ocrProcessor)
      return
    }

    val processingTask =
      Runnable {
        try {
          val imageResults =
            originalUriStrs.mapIndexed { i, uriStr ->
              val originalUri = uriStr.toUri()
              try {
                val corners =
                  allCorners.copyOfRange(
                    i * CropEditorActivity.CORNERS_PER_IMAGE,
                    (i + 1) * CropEditorActivity.CORNERS_PER_IMAGE,
                  )
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
                val outcome = runOcrAndAutoRotate(ocrProcessor, processed.file, scanOptions)
                val ocr = outcome.result
                val finalDims = outcome.rotatedDims ?: Pair(processed.width, processed.height)
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
                    ocrLinesFor(
                      scanOptions,
                      ocr,
                      processed.width,
                      processed.height,
                      outcome.rotatedDims,
                      outcome.remapDegrees,
                    ),
                )
              } finally {
                originalUri.path?.let { File(it).delete() }
              }
            }

          resolvePendingScan(token, ResultBuilder.buildSuccess(imageResults))
        } catch (e: OutOfMemoryError) {
          // OOM is Error, not Exception — convert to reject instead of killing the executor.
          // Report under the documented PROCESSING_FAILED code (the public error
          // contract has no OUT_OF_MEMORY); the message still names the cause.
          rejectPendingScan(
            token,
            "PROCESSING_FAILED",
            "Image too large to process: ${e.message ?: "out of memory"}",
            e,
          )
        } catch (e: Exception) {
          rejectPendingScan(token, "PROCESSING_FAILED", e.message ?: "Gallery processing failed", e)
        } finally {
          ocrProcessor?.close()
          originalUriStrs.forEach { uri -> uri.toUri().path?.let { File(it).delete() } }
        }
      }
    try {
      executor.execute(processingTask)
    } catch (e: RejectedExecutionException) {
      originalUriStrs.forEach { uri -> uri.toUri().path?.let { File(it).delete() } }
      rejectPendingScan(
        token,
        "PROCESSING_FAILED",
        e.message ?: "Gallery processing was interrupted",
        e,
        ocrProcessor,
      )
    }
  }

  private fun takePendingOcrProcessor(): OcrProcessor? {
    val processor = pendingOcrProcessor
    pendingOcrProcessor = null
    return processor
  }

  private fun finishPendingScan(
    token: PendingScanLifecycle.Token,
    terminalAction: (Promise?) -> Unit = {},
  ) {
    pendingScanLifecycle.complete(token) {
      val promise = pendingPromise
      pendingOcrPreparation?.cancel()
      pendingOcrPreparation = null
      takePendingOcrProcessor()?.close()
      pendingPromise = null
      pendingOptions = null
      terminalAction(promise)
    }
  }

  private fun resolvePendingScan(
    token: PendingScanLifecycle.Token,
    value: Any?,
    processor: OcrProcessor? = null,
  ) {
    finishPendingScan(token) { promise ->
      processor?.close()
      promise?.resolve(value)
    }
  }

  private fun rejectPendingScan(
    token: PendingScanLifecycle.Token,
    code: String,
    message: String?,
    error: Throwable? = null,
    processor: OcrProcessor? = null,
  ) {
    finishPendingScan(token) { promise ->
      processor?.close()
      if (error == null) {
        promise?.reject(code, message)
      } else {
        promise?.reject(code, message, error)
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
   * An OCR result together with the frame bookkeeping needed to place its boxes.
   *
   * The three values are only meaningful as a set — [remapDegrees] depends on
   * which pass [result] came from — so they travel together rather than being
   * recomputed at each call site.
   *
   * @property result The OCR result to report, or null when OCR was off or failed.
   * @property rotatedDims Output dimensions after auto-rotate, or null when the
   *                       pixels were not turned.
   * @property remapDegrees Clockwise rotation still owed to [result]'s line
   *                        boxes; `0` when they already sit in the output frame.
   */
  private data class OcrOutcome(
    val result: OcrProcessor.OcrResult?,
    val rotatedDims: Pair<Int, Int>?,
    val remapDegrees: Int,
  )

  /**
   * Recognize, apply the detected rotation to the file, then recognize again on
   * the rotated file.
   *
   * The second pass is what keeps `ocrText`'s line order matching the image that
   * ships. ML Kit orders lines by their position in the frame it was handed, so
   * text recognized before the rotation reads in the pre-rotation order — on a
   * receipt turned 180° that is bottom-to-top. Re-recognizing also measures the
   * boxes on the output frame directly, which is why the success path owes them
   * no rotation. iOS has always worked this way; see
   * docs/notes/platform-asymmetries.md §4.2.
   *
   * If the second pass cannot decode the file [applyAutoRotateIfNeeded] just
   * wrote, keep the first result and remap its boxes instead: losing the
   * corrected line order is much cheaper than losing all the text.
   */
  private fun runOcrAndAutoRotate(
    processor: OcrProcessor?,
    file: File,
    options: ScanOptions,
  ): OcrOutcome {
    val detected = runOcr(processor, file)
    val rotatedDims =
      applyAutoRotateIfNeeded(
        file,
        detected?.rotationDegrees ?: 0,
        options.autoRotate,
        options.quality,
      )
    if (rotatedDims == null) return OcrOutcome(detected, null, 0)

    val refreshed =
      processor?.let {
        try {
          it.recognizeInFinalFrame(file)
        } catch (e: Exception) {
          Log.w(NAME, "Re-OCR after auto-rotate failed for ${file.name}", e)
          null
        }
      }
    // An empty re-read counts as a failed one. Reaching here means the first
    // pass found enough text to detect a rotation, so "no lines now" says the
    // second pass did not work — not that the receipt is blank. The extra JPEG
    // re-encode can cost that much detail at a low `quality`, and accepting it
    // would hand the JS OcrFloor an empty result for a scan that did recognize.
    // A partial drop is left alone: those lines are what the shipped image
    // genuinely yields, and reporting the pre-rotation count would overstate it.
    // Require real text, not a positive lineCount: lineCount includes blank
    // ML Kit lines, and what ships below is refreshed.text.
    return if (refreshed != null && refreshed.text.isNotBlank()) {
      OcrOutcome(refreshed, rotatedDims, 0)
    } else {
      OcrOutcome(detected, rotatedDims, detected?.rotationDegrees ?: 0)
    }
  }

  /**
   * OCR line boxes expressed in the *output* JPEG's frame, or null when the
   * caller didn't ask for geometry.
   *
   * [remapDegrees] carries the whole decision — see [OcrOutcome]. It is `0` both
   * when nothing was rotated and when the boxes came from a pass that already
   * ran on the rotated file, and non-zero only when boxes measured before the
   * rotation have to be turned to catch up.
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
    remapDegrees: Int,
  ): List<OcrProcessor.Line>? {
    if (!options.ocrGeometry || ocr == null) return null
    val (frameWidth, frameHeight) = rotatedDims ?: Pair(sourceWidth, sourceHeight)
    return ocr.lines.mapNotNull { line ->
      val turned = OcrGeometry.rotateClockwise(line.box, sourceWidth, sourceHeight, remapDegrees)
      OcrGeometry.clamp(turned, frameWidth, frameHeight)?.let { line.copy(box = it) }
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
    pendingScanLifecycle.current()?.let(::finishPendingScan)
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
