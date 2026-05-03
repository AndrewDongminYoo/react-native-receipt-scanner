package com.receiptscanner

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.atan2

// Launched by ReceiptScannerModule for the gallery+crop flow.
// Opens the system image picker, then shows a quad-crop editor.
// On confirm, copies the picker URI to cache (picker_get_content permission expires on finish),
// then returns EXTRA_ORIGINAL_URI (file:// String) and EXTRA_CORNERS (FloatArray[8])
// where corners are [tl.x, tl.y, tr.x, tr.y, br.x, br.y, bl.x, bl.y] in full-res pixels.
internal class CropEditorActivity : Activity() {
  companion object {
    const val EXTRA_ORIGINAL_URI = "original_uri"
    const val EXTRA_CORNERS = "corners"
    private const val PICK_REQUEST_CODE = 0x9003
  }

  private lateinit var imageView: ImageView
  private lateinit var cropView: QuadCropView

  private var originalUri: Uri? = null
  private var originalWidth: Int = 0
  private var originalHeight: Int = 0
  private var imageRect: RectF = RectF()

  @Suppress("DEPRECATION")
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.navigationBarColor = Color.BLACK
    window.statusBarColor = Color.BLACK
    val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }
    startActivityForResult(intent, PICK_REQUEST_CODE)
  }

  @Deprecated("Deprecated in API 33")
  override fun onActivityResult(
    requestCode: Int,
    resultCode: Int,
    data: Intent?,
  ) {
    @Suppress("DEPRECATION")
    super.onActivityResult(requestCode, resultCode, data)
    if (requestCode != PICK_REQUEST_CODE) return
    if (resultCode != RESULT_OK || data?.data == null) {
      setResult(RESULT_CANCELED)
      finish()
      return
    }
    originalUri = data.data
    buildCropUI()
    loadAndDisplayImage()
  }

  @Deprecated("Deprecated in API 33")
  override fun onBackPressed() {
    @Suppress("DEPRECATION")
    super.onBackPressed()
    setResult(RESULT_CANCELED)
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
    val confirmBtn =
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

    root.addView(
      bottomContainer,
      FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { gravity = Gravity.BOTTOM },
    )

    setContentView(root)

    ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
      val navBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
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
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver
          .openInputStream(uri)
          ?.use { BitmapFactory.decodeStream(it, null, boundsOpts) }

        val maxDim = 2048
        var sample = 1
        var w = boundsOpts.outWidth
        var h = boundsOpts.outHeight
        while (w > maxDim || h > maxDim) {
          sample *= 2
          w /= 2
          h /= 2
        }

        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw =
          contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOpts)
          } ?: run {
            runOnUiThread {
              setResult(RESULT_CANCELED)
              finish()
            }
            return@Thread
          }

        val exifOrientation = readExifOrientation(uri)
        val oriented = ImageProcessor.applyExifRotation(raw, exifOrientation)

        originalWidth = oriented.width * sample
        originalHeight = oriented.height * sample

        runOnUiThread {
          if (isDestroyed || isFinishing) {
            oriented.recycle()
            return@runOnUiThread
          }
          imageView.setImageBitmap(oriented)
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
            // 10% inset as default fallback — matches iOS RNCropEditorViewController d=0.1
            val ix = rect.width() * 0.1f
            val iy = rect.height() * 0.1f
            cropView.setCorners(
              PointF(rect.left + ix, rect.top + iy),
              PointF(rect.right - ix, rect.top + iy),
              PointF(rect.right - ix, rect.bottom - iy),
              PointF(rect.left + ix, rect.bottom - iy),
            )
            detectCornersFromText(oriented)
          }
        }
      } catch (_: Exception) {
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

    return arrayOf(toDisplay(resolved[0]), toDisplay(resolved[1]), toDisplay(resolved[2]), toDisplay(resolved[3]))
  }

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
    } catch (_: Exception) {
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
    Thread {
      val cachedFile = File(cacheDir, "receipt_pick_${System.currentTimeMillis()}.jpg")
      try {
        contentResolver.openInputStream(uri)?.use { input ->
          FileOutputStream(cachedFile).use { output -> input.copyTo(output) }
        } ?: run {
          runOnUiThread {
            setResult(RESULT_CANCELED)
            finish()
          }
          return@Thread
        }
      } catch (_: Exception) {
        runOnUiThread {
          setResult(RESULT_CANCELED)
          finish()
        }
        return@Thread
      }
      val result =
        Intent().apply {
          putExtra(EXTRA_ORIGINAL_URI, Uri.fromFile(cachedFile).toString())
          putExtra(EXTRA_CORNERS, corners)
        }
      runOnUiThread {
        setResult(RESULT_OK, result)
        finish()
      }
    }.start()
  }

  private fun onCancelTapped() {
    setResult(RESULT_CANCELED)
    finish()
  }
}
