import type { MergedOcrResult, ReceiptImage } from "./types";

/** Longest suffix / prefix window compared at a seam, in lines. */
const MAX_WINDOW_LINES = 8;
/** Shorter window must reach this many characters for a multi-line match. */
const MIN_WINDOW_CHARACTERS = 12;
/** Similarity a multi-line window pair must reach to be accepted. */
const MIN_WINDOW_SIMILARITY = 0.85;
/** Shorter window must reach this many characters when either side is one line. */
const MIN_SINGLE_LINE_CHARACTERS = 24;
/** Similarity a single-line window pair must reach — one short line matches by coincidence too easily. */
const MIN_SINGLE_LINE_SIMILARITY = 0.92;
/** Longest non-identical pair still worth an O(n·m) edit-distance pass. */
const MAX_COMPARISON_CHARACTERS = 512;

/** An accepted seam candidate. Only `rightLineCount` affects the output; the rest rank candidates. */
type Overlap = {
  /** Lines to drop from the start of the later page. */
  rightLineCount: number;
  /** Characters actually compared — the shorter window's length. */
  comparedCharacters: number;
  /** `1 - editDistance / longerLength`, or exactly `1` for an identical pair. */
  similarity: number;
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
      const singleLine = leftCount === 1 || rightCount === 1;
      const minimumCharacters = singleLine ? MIN_SINGLE_LINE_CHARACTERS : MIN_WINDOW_CHARACTERS;
      const minimumSimilarity = singleLine ? MIN_SINGLE_LINE_SIMILARITY : MIN_WINDOW_SIMILARITY;

      const shorterLength = Math.min(left.length, right.length);
      if (shorterLength < minimumCharacters) continue;
      const longerLength = Math.max(left.length, right.length);
      // A length gap this wide already costs more edits than the threshold
      // allows, so the distance pass cannot rescue it.
      if ((longerLength - shorterLength) / longerLength > 1 - minimumSimilarity) continue;

      const exactMatch = left === right;
      // Edit distance is O(n·m); skip the pathological pairs instead of paying it.
      if (!exactMatch && longerLength > MAX_COMPARISON_CHARACTERS) continue;
      const similarity = exactMatch ? 1 : 1 - levenshteinDistance(left, right) / longerLength;
      if (similarity < minimumSimilarity) continue;

      const better =
        best === null ||
        similarity > best.similarity ||
        // Equal similarity: a longer proven overlap is stronger evidence.
        (similarity === best.similarity && shorterLength > best.comparedCharacters);
      if (better) {
        best = { rightLineCount: rightCount, comparedCharacters: shorterLength, similarity };
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

/**
 * Levenshtein distance over UTF-16 code units, matching the sibling Dart
 * implementation's `codeUnitAt` comparison. Korean syllables are BMP, so the
 * two agree on receipt text.
 */
function levenshteinDistance(left: string, right: string): number {
  if (left === right) return 0;
  if (left.length === 0) return right.length;
  if (right.length === 0) return left.length;

  // One row of the DP matrix plus two scalar carries, so each cell is read from
  // the array exactly once. Bounded by MAX_COMPARISON_CHARACTERS + 1.
  const row = new Uint32Array(right.length + 1);
  for (let index = 0; index <= right.length; index++) row[index] = index;
  // Correct for an empty left string; the loop below always overwrites it.
  let distance = right.length;

  for (let leftIndex = 0; leftIndex < left.length; leftIndex++) {
    let diagonal = leftIndex; // row[0] before the write below
    let leftCell = leftIndex + 1;
    row[0] = leftCell;
    for (let rightIndex = 0; rightIndex < right.length; rightIndex++) {
      // In range by the loop bound; the fallback only satisfies noUncheckedIndexedAccess.
      const above = row[rightIndex + 1] ?? 0;
      const substitutionCost = left.charCodeAt(leftIndex) === right.charCodeAt(rightIndex) ? 0 : 1;
      const cell = Math.min(above + 1, leftCell + 1, diagonal + substitutionCost);
      row[rightIndex + 1] = cell;
      diagonal = above;
      leftCell = cell;
    }
    distance = leftCell;
  }
  return distance;
}
