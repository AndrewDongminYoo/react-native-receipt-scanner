package com.receiptscanner

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap
import java.io.File

object ResultBuilder {
  fun buildImage(
    file: File,
    width: Int,
    height: Int,
    ocrText: String?,
    exifData: ImageProcessor.ExifData?,
    imageOrigin: String,
  ): WritableMap =
    Arguments.createMap().apply {
      putString("uri", "file://${file.absolutePath}")
      putInt("width", width)
      putInt("height", height)
      putString("fileName", file.name)
      putString("mimeType", "image/jpeg")
      putDouble("fileSize", file.length().toDouble())
      putString("imageOrigin", imageOrigin)

      if (ocrText != null) putString("ocrText", ocrText)

      if (exifData != null) {
        putMap(
          "exif",
          Arguments.createMap().apply {
            exifData.orientation?.let { putInt("orientation", it) }
            exifData.dateTimeOriginal?.let { putString("dateTimeOriginal", it) }
            exifData.make?.let { putString("make", it) }
            exifData.model?.let { putString("model", it) }

            if (exifData.gpsLatitude != null && exifData.gpsLongitude != null) {
              putMap(
                "gps",
                Arguments.createMap().apply {
                  putDouble("latitude", exifData.gpsLatitude)
                  putDouble("longitude", exifData.gpsLongitude)
                },
              )
            }
          },
        )
      }
    }

  fun buildSuccess(images: List<WritableMap>) =
    Arguments.createMap().apply {
      putString("status", "success")
      putArray(
        "images",
        Arguments.createArray().also { arr ->
          images.forEach { arr.pushMap(it) }
        },
      )
    }

  fun buildCancelled() =
    Arguments.createMap().apply {
      putString("status", "cancelled")
      putArray("images", Arguments.createArray())
    }
}
