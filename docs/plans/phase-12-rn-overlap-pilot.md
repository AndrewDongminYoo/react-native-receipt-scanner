# RN Overlap Matcher Pilot

## Contract

Implement [issue #69](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/issues/69) against the accepted [pilot specification](../specs/ocr-overlap-pilot.md).
Keep `ReceiptScanner`, public exports, web imports and the production JS matcher unchanged.
The internal synchronous module consumes canonical C ABI v1; hosts retain normalization, text assembly and quality policy.

## Implementation

1. Add regressions for numeric UTF-16 transport, invalid integers, descriptors, ABI compatibility and shared-corpus parity.
2. Add `NativeReceiptOverlapPilot` Codegen input and a C++ adapter under `cpp/overlap_pilot`, outside the immutable canonical snapshot.
   Reject non-finite, fractional, negative and overflowing values before casting; keep buffers local to each call.
3. Register the separate module using library Android CMake/autolinking and an iOS module provider.
   Include only required adapter/core source files in CocoaPods; retain the acquisition module registration.
4. Add an explicit synthetic harness, a frozen seeded workload generator and packed-package consumer tooling.
   Compare complete host operations against the unchanged JS implementation, not core-only time.
5. Validate actual packed consumers on Android and iOS Simulator, native-free web import, applicable Android 16 KiB alignment, host sanitizers and concurrent calls.
6. Record exact core/baseline/candidate revisions, samples, integration evidence and every incomplete measurement in the pilot report.
   Release-device latency, memory and paired build budgets remain separate gates; no device result is inferred from a simulator.

## Verification

Run `yarn typecheck`, `yarn lint`, `yarn test`, scoped `trunk fmt`, then `trunk check`.
Run canonical source attestation and host CMake/CTest, adapter transport regressions, Codegen, packed-source inspection, web builds and platform example/packed-consumer builds sequentially.
Prove fixture expectation corruption fails before treating corpus parity as evidence.
Use the accepted 100 warm-ups, 1,000 operations and five paired runs for device performance; preserve raw samples and frozen generator identity.
Inspect the final diff for contract omissions and source inclusion/registration failures before hosted review.

## Source-package measurement

Compare baseline and candidate `yarn pack` archives offline with `pilot/package-size.py`.
The reader owns archive identity, per-file payload accounting, separate compressed growth and the existing 256 KiB distribution budget.
Jest regressions use real synthetic tarballs to verify the exact threshold, budget rejection, shrinking packages and malformed archives.
Record the source revisions, shared build toolchain and archive checksums with measured results in the pilot report.
This source-package gate does not evaluate physical-device, native-code size or build-time budgets.

## Native-code size measurement

Freeze baseline `992a68d084b061066a7ca225bf982ee10405945a` and candidate `293e2f085bcbd4df96aef5478416de0e5a924ebe` before building.
Use independent packed consumers with identical application sources, dependency versions and Release settings.
Build all four Android ABIs and unsigned iOS device arm64 without installing or launching either application.
Count complete stripped native images conservatively, including registration and linked runtime changes; compare each architecture against the unchanged 131,072-byte budget.
Report APK and consistently compressed unsigned iOS app archives separately from native-image totals.
Preserve archives, hashes, build logs, native-tool inspection and per-image accounting in the local review evidence; reject wrong architectures, unstripped images and missing app images before trusting totals.
Record results and verification limits in the existing report and reproducible commands in the harness guide.
This measurement does not supply release-device latency, retained-memory or paired build-time evidence.

## Build-time measurement

Reuse the frozen native-size baseline/candidate archives and their independent packed consumers after verifying installed source bytes and unchanged application inputs.
Run Android and iOS sequentially, alternating baseline/candidate order across five pairs per platform.
For each side, reset generated build products outside the timed interval, time a Release build with dependencies already cached, then time the identical no-op command without editing inputs or resetting products.
Disable Android build/configuration caches and dependency networking; retain compiler/dependency caches and document the clean boundary on both platforms.
Preserve monotonic wall-clock samples, complete command logs, host load and toolchain identities.
Compare the median of five candidate times against the median of five baseline times using the accepted clean/no-op budgets; report per-pair variability and do not reinterpret inconclusive results as acceptance.
No application installation, launch or physical-device write is needed.
This measurement supplies no release-device latency or retained-memory evidence.

## Limits

No production adoption, new public API, unrelated dependency upgrades or receipt-domain logic.
No daily-iPhone writes without action-specific approval.
Missing device/profiler evidence is incomplete; thresholds remain those accepted before measurement.
The main workspace is the execution workspace; no worktree is needed.
