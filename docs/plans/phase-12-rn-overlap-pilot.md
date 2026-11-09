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

## Limits

No production adoption, new public API, unrelated dependency upgrades or receipt-domain logic.
No daily-iPhone writes without action-specific approval.
Missing device/profiler evidence is incomplete; thresholds remain those accepted before measurement.
The main workspace is the execution workspace; no worktree is needed.
