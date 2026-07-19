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
          if (scanOptions.ocr) OcrProcessor() else null

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
      val ocrProcessor = if (scanOptions.ocr) OcrProcessor() else null
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
    return if (refreshed != null) {
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
