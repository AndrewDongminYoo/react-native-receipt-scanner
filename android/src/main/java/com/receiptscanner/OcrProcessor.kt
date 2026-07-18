package com.receiptscanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import java.io.File
import java.util.Locale

/**
 * Wraps ML Kit's Korean text recogniser plus a single-pass content-rotation
 * heuristic. ML Kit Korean covers Latin too, so we don't ship a separate
 * Latin recogniser — see ADR-006 for the language strategy.
 *
 * Lifecycle: callers must invoke [close] to release the underlying ML Kit
 * client. [ReceiptScannerModule] does this once per scan, after iterating
 * all pages.
 *
 * Threading: every public method blocks on [Tasks.await] and **must** be
 * called from a background thread.
 *
 * @param context Used by [recognize]'s `InputImage.fromFilePath` overload.
 */
class OcrProcessor(
  private val context: Context,
) {
  private val recognizer =
    TextRecognition.getClient(
      KoreanTextRecognizerOptions.Builder().build(),
    )

  /**
   * One recognised line with the box it occupies, in the coordinates of the
   * image handed to [recognizeWithRotationDetection] — i.e. the processed JPEG
   * *before* any [ImageProcessor.rotateFileInPlace] auto-rotate. Callers that
   * rotate the pixels afterwards must remap via [OcrGeometry.rotateClockwise].
   *
   * @property confidence ML Kit's per-line confidence, or null when it reported NaN.
   */
  data class Line(
    val text: String,
    val box: OcrGeometry.Box,
    val confidence: Float?,
  )

  /**
   * Result of [recognizeWithRotationDetection].
   *
   * @property text Joined OCR text (newline-separated lines) for the chosen rotation.
   * @property rotationDegrees Detected rotation in degrees (0 / 90 / 180 / 270).
   *                           Caller is expected to bake this into the output pixels.
   * @property lineCount Number of recognised lines — used by the JS-side
   *                     `OcrFloor` gate.
   * @property confidence Mean per-line OCR confidence in [0, 1], or null when
   *                      no line reported a finite value. Reporting only.
   * @property lines Per-line geometry, always collected; the module only
   *                 serialises it when `options.ocrGeometry` is set.
   */
  data class OcrResult(
    val text: String,
    val rotationDegrees: Int,
    val lineCount: Int,
    val confidence: Float? = null,
    val lines: List<Line> = emptyList(),
  )

  /**
   * Internal aggregate for one recognition pass. `lineAspect` is the trimmed
   * mean width/height of recognised line bounding boxes — see [lineAspectOf].
   */
  private data class PassMetrics(
    val text: String,
    val lineCount: Int,
    val lineAspect: Float,
    val textLength: Int,
    val confidence: Float?,
    val lines: List<Line>,
    /** Per-line clockwise text angles, straight from ML Kit. May contain NaN. */
    val angles: List<Float>,
  )

  /**
   * Run text recognition with single-pass content-rotation detection.
   * MUST be called on a background thread — uses [Tasks.await] which blocks.
   *
   * Algorithm history (per ADR-006 D14):
   * - v1.0 added a lineAspect gate inside the portrait fast-path. Field data
   *   showed it misfired because ML Kit Korean tokenizes rotated text into
   *   short near-square boxes (lineAspect ≈ 1).
   * - v1.1 made portrait inputs always probe 90/180/270. The probe cost was
   *   not justified for natural-orientation scans.
   * - v1.2 deferred the work and kept the v2.0 fast-path.
   * - v1.3 dropped the multi-pass probe entirely. Field data showed
   *   ML Kit Korean's results are *rotation-invariant* — every probe in a
   *   four-pass loop returned identical lineCount/lineAspect/textLength.
   *   Instead a single Pass 0 measures lineAspect, and rotation is decided
   *   by comparing the *direction* of the image-aspect against the
   *   line-aspect: when they disagree, the content is rotated. The 90 vs
   *   270 ambiguity was resolved by a default heuristic (270° CW).
   * - v2.0 (current) replaces that heuristic default with ML Kit's own
   *   per-line `getAngle`. 2026-07-18 field data showed the fixed 270°
   *   default is right exactly half the time — both lie directions occur —
   *   and lineAspect cannot tell them apart because it measures line *shape*,
   *   which is identical at 90 and 270. The angle carries direction, so it
   *   separates the two and detects a plain 180 flip as well. lineAspect
   *   stays as the fallback for samples too small or too split to judge.
   *
   * See `docs/specs/ocr-angle-rotation-detection.md` v1.0, which supersedes
   * `docs/specs/portrait-rotation-detection.md` v1.3.
   */
  fun recognizeWithRotationDetection(file: File): OcrResult {
    val bitmap =
      BitmapFactory.decodeFile(file.absolutePath)
        ?: return OcrResult(text = "", rotationDegrees = 0, lineCount = 0)
    return try {
      runDetection(bitmap, file.name)
    } finally {
      bitmap.recycle()
    }
  }

  /** Backward-compatible single-pass entry retained for callers that don't
   *  participate in rotation detection (none today, but keeps the API stable). */
  fun recognize(imageUri: Uri): String {
    val image = InputImage.fromFilePath(context, imageUri)
    return Tasks.await(recognizer.process(image)).text
  }

  private fun runDetection(
    bitmap: Bitmap,
    fileName: String,
  ): OcrResult {
    val imageAspect =
      if (bitmap.height > 0) bitmap.width.toFloat() / bitmap.height.toFloat() else 1f
    val pass0 = measureAt(bitmap, 0)
    logProbe(fileName, "0", pass0, imageAspect)

    if (pass0.lineCount < 3) {
      logDecision(fileName, 0, "too-few-lines", pass0)
      return OcrResult(pass0.text, 0, pass0.lineCount, pass0.confidence, pass0.lines)
    }

    // Fallback signal (v1.3): compare image and line aspect *directions*.
    // - imageAspect > 1 → landscape image
    // - lineAspect > LINE_HORIZONTAL_THRESHOLD → text lines lay out horizontally
    // - lineAspect < LINE_VERTICAL_THRESHOLD   → text lines lay out vertically
    // Mismatch (e.g. landscape image with vertical lines) signals a rotated
    // portrait receipt that the user held sideways. Computed up front because
    // the angle path below uses it as a corroborating check.
    val imageIsLandscape = imageAspect > 1f
    val lineIsHorizontal = pass0.lineAspect > LINE_HORIZONTAL_THRESHOLD
    val lineIsVertical = pass0.lineAspect < LINE_VERTICAL_THRESHOLD
    val hasEnoughLinesForAspect = pass0.lineCount >= MISMATCH_MIN_LINES
    val aspectSuggestsRotation = imageIsLandscape && lineIsVertical && hasEnoughLinesForAspect

    // Primary signal: the angle ML Kit reports for the text itself. Unlike
    // lineAspect this carries *direction*, so it separates 90 from 270 and
    // detects a plain 180 flip — the two cases 2026-07-18 field data proved
    // lineAspect cannot reach. A confirmed 0 counts as an answer, not an
    // abstention: it is what stops the lineAspect fallback from firing on a
    // genuinely upright receipt.
    // See docs/specs/ocr-angle-rotation-detection.md.
    val textAngle = OcrGeometry.dominantQuarterTurn(pass0.angles)
    // ...except when lineAspect independently says the content is sideways. If
    // ML Kit reports angles in its own reading frame rather than in image space
    // — the one assumption this design rests on and cannot verify from source —
    // the symptom is exactly "every line upright" on a visibly rotated receipt.
    // Treating that 0 as truth would newly refuse to rotate images v1.3 does
    // rotate, so defer to the fallback instead and let the QA log settle it.
    val angleContradictsAspect = textAngle == 0 && aspectSuggestsRotation
    if (textAngle != null && !angleContradictsAspect) {
      val correction = OcrGeometry.correctionForTextAngle(textAngle)
      logDecision(fileName, correction, "text-angle-$textAngle", pass0)
      return OcrResult(pass0.text, correction, pass0.lineCount, pass0.confidence, pass0.lines)
    }

    // Need enough lines for the lineAspect mean to be reliable.
    if (!hasEnoughLinesForAspect) {
      logDecision(fileName, 0, "low-line-count-skip-mismatch", pass0)
      return OcrResult(pass0.text, 0, pass0.lineCount, pass0.confidence, pass0.lines)
    }

    // Most common rotated case: user held a portrait receipt sideways → image
    // is landscape, lines are vertical (lineAspect < 0.7).
    if (imageIsLandscape && lineIsVertical) {
      logDecision(fileName, ROTATED_DEFAULT_DEGREES, "landscape-vertical-lines", pass0)
      return OcrResult(
        pass0.text,
        ROTATED_DEFAULT_DEGREES,
        pass0.lineCount,
        pass0.confidence,
        pass0.lines,
      )
    }

    // Mirror case (rare for Korean receipts): portrait image with horizontal
    // lines that look vertical relative to the image — would mean the receipt
    // was held in landscape but rotated. Not observed in field data; left as a
    // future signal pending more samples.
    // if (!imageIsLandscape && lineIsVertical) { … }

    // Ambiguous middle band (LINE_VERTICAL_THRESHOLD ≤ lineAspect ≤ LINE_HORIZONTAL_THRESHOLD).
    if (!lineIsHorizontal && !lineIsVertical) {
      logDecision(fileName, 0, "line-aspect-ambiguous", pass0)
      return OcrResult(pass0.text, 0, pass0.lineCount, pass0.confidence, pass0.lines)
    }

    // Aspect-matched (portrait + horizontal lines OR landscape + horizontal lines).
    logDecision(fileName, 0, "aspect-matched", pass0)
    return OcrResult(pass0.text, 0, pass0.lineCount, pass0.confidence, pass0.lines)
  }

  private fun measureAt(
    bitmap: Bitmap,
    rotationDegrees: Int,
  ): PassMetrics {
    val image = InputImage.fromBitmap(bitmap, rotationDegrees)
    val result = Tasks.await(recognizer.process(image))
    return PassMetrics(
      text = result.text,
      lineCount = result.textBlocks.sumOf { it.lines.size },
      lineAspect = lineAspectOf(result, rotationDegrees),
      textLength = result.text.length,
      confidence = meanLineConfidence(result),
      lines = linesOf(result),
      angles = anglesOf(result),
    )
  }

  /**
   * Every recognised line's clockwise text angle. ML Kit documents `getAngle`
   * as "the angle (in degrees, clockwise is positive, range is [-180, 180]) of
   * the rotation of the recognized line" — which is already the clockwise
   * convention this package canonicalised on (see
   * docs/notes/platform-asymmetries.md §3.1), so no sign conversion is needed.
   *
   * Unlike [linesOf] this keeps lines with no bounding box: the angle is usable
   * on its own, and dropping them would shrink the sample the mode is taken over.
   */
  private fun anglesOf(result: Text): List<Float> {
    val angles = mutableListOf<Float>()
    for (block in result.textBlocks) {
      for (line in block.lines) {
        if (line.text.isBlank()) continue
        angles.add(line.angle)
      }
    }
    return angles
  }

  /**
   * Per-line text + bounding box, in the recognised image's pixel space.
   * Lines without usable text or geometry are dropped — `boundingBox` is
   * nullable in ML Kit, so this list does not line up index-wise with the
   * newline-joined [OcrResult.text].
   */
  private fun linesOf(result: Text): List<Line> {
    val lines = mutableListOf<Line>()
    for (block in result.textBlocks) {
      for (line in block.lines) {
        if (line.text.isBlank()) continue
        val box = line.boundingBox ?: continue
        val w = box.width()
        val h = box.height()
        if (w <= 0 || h <= 0) continue
        val c = line.confidence
        lines.add(
          Line(
            text = line.text,
            box = OcrGeometry.Box(box.left, box.top, w, h),
            confidence = if (c.isNaN()) null else c,
          ),
        )
      }
    }
    return lines
  }

  /**
   * Mean of ML Kit's per-line confidence ([0, 1]) over all recognised lines, or
   * null when no line reports a finite value. Mirrors the iOS per-observation
   * mean; reporting only, never used for routing.
   *
   * The bundled Korean recogniser reports confidence; the *unbundled* library on
   * Play Services < 22.30 returns 0 (indistinguishable here) — this package ships
   * the bundled recogniser, so values are real.
   */
  private fun meanLineConfidence(result: Text): Float? {
    var sum = 0f
    var count = 0
    for (block in result.textBlocks) {
      for (line in block.lines) {
        val c = line.confidence
        if (!c.isNaN()) {
          sum += c
          count++
        }
      }
    }
    return if (count > 0) sum / count else null
  }

  /**
   * Trimmed-mean (10% top / 10% bottom) of `width / height` for every recognized
   * line's bounding box. Returns 1.0 when there are no usable boxes. For 90° / 270°
   * input rotation hints the width/height are swapped so the metric reflects the
   * line shape *in the rotated frame* — but in practice ML Kit Korean returns the
   * same boxes regardless of the hint, so this swap is academic.
   */
  private fun lineAspectOf(
    result: Text,
    rotationDegrees: Int,
  ): Float {
    val ratios = mutableListOf<Float>()
    for (block in result.textBlocks) {
      for (line in block.lines) {
        val box = line.boundingBox ?: continue
        val w = box.width().toFloat()
        val h = box.height().toFloat()
        if (w <= 0f || h <= 0f) continue
        val ratio =
          when (rotationDegrees) {
            90, 270 -> h / w
            else -> w / h
          }
        ratios.add(ratio)
      }
    }
    if (ratios.isEmpty()) return 1f
    ratios.sort()
    val trimmable = ratios.size >= 5
    val from = if (trimmable) (ratios.size * TRIM_RATIO).toInt() else 0
    val to = if (trimmable) ratios.size - from else ratios.size
    val window = ratios.subList(from, to)
    if (window.isEmpty()) return 1f
    var sum = 0f
    for (r in window) sum += r
    return sum / window.size
  }

  private fun logProbe(
    fileName: String,
    label: String,
    m: PassMetrics,
    aspect: Float?,
  ) {
    val aspectStr = aspect?.let { String.format(Locale.US, " imageAspect=%.3f", it) } ?: ""
    // Full histogram rather than just the winner: calibrating ANGLE_MAJORITY
    // needs the spread, and a [n,0,0,0] shape on an image lineAspect calls
    // rotated is the fingerprint of ML Kit reporting angles in its own reading
    // frame instead of image space (docs/specs/ocr-angle-rotation-detection.md).
    val bins = OcrGeometry.quarterTurnHistogram(m.angles)
    Log.i(
      LOG_TAG,
      "probe deg=$label file=$fileName lineCount=${m.lineCount} lineAspect=${
        String.format(Locale.US, "%.2f", m.lineAspect)
      } textLength=${m.textLength}$aspectStr angleBins=${
        bins.joinToString(",", "[", "]")
      } angleN=${bins.sum()}",
    )
  }

  private fun logDecision(
    fileName: String,
    chosenDeg: Int,
    reason: String,
    m: PassMetrics,
  ) {
    Log.i(
      LOG_TAG,
      "decision file=$fileName chosen=$chosenDeg reason=$reason lineCount=${m.lineCount} " +
        "lineAspect=${String.format(Locale.US, "%.2f", m.lineAspect)} textLength=${m.textLength}",
    )
  }

  /**
   * Releases the underlying ML Kit recogniser. Idempotent on the ML Kit
   * side; safe to call once per [OcrProcessor] instance.
   */
  fun close() {
    recognizer.close()
  }

  companion object {
    private const val LOG_TAG = "ReceiptScanner.Ocr"

    /** lineAspect above this means lines lay out horizontally (normal Korean receipt). */
    private const val LINE_HORIZONTAL_THRESHOLD = 1.5f

    /** lineAspect below this means lines lay out vertically (rotated content). */
    private const val LINE_VERTICAL_THRESHOLD = 0.7f

    /** Minimum line count before the lineAspect-based mismatch decision is trusted. */
    private const val MISMATCH_MIN_LINES = 5

    /**
     * Default rotation applied when an aspect-mismatch is detected.
     * 270 = Android Matrix.postRotate(270) = 90° CCW from the user's perspective.
     * Picked because the most common "user holds a portrait receipt sideways"
     * pose puts the receipt's natural top edge on the image's left side, which
     * a 270° CW rotation maps to image-top. Subject to revision once more
     * field samples accumulate.
     */
    private const val ROTATED_DEFAULT_DEGREES = 270

    /** Top/bottom trim ratio for the line-aspect mean (only applied when ≥ 5 lines). */
    private const val TRIM_RATIO = 0.10f
  }
}
