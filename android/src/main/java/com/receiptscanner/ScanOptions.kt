package com.receiptscanner

import com.facebook.react.bridge.ReadableMap
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions

data class AndroidCameraOptions(
  val allowGalleryImport: Boolean,
  val scannerMode: Int,
) {
  companion object {
    val DEFAULT =
      AndroidCameraOptions(
        allowGalleryImport = false,
        scannerMode = GmsDocumentScannerOptions.SCANNER_MODE_FULL,
      )

    fun from(map: ReadableMap?): AndroidCameraOptions {
      if (map == null) return DEFAULT
      return AndroidCameraOptions(
        allowGalleryImport =
          if (map.hasKey("allowGalleryImport")) map.getBoolean("allowGalleryImport") else false,
        scannerMode =
          when (if (map.hasKey("scannerMode")) map.getString("scannerMode") else null) {
            "base" -> GmsDocumentScannerOptions.SCANNER_MODE_BASE
            else -> GmsDocumentScannerOptions.SCANNER_MODE_FULL
          },
      )
    }
  }
}

data class ScanOptions(
  val source: String,
  val maxPages: Int,
  val quality: Double,
  val includeExif: Boolean,
  val includeGpsExif: Boolean,
  val ocr: Boolean,
  val androidCameraOptions: AndroidCameraOptions,
) {
  companion object {
    fun from(map: ReadableMap): ScanOptions =
      ScanOptions(
        source =
          if (map.hasKey("source")) {
            map.getString("source")
              ?: "camera"
          } else {
            "camera"
          },
        maxPages = if (map.hasKey("maxPages")) map.getInt("maxPages") else 1,
        quality = if (map.hasKey("quality")) map.getDouble("quality") else 0.82,
        includeExif = if (map.hasKey("includeExif")) map.getBoolean("includeExif") else true,
        includeGpsExif = if (map.hasKey("includeGpsExif")) map.getBoolean("includeGpsExif") else false,
        ocr = if (map.hasKey("ocr")) map.getBoolean("ocr") else true,
        androidCameraOptions =
          AndroidCameraOptions.from(
            if (map.hasKey("androidCameraOptions")) map.getMap("androidCameraOptions") else null,
          ),
      )
  }
}
