package com.receiptscanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

class ImageProcessor(
  private val context: Context,
) {
  data class ExifData(
    val orientation: Int?,
    val dateTimeOriginal: String?,
    val make: String?,
    val model: String?,
    val gpsLatitude: Double?,
    val gpsLongitude: Double?,
  )

  data class ProcessedImage(
    val file: File,
    val width: Int,
    val height: Int,
    val exifData: ExifData?,
  )

  fun process(
    sourceUri: Uri,
    quality: Double,
    includeExif: Boolean,
    includeGpsExif: Boolean,
    synthesizeDeviceInfo: Boolean = false,
  ): ProcessedImage {
    val bitmap = decodeBitmap(sourceUri)
    val width = bitmap.width
    val height = bitmap.height
    val outFile = File(context.cacheDir, "receipt_${System.currentTimeMillis()}.jpg")
    FileOutputStream(outFile).use { out ->
      bitmap.compress(
        Bitmap.CompressFormat.JPEG,
        (quality * 100).toInt().coerceIn(1, 100),
        out,
      )
    }
    bitmap.recycle()

    val exifData =
      if (includeExif) readExif(sourceUri, includeGpsExif, synthesizeDeviceInfo) else null

    return ProcessedImage(outFile, width, height, exifData)
  }

  private fun decodeBitmap(uri: Uri): Bitmap {
    if (uri.scheme == "content") {
      return context.contentResolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream)
      } ?: throw IllegalArgumentException("Failed to open content stream: $uri")
    }
    val path = requireNotNull(uri.path) { "URI has no path: $uri" }
    return requireNotNull(BitmapFactory.decodeFile(path)) { "Failed to decode image: $path" }
  }

  private fun readExif(
    sourceUri: Uri,
    includeGps: Boolean,
    synthesizeDeviceInfo: Boolean,
  ): ExifData {
    val exif =
      if (sourceUri.scheme == "content") {
        context.contentResolver.openInputStream(sourceUri)?.use { stream ->
          ExifInterface(stream)
        } ?: return ExifData(null, null, null, null, null, null)
      } else {
        val path = sourceUri.path ?: return ExifData(null, null, null, null, null, null)
        ExifInterface(path)
      }

    val rawOrientation =
      exif.getAttributeInt(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.ORIENTATION_UNDEFINED,
      )

    val gps =
      if (includeGps) {
        val latLon = FloatArray(2)
        if (exif.getLatLong(latLon)) {
          Pair(
            latLon[0].toDouble(),
            latLon[1].toDouble(),
          )
        } else {
          null
        }
      } else {
        null
      }

    // GmsDocumentScanner re-encodes pages as new JPEGs, stripping original EXIF.
    // Synthesize device info only for camera-sourced images so gallery images
    // honestly report null rather than inheriting the device's make/model.
    val make =
      exif.getAttribute(ExifInterface.TAG_MAKE)
        ?: if (synthesizeDeviceInfo) Build.MANUFACTURER else null
    val model =
      exif.getAttribute(ExifInterface.TAG_MODEL)
        ?: if (synthesizeDeviceInfo) Build.MODEL else null
    val dateTime =
      exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
        ?: if (synthesizeDeviceInfo) {
          SimpleDateFormat(
            "yyyy:MM:dd HH:mm:ss",
            Locale.US,
          ).format(Date())
        } else {
          null
        }

    return ExifData(
      orientation = rawOrientation.takeIf { it != ExifInterface.ORIENTATION_UNDEFINED },
      dateTimeOriginal = dateTime,
      make = make,
      model = model,
      gpsLatitude = gps?.first,
      gpsLongitude = gps?.second,
    )
  }

  /**
   * Loads the original image at [originalUri], applies the same EXIF rotation used by
   * CropEditorActivity, then applies the perspective transform defined by [corners]
   * ([tl.x, tl.y, tr.x, tr.y, br.x, br.y, bl.x, bl.y] in full-resolution pixels).
   */
  fun processGallery(
    originalUri: Uri,
    corners: FloatArray,
    quality: Double,
    includeExif: Boolean,
    includeGpsExif: Boolean,
  ): ProcessedImage {
    val exifOrientation = readExifOrientation(originalUri)
    val raw = decodeBitmap(originalUri)
    val oriented = applyExifRotation(raw, exifOrientation)
    // raw is recycled inside applyExifRotation if a new bitmap was produced;
    // if no rotation happened, oriented === raw and we recycle via oriented.
    val corrected = perspectiveCorrectedBitmap(oriented, corners)
    oriented.recycle()

    val width = corrected.width
    val height = corrected.height
    val outFile = File(context.cacheDir, "receipt_${System.currentTimeMillis()}.jpg")
    FileOutputStream(outFile).use { out ->
      corrected.compress(
        Bitmap.CompressFormat.JPEG,
        (quality * 100).toInt().coerceIn(1, 100),
        out,
      )
    }
    corrected.recycle()

    val exifData = if (includeExif) readExif(originalUri, includeGpsExif, false) else null
    return ProcessedImage(outFile, width, height, exifData)
  }

  /**
   * Infers imageOrigin from MediaStore bucket name, with EXIF heuristic as fallback.
   * Never returns "download" — gallery images without camera metadata are "unknown"
   * unless the bucket name explicitly matches "download/downloads".
   */
  fun inferOrigin(
    uri: Uri,
    exifData: ExifData?,
  ): String {
    if (uri.scheme == "content") {
      try {
        context.contentResolver
          .query(
            uri,
            arrayOf(MediaStore.Images.Media.BUCKET_DISPLAY_NAME),
            null,
            null,
            null,
          )?.use { cursor ->
            if (cursor.moveToFirst()) {
              when (cursor.getString(0)?.lowercase()) {
                "camera" -> return "camera"
                "screenshots", "screenshot" -> return "screenshot"
                "download", "downloads" -> return "download"
              }
            }
          }
      } catch (_: Exception) {
      }
    }
    if (exifData?.dateTimeOriginal != null) return "camera"
    if (exifData?.make != null && exifData.model != null) return "camera"
    return "unknown"
  }

  /** Delete JPEG files written to cacheDir by a previous scan() session. */
  fun deletePreviousSessionFiles() {
    context.cacheDir
      .listFiles { file -> file.name.startsWith("receipt_") && file.name.endsWith(".jpg") }
      ?.forEach { it.delete() }
  }

  // Reads EXIF TAG_ORIENTATION without decoding the full bitmap.
  private fun readExifOrientation(uri: Uri): Int =
    try {
      val exif =
        if (uri.scheme == "content") {
          context.contentResolver.openInputStream(uri)?.use { ExifInterface(it) }
        } else {
          uri.path?.let { ExifInterface(it) }
        }
      exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        ?: ExifInterface.ORIENTATION_NORMAL
    } catch (_: Exception) {
      ExifInterface.ORIENTATION_NORMAL
    }

  // Applies a perspective warp from the source quad to a canonical output rectangle.
  // Caller must recycle the returned bitmap. Does not recycle [bitmap].
  private fun perspectiveCorrectedBitmap(
    bitmap: Bitmap,
    corners: FloatArray,
  ): Bitmap {
    require(corners.size == 8) { "corners must have 8 elements" }
    val tlX = corners[0]
    val tlY = corners[1]
    val trX = corners[2]
    val trY = corners[3]
    val brX = corners[4]
    val brY = corners[5]
    val blX = corners[6]
    val blY = corners[7]

    fun dist(
      ax: Float,
      ay: Float,
      bx: Float,
      by: Float,
    ) = sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay))

    val topW = dist(tlX, tlY, trX, trY)
    val botW = dist(blX, blY, brX, brY)
    val leftH = dist(tlX, tlY, blX, blY)
    val rightH = dist(trX, trY, brX, brY)

    val outW = ((topW + botW) / 2f).toInt().coerceAtLeast(1)
    val outH = ((leftH + rightH) / 2f).toInt().coerceAtLeast(1)

    val dst =
      floatArrayOf(
        0f,
        0f,
        outW.toFloat(),
        0f,
        outW.toFloat(),
        outH.toFloat(),
        0f,
        outH.toFloat(),
      )

    val matrix = Matrix()
    matrix.setPolyToPoly(corners, 0, dst, 0, 4)

    val output = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)
    canvas.drawBitmap(bitmap, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
    return output
  }

  companion object {
    /**
     * Rotates [bitmap] according to [exifOrientation] (an ExifInterface.ORIENTATION_* constant).
     * Recycles [bitmap] and returns the rotated copy if rotation is needed; otherwise returns
     * [bitmap] as-is (no recycle). Caller owns the returned bitmap.
     */
    internal fun applyExifRotation(
      bitmap: Bitmap,
      exifOrientation: Int,
    ): Bitmap {
      val matrix = Matrix()
      when (exifOrientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> {
          matrix.postRotate(90f)
        }

        ExifInterface.ORIENTATION_ROTATE_180 -> {
          matrix.postRotate(180f)
        }

        ExifInterface.ORIENTATION_ROTATE_270 -> {
          matrix.postRotate(270f)
        }

        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> {
          matrix.postScale(-1f, 1f)
        }

        ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
          matrix.postScale(1f, -1f)
        }

        ExifInterface.ORIENTATION_TRANSPOSE -> {
          matrix.postScale(-1f, 1f)
          matrix.postRotate(90f)
        }

        ExifInterface.ORIENTATION_TRANSVERSE -> {
          matrix.postScale(-1f, 1f)
          matrix.postRotate(270f)
        }

        else -> {
          return bitmap
        }
      }
      val rotated =
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
      bitmap.recycle()
      return rotated
    }
  }
}
