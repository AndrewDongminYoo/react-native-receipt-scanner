import NativeReceiptScanner from "./NativeReceiptScanner";
import { DEFAULT_OCR_FLOOR, DEFAULT_SCAN_OPTIONS } from "./types";
import type {
  OcrFloor,
  OcrQuality,
  ReceiptImage,
  ScanReceiptOptions,
  ScanReceiptResult,
} from "./types";

/**
 * Native (iOS / Android) entry point for {@link scan}. Merges caller options
 * with {@link DEFAULT_SCAN_OPTIONS}, delegates to the TurboModule, and post-
 * processes the result on the JS side: derives {@link OcrQuality} metrics
 * and partitions images by the {@link OcrFloor} acceptance gate.
 *
 * @param options - Caller-supplied options. Missing fields are filled from
 *                  {@link DEFAULT_SCAN_OPTIONS}.
 * @returns A {@link ScanReceiptResult}. `rejectedImages` is always an array
 *          (empty when nothing was rejected).
 */
export async function scan(options?: ScanReceiptOptions): Promise<ScanReceiptResult> {
  const merged = { ...DEFAULT_SCAN_OPTIONS, ...options };
  // Type assertion is intentional: the TurboModule Spec uses `Object` for Phase 1.
  // Phase 2 will tighten this once the native shape is stabilized.
  const native = (await NativeReceiptScanner.scan(merged)) as ScanReceiptResult;

  if (native.status !== "success") {
    return { ...native, rejectedImages: native.rejectedImages ?? [] };
  }

  const withQuality = native.images.map(annotateQuality);

  // Floor only applies when OCR ran. Without OCR there's nothing to measure.
  // ocrFloor === false explicitly disables the check.
  const floor = resolveFloor(merged.ocr, options?.ocrFloor);
  if (!floor) {
    return { ...native, images: withQuality, rejectedImages: [] };
  }

  const passed: ReceiptImage[] = [];
  const rejected: ReceiptImage[] = [];
  for (const img of withQuality) {
    (meetsFloor(img, floor) ? passed : rejected).push(img);
  }

  if (passed.length === 0 && rejected.length > 0) {
    return { status: "rejected", images: [], rejectedImages: rejected };
  }
  return { status: "success", images: passed, rejectedImages: rejected };
}

/**
 * Resolves the effective {@link OcrFloor} for this call.
 *
 * @param ocr - Whether OCR ran. When `false`, no floor applies.
 * @param override - Caller's `ocrFloor` argument (`undefined`, `false`, or partial floor).
 * @returns A fully populated floor, or `null` when the gate should be skipped.
 */
function resolveFloor(
  ocr: boolean,
  override: OcrFloor | false | undefined
): Required<OcrFloor> | null {
  if (!ocr) return null;
  if (override === false) return null;
  return { ...DEFAULT_OCR_FLOOR, ...(override ?? {}) };
}

/**
 * Attaches a derived {@link OcrQuality} block to an image whose `ocrText`
 * the native layer populated. No-op when OCR didn't run.
 */
function annotateQuality(image: ReceiptImage): ReceiptImage {
  if (typeof image.ocrText !== "string") return image;
  return { ...image, ocrQuality: deriveQuality(image.ocrText, image.ocrQuality?.confidence) };
}

/**
 * Computes {@link OcrQuality} metrics from the joined OCR text. Confidence
 * passes through from the native layer on both platforms (iOS Vision, Android
 * ML Kit per-line); it is `undefined` only when OCR didn't run.
 */
function deriveQuality(text: string, confidence?: number): OcrQuality {
  const trimmedLength = text.trim().length;
  let lineCount = 0;
  for (const line of text.split("\n")) {
    if (line.trim().length > 0) lineCount++;
  }
  return confidence !== undefined
    ? { textLength: trimmedLength, lineCount, confidence }
    : { textLength: trimmedLength, lineCount };
}

/**
 * Predicate for the {@link OcrFloor} gate. `confidence` is populated on both
 * platforms when OCR runs; absent confidence (OCR off or no text) is
 * treated as "satisfied" so the gate never rejects on a field that wasn't
 * produced. Confidence stays reporting-only — not a cross-platform enforcement
 * signal until validated comparable.
 */
function meetsFloor(image: ReceiptImage, floor: Required<OcrFloor>): boolean {
  const q = image.ocrQuality;
  if (!q) return false;
  if (q.textLength < floor.minTextLength) return false;
  if (q.lineCount < floor.minLines) return false;
  // Absent confidence => satisfied: present on both platforms when OCR runs
  // but undefined when OCR is off / no text — don't gate on that.
  if (q.confidence !== undefined && q.confidence < floor.minConfidence) return false;
  return true;
}
