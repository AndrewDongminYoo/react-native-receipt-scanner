# Shared OCR Contract Fixtures

This directory owns the synthetic input/output contract shared with `AndrewDongminYoo/flutter_receipt_scanner`.
`v1/contract.json` contains literal expectations for merge results and OCR-floor behavior; `v1/schema.json` describes its JSON format.
The checker enforces the supported corpus version and snapshot integrity; the schema is descriptive and is not executed as a JSON Schema validator.
Both test readers execute production postprocessing code, using `file:///contract/page-<index>.jpg` as synthetic page URIs.
The corpus contains no captured receipts or personal data.

## Contract

Merge cases use the rules in `docs/specs/long-receipt-ocr-merge.md`: adjacent pages, deepest exact overlap, at least two distinct normalized lines, and a 12-UTF-16-unit minimum after joining comparison lines with one space.
The `known-repeated-block-ambiguity` case preserves the documented limitation: text alone cannot distinguish a repeated block from a recaptured one.
Quality cases preserve the default 12-unit/two-line floor, optional confidence enforcement, absent-confidence compatibility, and disabled/OCR-off behavior.
Native dropped-page counts and acquisition restrictions remain package-specific adapter tests.
The common assertions apply to the corpus inputs, not every Unicode string.
Each package keeps its runtime's lowercase and whitespace semantics; separate merge tests characterize the JavaScript/Dart VM difference for Greek final sigma.

## Provenance

`manifest.json` pins the exact bytes of the corpus and schema with SHA-256.
`sourceBaseCommit` identifies the RN implementation inspected when defining the snapshot; it does not claim the new corpus already exists at that commit.
`sourceCommit` is null during local development and must be pinned to a commit containing the canonical corpus and schema before the vendored snapshot is published.
Commit the canonical corpus first, then update both manifests with that commit; metadata-only pinning does not change the corpus hashes.
Do not invent a commit or silently substitute the implementation baseline.

## Updating the snapshot

1. Change the canonical corpus/schema here and review every changed expectation against the approved contract.
2. Format the two JSON files and update their manifest hashes.
3. Copy `contract.json`, `schema.json`, and `manifest.json` unchanged into Flutter's `flutter_receipt_scanner/test/fixtures/ocr-contracts/v1/` directory.
4. Run the behavioral readers in both repositories and the explicit comparison below.
5. Pin `sourceCommit` after the canonical commit exists, then repeat the comparison before release.

The ordinary test commands work offline from either checkout alone.
To compare a checked-out Flutter snapshot with the canonical RN files, run this command from the RN repository with the actual target directory:

```bash
node scripts/check-ocr-contracts.js /absolute/flutter_receipt_scanner/flutter_receipt_scanner/test/fixtures/ocr-contracts/v1
```

Without an argument, the checker verifies the canonical snapshot only.
It compares actual file bytes and manifest content; matching hashes do not prove that a published upstream commit exists.
The Jest suite also verifies that changing a copied fixture or its manifest is rejected, including an unsupported corpus version with a refreshed checksum.
