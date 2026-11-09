# Shared OCR Contract Fixtures

This directory owns the synthetic input/output contract shared with `AndrewDongminYoo/flutter_receipt_scanner`.
`v1/contract.json` contains literal expectations for merge results, OCR-floor behavior and geometry; `v1/schema.json` describes its JSON format.
The checker enforces the supported corpus version and snapshot integrity; the schema is descriptive and is not executed as a JSON Schema validator.
The JS/Dart readers execute production postprocessing code, using `file:///contract/page-<index>.jpg` as synthetic page URIs.
Kotlin readers execute the production `OcrGeometry` and `QuadGeometry` helpers through each plugin's normal Gradle unit-test graph.
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

## Geometry numeric contract

The additive `geometry` section has 13 quantization, 14 histogram/dominance, 7 correction, 14 box and 16 quadrilateral cases.
Angles are clockwise-positive degrees; exact ties between quarter turns round toward positive infinity, so -45 maps to 0 and -135 maps to 270.
Histogram bins are ordered 0/90/180/270; only finite angles count toward the five-line floor and the inclusive 70% majority.
`NaN`, `+Infinity` and `-Infinity` are explicit string tokens accepted only in angle lists, never JSON numbers or direct quantizer inputs.
Correction is the clockwise turn that undoes the supplied text angle.

Boxes use top-left-origin integer pixels as `[x, y, width, height]`; frames are `[width, height]`.
Rotation and clamping have exact integer expectations, including the swapped output frame at 90/270 degrees and null for an empty intersection.
Quad corners are `[tlx, tly, trx, try, brx, bry, blx, bly]` in a consistent coordinate space.
Kotlin converts angle and quad numbers to IEEE-754 binary32; histogram counts, selected turns and distortion decisions are exact, with no epsilon applied to thresholds.
The opposite-edge ratio is allowed at 2.2 and rejected above it; both width and height pairs have below/at/above cases.
There is no shortest/longest-edge rejection: a valid 25:1 receipt stays valid.
Future floating-point box readers may compare these integer-valued coordinates with absolute tolerance 0.000001 pixels; that tolerance does not relax discrete decisions or thresholds.
This corpus covers synthetic helper behavior, not scanner UI, real OCR accuracy or image processing.

Run from RN's `example/android` directory:

```bash
./gradlew :react-native-receipt-scanner:testDebugUnitTest
```

Prepare Flutter's ignored Gradle wrapper from `flutter_receipt_scanner/example`, then run the suite:

```bash
flutter build apk --debug --config-only
cd android
./gradlew :flutter_receipt_scanner_android:testDebugUnitTest
```

Both readers load the checked-in corpus as a Gradle test resource and need neither the other checkout nor a device.
For a behavioral negative check, change `negative-45-tie.expected` to 270 or `exactly-70-percent.expectedDominant` to null, then run with `--tests '*GeometryConformanceTest'`.
Each must fail its named assertion, independently of snapshot checksums; restore the fixture before the passing run.
Gson is a test-only JSON dependency because Android's stub `org.json` is not a JVM parser; no parser is added to production.

## Apple production geometry readers (#66)

RN compiles `ios/RNOcrGeometry.m` and `ios/RNQuadGeometry.m` directly into an iOS Simulator executable linked with UIKit.
No React Native mock, copied algorithm, source-text assertion or macOS substitution is used.
Run from this repository with an already booted Simulator UUID:

```bash
bash scripts/apple-geometry/run.sh <simulator-uuid>
# Optional second argument reads an isolated altered corpus for a negative check.
bash scripts/apple-geometry/run.sh <simulator-uuid> /tmp/corrupted-contract.json
```

The reader executes all 64 geometry cases and compares pixel coordinates with absolute tolerance 0.000001; counts, turns and distortion decisions remain exact.
Each box also round-trips through Vision's bottom-left normalized convention using the production converter, and literal non-square angle vectors verify pixel scaling and sign.
The geometry helpers accept canonical clockwise degrees; the iOS image pipeline negates its counterclockwise pixel rotation before calling them, so the reader must not negate the corpus degrees again.
Flutter's corresponding command is `SIMULATOR_UDID=<simulator-uuid> VERIFY_CORRUPTION=1 bash flutter_receipt_scanner_ios/ios/tests/run-geometry.sh` from `AndrewDongminYoo/flutter_receipt_scanner`; its reader uses that repository's vendored snapshot and actual Swift helpers.
Native iOS CI runs the RN reader before the example Simulator build, independently of the Turborepo build cache.

For a behavioral negative check, copy the corpus to a temporary file and change `negative-45-tie.expected` to 270 or `exactly-70-percent.expectedDominant` to null.
Pass that file as the second argument: the named assertion must fail with a nonzero exit, without invoking the checksum checker.
Restore/use the checked-in corpus for the passing run.
These tests exercise synthetic geometry only; they do not establish real OCR accuracy, scanner UI behavior or pixel-image rotation correctness.
