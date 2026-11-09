# Phase 10: Shared OCR Contract Conformance

Tracking: [shared OCR contracts and FFI pilot](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/issues/60), with this batch covering the shared-contract decision in #62 and implementation in #63 and #64.
The operator approved the proposed direction and implementation on 2026-11-08.

## Scope

Use the existing RN merge contract as the shared target: deepest adjacent exact overlap, two distinct normalized lines, and at least 12 UTF-16 code units when the overlap lines are joined with one space.
Flutter's short-overlap acceptance is an intentional existing behavior, so adopting the length floor is a documented behavior change.
Keep Unicode case and whitespace normalization, original trimmed output, rejected-page text, and public result shapes.
Shared conformance applies to corpus inputs; existing runtime-specific normalization differences, including Greek final sigma, remain separately characterized.
Keep quality calculation and enforcement in TS/Dart, including the existing absent-confidence compatibility behavior.
Keep Flutter's camera-only merge restriction and discarded-page completeness adjustment; RN's broader acquisition support and narrower completeness signal are separate policies.

## Owned changes

- RN: canonical JSON corpus, schema and provenance manifest under `src/__tests__/fixtures/ocr-contracts/v1/`; a Jest reader; `scripts/check-ocr-contracts.js`; the directly affected merge specification and this plan.
- Flutter: the same snapshot under `flutter_receipt_scanner/test/fixtures/ocr-contracts/v1/`; a Flutter test reader; the minimum-length guard in `ocr_page_merger.dart`; affected regression expectations, README, specification, work item and changelog.
- Validation configuration: a fixture-scoped RN spelling exception and a Flutter Prettier exclusion that preserves the vendored snapshot bytes.
- No native, Pigeon, public API, dependency-manifest, or lockfile changes are required.

## Implementation sequence

1. Define literal, synthetic expectations for merge boundaries and quality gating in one versioned corpus.
2. Run both readers before changing production behavior; record Flutter's short-window failures and RN's passing baseline.
3. Add the approved Flutter guard and update its intentional short-overlap regression.
4. Verify snapshot integrity locally and compare the Flutter copy against the canonical RN files without a network fetch.
5. Correct the directly affected contract prose and run both repositories' declared checks.
6. Review the final diff, including untracked fixtures and documentation, before handing off the local changes.

## Acceptance

- Both readers execute all 29 merge and 21 quality cases and produce the same expected diagnostics for the shared input domain.
- The corpus covers 11/12/13 UTF-16 units, whitespace-normalized length, supplementary characters, case normalization, repeated rows, deep overlap, empty/rejected pages, invalid indexes, order preservation and confidence boundaries.
- A changed copied fixture or manifest fails the snapshot checker; an unsupported corpus version fails even with a refreshed checksum, and a wrong expected result fails the corresponding behavioral reader.
- RN: `yarn typecheck`, `yarn lint`, `yarn test`, scoped `trunk fmt`, and `trunk check`.
- Flutter: workspace analysis/tests, package CI-equivalent analysis and coverage, scoped Dart formatting, and scoped Trunk formatting/checks.
- The snapshot can run from either checkout alone; an explicit local comparison checks both copies together.

## Provenance and delivery

The manifest records SHA-256 hashes and the implementation baseline commit.
Its `sourceCommit` remains null while the new corpus is uncommitted; the baseline must not be presented as a commit containing the corpus.
After the canonical corpus has a commit, pin that commit in both manifests before publishing a release or declaring the vendoring handoff complete.
The PRs deliver the shared contract and Flutter alignment; publication and native-core adoption remain separate steps.

## Deferred pilot

The approved follow-up boundary is a C ABI function that returns overlap length from borrowed normalized UTF-16 buffers; TS/Dart still assemble output and apply quality policy.
Geometry migration, OCR replacement, receipt-domain parsing and native omitted-page diagnostics are outside this batch.
Production adoption requires both mobile adapter reports and the separate decision in issue #70; ADR-008's JS-only production decision remains in force meanwhile.
The [pilot decision](../specs/ocr-overlap-pilot.md) supplies the measurement protocol, acceptance budgets, source-distribution policy and rollback for issue #62.
The operator approved those criteria separately on 2026-11-09; native implementation and production adoption remain subsequent work.

## Geometry conformance follow-up (#65)

Extend the existing v1 corpus and schema with synthetic angle, correction, box and quad cases; keep merge/quality expectations unchanged.
The fixture guide owns the numeric contract and commands.
RN owns the canonical snapshot and Kotlin reader; Flutter vendors the exact snapshot and runs its own production Kotlin helpers.
Only tests, test resources/dependencies, CI wiring and directly affected documentation change; public APIs, geometry algorithms and production pilot defaults remain unchanged.

1. Add literal boundary expectations and a parameterized JVM reader to each normal Android Gradle test graph.
2. Cover quarter-turn ties, finite-sample and majority boundaries, output frames, empty clamps, degenerate quads, opposite-edge ratios and long receipts.
3. Deliberately corrupt tie and threshold expectations, observe named assertion failures in both readers, restore the corpus and rerun.
4. Run normal Kotlin suites and repository gates, compare snapshot bytes, commit the canonical corpus, then pin its retained commit in both manifests.
5. Review and deliver one PR per repository; keep #65 open until both implementations are verified.

## Apple geometry conformance follow-up (#66)

Execute the same 64 geometry cases through production Objective-C and Swift helpers on iOS Simulator.
RN owns `scripts/apple-geometry/`, native iOS CI wiring and the fixture guide; Flutter owns its corresponding native reader, invocation and CI.
Preserve all public APIs and production algorithms, and keep Vision coordinate conversion and canonical clockwise geometry distinct from iOS counterclockwise pixel transforms.
The fixture guide remains the numeric contract; no corpus/schema/provenance changes are required.

1. Compile actual Apple helper sources into a UIKit-linked Simulator executable in each repository.
2. Read the existing local corpus, assert exact discrete decisions and pixel values within 0.000001, and test Vision conversion/sign on non-square frames.
3. Corrupt isolated tie and majority expectations, require named behavioral failures, then rerun the original corpus.
4. Run declared repository gates and relevant example Simulator builds sequentially; review and deliver one linked PR per repository.
5. Keep #66 open until both implementations and their required gates are verified.

The change adds no visible product output, so operator visual approval is not applicable.

## Parent geometry acceptance (#61)

Evidence reconciled on 2026-11-10 against the merged child PRs below.
The bounded acceptance criterion in [#61](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/issues/61) is satisfied: both repositories execute the same versioned geometry cases against their production Kotlin and Apple helpers, with explicit coordinate adapters and numeric comparisons.
This is an acceptance record for synthetic helper conformance; it does not change algorithms or public APIs.

| Production reader                                 | Merged delivery                                                                    | Verified PR head                           | Passing native CI                                                                                                                                                  |
| ------------------------------------------------- | ---------------------------------------------------------------------------------- | ------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| RN Kotlin `OcrGeometry` / `QuadGeometry`          | [RN #76](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/pull/76) | `bc948775ac039ad78607d46e427e386754069924` | [Android unit-test graph](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/actions/runs/42600423800/job/129103213071)                              |
| Flutter Kotlin `OcrGeometry` / `QuadGeometry`     | [Flutter #39](https://github.com/AndrewDongminYoo/flutter_receipt_scanner/pull/39) | `f2bf49b39d62045bcba87e6c229e6c54402df8c8` | [Android unit-test graph](https://github.com/AndrewDongminYoo/flutter_receipt_scanner/actions/runs/42600577063/job/129103725238)                                   |
| RN Objective-C `RNOcrGeometry` / `RNQuadGeometry` | [RN #77](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/pull/77) | `458c23bfe67bd28bb442db2de444c8afbfdec788` | [Simulator conformance and example build](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/actions/runs/42610786676/job/129139166868)              |
| Flutter Swift `OcrGeometry` / `QuadDetector`      | [Flutter #40](https://github.com/AndrewDongminYoo/flutter_receipt_scanner/pull/40) | `f789902c307c8af926f2deb3c63b41e660ea7f11` | [Simulator conformance, negative control and example build](https://github.com/AndrewDongminYoo/flutter_receipt_scanner/actions/runs/42716701761/job/129500009604) |

- **One versioned corpus:** all four readers enumerate the 64 geometry cases in the shared v1 snapshot.
  The canonical manifest pins RN source `0179aa4663d1dedd9aa7dd9268ca1744bd6adbc3`; its corpus, schema and manifest match the Flutter copy byte-for-byte.
  The [fixture guide](../../src/__tests__/fixtures/ocr-contracts/README.md#geometry-numeric-contract) owns the group counts, numeric rules and repeatable commands.
- **Actual production execution:** the Kotlin CI logs show the normal `testDebugUnitTest` tasks completing successfully; each parameterized reader loads every geometry group.
  The Apple CI logs explicitly report 64 passing cases, and the runners compile the production helper source files for iOS Simulator.
  The separate Swift macOS batch host still excludes UIKit/Vision geometry; it is not the Apple geometry acceptance path.
- **Coordinates and comparisons:** boxes use canonical clockwise, top-left pixels; Apple adapters explicitly convert Vision bottom-left normalized coordinates and scale angle vectors in pixel space.
  The Swift quad adapter reflects Y without changing corner order, and geometry degrees remain distinct from iOS pixel-rotation degrees.
  Discrete decisions are exact; Kotlin integer boxes and Swift corpus boxes compare exactly, RN Apple boxes allow absolute error of 0.000001 pixels, and the additional Vision conversion checks allow 0.000001 pixels/degrees as applicable.
- **Negative controls:** the Kotlin delivery records named failures for corrupted tie and majority expectations and for reciprocal-ratio implementation mutations, followed by restored passing suites.
  RN Apple recorded named tie, majority and category-count failures; Flutter Apple CI rejects the corrupted `rotate-90` expected X coordinate, and its runner repair also verifies reordered box cases.
  The earlier Apple follow-up plan listed tie/majority corruption; separate runs of those variants remain unverified for Swift.
  This limit does not change the parent issue's stated requirement to execute the same geometry cases with explicit adapters and tolerances.
- **Boundaries retained:** the delivery diffs add tests, fixtures, test resources/dependencies, CI and documentation; production geometry and public APIs remain unchanged.
  This does not establish real OCR accuracy, scanner UI correctness, image-pixel rotation correctness, release-device performance or native matcher adoption.
  Geometry migration to C remains outside #61; the overlap pilot and its adoption decision stay separate under #68, #69 and #70.

This reconciliation reads retained CI logs, merged source and snapshot bytes; it does not claim a new native execution or a new measurement campaign.
