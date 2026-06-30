package com.receiptscanner

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap
import java.io.File

/**
 * Builds the [WritableMap] payloads that [ReceiptScannerModule] resolves to
 * the JS `Promise`. The shapes here must match `ReceiptImage` and
 * `ScanReceiptResult` in `src/types.ts` exactly — the JS side casts the
 * bridge result without runtime validation.
 */
object ResultBuilder {
  /**
   * Builds the per-image dictionary returned in `ScanReceiptResult.images`.
   *
   * @param file Cached JPEG written by [ImageProcessor]. The URI is built as
   *             `file://<absolutePath>`.
   * @param width Output pixel width (post-rotation, post-crop).
   * @param height Output pixel height (post-rotation, post-crop).
   * @param ocrText Joined OCR text or `null` when OCR didn't run.
   * @param exifData Parsed EXIF or `null` when `includeExif` was `false`.
   * @param imageOrigin One of `"camera"`, `"screenshot"`, `"download"`, `"unknown"`.
   */
  fun buildImage(
    file: File,
    width: Int,
    height: Int,
    ocrText: String?,
    exifData: ImageProcessor.ExifData?,
    imageOrigin: String,
    confidence: Double? = null,
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

      // Mean per-line OCR confidence ([0, 1]); the JS layer reads it as
      // `ocrQuality.confidence` and re-derives textLength/lineCount.
      if (confidence != null) {
        putMap(
          "ocrQuality",
          Arguments.createMap().apply { putDouble("confidence", confidence) },
        )
      }

      if (exifData != null) {
        putMap("exif", buildExifMap(exifData))
      }
    }

  private fun buildExifMap(exifData: ImageProcessor.ExifData): WritableMap =
    Arguments.createMap().apply {
      // ── Image metadata ──
      exifData.orientation?.let { putInt("orientation", it) }
      exifData.colorSpace?.let { putInt("colorSpace", it) }
      exifData.lightSource?.let { putInt("lightSource", it) }
      exifData.exifVersion?.let { putString("exifVersion", it) }

      // ── Device + software ──
      exifData.make?.let { putString("make", it) }
      exifData.model?.let { putString("model", it) }
      exifData.software?.let { putString("software", it) }

      // ── Timestamps ──
      exifData.dateTime?.let { putString("dateTime", it) }
      exifData.dateTimeOriginal?.let { putString("dateTimeOriginal", it) }
      exifData.dateTimeDigitized?.let { putString("dateTimeDigitized", it) }

      // ── Camera settings ──
      exifData.exposureTime?.let { putDouble("exposureTime", it) }
      exifData.fNumber?.let { putDouble("fNumber", it) }
      exifData.iso?.let { putInt("iso", it) }
      exifData.focalLength?.let { putDouble("focalLength", it) }
      exifData.flash?.let { putInt("flash", it) }
      exifData.whiteBalance?.let { putInt("whiteBalance", it) }
      exifData.exposureMode?.let { putInt("exposureMode", it) }
      exifData.exposureProgram?.let { putInt("exposureProgram", it) }
      exifData.meteringMode?.let { putInt("meteringMode", it) }

      // ── GPS ──
      if (exifData.gpsLatitude != null && exifData.gpsLongitude != null) {
        putMap(
          "gps",
          Arguments.createMap().apply {
            putDouble("latitude", exifData.gpsLatitude)
            putDouble("longitude", exifData.gpsLongitude)
            exifData.gpsAltitude?.let { putDouble("altitude", it) }
            exifData.gpsTimestamp?.let { putString("timestamp", it) }
            exifData.gpsSpeed?.let { putDouble("speed", it) }
            exifData.gpsHeading?.let { putDouble("heading", it) }
          },
        )
      }

      // ── Raw passthrough ──
      val raw = exifData.raw
      if (!raw.isNullOrEmpty()) {
        putMap(
          "raw",
          Arguments.createMap().apply {
            for ((key, value) in raw) putString(key, value)
          },
        )
      }
    }

  /** Wraps a list of per-image maps into the `status: "success"` envelope. */
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

  /** Builds the `status: "cancelled"` envelope (user dismissed the flow). */
  fun buildCancelled() =
    Arguments.createMap().apply {
      putString("status", "cancelled")
      putArray("images", Arguments.createArray())
    }
}
