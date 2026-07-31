# Long Receipt OCR Page Merge (`mergeOcrPages`)

**Spec version:** 1.0
**Written:** 2026-07-31
**Parent document:** [`api-contract.md`](./api-contract.md)
**Related decisions:** ADR-003 (package boundaries), ADR-008 (this feature's boundary argument)
**Platforms:** JS layer only — no native change on iOS or Android
**Target release:** 0.9.0

## Status

Proposed. This document is the normative reference for the behaviour once implemented.

## Problem

A receipt longer than a single frame cannot be captured at usable text resolution in one shot.
The package already lets a user capture up to ten pages through `maxPages`, but it returns them as independent JPEGs with independent OCR strings.
Nothing ties those pages into one logical receipt, and the text where two captures overlap is duplicated in the output.

The same problem occurs without a camera: a long electronic receipt is commonly captured as several screenshots and picked from the gallery.

### Why this does not return one stitched image

Compositing the pages into a single tall JPEG makes OCR worse on both platforms:

- Android caps the processed long edge at 3,072 pixels (`ImageProcessor.MAX_PROCESSING_DIM`). A taller logical receipt is downsampled to fit, so each character gets fewer pixels.
- iOS Vision's `minimumTextHeight` is a fraction of the **whole image height** (package default 1/32). The taller the image, the larger a character must be to survive recognition at all.

Both effects push in the same direction: the more pages you merge into one bitmap, the less text you recover.
Since the value the consumer wants is the receipt's text, the package merges the text and leaves each page JPEG untouched.

This mirrors the sibling `flutter_receipt_scanner` package's spec 0001, which reached the same conclusion and lists "returning one stitched receipt JPEG, PDF, or bitmap" as out of scope.

## Decision

Add an opt-in `mergeOcrPages` flag.
When enabled, the JS layer keeps every native JPEG unchanged, walks the pages in native order, removes **proven** duplicated text at each adjacent seam, and returns one merged OCR string plus per-seam diagnostics.

The package never decides that two images are or are not the same receipt.
It only reports whether an overlap could be proven at each adjacent boundary.
An unproven boundary emits both pages' text in full and records the boundary index — no text is ever discarded to make a result look clean.

## Scope Boundary (ADR-003)

The merge consumes `ReceiptImage.ocrText`, which ADR-003 already lists as an in-scope primitive ("On-device OCR text (raw string)").
It produces another raw string plus integer indexes. It does not parse merchants, amounts, dates, or line items, and it does not compare non-adjacent pages or globally remove repeated lines such as headers, subtotals, or totals.

The merge orchestration and the merger itself live in the JS layer for the same reason `ocrFloor` does: keeping derived-signal logic out of native code preserves the boundary rule structurally rather than by convention.

## Public API

### Option

`ScanReceiptOptions` gains one backward-compatible field:

```ts
/**
 * Merge OCR text across the captured pages into one ordered string on
 * {@link ScanReceiptResult.mergedOcr}, removing proven duplicate text where
 * two adjacent captures overlap. Page JPEGs are never combined — see
 * `docs/specs/long-receipt-ocr-merge.md` for why a single tall image would
 * lose text resolution.
 *
 * Requires `ocr: true` and `maxPages >= 2`.
 *
 * @defaultValue `false`
 */
mergeOcrPages?: boolean;
```

`DEFAULT_SCAN_OPTIONS.mergeOcrPages` is `false`, so the option is forwarded to the native module like every other field.
Both native option parsers read a fixed key whitelist (`ScanOptions.from` via `hasKey`, `RNScanOptions.optionsFromDictionary` via class checks with defaults) and ignore unknown keys, so forwarding a JS-only field is inert on both platforms.

### Validation

When `mergeOcrPages === true`, `scan()` throws **before** the native module is called — no scanner UI opens:

| Condition      | Reason                                               |
| -------------- | ---------------------------------------------------- |
| `ocr !== true` | There is no text to merge.                           |
| `maxPages < 2` | A merge needs at least two pages to have a boundary. |

The thrown error follows the existing single-class convention set by `InvalidOcrLanguageError` in `src/scan.native.tsx` — a typed `Error` subclass carrying a literal `code`. No new error hierarchy is introduced.

```ts
class InvalidMergeOptionError extends Error {
  readonly code: "INVALID_MERGE_OPTION" = "INVALID_MERGE_OPTION";
}
```

`source` is **not** validated. Both acquisition paths are supported — see [Page Ordering](#page-ordering).

### Result

`ScanReceiptResult` gains one optional field, populated when `mergeOcrPages === true` and the **native** scan succeeded:

```ts
mergedOcr?: MergedOcrResult;
```

```ts
export type MergedOcrResult = {
  /** Merged text, newline-joined, in native page order with proven overlap removed once. */
  text: string;
  /**
   * True only when every adjacent boundary between the **returned** pages was
   * proven and no returned page was rejected. It cannot detect a page the
   * native layer dropped before returning (see "Known limitation" below), so
   * it means "everything that came back joined up", not "the whole receipt is
   * here".
   */
  isComplete: boolean;
  /** Page URIs in native order. The index space for both index arrays below. */
  pageUris: string[];
  /** Boundary index `i` is the seam between `pageUris[i]` and `pageUris[i + 1]`. */
  unmatchedBoundaryIndexes: number[];
  /** Pages with no usable OCR text, or rejected by the OCR floor. */
  rejectedPageIndexes: number[];
};
```

`images`, `rejectedImages`, their JPEG files, and their per-page `ocrText` keep their current behaviour.
Per-page `ocrLines` geometry is **not** merged and stays attached to its own page — `mergedOcr.text` has no corresponding geometry, because boxes from different pages live in different pixel spaces.

The gate is the **native** status, not the returned one.
When every page falls below the OCR floor the returned `status` becomes `"rejected"`, and `mergedOcr` is still attached — that is precisely when a consumer needs the diagnostics to decide whether to ask for a re-shoot.
Only a `"cancelled"` scan leaves `mergedOcr` undefined, because nothing was captured.
The web fallback (`src/scan.tsx`) always resolves `"cancelled"`, so it needs no change.

## Page Ordering

The merger consumes pages in the order the native layer returns them and never reorders.
Reordering would require inferring which pages belong together, which is exactly the judgment this feature refuses to make.

Both acquisition paths were verified to be strictly serial and order-preserving in this codebase:

| Path            | Mechanism                                                                                         |
| --------------- | ------------------------------------------------------------------------------------------------- |
| iOS camera      | `RNDocumentCameraDelegate` iterates `imageOfPageAtIndex:` from `0` to `min(pageCount, maxPages)`. |
| iOS gallery     | `RNGalleryPickerDelegate` walks `queuedItems` through `processNextQueuedItem` one at a time.      |
| Android camera  | `ReceiptScannerModule` iterates the GMS scanner's page list in list order.                        |
| Android gallery | `CropEditorActivity` drains `pendingUris` with `removeFirstOrNull()`.                             |

For the gallery path, that order is the **picker's** order, not necessarily the user's intended sequence.
`PHPickerConfiguration` is created without `selection = .ordered`, so iOS returns library order; Android's `PickMultipleVisualMedia` documents no ordering guarantee.
For sequentially captured screenshots — the electronic-receipt case — library order is chronological and therefore correct.
When it is not, the seams simply fail to match: the boundary is reported in `unmatchedBoundaryIndexes`, `isComplete` is `false`, and every line of text is still present in `text`.
The failure mode is a reported gap, never lost or silently reordered content.

The orchestration snapshots the native page order **before** the OCR-floor gate partitions `images` and `rejectedImages`, then restores that order by URI.
A duplicate page URI is an internal state error and throws rather than being silently reordered.

## Merge Algorithm

Pure function, no I/O, no dependencies: `mergeOcrPages(pages, rejectedPageIndexes) -> MergedOcrResult`.

### Line extraction

Split each page's `ocrText` on `\n`, trim each line, drop empty lines.
A page whose result is empty (or whose `ocrText` is absent) is added to `rejectedPageIndexes` and makes each of its adjacent boundaries unmatched.

### Seam matching

For each adjacent pair, compare the previous page's **suffix** windows against the next page's **prefix** windows, 1 through 8 lines each.

Comparison text for a window is its lines lowercased, with runs of whitespace collapsed to a single space, trimmed, and joined by a single space.
This normalization is used **only** for comparison; emitted text is always the original trimmed lines.
Comparison is over UTF-16 code units, matching the Dart implementation — Korean syllables are BMP, so there is no surrogate-pair divergence between the two packages.

A candidate pair is evaluated as follows, with `shorter` / `longer` being the two windows' comparison-text lengths:

| Step               | Rule                                                                                     |
| ------------------ | ---------------------------------------------------------------------------------------- |
| Threshold select   | Either window is a single line → 24 chars / 0.92 similarity. Otherwise 12 chars / 0.85.  |
| Length floor       | `shorter < minChars` → skip.                                                             |
| Length-ratio guard | `(longer - shorter) / longer > 1 - minSimilarity` → skip; the edit distance cannot pass. |
| Cost guard         | Not an exact string match and `longer > 512` → skip, to bound the Levenshtein cost.      |
| Similarity         | Exact match → `1.0`. Otherwise `1 - levenshteinDistance(left, right) / longer`.          |
| Accept             | `similarity >= minSimilarity`.                                                           |

The stricter single-line thresholds exist because one short line matches by coincidence far too easily.

### Candidate selection

Among accepted candidates, pick the highest similarity; break ties by the **larger** compared character count — a longer proven overlap is stronger evidence than a shorter one.
Remaining ties keep the first candidate found. Iteration is `rightCount` ascending in the outer loop and `leftCount` ascending in the inner loop, so a full tie removes the **fewest** lines from the next page. This ordering is part of the contract: the result must be deterministic.

An accepted seam keeps the previous page's suffix in full and removes only the matched prefix lines from the next page.
An unmatched seam appends every line of the next page and records the boundary index.

> **Divergence from `flutter_receipt_scanner` spec 0001.** That spec's prose describes the tie-break as "then the shorter normalized character count, then minimizes prefix lines removed, then minimizes suffix lines consumed", but its shipped `_Overlap.isBetterThan` breaks ties on the **larger** compared character count and stops there. This spec ports the shipped implementation, which is the behaviour its tests fix. The two guards above (`512`-character cost guard, length-ratio prefilter) are likewise in the Dart implementation but absent from its spec text.

### Completeness

`isComplete` is true only when there is at least one page, `rejectedPageIndexes` is empty, and `unmatchedBoundaryIndexes` is empty.
A single-page merge is complete when its text is non-empty, because it has no boundary to prove — though `maxPages >= 2` is required to enable the flag, a user may still finish the capture after one page.

### Known limitation: natively dropped pages

Both platforms can return fewer pages than the user captured or picked, without reporting it:

- iOS gallery: `RNGalleryPickerDelegate.didFinishOneItem:` appends only non-nil results, so an item that fails to decode disappears.
- Android gallery: `CropEditorActivity` returns only the URIs it finished processing.

The JS layer cannot distinguish "the user captured three pages" from "the user captured five and two were dropped", so `isComplete` can be `true` while a page is missing from the middle of a receipt.
The field's doc comment states this in its first sentence.
The sibling Flutter package solved this with a native `discardedPageCount`; adding the equivalent here is a native change and is deferred (see Follow-Ups).

## Errors and Diagnostics

- Invalid option combinations throw before any native UI is presented.
- Cancellation is not an error.
- Missing OCR, floor-rejected pages, and unmatched seams never throw after a completed capture — they produce an incomplete merged result.
- Two internal invariants are the deliberate exception and do reject the promise after capture: a duplicate page URI, and a snapshot URI missing from the post-gate result. Both mean the page identity the order restoration depends on is broken, so any merged text would be silently wrong about which page it came from. Neither is reachable from user input; both indicate a defect in this package or the native layer. Everything a user can cause is reported, not thrown.
- The merger must not delete uncertain content to make a result appear complete.
- Input arrays, `ocrText` values, and image URIs are not mutated.

## Out of Scope

1. Returning one stitched receipt JPEG, PDF, or bitmap.
2. Any native Kotlin, Objective-C, or TurboModule spec change.
3. Reordering pages, or comparing non-adjacent pages.
4. Globally removing repeated lines such as headers, taxes, or totals.
5. Structured receipt parsing of any kind — merchant, item, tax, total, date, payment.
6. Merging `ocrLines` geometry across pages.
7. Exposing the similarity thresholds as public configuration; they stay private in v1.
8. A guided overlap-capture UI. `VNDocumentCameraViewController` and the GMS document scanner are closed UIs with no previous-frame overlay or per-frame callback; building one would mean a custom camera on both platforms and would reverse ADR-001 and ADR-002.
9. Any claimed maximum receipt length or aspect ratio. The Flutter package's 11.0 ratio claim is backed by image fixtures and physical-device acceptance runs; this package has run neither, so it claims neither.

## Testing Strategy

The merger consumes strings only, so it is fully testable without image fixtures.

Pure unit tests in `src/__tests__/`:

1. Flag defaults to disabled and the result is byte-identical to today's.
2. Invalid `ocr` and `maxPages` combinations throw before the mocked native module is called.
3. Exact two-line overlap is removed exactly once.
4. Fuzzy Korean-plus-Latin overlap at or above threshold is removed once.
5. A candidate below threshold is preserved and records an unmatched boundary.
6. A single-line candidate is held to the stricter 24-character / 0.92 thresholds.
7. Repeated totals away from the adjacent suffix and prefix are preserved.
8. Absent or empty `ocrText` marks the page rejected and both its boundaries unmatched.
9. Floor-rejected pages appear in `pageUris` and in `rejectedPageIndexes`, and make the merge incomplete.
10. Native page order is restored after the floor gate partitions the images.
11. A duplicate page URI throws rather than being silently reordered.
12. Cancelled and rejected results carry no `mergedOcr`.
13. Inputs are not mutated.
14. A ten-page, 200-lines-per-page merge completes within a recorded time bound. The bound is set from the measured value on the development machine; no performance number is documented before it is measured.

Gate before claiming done:

```bash
yarn typecheck && yarn lint && yarn test && trunk fmt && trunk check
```

No native code changes, so no Gradle or Xcode build is required for correctness.
The example app should still exercise the flag once manually before release.

## Follow-Ups

1. Report natively dropped pages (the Flutter package's `discardedPageCount`) so `isComplete` can account for them. Requires a native change on both platforms.
2. Set `PHPickerConfiguration.selection = PHPickerConfigurationSelectionOrdered` (iOS 15+, and this package targets iOS 16) so the gallery path returns user selection order instead of library order. One native line; deferred to keep this change JS-only.
3. Reconcile the `flutter_receipt_scanner` spec 0001 tie-break prose with its shipped implementation, so the two packages' specs do not describe different algorithms.
4. Consider ordered gallery selection with a review-and-reorder surface only as its own spec.
