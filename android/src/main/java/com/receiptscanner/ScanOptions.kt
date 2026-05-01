package com.receiptscanner

import com.facebook.react.bridge.ReadableMap

data class ScanOptions(
  val source: String,
  val maxPages: Int,
  val quality: Double,
  val includeExif: Boolean,
  val includeGpsExif: Boolean,
  val ocr: Boolean,
) {
  companion object {
    fun from(map: ReadableMap): ScanOptions =
      ScanOptions(
        source = if (map.hasKey("source")) map.getString("source") ?: "camera" else "camera",
        maxPages = if (map.hasKey("maxPages")) map.getInt("maxPages") else 1,
        quality = if (map.hasKey("quality")) map.getDouble("quality") else 0.82,
        includeExif = if (map.hasKey("includeExif")) map.getBoolean("includeExif") else true,
        includeGpsExif = if (map.hasKey("includeGpsExif")) map.getBoolean("includeGpsExif") else false,
        ocr = if (map.hasKey("ocr")) map.getBoolean("ocr") else true,
      )
  }
}
