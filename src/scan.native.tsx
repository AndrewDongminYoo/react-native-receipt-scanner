import { mergeOcrPages } from "./mergeOcrPages";
import NativeReceiptScanner from "./NativeReceiptScanner";
import { DEFAULT_OCR_FLOOR, DEFAULT_SCAN_OPTIONS } from "./types";
import type {
  MergedOcrResult,
  OcrCapabilities,
  OcrFloor,
  OcrQuality,
  ReceiptImage,
  ScanReceiptOptions,
  ScanReceiptResult,
} from "./types";

/**
 * Native (iOS / Android) entry point for {@link scan}. Merges caller options
 * with {@link DEFAULT_SCAN_OPTIONS}, delegates to the TurboModule, and post-
 * processes the result on the JS side: derives {@link OcrQuality} metrics,
 * partitions images by the {@link OcrFloor} acceptance gate, and optionally
 * assembles cross-page OCR text.
 *
 * @param options - Caller-supplied options. Missing fields are filled from
 *                  {@link DEFAULT_SCAN_OPTIONS}.
 * @returns A {@link ScanReceiptResult}. `rejectedImages` is always an array
 *          (empty when nothing was rejected).
 * @throws When {@link ScanReceiptOptions.mergeOcrPages} is combined with
 *         `ocr: false` or `maxPages < 2` — before any scanner UI opens.
 */
export async function scan(options?: ScanReceiptOptions): Promise<ScanReceiptResult> {
  const merged = {
    ...DEFAULT_SCAN_OPTIONS,
    ...options,
    ocrLanguages: options?.ocrLanguages ?? DEFAULT_SCAN_OPTIONS.ocrLanguages,
  };
  // Validate before dispatch so an impossible request never costs the user a capture.
  if (merged.mergeOcrPages) validateMergeOptions(merged);

  const forwardedOptions = merged.ocr
    ? { ...merged, ocrLanguages: normalizeOcrLanguages(merged.ocrLanguages) }
    : merged;
  // Type assertion is intentional: the TurboModule Spec uses `Object` for Phase 1.
  // Phase 2 will tighten this once the native shape is stabilized.
  const native = (await NativeReceiptScanner.scan(forwardedOptions)) as ScanReceiptResult;

  if (native.status !== "success") {
    return { ...native, rejectedImages: native.rejectedImages ?? [] };
  }

  const withQuality = native.images.map(annotateQuality);
  // Snapshot capture order before the floor gate partitions the array — the
  // merge needs page order, and `images` / `rejectedImages` no longer carry it.
  const nativePageUris = merged.mergeOcrPages ? snapshotPageUris(withQuality) : null;

  // Floor only applies when OCR ran. Without OCR there's nothing to measure.
  // ocrFloor === false explicitly disables the check.
  const floor = resolveFloor(merged.ocr, options?.ocrFloor);
  const gated: ScanReceiptResult = floor
    ? partitionByFloor(withQuality, floor)
    : { ...native, images: withQuality, rejectedImages: [] };

  if (nativePageUris === null) return gated;
  // Attached even when the gate downgraded the status to "rejected": that is
  // exactly when the consumer needs the diagnostics to prompt a re-shoot.
  return { ...gated, mergedOcr: buildMergedOcr(nativePageUris, gated) };
}

/** Splits images by the {@link OcrFloor} gate into the accepted / rejected result shape. */
function partitionByFloor(
  images: readonly ReceiptImage[],
  floor: Required<OcrFloor>
): ScanReceiptResult {
  const passed: ReceiptImage[] = [];
  const rejected: ReceiptImage[] = [];
  for (const img of images) {
    (meetsFloor(img, floor) ? passed : rejected).push(img);
  }

  if (passed.length === 0 && rejected.length > 0) {
    return { status: "rejected", images: [], rejectedImages: rejected };
  }
  return { status: "success", images: passed, rejectedImages: rejected };
}

/** Returns the active native OCR capability without presenting scanner UI. */
export async function getOcrCapabilities(): Promise<OcrCapabilities> {
  return (await NativeReceiptScanner.getOcrCapabilities()) as OcrCapabilities;
}

/** Typed validation error for OCR language hints rejected at the JS boundary. */
class InvalidOcrLanguageError extends Error {
  readonly code: "INVALID_OCR_LANGUAGE" = "INVALID_OCR_LANGUAGE";

  constructor(message: string) {
    super(message);
    this.name = "InvalidOcrLanguageError";
  }
}

/** Trims, validates, and de-duplicates OCR language hints in caller order. */
function normalizeOcrLanguages(languages: readonly string[]): string[] {
  // Guard the container for the same reason as each entry below: the type is a
  // compile-time promise, and `ocrLanguages: 1` would otherwise reach the
  // for-of and throw an untyped TypeError instead of INVALID_OCR_LANGUAGE.
  if (!Array.isArray(languages)) {
    throw new InvalidOcrLanguageError("OCR language hints must be an array.");
  }
  if (languages.length === 0) {
    throw new InvalidOcrLanguageError("OCR language hints must not be empty.");
  }

  const normalized: string[] = [];
  const seen = new Set<string>();
  for (const language of languages) {
    // The array type is only a compile-time promise; untyped JS callers and
    // runtime-derived options reach here with anything. Fail through the
    // documented error contract rather than an untyped TypeError from .trim().
    if (typeof language !== "string") {
      throw new InvalidOcrLanguageError("OCR language hints must be strings.");
    }
    const trimmed = language.trim();
    if (trimmed.length === 0) {
      throw new InvalidOcrLanguageError("OCR language hints must not contain empty values.");
    }
    if (!seen.has(trimmed)) {
      seen.add(trimmed);
      normalized.push(trimmed);
    }
  }
  return normalized;
}

/** Typed validation error for `mergeOcrPages` combinations rejected at the JS boundary. */
class InvalidMergeOptionError extends Error {
  readonly code: "INVALID_MERGE_OPTION" = "INVALID_MERGE_OPTION";

  constructor(message: string) {
    super(message);
    this.name = "InvalidMergeOptionError";
  }
}

/**
 * Rejects `mergeOcrPages` combinations that cannot produce a merge, before the
 * native module is called so no scanner UI opens on a request that cannot work.
 */
function validateMergeOptions(options: Required<ScanReceiptOptions>): void {
  if (!options.ocr) {
    throw new InvalidMergeOptionError(
      "mergeOcrPages requires ocr: true — there is no recognized text to merge."
    );
  }
  if (options.maxPages < 2) {
    throw new InvalidMergeOptionError(
      "mergeOcrPages requires maxPages >= 2 — a merge needs at least one page boundary."
    );
  }
}

/**
 * Records native capture order and refuses a duplicate URI, which would make
 * the order restoration below ambiguous. Duplicates are an internal state
 * error, not something to silently reorder around.
 */
function snapshotPageUris(images: readonly ReceiptImage[]): string[] {
  const uris: string[] = [];
  const seen = new Set<string>();
  for (const image of images) {
    if (seen.has(image.uri)) {
      throw new Error(`Duplicate receipt page URI: ${image.uri}`);
    }
    seen.add(image.uri);
    uris.push(image.uri);
  }
  return uris;
}

/**
 * Rebuilds the pages in native capture order from the post-gate result, then
 * merges their OCR text. Floor-rejected pages stay in the page list — they
 * still contribute text and still count against completeness.
 */
function buildMergedOcr(
  nativePageUris: readonly string[],
  gated: ScanReceiptResult
): MergedOcrResult {
  const annotatedByUri = new Map<string, ReceiptImage>();
  for (const image of [...gated.images, ...gated.rejectedImages]) {
    annotatedByUri.set(image.uri, image);
  }

  const rejectedUris = new Set(gated.rejectedImages.map((image) => image.uri));
  const orderedPages: ReceiptImage[] = [];
  const rejectedPageIndexes: number[] = [];
  for (const uri of nativePageUris) {
    const page = annotatedByUri.get(uri);
    if (!page) {
      throw new Error(`OCR floor result is missing receipt page URI: ${uri}`);
    }
    if (rejectedUris.has(uri)) rejectedPageIndexes.push(orderedPages.length);
    orderedPages.push(page);
  }

  return mergeOcrPages(orderedPages, rejectedPageIndexes);
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
 * passes through from the native layer (iOS Vision, Android ML Kit per-line);
 * it is `undefined` when OCR didn't run, and on Android also when the
 * recognizer's provider cannot supply a real value.
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
 * Predicate for the {@link OcrFloor} gate. Absent confidence is treated as
 * "satisfied" so the gate never rejects on a field that wasn't produced — it is
 * absent when OCR is off or found no text, and also on Android when the
 * Play-services-delivered recognizer cannot supply a real value (see
 * `OcrProcessor.meanLineConfidence`). Confidence stays reporting-only — not a
 * cross-platform enforcement signal until validated comparable.
 */
function meetsFloor(image: ReceiptImage, floor: Required<OcrFloor>): boolean {
  const q = image.ocrQuality;
  if (!q) return false;
  if (q.textLength < floor.minTextLength) return false;
  if (q.lineCount < floor.minLines) return false;
  // Absent confidence => satisfied: undefined when OCR is off / no text, or
  // when the provider can't supply one — don't gate on that.
  if (q.confidence !== undefined && q.confidence < floor.minConfidence) return false;
  return true;
}
