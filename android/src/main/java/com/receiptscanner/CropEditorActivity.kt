package com.receiptscanner

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.math.PI
import kotlin.math.atan2

/**
 * Launched by ReceiptScannerModule for the gallery+crop flow.
 * Opens the system image picker (single or multi-select), then shows a quad-crop editor
 * for each selected image sequentially. On final confirm, returns:
 *   EXTRA_ORIGINAL_URIS — StringArray of file:// URIs (cached copies, one per image)
 *   EXTRA_ALL_CORNERS   — FloatArray[8×N] where each 8-element block is
 *                         [tl.x, tl.y, tr.x, tr.y, br.x, br.y, bl.x, bl.y] in full-res pixels.
 */
internal class CropEditorActivity : ComponentActivity() {
  companion object {
    /** Result extra: `String[]` of `file://` URIs (cached copies, one per image). */
    const val EXTRA_ORIGINAL_URIS = "original_uris"

    /**
     * Result extra: `FloatArray[CORNERS_PER_IMAGE * N]` where each
     * 8-element block is `[tl.x, tl.y, tr.x, tr.y, br.x, br.y, bl.x, bl.y]`
     * in full-resolution pixels.
     */
    const val EXTRA_ALL_CORNERS = "all_corners"

    /** Input extra: `Int` upper bound on multi-select count. Coerced to `>= 1`. */
    const val EXTRA_MAX_IMAGES = "max_images"

    /** Number of `Float`s per image in [EXTRA_ALL_CORNERS] (4 corners × 2 axes). */
    const val CORNERS_PER_IMAGE = 8

    /** Logcat tag for the gallery flow. Filter with `adb logcat -s ReceiptScanner.Gallery`. */
    private const val LOG_TAG = "ReceiptScanner.Gallery"

    /** 10% inset on each side — matches iOS `RNCropEditorViewController` `d=0.1`. */
    private const val DEFAULT_INSET_FRACTION = 0.1f

    /** Expands detected text-block corners so the initial crop accepts document background. */
    private const val DETECTED_CROP_EXPANSION_FACTOR = 1.12f

    /**
     * Longer-side cap (in pixels) for the on-screen preview decode. Lower than
     * `ImageProcessor.GALLERY_MAX_DIM` because the editor only needs a display-
     * resolution bitmap; the final crop re-decodes at the larger cap.
     */
    private const val PREVIEW_MAX_DIM = 2048
  }

  private lateinit var imageView: ImageView
  private lateinit var cropView: QuadCropView
  private lateinit var confirmBtn: Button
  private lateinit var pickImagesLauncher: ActivityResultLauncher<PickVisualMediaRequest>

  // Sequential crop state machine
  private val pendingUris = ArrayDeque<Uri>()
  private val processedOriginalUris = mutableListOf<String>()
  private val processedCorners = mutableListOf<FloatArray>()
  private var hasBuiltUI = false
  private var totalImageCount = 0
  private var requestedMaxImages = 1
  private var galleryPickerLaunched = false

  // Current image state
  private var originalUri: Uri? = null
  private var originalWidth: Int = 0
  private var originalHeight: Int = 0
  private var imageRect: RectF = RectF()
  private var displayedBitmap: Bitmap? = null

  @Suppress("DEPRECATION")
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.navigationBarColor = Color.BLACK
    window.statusBarColor = Color.BLACK
    requestedMaxImages = intent.getIntExtra(EXTRA_MAX_IMAGES, 1).coerceAtLeast(1)
    pickImagesLauncher =
      if (requestedMaxImages == 1) {
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
          handlePickedUris(if (uri != null) listOf(uri) else emptyList())
        }
      } else {
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(requestedMaxImages)) { uris ->
          handlePickedUris(uris)
        }
      }
    Log.i(
      LOG_TAG,
      "onCreate maxImages=$requestedMaxImages sdk=${Build.VERSION.SDK_INT} contract=PickMultipleVisualMedia",
    )
  }

  override fun onPostResume() {
    super.onPostResume()
    if (!galleryPickerLaunched) {
      galleryPickerLaunched = true
      launchGalleryPicker()
    }
  }

  private fun launchGalleryPicker() {
    Log.i(LOG_TAG, "launchGalleryPicker maxImages=$requestedMaxImages")
    pickImagesLauncher.launch(
      PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
    )
  }

  private fun handlePickedUris(uris: List<Uri>) {
    Log.i(
      LOG_TAG,
      "picker result uris=${uris.size}",
    )

    if (uris.isEmpty()) {
      Log.i(LOG_TAG, "picker cancelled or returned no URIs; finishing with RESULT_CANCELED")
      setResult(RESULT_CANCELED)
      finish()
      return
    }

    Log.i(LOG_TAG, "picker parsed uris=${uris.size}")
    totalImageCount = uris.size
    pendingUris.addAll(uris)
    loadNextImage()
  }

  @Deprecated("Deprecated in API 33")
  override fun onBackPressed() {
    setResult(RESULT_CANCELED)
    finish()
  }

  // Dequeues the next URI and advances the crop editor. When the queue is empty,
  // all images have been confirmed and results are returned to the caller.
  private fun loadNextImage() {
    val uri =
      pendingUris.removeFirstOrNull() ?: run {
        returnAllResults()
        return
      }
    Log.i(
      LOG_TAG,
      "loadNextImage index=${totalImageCount - pendingUris.size}/$totalImageCount uri=$uri",
    )
    originalUri = uri

    if (!hasBuiltUI) {
      buildCropUI()
      hasBuiltUI = true
    }

    // Show progress in the confirm button when multiple images are queued
    val doneIndex = totalImageCount - pendingUris.size
    confirmBtn.text =
      if (pendingUris.isNotEmpty()) {
        "$doneIndex / $totalImageCount ›"
      } else {
        getString(R.string.RNReceiptScanner_confirmButton)
      }

    // Clear prior manual adjustments and reset corners to the inset default so
    // the previous image's selection doesn't remain visible while the next image loads.
    cropView.resetUserAdjusted()
    if (imageRect.width() > 0) applyDefaultInsetCorners(imageRect)

    loadAndDisplayImage()
  }

  private fun applyDefaultInsetCorners(rect: RectF) {
    val ix = rect.width() * DEFAULT_INSET_FRACTION
    val iy = rect.height() * DEFAULT_INSET_FRACTION
    cropView.setCorners(
      PointF(rect.left + ix, rect.top + iy),
      PointF(rect.right - ix, rect.top + iy),
      PointF(rect.right - ix, rect.bottom - iy),
      PointF(rect.left + ix, rect.bottom - iy),
    )
  }

  private fun buildCropUI() {
    val dp = resources.displayMetrics.density
    val separatorHeight = (1 * dp).toInt().coerceAtLeast(1)
    val buttonBarHeight = (50 * dp).toInt() // matches iOS buttonBar.heightAnchor = 50

    val root = FrameLayout(this)
    root.setBackgroundColor(Color.BLACK)

    imageView = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    cropView = QuadCropView(this)

    val imageParams =
      FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply {
        bottomMargin = separatorHeight + buttonBarHeight
      }
    val cropParams =
      FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply {
        bottomMargin = separatorHeight + buttonBarHeight
      }
    root.addView(imageView, imageParams)
    root.addView(cropView, cropParams)

    val instructionView =
      TextView(this).apply {
        text = context.getString(R.string.RNReceiptScanner_cropInstruction)
        setTextColor(Color.WHITE)
        textSize = 15f
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        maxLines = 2
        isClickable = false
        isFocusable = false
        setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
        background =
          GradientDrawable().apply {
            setColor(0x9E000000.toInt())
            cornerRadius = 8 * dp
          }
      }

    // Wrap separator + button bar so nav-bar insets can be applied as a single paddingBottom
    // Background matches iOS colorWithWhite:0.12 alpha:1.0 (0.12 * 255 ≈ 31 = 0x1F)
    val bottomContainer =
      LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(0xFF1F1F1F.toInt())
      }

    val separatorView = View(this).apply { setBackgroundColor(0xFF444444.toInt()) }

    // RelativeLayout mirrors iOS: cancel pinned to leading +20, confirm pinned to trailing -20
    val cancelId = View.generateViewId()
    val confirmId = View.generateViewId()
    val buttonBar = RelativeLayout(this)

    val margin20 = (20 * dp).toInt()
    val cancelBtn =
      Button(this).apply {
        id = cancelId
        text = context.getString(R.string.RNReceiptScanner_cancelButton)
        setTextColor(0xFF007AFF.toInt())
        textSize = 17f
        background = null
        setOnClickListener { onCancelTapped() }
      }
    confirmBtn =
      Button(this).apply {
        id = confirmId
        text = context.getString(R.string.RNReceiptScanner_confirmButton)
        setTextColor(0xFF007AFF.toInt())
        textSize = 17f
        setTypeface(typeface, Typeface.BOLD)
        background = null
        setOnClickListener { onConfirmTapped() }
      }

    val cancelParams =
      RelativeLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
        addRule(RelativeLayout.ALIGN_PARENT_START)
        addRule(RelativeLayout.CENTER_VERTICAL)
        marginStart = margin20
      }
    val confirmParams =
      RelativeLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
        addRule(RelativeLayout.ALIGN_PARENT_END)
        addRule(RelativeLayout.CENTER_VERTICAL)
        marginEnd = margin20
      }
    buttonBar.addView(cancelBtn, cancelParams)
    buttonBar.addView(confirmBtn, confirmParams)

    bottomContainer.addView(
      separatorView,
      LinearLayout.LayoutParams(MATCH_PARENT, separatorHeight),
    )
    bottomContainer.addView(buttonBar, LinearLayout.LayoutParams(MATCH_PARENT, buttonBarHeight))

    val instructionParams =
      FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        topMargin = (12 * dp).toInt()
        marginStart = (20 * dp).toInt()
        marginEnd = (20 * dp).toInt()
      }
    root.addView(instructionView, instructionParams)

    root.addView(
      bottomContainer,
      FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { gravity = Gravity.BOTTOM },
    )

    setContentView(root)

    ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
      val statusTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
      val navBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
      instructionParams.topMargin = (12 * dp).toInt() + statusTop
      instructionView.requestLayout()
      bottomContainer.setPadding(0, 0, 0, navBottom)
      val totalBottom = separatorHeight + buttonBarHeight + navBottom
      imageParams.bottomMargin = totalBottom
      cropParams.bottomMargin = totalBottom
      imageView.requestLayout()
      cropView.requestLayout()
      insets
    }
  }

  private fun loadAndDisplayImage() {
    val uri = originalUri ?: return
    Thread {
      try {
        val (raw, sample) = ImageProcessor.decodeBitmapSampled(this, uri, PREVIEW_MAX_DIM)
        val exifOrientation = readExifOrientation(uri)
        val oriented = ImageProcessor.applyExifRotation(raw, exifOrientation)

        originalWidth = oriented.width * sample
        originalHeight = oriented.height * sample

        runOnUiThread {
          if (isDestroyed || isFinishing) {
            oriented.recycle()
            return@runOnUiThread
          }
          // Release the previous image's display bitmap before showing the next one
          val prev = displayedBitmap
          imageView.setImageBitmap(oriented)
          displayedBitmap = oriented
          prev?.recycle()

          imageView.post {
            val rect =
              computeFitCenterRect(
                oriented.width,
                oriented.height,
                imageView.width,
                imageView.height,
              )
            imageRect = rect
            cropView.setImageBounds(rect.left, rect.top, rect.right, rect.bottom)
            applyDefaultInsetCorners(rect)
            detectCornersFromText(oriented)
          }
        }
      } catch (e: Exception) {
        Log.e(LOG_TAG, "loadAndDisplayImage failed for uri=$uri", e)
        runOnUiThread {
          setResult(RESULT_CANCELED)
          finish()
        }
      }
    }.start()
  }

  // Runs ML Kit text recognition on the display bitmap to find document corners.
  // Updates cropView.setCorners() with the detected quad on success.
  // Falls back silently to the current 10% inset if detection fails or finds too little text.
  // Reuses the Korean recognizer (same artifact OcrProcessor uses) — it covers Latin too,
  // and adding text-recognition (Latin) as a separate dep would inflate APK size.
  private fun detectCornersFromText(bitmap: Bitmap) {
    val recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    recognizer
      .process(InputImage.fromBitmap(bitmap, 0))
      .addOnSuccessListener { result ->
        if (!isDestroyed && !isFinishing) {
          val corners = quadFromTextBlocks(result, bitmap.width, bitmap.height)
          if (corners != null) {
            cropView.setCorners(corners[0], corners[1], corners[2], corners[3])
          }
        }
        recognizer.close()
      }.addOnFailureListener {
        recognizer.close()
      }
  }

  // Derives an approximate document quadrilateral from ML Kit TextBlock cornerPoints.
  // Algorithm: collect all text corner points, find the furthest point in each of the four
  // angular sectors from the centroid (TL/TR/BR/BL), then map back to display coordinates.
  // Returns null if there are fewer than 2 text blocks or any sector has no coverage.
  private fun quadFromTextBlocks(
    result: Text,
    bitmapWidth: Int,
    bitmapHeight: Int,
  ): Array<PointF>? {
    val pts = mutableListOf<PointF>()
    for (block in result.textBlocks) {
      block.cornerPoints?.forEach { pts.add(PointF(it.x.toFloat(), it.y.toFloat())) }
    }
    if (pts.size < 8) return null // < 2 text blocks — not enough to infer document boundary

    val cx = pts.sumOf { it.x.toDouble() }.toFloat() / pts.size
    val cy = pts.sumOf { it.y.toDouble() }.toFloat() / pts.size

    // In image coords (y-down), atan2 sectors map to document corners:
    //   TL: angle ∈ (-π, -π/2)  TR: ∈ [-π/2, 0)  BR: ∈ [0, π/2)  BL: ∈ [π/2, π]
    val best = arrayOfNulls<PointF>(4)
    val bestDist = FloatArray(4)
    val halfPi = (PI / 2).toFloat()
    for (pt in pts) {
      val dx = pt.x - cx
      val dy = pt.y - cy
      val angle = atan2(dy, dx)
      val dist = dx * dx + dy * dy
      val sector =
        when {
          // TL
          angle < -halfPi -> 0

          // TR
          angle < 0f -> 1

          // BR
          angle < halfPi -> 2

          // BL
          else -> 3
        }
      if (dist > bestDist[sector]) {
        bestDist[sector] = dist
        best[sector] = pt
      }
    }

    val resolved = best.filterNotNull()
    if (resolved.size != 4) return null

    // Map text-recognition bitmap coords → display coords via imageRect
    val scaleX = imageRect.width() / bitmapWidth
    val scaleY = imageRect.height() / bitmapHeight

    fun toDisplay(pt: PointF) = PointF(imageRect.left + pt.x * scaleX, imageRect.top + pt.y * scaleY)

    val quad =
      expandedDetectedCorners(
        arrayOf(toDisplay(resolved[0]), toDisplay(resolved[1]), toDisplay(resolved[2]), toDisplay(resolved[3])),
      )
    val flat =
      floatArrayOf(
        quad[0].x,
        quad[0].y,
        quad[1].x,
        quad[1].y,
        quad[2].x,
        quad[2].y,
        quad[3].x,
        quad[3].y,
      )
    // Distorted detected quad → discard so the editor keeps its 10% inset default.
    if (QuadGeometry.isDistorted(flat)) return null
    return quad
  }

  private fun expandedDetectedCorners(corners: Array<PointF>): Array<PointF> {
    val centerX = corners.sumOf { it.x.toDouble() }.toFloat() / corners.size
    val centerY = corners.sumOf { it.y.toDouble() }.toFloat() / corners.size

    return Array(corners.size) { index ->
      val point = corners[index]
      PointF(
        centerX + (point.x - centerX) * DETECTED_CROP_EXPANSION_FACTOR,
        centerY + (point.y - centerY) * DETECTED_CROP_EXPANSION_FACTOR,
      ).clampedToImageRect()
    }
  }

  private fun PointF.clampedToImageRect(): PointF =
    PointF(
      x.coerceIn(imageRect.left, imageRect.right),
      y.coerceIn(imageRect.top, imageRect.bottom),
    )

  private fun readExifOrientation(uri: Uri): Int =
    try {
      val exif =
        if (uri.scheme == "content") {
          contentResolver.openInputStream(uri)?.use { ExifInterface(it) }
        } else {
          uri.path?.let { ExifInterface(it) }
        }
      exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        ?: ExifInterface.ORIENTATION_NORMAL
    } catch (e: Exception) {
      Log.w(LOG_TAG, "readExifOrientation failed for uri=$uri; falling back to ORIENTATION_NORMAL", e)
      ExifInterface.ORIENTATION_NORMAL
    }

  private fun computeFitCenterRect(
    bitmapW: Int,
    bitmapH: Int,
    viewW: Int,
    viewH: Int,
  ): RectF {
    if (viewW <= 0 || viewH <= 0) return RectF(0f, 0f, viewW.toFloat(), viewH.toFloat())
    val scale = minOf(viewW.toFloat() / bitmapW, viewH.toFloat() / bitmapH)
    val scaledW = bitmapW * scale
    val scaledH = bitmapH * scale
    val left = (viewW - scaledW) / 2f
    val top = (viewH - scaledH) / 2f
    return RectF(left, top, left + scaledW, top + scaledH)
  }

  private fun onConfirmTapped() {
    val uri = originalUri ?: return
    val corners =
      cropView.getCornersInImageSpace(
        imageRect.left,
        imageRect.top,
        imageRect.width(),
        imageRect.height(),
        originalWidth,
        originalHeight,
      )
    // picker_get_content URI permission expires when this activity finishes.
    // Copy the bytes to cache now so ImageProcessor can read via file:// after finish.
    // Index suffix prevents collision when multiple images are confirmed in rapid succession.
    Thread {
      val index = processedOriginalUris.size
      val cachedFile = File(cacheDir, "receipt_pick_${System.currentTimeMillis()}_$index.jpg")
      try {
        openPickedUriInputStream(uri).use { input ->
          FileOutputStream(cachedFile).use { output -> input.copyTo(output) }
        }
      } catch (e: Exception) {
        Log.e(LOG_TAG, "onConfirmTapped: failed to copy picker URI to cache uri=$uri", e)
        runOnUiThread {
          setResult(RESULT_CANCELED)
          finish()
        }
        return@Thread
      }
      processedOriginalUris.add(Uri.fromFile(cachedFile).toString())
      processedCorners.add(corners)
      runOnUiThread { loadNextImage() }
    }.start()
  }

  private fun openPickedUriInputStream(uri: Uri): InputStream {
    if (uri.scheme != "content") {
      val path = requireNotNull(uri.path) { "URI has no path: $uri" }
      return File(path).inputStream()
    }

    contentResolver.openInputStream(uri)?.let { return it }
    Log.w(
      LOG_TAG,
      "openInputStream returned null; falling back to openFileDescriptor uri=$uri",
    )
    val pfd =
      contentResolver.openFileDescriptor(uri, "r")
        ?: throw IllegalArgumentException("Cannot open picker URI: $uri (mimeType=${contentResolver.getType(uri)})")
    return ParcelFileDescriptor.AutoCloseInputStream(pfd)
  }

  private fun returnAllResults() {
    // Pack all per-image corners into a flat FloatArray[CORNERS_PER_IMAGE×N]
    val allCorners = FloatArray(processedCorners.size * CORNERS_PER_IMAGE)
    processedCorners.forEachIndexed { i, c -> c.copyInto(allCorners, i * CORNERS_PER_IMAGE) }
    Log.i(
      LOG_TAG,
      "returnAllResults images=${processedOriginalUris.size} corners=${allCorners.size}",
    )
    val result =
      Intent().apply {
        putExtra(EXTRA_ORIGINAL_URIS, processedOriginalUris.toTypedArray())
        putExtra(EXTRA_ALL_CORNERS, allCorners)
      }
    setResult(RESULT_OK, result)
    finish()
  }

  private fun onCancelTapped() {
    setResult(RESULT_CANCELED)
    finish()
  }

  override fun onDestroy() {
    super.onDestroy()
    Log.i(LOG_TAG, "onDestroy isFinishing=$isFinishing isChangingConfigurations=$isChangingConfigurations")
    if (::imageView.isInitialized) imageView.setImageDrawable(null)
    displayedBitmap?.recycle()
    displayedBitmap = null
  }
}
