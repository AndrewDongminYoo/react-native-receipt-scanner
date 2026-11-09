import { mergeOcrPages } from "../src/mergeOcrPages";
import { mergeWithPilot } from "../src/overlapPilot";
import type { PilotMatcher } from "../src/overlapPilot";
import type { ReceiptImage } from "../src/types";
import { workloads } from "./workloads";

interface MergeFixture {
  id: string;
  pages: (string | null)[];
  rejectedPageIndexes: number[];
  expectedError: string | null;
  expected: object | null;
}

function pagesFor(texts: readonly (string | null)[]): ReceiptImage[] {
  return texts.map((text, index) => ({
    uri: `file:///pilot/${index}.jpg`,
    fileName: `${index}.jpg`,
    mimeType: "image/jpeg",
    width: 1,
    height: 1,
    fileSize: 1,
    imageOrigin: "camera",
    ...(text === null ? {} : { ocrText: text }),
  }));
}

function canonical(value: unknown): string {
  if (value === null || typeof value !== "object") return JSON.stringify(value);
  if (Array.isArray(value)) return `[${value.map(canonical).join(",")}]`;
  return `{${Object.entries(value)
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([key, item]) => `${JSON.stringify(key)}:${canonical(item)}`)
    .join(",")}}`;
}

function equal(actual: unknown, expected: unknown, label: string): void {
  if (canonical(actual) !== canonical(expected)) throw new Error(`Parity failed: ${label}`);
}

/** Exercises the actual injected native module; it never opens scanner UI. */
export function runParity(matcher: PilotMatcher, corpus: { mergeCases: MergeFixture[] }): object {
  equal(matcher.getAbiVersion(), 1, "ABI");
  for (const fixture of corpus.mergeCases) {
    const pages = pagesFor(fixture.pages);
    if (fixture.expectedError) {
      let rejected = false;
      try {
        mergeWithPilot(pages, fixture.rejectedPageIndexes, matcher);
      } catch (error) {
        rejected = error instanceof RangeError;
      }
      equal(rejected, true, fixture.id);
    } else {
      const result = mergeWithPilot(pages, fixture.rejectedPageIndexes, matcher);
      equal(result, { ...fixture.expected, pageUris: pages.map((page) => page.uri) }, fixture.id);
      equal(result, mergeOcrPages(pages, fixture.rejectedPageIndexes), `${fixture.id}/baseline`);
    }
  }
  const invalid = [
    [[NaN], [], [], []],
    [[Infinity], [], [], []],
    [[-1], [], [], []],
    [[0.5], [], [], []],
    [[65536], [], [], []],
    [[], [0], [], []],
    [[], [0, 1], [], []],
    [[], [4294967296, 0], [], []],
    [[], [0, -1], [], []],
    [[], [], [], [0, 1]],
    [[], [0.5, 0], [], []],
  ];
  for (const [index, input] of invalid.entries()) {
    let rejected = false;
    try {
      matcher.findOverlap(input[0] ?? [], input[1] ?? [], input[2] ?? [], input[3] ?? []);
    } catch {
      rejected = true;
    }
    equal(rejected, true, `malformed/${index}`);
  }
  equal(matcher.findOverlap([], [], [], []), 0, "empty pages");
  // Lone surrogates must remain literal code units, not UTF-8 replacement chars.
  equal(
    matcher.findOverlap(
      [0xd800, 97, 98, 99, 100, 101, 102, 103, 104, 105, 106],
      [0, 5, 5, 6],
      [0xd800, 97, 98, 99, 100, 101, 102, 103, 104, 105, 106],
      [0, 5, 5, 6]
    ),
    2,
    "lone surrogate"
  );
  equal(
    matcher.findOverlap(
      [0xd800, 97, 98, 99, 100, 101, 102, 103, 104, 105, 106],
      [0, 5, 5, 6],
      [0xd801, 97, 98, 99, 100, 101, 102, 103, 104, 105, 106],
      [0, 5, 5, 6]
    ),
    0,
    "different lone surrogates must remain distinct"
  );
  for (const workload of workloads()) {
    const pages = pagesFor(workload.pages);
    equal(mergeWithPilot(pages, [], matcher), mergeOcrPages(pages), workload.id);
  }
  return {
    abi: 1,
    mergeCases: corpus.mergeCases.length,
    malformedCases: invalid.length,
    workloadCases: workloads().length,
  };
}

/** One paired campaign. Caller records release-device/profiler metadata. */
export function measure(matcher: PilotMatcher, warmups = 100, operations = 1000): object {
  const samples: object[] = [];
  let consumed = 0;
  for (const workload of workloads()) {
    const pages = pagesFor(workload.pages);
    for (let run = 0; run < 5; run++) {
      const order = run % 2 === 0 ? ["baseline", "candidate"] : ["candidate", "baseline"];
      for (const implementation of order) {
        const operation = () =>
          implementation === "baseline" ? mergeOcrPages(pages) : mergeWithPilot(pages, [], matcher);
        for (let index = 0; index < warmups; index++) consumed += operation().text.length;
        const raw: number[] = [];
        for (let index = 0; index < operations; index++) {
          const start = performance.now();
          const result = operation();
          raw.push(performance.now() - start);
          consumed += result.text.length;
        }
        const sorted = [...raw].sort((a, b) => a - b);
        samples.push({
          workload: workload.id,
          run,
          implementation,
          raw,
          median: sorted[Math.floor(sorted.length / 2)],
          p95: sorted[Math.ceil(sorted.length * 0.95) - 1],
        });
      }
    }
  }
  return { warmups, operations, consumed, samples };
}
