import type { MergedOcrResult, ReceiptImage } from "./types";

/** An overlap must reach this many characters to prove a seam. */
const MIN_WINDOW_CHARACTERS = 12;
/**
 * An overlap must contain this many *distinct* lines.
 *
 * A single repeated row is not evidence of a seam: buying two of one item
 * prints the identical row twice, so a page ending with that row and the next
 * page beginning with it match exactly without overlapping at all. Merging on
 * that deletes a real purchase. Requiring two distinct lines also rejects a
 * degenerate run of one line repeated N times, which fails the same way at
 * greater depth.
 */
const MIN_DISTINCT_LINES = 2;

/**
 * Assembles the OCR text of one logical receipt from its pages, removing text
 * duplicated where two adjacent captures overlap.
 *
 * Pages are consumed in the order given and never reordered — deciding which
 * captures belong together is the caller's business, not this package's. Only
 * adjacent pages are compared, and repeated lines away from a seam (totals,
 * headers) are always preserved.
 *
 * A seam whose overlap cannot be proven contributes both pages' lines in full
 * and records its boundary index, so enabling the merge never loses text.
 *
 * @param pages - Pages in native capture order.
 * @param rejectedPageIndexes - Pages the `ocrFloor` gate rejected. Pages with
 *                              no usable text are added automatically.
 * @returns The merged text and its completeness diagnostics.
 * @throws RangeError when an index falls outside `pages`.
 * @see `docs/specs/long-receipt-ocr-merge.md` for the normative contract.
 */
export function mergeOcrPages(
  pages: readonly ReceiptImage[],
  rejectedPageIndexes: readonly number[] = []
): MergedOcrResult {
  validateRejectedIndexes(rejectedPageIndexes, pages.length);

  const linesByPage = pages.map((page) => nonEmptyLines(page.ocrText));
  const rejected = new Set(rejectedPageIndexes);
  for (const [index, lines] of linesByPage.entries()) {
    if (lines.length === 0) rejected.add(index);
  }

  const firstPageLines = linesByPage[0];
  const mergedLines: string[] = firstPageLines ? [...firstPageLines] : [];
  const unmatchedBoundaries: number[] = [];

  for (let pageIndex = 1; pageIndex < linesByPage.length; pageIndex++) {
    // Both reads are in range by the loop bounds; the fallback only satisfies
    // noUncheckedIndexedAccess.
    const previousLines = linesByPage[pageIndex - 1] ?? [];
    const currentLines = linesByPage[pageIndex] ?? [];
    // A page with no text cannot prove a seam, so its boundary is unmatched.
    const overlapLines =
      previousLines.length > 0 && currentLines.length > 0
        ? findOverlap(previousLines, currentLines)
        : null;

    if (overlapLines === null) {
      unmatchedBoundaries.push(pageIndex - 1);
      mergedLines.push(...currentLines);
      continue;
    }
    mergedLines.push(...currentLines.slice(overlapLines));
  }

  const sortedRejected = [...rejected].sort((a, b) => a - b);
  return {
    text: mergedLines.join("\n"),
    isComplete: pages.length > 0 && sortedRejected.length === 0 && unmatchedBoundaries.length === 0,
    pageUris: pages.map((page) => page.uri),
    unmatchedBoundaryIndexes: unmatchedBoundaries,
    rejectedPageIndexes: sortedRejected,
  };
}

/** Rejects indexes that do not address a page, rather than silently ignoring them. */
function validateRejectedIndexes(indexes: readonly number[], pageCount: number): void {
  for (const index of indexes) {
    if (!Number.isInteger(index) || index < 0 || index >= pageCount) {
      throw new RangeError(`rejectedPageIndexes contains an out-of-range page index: ${index}`);
    }
  }
}

/** Splits OCR text into trimmed, non-empty lines. Absent text yields no lines. */
function nonEmptyLines(text: string | undefined): string[] {
  if (typeof text !== "string") return [];
  const lines: string[] = [];
  for (const line of text.split("\n")) {
    const trimmed = line.trim();
    if (trimmed.length > 0) lines.push(trimmed);
  }
  return lines;
}

/**
 * Returns how many leading lines of the later page repeat the earlier page's
 * trailing lines, or `null` when no overlap is provable.
 *
 * Equality is the only evidence accepted. Approximate matching was implemented,
 * measured, and removed: on receipt text an edit-distance threshold cannot tell
 * "the same line, misread" from "a different purchase". Two rows for one product
 * at quantities 1 and 2 differ only in digits, which carry all the meaning and
 * almost none of the edit distance — measured at 0.9200 similarity for a lone
 * 25-character row and 0.8974 for a 39-character two-line window, both above any
 * floor that still merges anything, and both deleting real rows while reporting
 * the seam proven.
 *
 * ponytail: exact-only trades merges for safety — a genuine seam whose OCR
 * differs by one character is reported unproven instead of merged, which is
 * visible to the consumer where a deleted row is not. If device data shows too
 * many unproven seams, the upgrade path is a digit-aware comparison (equal digit
 * runs, fuzzy elsewhere), never a looser similarity floor.
 */
function findOverlap(leftLines: readonly string[], rightLines: readonly string[]): number | null {
  const left = leftLines.map(normalizeLine);
  const right = rightLines.map(normalizeLine);

  // Deepest first. A shorter suffix/prefix pair can coincide *inside* a deeper
  // true overlap — a receipt that repeats a separator and a section header at
  // both ends of the overlapped region matches at two lines as readily as at
  // nine — and merging on that shallow match leaves the rest duplicated while
  // reporting the seam proven. Taking the deepest match removes that class
  // outright, so there is no window ceiling to exceed.
  for (let lineCount = Math.min(left.length, right.length); lineCount >= 1; lineCount--) {
    if (!suffixEqualsPrefix(left, right, lineCount)) continue;

    // Guard against text that repeats by coincidence rather than by overlap.
    const matchedLines = right.slice(0, lineCount);
    if (new Set(matchedLines).size < MIN_DISTINCT_LINES) continue;
    if (matchedLines.join(" ").length < MIN_WINDOW_CHARACTERS) continue;

    return lineCount;
  }
  return null;
}

/** Whether `left`'s last `lineCount` lines equal `right`'s first `lineCount`. */
function suffixEqualsPrefix(
  left: readonly string[],
  right: readonly string[],
  lineCount: number
): boolean {
  const offset = left.length - lineCount;
  for (let index = 0; index < lineCount; index++) {
    if (left[offset + index] !== right[index]) return false;
  }
  return true;
}

/**
 * Normalizes one line for comparison only — lowercased, whitespace runs
 * collapsed. Emitted text always uses the original trimmed lines.
 */
function normalizeLine(line: string): string {
  return line.toLowerCase().replace(/\s+/g, " ").trim();
}
