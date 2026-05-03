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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

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
    val buttonBarHeight = (64 * dp).toInt()

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
    val bottomContainer =
      LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(0xFF222222.toInt())
      }

    val separatorView = View(this).apply { setBackgroundColor(0xFF444444.toInt()) }

    val buttonBar =
      LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding((16 * dp).toInt(), 0, (16 * dp).toInt(), 0)
      }

    val cancelBtn =
      Button(this).apply {
        text = context.getString(R.string.RNReceiptScanner_cancelButton)
        setTextColor(0xFFCCCCCC.toInt())
        textSize = 17f
        setOnClickListener { onCancelTapped() }
      }
    val confirmBtn =
      Button(this).apply {
        text = context.getString(R.string.RNReceiptScanner_confirmButton)
        setTextColor(0xFF4CAF50.toInt())
        textSize = 17f
        setOnClickListener { onConfirmTapped() }
      }

    val halfWeight = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
    buttonBar.addView(cancelBtn, halfWeight)
    buttonBar.addView(confirmBtn, halfWeight)

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
            val ix = rect.width() * 0.05f
            val iy = rect.height() * 0.05f
            cropView.setImageRect(
              rect.left + ix,
              rect.top + iy,
              rect.right - ix,
              rect.bottom - iy,
            )
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
