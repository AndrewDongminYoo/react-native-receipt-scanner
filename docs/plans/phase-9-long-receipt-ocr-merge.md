# Phase 9 — Long Receipt OCR Page Merge

## Status: In progress. Targeting 0.9.0.

The normative behaviour — public API, validation contract, merge algorithm, and acceptance criteria — lives in [`../specs/long-receipt-ocr-merge.md`](../specs/long-receipt-ocr-merge.md).
The boundary argument that admitted the feature lives in [`../notes/adr-008-long-receipt-merge-boundary.md`](../notes/adr-008-long-receipt-merge-boundary.md).
This document records scope, sequencing, and the decisions that shaped the implementation.
It is deliberately not a step-by-step script: the working code is the source tree, and per [`../AGENTS.md`](../AGENTS.md) persistent plans stay summaries.

## Goal

Let a user capture one very long receipt across several overlapping pages — by camera or as a set of screenshots from the gallery — and get back one ordered OCR string with the duplicated text at each seam removed once.

The package still ships raw OCR primitives only.
It does not parse the receipt, and it does not decide that two images are the same receipt (ADR-003, ADR-008).

## Architecture

Entirely in the JS layer. No Kotlin, no Objective-C, no TurboModule spec change, no codegen run.

The merge is a pure function over `ReceiptImage.ocrText` strings, sitting beside the existing `ocrFloor` gate in the same post-native stage of `scan.native.tsx`.
That placement is the point, not an implementation convenience: it is the structural argument that keeps derived-signal logic out of native code, the same one that put `ocrFloor` in JS.

The orchestration has one ordering obligation.
The OCR-floor gate partitions the native `images` array into `images` and `rejectedImages`, which destroys page order.
So `scan()` snapshots the native URI order **before** the gate and rebuilds the page list from that snapshot afterwards, mapping annotated pages back by URI.
A duplicate URI throws rather than being silently reordered.

## Key decisions

**No stitched bitmap.** Android caps the processed long edge at 3,072 pixels and iOS Vision's `minimumTextHeight` is relative to the whole image height, so compositing pages into one tall JPEG actively degrades the text this feature exists to recover. Full argument in ADR-008.

**Gallery is supported, unlike the sibling Flutter package.** Its spec restricts merging to the camera because gallery selection order is unreliable. Both gallery paths in this repo were verified strictly serial and order-preserving — `RNGalleryPickerDelegate.processNextQueuedItem` and `CropEditorActivity.loadNextImage` — and the gallery path is what covers a long electronic receipt captured as several screenshots. Where the picker's order is not the user's intended order, the seam simply fails to match and is reported; no text is lost.

**Unproven seams are reported, never guessed.** An overlap is removed only when a window comparison clears its threshold. Otherwise both pages' lines are emitted in full and the boundary index is recorded. The merger never compares non-adjacent pages and never removes globally repeated lines such as totals — that would be receipt-domain inference.

**`isComplete` is narrower than its name, and says so.** Both platforms silently drop a page that fails to decode (`didFinishOneItem:` appends only non-nil results; `CropEditorActivity` returns only finished URIs), and JS cannot see that. The field means "everything that came back joined up". Its JSDoc states the limitation in the first sentence rather than in a footnote. The native page-count signal that would close it is deferred — see the spec's §Follow-Ups.

**The algorithm ports the Flutter package's shipped implementation, not its spec prose.** The two disagree on the candidate tie-break, and the implementation is what its tests fix. The divergence and the two guards missing from that spec text are recorded in this package's spec.

## Scope

New: `src/mergeOcrPages.ts` (pure merger, no imports beyond types) and its unit-test peer.

Modified, by layer:

- **JS** — `types.ts` (`MergedOcrResult`, `ScanReceiptOptions.mergeOcrPages`, `ScanReceiptResult.mergedOcr`, defaults), `scan.native.tsx` (validation, order snapshot and restore, merge delegation), `index.tsx` (exports), `__tests__`.
- **Example app** — `example/src/App.tsx`: the option toggle plus a result section rendering `mergedOcr`. The example app is this repo's only integration test, so shipping the option without a way to exercise it would leave the feature unverified against real native output. The toggle forwards an invalid combination as-is on purpose, so the demo surfaces the real `INVALID_MERGE_OPTION` rejection rather than hiding it.
- **Docs** — `specs/api-contract.md`, `specs/scan-pipeline.md`, `README.md`, `CLAUDE.md`, `AGENTS.md`, `docs/AGENTS.md` (normative-spec list).

`src/scan.tsx` (web) is unchanged: it always resolves `"cancelled"`, and a cancelled result carries no `mergedOcr`.

No native **code** changes. Two native doc comments do: `ScanOptions.kt` and `RNScanOptions.h` both told contributors to keep their field set in sync with `src/types.ts` without qualification, which a JS-only option contradicts — `ocrFloor` had already been silently exempt. Both now name the carve-out. `android/AGENTS.md` and `ios/AGENTS.md` are unchanged.

## Out of scope

A stitched JPEG, PDF, or bitmap; any native change; page reordering; non-adjacent comparison; global repeated-line removal; merging `ocrLines` geometry across pages; public threshold configuration; a guided overlap-capture UI; and any claimed maximum receipt length or aspect ratio.
Full list with reasons in the spec's §Out of Scope.

## Verification

Gate: `yarn typecheck && yarn lint && yarn test && trunk fmt && trunk check`.
No native build is required for correctness, since no native file changes.
`/codex-review` runs on the branch diff before the PR is opened.

Required test cases are enumerated in the spec's §Testing Strategy.
The ten-page merge timing bound is set from a measured value, not an assumed one.

Not observed, and not claimed: **the feature has never run against real OCR output.** The example-app control exists but was not launched — `example/ios` has a pre-existing `Podfile.lock` versus `Pods/Manifest.lock` mismatch that blocks the iOS run, and a device pass is a heavy job the machine takes one at a time. Every claim here rests on unit tests over synthetic OCR strings.

That gap matters most where synthetic text is least like real output: whether the 0.85 / 0.92 similarity thresholds hold when both platforms' recognizers segment the _same_ physical line differently on either side of a seam. Run the example app on one device per platform with a genuinely long receipt before release, and treat an unmatched boundary there as a threshold-calibration finding, not a test failure.
