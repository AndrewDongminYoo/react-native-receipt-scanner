import type { MergedOcrResult, ReceiptImage } from "./types";

export interface PilotMatcher {
  getAbiVersion(): number;
  findOverlap(left: number[], leftRanges: number[], right: number[], rightRanges: number[]): number;
}

export function mergeWithPilot(
  pages: readonly ReceiptImage[],
  rejected: readonly number[],
  matcher: PilotMatcher
): MergedOcrResult {
  if (matcher.getAbiVersion() !== 1) throw new Error("Unsupported receipt core ABI");
  for (const index of rejected) {
    if (!Number.isInteger(index) || index < 0 || index >= pages.length) {
      throw new RangeError("Rejected page index is outside pages");
    }
  }
  const lines = pages.map((page) =>
    (page.ocrText ?? "")
      .split("\n")
      .map((line) => line.trim())
      .filter(Boolean)
  );
  const rejectedIndexes = new Set(rejected);
  lines.forEach((page, index) => {
    if (page.length === 0) rejectedIndexes.add(index);
  });
  const merged = [...(lines[0] ?? [])];
  const unmatched: number[] = [];
  for (let index = 1; index < lines.length; index++) {
    const previous = lines[index - 1] ?? [];
    const current = lines[index] ?? [];
    let count = 0;
    if (previous.length && current.length) {
      const left = encodePage(previous);
      const right = encodePage(current);
      count = matcher.findOverlap(left.units, left.ranges, right.units, right.ranges);
      if (
        !Number.isInteger(count) ||
        count < 0 ||
        count > Math.min(previous.length, current.length)
      ) {
        throw new Error("Invalid native overlap count");
      }
    }
    if (count === 0) unmatched.push(index - 1);
    merged.push(...current.slice(count));
  }
  const sortedRejected = [...rejectedIndexes].sort((a, b) => a - b);
  return {
    text: merged.join("\n"),
    isComplete: pages.length > 0 && sortedRejected.length === 0 && unmatched.length === 0,
    pageUris: pages.map((page) => page.uri),
    unmatchedBoundaryIndexes: unmatched,
    rejectedPageIndexes: sortedRejected,
  };
}

/** Numeric transport preserves code units, including lone surrogates. */
function encodePage(lines: readonly string[]): { units: number[]; ranges: number[] } {
  const units: number[] = [];
  const ranges: number[] = [];
  for (const line of lines) {
    const normalized = line.toLowerCase().replace(/\s+/g, " ").trim();
    ranges.push(units.length, normalized.length);
    for (let index = 0; index < normalized.length; index++) {
      units.push(normalized.charCodeAt(index));
    }
  }
  return { units, ranges };
}
