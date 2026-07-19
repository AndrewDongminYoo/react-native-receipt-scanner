package com.receiptscanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

/**
 * Image post-processing utility used by both the camera and gallery paths.
 *
 * Responsibilities:
 *  - Decode the source URI (content scheme or file path).
 *  - Recompress to JPEG at the caller's quality target.
 *  - Read EXIF metadata (white-list + optional raw passthrough) and normalize
 *    orientation in the output.
 *  - Apply perspective correction from a four-corner quad (gallery path).
 *  - Rotate the output file in place when [OcrProcessor] detects rotated content.
 *  - Heuristically infer [imageOrigin][ResultBuilder.buildImage] from
 *    `MediaStore` + EXIF signals.
 *
 * All public methods write outputs to `context.cacheDir` using the
 * `receipt_*.jpg` naming scheme — see [deletePreviousSessionFiles] for the
 * temp file lifecycle.
 *
 * @param context Used for `cacheDir` and `contentResolver` access. The
 *                `ReactApplicationContext` is fine — we don't outlive it.
 */
class ImageProcessor(
  private val context: Context,
) {
  /**
   * Normalized EXIF white-list extracted from the source image. Values map
   * 1:1 to `ReceiptExif` in `src/types.ts`.
   *
   * `orientation` is the *output* orientation (always [ExifInterface.ORIENTATION_NORMAL]
   * after processing); the original source value is forwarded under [raw]
   * when raw passthrough is enabled.
   */
  data class ExifData(
    val orientation: Int?,
    val dateTime: String?,
    val dateTimeOriginal: String?,
    val dateTimeDigitized: String?,
    val make: String?,
    val model: String?,
    val software: String?,
    val exposureTime: Double?,
    val fNumber: Double?,
    val iso: Int?,
    val focalLength: Double?,
    val flash: Int?,
    val whiteBalance: Int?,
    val exposureMode: Int?,
    val exposureProgram: Int?,
    val meteringMode: Int?,
    val colorSpace: Int?,
    val lightSource: Int?,
    val exifVersion: String?,
    val gpsLatitude: Double?,
    val gpsLongitude: Double?,
    val gpsAltitude: Double?,
    val gpsTimestamp: String?,
    val gpsSpeed: Double?,
    val gpsHeading: Double?,
    /** Flat raw EXIF map (when includeRawExif is true). Keys are EXIF tag names. */
    val raw: Map<String, String>?,
  )

  /**
   * Result of [process] / [processGallery]. Owns a JPEG written to
   * `context.cacheDir`; callers do not need to delete it explicitly —
   * [deletePreviousSessionFiles] sweeps the directory at the start of
   * the next scan.
   *
   * @property file The cached JPEG file.
   * @property width Pixel width of the encoded image.
   * @property height Pixel height of the encoded image.
   * @property exifData Parsed EXIF, or `null` when `includeExif` was `false`.
   */
  data class ProcessedImage(
    val file: File,
    val width: Int,
    val height: Int,
    val exifData: ExifData?,
  )

  /**
   * Processes a camera-path image — decodes [sourceUri], recompresses at
   * [quality], and optionally extracts EXIF.
   *
   * @param sourceUri URI returned by the GMS Document Scanner (content://
   *                  or file://).
   * @param quality JPEG quality in `[0.0, 1.0]`, scaled to `1..100` for
   *                [Bitmap.compress].
   * @param includeExif When `true`, populates [ProcessedImage.exifData].
   * @param includeGpsExif When `true`, includes the GPS dictionary.
   * @param includeRawExif When `true`, attaches the flat raw EXIF map.
   * @param synthesizeDeviceInfo When `true`, falls back to [Build.MANUFACTURER]
   *        / [Build.MODEL] / current time when the source EXIF is empty —
   *        appropriate for camera captures (GMS strips original EXIF), not
   *        for gallery imports.
   * @return The cached [ProcessedImage]. Throws on decode / write failure.
   */
  fun process(
    sourceUri: Uri,
    quality: Double,
    includeExif: Boolean,
    includeGpsExif: Boolean,
    includeRawExif: Boolean = false,
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
      if (includeExif) {
        readExif(sourceUri, includeGpsExif, includeRawExif, synthesizeDeviceInfo)
      } else {
        null
      }

    return ProcessedImage(outFile, width, height, exifData)
  }

  /**
   * Writes the structured EXIF tags from [exifData] onto the output JPEG at
   * [file]. Output pixels are always upright, so orientation is
   * written as `NORMAL` — matching the iOS output-EXIF invariant. Null fields
   * are skipped; the flat `raw` map and GPS speed/heading/timestamp are not
   * written back (v1 scope). GPS lat/lng/altitude are written only when present
   * (already gated by `includeGpsExif` at read time).
   *
   * MUST run after the final JPEG compression (i.e. after any
   * [rotateFileInPlace]); a later re-compress would strip these tags.
   */
  fun writeExifToFile(
    file: File,
    exifData: ExifData,
  ) {
    try {
      val exif = ExifInterface(file.absolutePath)
      exif.setAttribute(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.ORIENTATION_NORMAL.toString(),
      )
      exifData.make?.let { exif.setAttribute(ExifInterface.TAG_MAKE, it) }
      exifData.model?.let { exif.setAttribute(ExifInterface.TAG_MODEL, it) }
      exifData.software?.let { exif.setAttribute(ExifInterface.TAG_SOFTWARE, it) }
      exifData.dateTime?.let { exif.setAttribute(ExifInterface.TAG_DATETIME, it) }
      exifData.dateTimeOriginal?.let {
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, it)
      }
      exifData.dateTimeDigitized?.let {
        exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, it)
      }
      val lat = exifData.gpsLatitude
      val lng = exifData.gpsLongitude
      if (lat != null && lng != null) exif.setLatLong(lat, lng)
      exifData.gpsAltitude?.let { exif.setAltitude(it) }
      exif.saveAttributes()
    } catch (e: Exception) {
      Log.w(LOG_TAG, "Failed to write EXIF to ${file.name}: ${e.message}")
    }
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

  private fun emptyExifData(): ExifData =
    ExifData(
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
    )

  private fun readExif(
    sourceUri: Uri,
    includeGps: Boolean,
    includeRawExif: Boolean,
    synthesizeDeviceInfo: Boolean,
  ): ExifData {
    val exif =
      if (sourceUri.scheme == "content") {
        context.contentResolver.openInputStream(sourceUri)?.use { stream ->
          ExifInterface(stream)
        } ?: return emptyExifData()
      } else {
        val path = sourceUri.path ?: return emptyExifData()
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
        @Suppress("DEPRECATION")
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
    val dateTimeOriginal =
      exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
        ?: if (synthesizeDeviceInfo) {
          SimpleDateFormat(
            "yyyy:MM:dd HH:mm:ss",
            Locale.US,
          ).format(Date())
        } else {
          null
        }

    val software = exif.getAttribute(ExifInterface.TAG_SOFTWARE)

    return ExifData(
      orientation = rawOrientation.takeIf { it != ExifInterface.ORIENTATION_UNDEFINED },
      dateTime = exif.getAttribute(ExifInterface.TAG_DATETIME),
      dateTimeOriginal = dateTimeOriginal,
      dateTimeDigitized = exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED),
      make = make,
      model = model,
      software = software,
      exposureTime = exif.getAttributeDoubleOrNull(ExifInterface.TAG_EXPOSURE_TIME),
      fNumber = exif.getAttributeDoubleOrNull(ExifInterface.TAG_F_NUMBER),
      iso =
        exif.getAttributeIntOrNull(ExifInterface.TAG_ISO_SPEED)
          ?: exif.getAttributeIntOrNull(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY),
      focalLength = exif.getAttributeDoubleOrNull(ExifInterface.TAG_FOCAL_LENGTH),
      flash = exif.getAttributeIntOrNull(ExifInterface.TAG_FLASH),
      whiteBalance = exif.getAttributeIntOrNull(ExifInterface.TAG_WHITE_BALANCE),
      exposureMode = exif.getAttributeIntOrNull(ExifInterface.TAG_EXPOSURE_MODE),
      exposureProgram = exif.getAttributeIntOrNull(ExifInterface.TAG_EXPOSURE_PROGRAM),
      meteringMode = exif.getAttributeIntOrNull(ExifInterface.TAG_METERING_MODE),
      colorSpace = exif.getAttributeIntOrNull(ExifInterface.TAG_COLOR_SPACE),
      lightSource = exif.getAttributeIntOrNull(ExifInterface.TAG_LIGHT_SOURCE),
      exifVersion = exif.getAttribute(ExifInterface.TAG_EXIF_VERSION),
      gpsLatitude = gps?.first,
      gpsLongitude = gps?.second,
      gpsAltitude =
        if (includeGps) {
          exif.getAltitudeOrNull()
        } else {
          null
        },
      gpsTimestamp =
        if (includeGps) {
          exif.getAttribute(ExifInterface.TAG_GPS_TIMESTAMP)
        } else {
          null
        },
      gpsSpeed =
        if (includeGps) {
          exif.getAttributeDoubleOrNull(ExifInterface.TAG_GPS_SPEED)
        } else {
          null
        },
      gpsHeading =
        if (includeGps) {
          exif.getAttributeDoubleOrNull(ExifInterface.TAG_GPS_IMG_DIRECTION)
            ?: exif.getAttributeDoubleOrNull(ExifInterface.TAG_GPS_DEST_BEARING)
        } else {
          null
        },
      raw = if (includeRawExif) buildRawExifMap(exif, includeGps) else null,
    )
  }

  private fun ExifInterface.getAttributeDoubleOrNull(tag: String): Double? {
    val sentinel = Double.NaN
    val value = this.getAttributeDouble(tag, sentinel)
    return if (value.isNaN()) null else value
  }

  private fun ExifInterface.getAttributeIntOrNull(tag: String): Int? {
    val sentinel = Int.MIN_VALUE
    val value = this.getAttributeInt(tag, sentinel)
    return if (value == sentinel) null else value
  }

  private fun ExifInterface.getAltitudeOrNull(): Double? {
    val sentinel = Double.NaN
    val value = this.getAltitude(sentinel)
    return if (value.isNaN()) null else value
  }

  /**
   * Build a flat map of every ExifInterface TAG_* attribute that has a non-null value.
   * Keys are the standard EXIF tag names (e.g. "Make", "FNumber", "GPSLatitude"); values
   * are forwarded as the raw string ExifInterface returns. Binary fields (Thumbnail*,
   * MakerNote, UserComment) and bridge-incompatible types are excluded. GPS-prefixed
   * tags are skipped entirely when [includeGps] is false.
   */
  private fun buildRawExifMap(
    exif: ExifInterface,
    includeGps: Boolean,
  ): Map<String, String> {
    val raw = LinkedHashMap<String, String>(rawTagNames.size)
    for (tag in rawTagNames) {
      if (!includeGps && tag.startsWith("GPS")) continue
      if (rawTagDenyList.contains(tag)) continue
      val value = exif.getAttribute(tag) ?: continue
      raw[tag] = value
    }
    return raw
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
    includeRawExif: Boolean = false,
  ): ProcessedImage {
    val exifOrientation = readExifOrientation(originalUri)
    val (raw, sample) = decodeBitmapSampled(context, originalUri, GALLERY_MAX_DIM)
    val oriented = applyExifRotation(raw, exifOrientation)
    // corners arrive in full-resolution oriented space (CropEditorActivity's
    // originalWidth/Height); scale by 1/sample to match the decoded bitmap.
    val scaledCorners =
      if (sample == 1) {
        corners
      } else {
        val factor = 1f / sample.toFloat()
        FloatArray(corners.size) { i -> corners[i] * factor }
      }
    val corrected = perspectiveCorrectedBitmap(oriented, scaledCorners)
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

    // Pixels are already rotation-corrected; report orientation as NORMAL so callers
    // don't apply a second rotation. Mirrors iOS RNImageProcessor which always writes
    // kCGImagePropertyOrientationUp (1) in the output.
    val exifData =
      if (includeExif) {
        readExif(originalUri, includeGpsExif, includeRawExif, false)
          .copy(orientation = ExifInterface.ORIENTATION_NORMAL)
      } else {
        null
      }
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

  /**
   * Rotate the JPEG file in place by [degrees] (0 / 90 / 180 / 270 CCW from
   * the autoRotate detector) and return the new (width, height). The file is
   * re-encoded at [quality]; EXIF is not written by this path on Android
   * (see ADR-005), consistent with the rest of `ImageProcessor`.
   */
  fun rotateFileInPlace(
    file: File,
    degrees: Int,
    quality: Double,
  ): Pair<Int, Int> {
    if (degrees == 0) {
      val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeFile(file.absolutePath, opts)
      return Pair(opts.outWidth, opts.outHeight)
    }
    val src = BitmapFactory.decodeFile(file.absolutePath) ?: return Pair(0, 0)
    val matrix = android.graphics.Matrix().apply { postRotate(degrees.toFloat()) }
    val rotated =
      Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    if (rotated !== src) src.recycle()
    FileOutputStream(file).use { out ->
      rotated.compress(
        Bitmap.CompressFormat.JPEG,
        (quality * 100).toInt().coerceIn(1, 100),
        out,
      )
    }
    val w = rotated.width
    val h = rotated.height
    rotated.recycle()
    return Pair(w, h)
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
    if (QuadGeometry.isDistorted(corners)) {
      return boundingBoxCrop(bitmap, corners)
    }
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

    val output = createBitmap(outW, outH)
    val canvas = Canvas(output)
    canvas.drawBitmap(bitmap, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
    return output
  }

  // Distorted quad → crop the axis-aligned bounding box of the corners instead of
  // warping. Undistorted, with some extra background. See quad-distortion-backstop.md.
  private fun boundingBoxCrop(
    bitmap: Bitmap,
    corners: FloatArray,
  ): Bitmap {
    val xs = floatArrayOf(corners[0], corners[2], corners[4], corners[6])
    val ys = floatArrayOf(corners[1], corners[3], corners[5], corners[7])
    val left = xs.min().toInt().coerceIn(0, bitmap.width - 1)
    val top = ys.min().toInt().coerceIn(0, bitmap.height - 1)
    val right = xs.max().toInt().coerceIn(left + 1, bitmap.width)
    val bottom = ys.max().toInt().coerceIn(top + 1, bitmap.height)
    val cropped = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
    // createBitmap returns the SAME instance when an immutable source is cropped in full
    // (left=top=0, full width/height). perspectiveCorrectedBitmap's contract is that the
    // return is always a new bitmap the caller can recycle independently of [bitmap], so
    // copy in that case — otherwise the caller's oriented.recycle() also recycles this.
    return if (cropped === bitmap) {
      cropped.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)
    } else {
      cropped
    }
  }

  companion object {
    private const val LOG_TAG = "ReceiptScanner.Image"

    /**
     * Longer-side cap (in pixels) used by [processGallery]'s sampled decode.
     * At ARGB_8888 the bitmap itself is ≤ 36MB; [applyExifRotation] briefly
     * doubles that. Lower than 4096 because applyExifRotation's transient peak
     * (~72MB) is still risky on 2GB-RAM devices; raising it costs visible OCR
     * accuracy ceiling on Korean receipts only above ~3000 px.
     */
    private const val GALLERY_MAX_DIM = 3072

    /** EXIF tag values whose payload is binary or large enough to bloat the IPC bridge. */
    private val rawTagDenyList: Set<String> =
      setOf(
        // Thumbnail blob and its inner offsets/lengths
        "JPEGInterchangeFormat",
        "JPEGInterchangeFormatLength",
        "ThumbnailImageWidth",
        "ThumbnailImageLength",
        "ThumbnailImage",
        // Free-form binary fields
        "MakerNote",
        "UserComment",
      )

    /**
     * All standard EXIF tag *names* exposed by ExifInterface as TAG_* string constants.
     * Resolved once via reflection on the class so we don't hand-maintain the list.
     */
    private val rawTagNames: List<String> by lazy {
      ExifInterface::class
        .java
        .declaredFields
        .asSequence()
        .filter { f ->
          f.name.startsWith("TAG_") &&
            java.lang.reflect.Modifier
              .isStatic(f.modifiers) &&
            f.type == String::class.java
        }.mapNotNull { f ->
          try {
            f.isAccessible = true
            f.get(null) as? String
          } catch (_: Exception) {
            null
          }
        }.toList()
    }

    /**
     * Decode [uri] with `inSampleSize` chosen so the result's longer side fits within [maxDim].
     * Returns the bitmap plus the power-of-two `inSampleSize` applied. Coordinates computed
     * against the full-resolution source must be scaled by `1 / sample` to map into the
     * decoded bitmap's space.
     *
     * Modern phone cameras emit 50-200MP JPEGs; full-resolution decode allocates 200-800MB
     * ARGB_8888, and [applyExifRotation] allocates the same again for any non-NORMAL EXIF
     * orientation. Capping the longer side keeps peak memory bounded against OOM/ANR on
     * batch gallery scans.
     */
    internal fun decodeBitmapSampled(
      context: Context,
      uri: Uri,
      maxDim: Int,
    ): Pair<Bitmap, Int> {
      val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      if (uri.scheme == "content") {
        openContentStream(context, uri).use {
          BitmapFactory.decodeStream(it, null, boundsOpts)
        }
      } else {
        val path = requireNotNull(uri.path) { "URI has no path: $uri" }
        BitmapFactory.decodeFile(path, boundsOpts)
      }

      var sample = 1
      var w = boundsOpts.outWidth
      var h = boundsOpts.outHeight
      while (w > maxDim || h > maxDim) {
        sample *= 2
        w /= 2
        h /= 2
      }

      val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
      val bitmap =
        if (uri.scheme == "content") {
          openContentStream(context, uri).use {
            BitmapFactory.decodeStream(it, null, decodeOpts)
          }
        } else {
          BitmapFactory.decodeFile(requireNotNull(uri.path), decodeOpts)
        } ?: throw IllegalArgumentException("Failed to decode image: $uri")
      return Pair(bitmap, sample)
    }

    /**
     * Open a `content://` URI as an [InputStream], falling back to
     * [ContentResolver.openFileDescriptor] when `openInputStream` returns null.
     *
     * The Photo Picker provider (`com.android.providers.media.photopicker`) is
     * known to graceful-null on `openInputStream` for some URIs even when the
     * underlying file is readable through the FD-based path. Trying both before
     * giving up avoids a silent `RESULT_CANCELED` cycle in the gallery flow.
     */
    private fun openContentStream(
      context: Context,
      uri: Uri,
    ): InputStream {
      val cr = context.contentResolver
      cr.openInputStream(uri)?.let { return it }
      Log.w(
        LOG_TAG,
        "openInputStream returned null; falling back to openFileDescriptor uri=$uri",
      )
      val pfd =
        cr.openFileDescriptor(uri, "r")
          ?: throw IOException("Cannot open content URI: $uri (mimeType=${cr.getType(uri)})")
      return ParcelFileDescriptor.AutoCloseInputStream(pfd)
    }

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
