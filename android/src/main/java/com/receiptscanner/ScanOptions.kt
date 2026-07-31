package com.receiptscanner

import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.ReadableType

/**
 * Typed mirror of the JS-side `ScanReceiptOptions`. Produced by [from] from
 * the raw [ReadableMap] the bridge hands us. Keep the field set in sync
 * with `src/types.ts` — the JS layer fills in defaults via
 * `DEFAULT_SCAN_OPTIONS` *before* dispatch, so missing keys here represent
 * a contract violation rather than a "use the default" signal.
 *
 * "In sync" covers the options this layer acts on, not every field in
 * `src/types.ts`. Options the JS layer consumes itself — `ocrFloor` and
 * `mergeOcrPages` — are deliberately absent: adding them here would move
 * derived-signal logic into native code, which ADR-003 and ADR-008 keep out.
 * [from] reads a fixed key whitelist, so an unmirrored key is inert.
 *
 * @property source Acquisition path: `"camera"` or `"gallery"`.
 * @property maxPages Page / multi-select limit (coerced to `1..10`).
 * @property quality JPEG compression target in `[0.0, 1.0]`.
 * @property includeExif Whether to read and forward source EXIF.
 * @property includeGpsExif Whether to forward the GPS dictionary specifically.
 * @property ocr Whether to run on-device OCR.
 * @property ocrLanguages Ordered BCP 47 tags that select the OCR script.
 * @property autoRotate Whether to bake OCR-detected rotation into output pixels.
 * @property includeRawExif Whether to attach the flat raw EXIF map under `exif.raw`.
 * @property ocrGeometry Whether to attach per-line OCR boxes under `ocrLines`.
 */
data class ScanOptions(
  val source: String,
  val maxPages: Int,
  val quality: Double,
  val includeExif: Boolean,
  val includeGpsExif: Boolean,
  val ocr: Boolean,
  val ocrLanguages: List<String>,
  val autoRotate: Boolean,
  val includeRawExif: Boolean,
  val ocrGeometry: Boolean,
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
            map.getInt("maxPages").coerceIn(1, MAX_PAGES)
          } else {
            1
          },
        quality = if (map.hasKey("quality")) map.getDouble("quality") else 0.82,
        includeExif = if (map.hasKey("includeExif")) map.getBoolean("includeExif") else true,
        includeGpsExif = if (map.hasKey("includeGpsExif")) map.getBoolean("includeGpsExif") else false,
        ocr = if (map.hasKey("ocr")) map.getBoolean("ocr") else true,
        ocrLanguages = ocrLanguagesFrom(map),
        autoRotate = if (map.hasKey("autoRotate")) map.getBoolean("autoRotate") else true,
        includeRawExif = if (map.hasKey("includeRawExif")) map.getBoolean("includeRawExif") else false,
        ocrGeometry = if (map.hasKey("ocrGeometry")) map.getBoolean("ocrGeometry") else false,
      )

    internal const val MAX_PAGES = 10

    private val DEFAULT_OCR_LANGUAGES = listOf("ko-KR", "en-US")

    private fun ocrLanguagesFrom(map: ReadableMap): List<String> {
      if (!map.hasKey("ocrLanguages") || map.getType("ocrLanguages") != ReadableType.Array) {
        return DEFAULT_OCR_LANGUAGES
      }

      val languages = map.getArray("ocrLanguages") ?: return DEFAULT_OCR_LANGUAGES
      return List(languages.size()) { index ->
        if (languages.getType(index) == ReadableType.String) {
          languages.getString(index).orEmpty()
        } else {
          ""
        }
      }
    }
  }
}
