# Phase 10: Shared OCR Contract Conformance

Tracking: [shared OCR contracts and FFI pilot](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/issues/60), with this batch covering the shared-contract decision in #62 and implementation in #63 and #64.
The operator approved the proposed direction and implementation on 2026-11-08.

## Scope

Use the existing RN merge contract as the shared target: deepest adjacent exact overlap, two distinct normalized lines, and at least 12 UTF-16 code units when the overlap lines are joined with one space.
Flutter's short-overlap acceptance is an intentional existing behavior, so adopting the length floor is a documented behavior change.
Keep Unicode case and whitespace normalization, original trimmed output, rejected-page text, and public result shapes.
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

- Both readers execute all 28 merge and 20 quality cases and produce the same expected diagnostics for the shared input domain.
- The corpus covers 11/12/13 UTF-16 units, whitespace-normalized length, supplementary characters, case normalization, repeated rows, deep overlap, empty/rejected pages, invalid indexes, order preservation and confidence boundaries.
- A changed copied fixture or manifest fails the snapshot checker; a wrong expected result fails the corresponding behavioral reader.
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
