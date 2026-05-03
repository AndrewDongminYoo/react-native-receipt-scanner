package com.receiptscanner

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.RectF
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
import androidx.exifinterface.media.ExifInterface

// Launched by ReceiptScannerModule for the gallery+crop flow.
// Opens the system image picker, then shows a quad-crop editor.
// On confirm, returns EXTRA_ORIGINAL_URI (String) and EXTRA_CORNERS (FloatArray[8])
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
    val buttonBarHeight = (64 * dp).toInt()
    val barTotalHeight = separatorHeight + buttonBarHeight

    val root = FrameLayout(this)
    root.setBackgroundColor(Color.BLACK)

    imageView =
      ImageView(this).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
      }
    cropView = QuadCropView(this)

    val contentParams =
      FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply {
        bottomMargin = barTotalHeight
      }
    root.addView(imageView, contentParams)
    root.addView(cropView, contentParams)

    val separator =
      View(this).apply {
        setBackgroundColor(0xFF444444.toInt())
      }

    val buttonBar =
      LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setBackgroundColor(0xFF222222.toInt())
        gravity = Gravity.CENTER_VERTICAL
        setPadding((16 * dp).toInt(), 0, (16 * dp).toInt(), 0)
      }

    val cancelBtn =
      Button(this).apply {
        text = "Cancel"
        setTextColor(0xFFCCCCCC.toInt())
        textSize = 16f
        setOnClickListener { onCancelTapped() }
      }
    val confirmBtn =
      Button(this).apply {
        text = "Confirm"
        setTextColor(0xFF4CAF50.toInt())
        textSize = 16f
        setOnClickListener { onConfirmTapped() }
      }

    val halfWeight = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
    buttonBar.addView(cancelBtn, halfWeight)
    buttonBar.addView(confirmBtn, halfWeight)

    val separatorParams =
      FrameLayout.LayoutParams(MATCH_PARENT, separatorHeight).apply {
        gravity = Gravity.BOTTOM
        bottomMargin = buttonBarHeight
      }
    val barParams =
      FrameLayout.LayoutParams(MATCH_PARENT, buttonBarHeight).apply {
        gravity = Gravity.BOTTOM
      }
    root.addView(separator, separatorParams)
    root.addView(buttonBar, barParams)

    setContentView(root)
  }

  private fun loadAndDisplayImage() {
    val uri = originalUri ?: return
    Thread {
      try {
        // Determine original dimensions without decoding
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, boundsOpts) }

        // Compute a power-of-2 sample size to keep the display bitmap under 2048px
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

        // Auto-rotate to match display orientation
        val exifOrientation = readExifOrientation(uri)
        val oriented = ImageProcessor.applyExifRotation(raw, exifOrientation)

        // Approximate full-res oriented dimensions for corner scaling
        originalWidth = oriented.width * sample
        originalHeight = oriented.height * sample

        runOnUiThread {
          if (isDestroyed || isFinishing) {
            oriented.recycle()
            return@runOnUiThread
          }
          imageView.setImageBitmap(oriented)
          // Wait for the ImageView to be laid out before computing imageRect
          imageView.post {
            val rect =
              computeFitCenterRect(
                oriented.width,
                oriented.height,
                imageView.width,
                imageView.height,
              )
            imageRect = rect
            val ix = rect.width() * 0.05f
            val iy = rect.height() * 0.05f
            cropView.setImageRect(rect.left + ix, rect.top + iy, rect.right - ix, rect.bottom - iy)
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
    val result =
      Intent().apply {
        putExtra(EXTRA_ORIGINAL_URI, uri.toString())
        putExtra(EXTRA_CORNERS, corners)
      }
    setResult(RESULT_OK, result)
    finish()
  }

  private fun onCancelTapped() {
    setResult(RESULT_CANCELED)
    finish()
  }
}
