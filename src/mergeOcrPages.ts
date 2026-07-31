import type { MergedOcrResult, ReceiptImage } from "./types";

/**
 * Longest suffix / prefix window compared at a seam, in lines.
 *
 * ponytail: hard ceiling on provable overlap — an overlap deeper than this
 * cannot be matched, so the seam is reported unproven rather than merged. That
 * is the safe direction (nothing is deleted), and it is why the capture
 * guidance asks for a few overlapping lines rather than a fraction of the page.
 * Raise it if real receipts turn out to overlap more deeply; cost is quadratic
 * in this value.
 */
const MAX_WINDOW_LINES = 8;
/** A multi-line window pair must reach this many characters to prove a seam. */
const MIN_WINDOW_CHARACTERS = 12;
/** A single-line pair must reach this many characters — one short line repeats by coincidence. */
const MIN_SINGLE_LINE_CHARACTERS = 24;

/** An accepted seam candidate. Only `rightLineCount` affects the output; `matchedCharacters` ranks. */
type Overlap = {
  /** Lines to drop from the start of the later page. */
  rightLineCount: number;
  /** Length of the matched window — longer is stronger evidence. */
  matchedCharacters: number;
};

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
    const overlap =
      previousLines.length > 0 && currentLines.length > 0
        ? findOverlap(previousLines, currentLines)
        : null;

    if (overlap === null) {
      unmatchedBoundaries.push(pageIndex - 1);
      mergedLines.push(...currentLines);
      continue;
    }
    mergedLines.push(...currentLines.slice(overlap.rightLineCount));
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
 * Finds the strongest provable overlap between the earlier page's trailing
 * lines and the later page's leading lines, or `null` when none clears its
 * threshold.
 */
function findOverlap(leftLines: readonly string[], rightLines: readonly string[]): Overlap | null {
  const leftLimit = Math.min(MAX_WINDOW_LINES, leftLines.length);
  const rightLimit = Math.min(MAX_WINDOW_LINES, rightLines.length);
  // Window at index k spans k+1 lines: the earlier page's last k+1, the later
  // page's first k+1. Built once because each is compared up to eight times.
  const leftWindows = Array.from({ length: leftLimit }, (_unused, index) =>
    comparisonText(leftLines.slice(leftLines.length - index - 1))
  );
  const rightWindows = Array.from({ length: rightLimit }, (_unused, index) =>
    comparisonText(rightLines.slice(0, index + 1))
  );

  let best: Overlap | null = null;
  // Both loops ascend and a tie keeps the incumbent, so an otherwise equal
  // match drops the fewest lines from the later page.
  for (const [rightIndex, right] of rightWindows.entries()) {
    const rightCount = rightIndex + 1;
    for (const [leftIndex, left] of leftWindows.entries()) {
      const leftCount = leftIndex + 1;
      // Equality is the only evidence accepted. Approximate matching was tried
      // and removed: on receipt text an edit-distance threshold cannot tell
      // "the same line, misread" from "a different purchase". Two rows for one
      // product at quantities 1 and 2 differ only in digits, which carry all
      // the meaning and almost none of the edit distance — measured at 0.9200
      // for a lone 25-character row and 0.8974 for a 39-character two-line
      // window, both above any floor that still merges anything. Both deleted
      // real rows while reporting isComplete.
      //
      // ponytail: exact-only trades merges for safety — a genuine seam whose
      // OCR differs by one character is reported unproven instead of merged,
      // which is visible to the consumer, where a deleted row is not. If device
      // data shows too many unproven seams, the upgrade path is a digit-aware
      // comparison (equal digit runs, fuzzy elsewhere), never a looser floor.
      if (left !== right) continue;

      const matchedCharacters = left.length;
      const minimumCharacters =
        leftCount === 1 || rightCount === 1 ? MIN_SINGLE_LINE_CHARACTERS : MIN_WINDOW_CHARACTERS;
      if (matchedCharacters < minimumCharacters) continue;

      // A longer match is stronger evidence; a tie keeps the incumbent, which
      // by the loop order drops the fewest lines from the later page.
      if (best === null || matchedCharacters > best.matchedCharacters) {
        best = { rightLineCount: rightCount, matchedCharacters };
      }
    }
  }
  return best;
}

/**
 * Normalizes a window for comparison only — lowercased, whitespace runs
 * collapsed, lines joined by a single space. Emitted text always uses the
 * original trimmed lines.
 */
function comparisonText(lines: readonly string[]): string {
  return lines.map((line) => line.toLowerCase().replace(/\s+/g, " ").trim()).join(" ");
}
