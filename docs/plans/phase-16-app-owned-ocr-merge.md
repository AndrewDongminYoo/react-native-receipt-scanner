# App-owned OCR page merge migration

## Approved direction

The operator confirmed on 2026-11-11 that the current RN native candidate is not adopted and production JS/Dart remain in place.
The pilot delivered an implementation and parity evidence; the measured RN candidate failed the latency adoption criterion.
UTF-16 conversion and array copying are possible overhead sources, not a confirmed diagnosis without phase profiling.
Flutter physical-device performance remains unverified; RN results do not establish a general FFI failure.

Prepare application ownership of page merging before deprecating the library option.
First preserve order and diagnostics, then verify the alternative against existing behavior, then announce deprecation; removal requires a separately announced compatibility-breaking release.
No removal date or release number is approved here.

## Starting constraint

Before this migration, `scan.native.tsx` partitioned native results into accepted `images` and `rejectedImages` before returning.
Only the optional legacy merge retained the original returned-page order, in `mergedOcr.pageUris`.
Without new metadata, an app cannot recover rejected-page positions by concatenating those arrays when the legacy option is off.
The [existing merge specification](../specs/long-receipt-ocr-merge.md) remains the behavior contract; do not redefine its algorithm or normalization.

## Implemented contract and root implementation choices

- Add optional public `ScanReceiptResult.pageUris` for source compatibility with existing result objects.
  The updated JS runtime populates it independently of OCR or `mergeOcrPages`, before floor partitioning, including floor-rejected pages in their original native-return order.
  The web/cancelled path returns an empty list.
  This is returned-page order, not a guarantee of the user's intended gallery selection order.
- Preserve existing legacy merge validation and duplicate-URI behavior.
  Do not introduce new scan errors for callers with the merge option disabled.
  The app-owned merger validates unique order metadata and a one-to-one mapping to accepted/rejected images; missing metadata, duplicate URIs, missing expected pages and unlisted pages are explicit errors rather than guessed order or silently dropped content.
- Put a dependency-free reference implementation in `example/src/receiptMerge.ts`, owned by the consuming app and using only public image/result types.
  It copies the existing JS matching/assembly behavior while the legacy implementation remains available for compatibility.
  It does not import the library's private merger or require the deprecated option to obtain order.
  App-owned result types retain text, completeness, page URIs, unmatched boundaries and rejected indexes.
- Keep rejected pages' usable text, blank/OCR-missing-page rejection, seam indexes, deepest exact overlap, UTF-16 length and JavaScript normalization unchanged.
  Preserve all-rejected results and cancellation behavior.
  App validation retains the demo's existing `INVALID_MERGE_OPTION` behavior before acquisition for OCR-disabled or invalid-page-count combinations.
- Wire the example's existing merge toggle to app processing without changing layout, labels or styles.
  The example stops forwarding the library merge flag and combines the app result with the scan result locally.
- After the alternative passes the existing corpus and integration regressions, mark only `ScanReceiptOptions.mergeOcrPages` deprecated in types and migration guidance.
  Keep the option, defaults, validation, `MergedOcrResult`, `mergedOcr`, native acquisition and web fallback compatible.
  Do not introduce another package-level merge API or dependency.

## Missing-page boundary

The ordering snapshot includes every page returned to JS, including OCR-floor rejects.
Missing OCR text remains a rejected page with unmatched adjacent seams.
A page missing from the app's expected URI map is a structural error, matching the legacy failure policy.
Neither the current merger nor the proposed metadata detects a page discarded by native processing before JS receives it.
`isComplete` continues to cover returned pages only; do not turn unknown native completeness into a success claim.
A native page-count/dropped-page signal would require a separately scoped cross-platform change.

## Owned files

Root implementation owns `src/types.ts`, `src/scan.native.tsx`, `src/scan.tsx`, `example/src/receiptMerge.ts`, `example/src/App.tsx`, `src/__tests__/appMergeOcrPages.test.ts`, and targeted assertions in existing scan tests.
Documentation updates are limited to this plan, the existing pilot report, merge/API specifications, ADR-008 and the README migration guidance.
No worker may stage or commit; the builder check was read-only and found no important unanswered question.

## Delivery and checks

1. Record the approved no-adoption outcome in the existing pilot report without claiming that FFI implementation failed or Flutter was measured.
2. Write a failing scan regression for order metadata with interleaved accepted/rejected pages and the legacy merge option disabled.
   Add the JS result metadata with no native API change.
3. Add app reference regressions first, then the app merger and example wiring.
   Reuse the checksum-verified shared corpus for literal expected results, plus legacy-order/floor/cancellation/error and JS-specific normalization cases.
4. Verify the alternative and legacy path against the same raw native inputs and expected diagnostics.
   Prove the new reader rejects deliberately corrupted fixture expectations or order inputs before trusting its pass.
5. Only then add deprecation copy and update the existing public API/merge docs and README.
   Explain the order-metadata prerequisite for older results and the native-drop limitation.
6. Run `yarn typecheck`, `yarn lint`, `yarn test`, `yarn prepare`, scoped `trunk fmt`, then `trunk check`.
   Bundle the example for native and web surfaces as applicable; no phone installation or native-platform source change is required.
   Review compatibility and documentation independently before publication under the retained PR workflow.

Scope: this RN repository and its example only; Flutter remains on its current Dart implementation.
No native pilot removal, scanner redesign, dependency upgrade, geometry change or new OCR behavior is included.

## Verification record

The order test first failed with missing metadata, then passed after the JS result change.
The app reference passed all 29 existing merge fixtures plus orchestration/error regressions; deliberately corrupted literal expectations failed in an independent app-only reader.
The actual example handler was tested against its pre-migration implementation and then the migrated implementation, proving legacy-option forwarding was removed and app validation retained.
Final host checks passed on Node 24.20.0: typecheck, lint, 216 Jest tests, library JS/type build, scoped formatting and modified-file Trunk checks.
The example web build and sequential iOS/Android Metro production bundles passed.
Two independent read-only reviews found no blocking implementation or migration-contract issue.
No native source or device operation changed, and no native device build/runtime pass is claimed for this JS-only migration.
