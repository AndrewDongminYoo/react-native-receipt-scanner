// Host-only adapter: the core never normalizes or assembles text.
const assert = require("node:assert/strict");
const { spawnSync } = require("node:child_process");
const { readFileSync } = require("node:fs");
const path = require("node:path");
const fixture =
  process.argv[3] ||
  path.resolve(__dirname, "../../../src/__tests__/fixtures/ocr-contracts/v1/contract.json");
const corpus = JSON.parse(readFileSync(fixture, "utf8"));
assert.equal(corpus.schemaVersion, 1);
const lines = (text) =>
  (text || "")
    .split("\n")
    .map((s) => s.trim())
    .filter(Boolean);
function encode(page) {
  const normalized = page.map((s) => s.toLowerCase().replace(/\s+/g, " ").trim());
  const data = normalized.join("");
  let offset = 0;
  const descriptors = normalized.flatMap((s) => {
    const range = [offset, s.length];
    offset += s.length;
    return range;
  });
  return [
    data.length,
    normalized.length,
    ...Array.from({ length: data.length }, (_, i) => data.charCodeAt(i)),
    ...descriptors,
  ].join(" ");
}
let boundaries = 0;
for (const test of corpus.mergeCases) {
  const pages = test.pages.map(lines);
  if (test.expectedError) {
    assert(
      test.rejectedPageIndexes.some((i) => !Number.isInteger(i) || i < 0 || i >= pages.length)
    );
    continue; // Index policy remains in the host, not the C ABI.
  }
  const pairs = pages.slice(1).map((page, i) => `${encode(pages[i])}\n${encode(page)}`);
  const result = spawnSync(process.argv[2], [], { input: pairs.join("\n"), encoding: "utf8" });
  assert.equal(result.status, 0, `${test.id}: ${result.stderr}`);
  const overlaps = result.stdout.trim() ? result.stdout.trim().split(/\s+/).map(Number) : [];
  assert.equal(overlaps.length, pairs.length);
  boundaries += pairs.length;
  const rejected = new Set(test.rejectedPageIndexes);
  pages.forEach((page, i) => {
    if (!page.length) rejected.add(i);
  });
  const unmatched = [];
  const merged = [...(pages[0] || [])];
  overlaps.forEach((overlap, i) => {
    assert(Number.isInteger(overlap) && overlap >= 0 && overlap <= pages[i + 1].length);
    if (!overlap) unmatched.push(i);
    merged.push(...pages[i + 1].slice(overlap));
  });
  assert.deepEqual(
    {
      text: merged.join("\n"),
      isComplete: pages.length > 0 && rejected.size === 0 && unmatched.length === 0,
      unmatchedBoundaryIndexes: unmatched,
      rejectedPageIndexes: [...rejected].sort((a, b) => a - b),
    },
    test.expected,
    test.id
  );
}
console.log(`${corpus.mergeCases.length} merge cases verified (${boundaries} native boundaries)`);
