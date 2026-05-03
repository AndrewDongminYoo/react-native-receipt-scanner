package com.receiptscanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    val exifData = if (includeExif) readExif(sourceUri, includeGpsExif) else null

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
        if (exif.getLatLong(latLon)) Pair(latLon[0].toDouble(), latLon[1].toDouble()) else null
      } else {
        null
      }

    // GmsDocumentScanner outputs are re-encoded JPEGs with no original EXIF.
    // Fall back to device identifiers so the fields are not silently empty.
    val isFileUri = sourceUri.scheme != "content"
    val make =
      exif.getAttribute(ExifInterface.TAG_MAKE)
        ?: if (isFileUri) Build.MANUFACTURER else null
    val model =
      exif.getAttribute(ExifInterface.TAG_MODEL)
        ?: if (isFileUri) Build.MODEL else null
    val dateTime =
      exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
        ?: if (isFileUri) SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date()) else null

    return ExifData(
      orientation = rawOrientation.takeIf { it != ExifInterface.ORIENTATION_UNDEFINED },
      dateTimeOriginal = dateTime,
      make = make,
      model = model,
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
