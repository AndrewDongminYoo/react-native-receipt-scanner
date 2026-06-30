package com.receiptscanner

import com.facebook.react.bridge.ReadableMap

/**
 * Typed mirror of the JS-side `ScanReceiptOptions`. Produced by [from] from
 * the raw [ReadableMap] the bridge hands us. Keep the field set in sync
 * with `src/types.ts` — the JS layer fills in defaults via
 * `DEFAULT_SCAN_OPTIONS` *before* dispatch, so missing keys here represent
 * a contract violation rather than a "use the default" signal.
 *
 * @property source Acquisition path: `"camera"` or `"gallery"`.
 * @property maxPages Page / multi-select limit (coerced `>= 1`).
 * @property quality JPEG compression target in `[0.0, 1.0]`.
 * @property includeExif Whether to read and forward source EXIF.
 * @property includeGpsExif Whether to forward the GPS dictionary specifically.
 * @property ocr Whether to run on-device OCR.
 * @property autoRotate Whether to bake OCR-detected rotation into output pixels.
 * @property includeRawExif Whether to attach the flat raw EXIF map under `exif.raw`.
 */
data class ScanOptions(
  val source: String,
  val maxPages: Int,
  val quality: Double,
  val includeExif: Boolean,
  val includeGpsExif: Boolean,
  val ocr: Boolean,
  val autoRotate: Boolean,
  val includeRawExif: Boolean,
) {
  companion object {
    /**
     * Parses a [ReadableMap] from JS into a [ScanOptions]. Each `hasKey`
     * fallback mirrors `DEFAULT_SCAN_OPTIONS` in `src/types.ts` — the values
     * here only matter when the JS layer is bypassed (e.g. tests).
     */
    fun from(map: ReadableMap): ScanOptions =
      ScanOptions(
        source =
          if (map.hasKey("source")) {
            map.getString("source")
              ?: "camera"
          } else {
            "camera"
          },
        maxPages =
          if (map.hasKey("maxPages")) {
            map.getInt("maxPages").coerceAtLeast(1)
          } else {
            1
          },
        quality = if (map.hasKey("quality")) map.getDouble("quality") else 0.82,
        includeExif = if (map.hasKey("includeExif")) map.getBoolean("includeExif") else true,
        includeGpsExif = if (map.hasKey("includeGpsExif")) map.getBoolean("includeGpsExif") else false,
        ocr = if (map.hasKey("ocr")) map.getBoolean("ocr") else true,
        autoRotate = if (map.hasKey("autoRotate")) map.getBoolean("autoRotate") else true,
        includeRawExif = if (map.hasKey("includeRawExif")) map.getBoolean("includeRawExif") else false,
      )
  }
}
