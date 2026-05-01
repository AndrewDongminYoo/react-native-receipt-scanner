package com.receiptscanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

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
  ): ProcessedImage {
    val sourcePath = requireNotNull(sourceUri.path) { "URI has no path: $sourceUri" }

    val bitmap =
      requireNotNull(BitmapFactory.decodeFile(sourcePath)) {
        "Failed to decode image: $sourcePath"
      }
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

    val exifData = if (includeExif) readExif(sourcePath, includeGpsExif) else null

    return ProcessedImage(outFile, width, height, exifData)
  }

  private fun readExif(
    filePath: String,
    includeGps: Boolean,
  ): ExifData {
    val exif = ExifInterface(filePath)

    val rawOrientation =
      exif.getAttributeInt(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.ORIENTATION_UNDEFINED,
      )

    val gps =
      if (includeGps) {
        val latLon = FloatArray(2)
        if (exif.getLatLong(latLon)) Pair(latLon[0].toDouble(), latLon[1].toDouble()) else null
      } else {
        null
      }

    return ExifData(
      orientation = rawOrientation.takeIf { it != ExifInterface.ORIENTATION_UNDEFINED },
      dateTimeOriginal = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
      make = exif.getAttribute(ExifInterface.TAG_MAKE),
      model = exif.getAttribute(ExifInterface.TAG_MODEL),
      gpsLatitude = gps?.first,
      gpsLongitude = gps?.second,
    )
  }

  /** Delete JPEG files written to cacheDir by a previous scan() session. */
  fun deletePreviousSessionFiles() {
    context.cacheDir
      .listFiles { file -> file.name.startsWith("receipt_") && file.name.endsWith(".jpg") }
      ?.forEach { it.delete() }
  }
}
