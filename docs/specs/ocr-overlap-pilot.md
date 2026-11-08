# OCR Overlap Matcher Pilot

## Decision status

Accepted by the operator on 2026-11-09 for [issue #62](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/issues/62).
Proceed with a bounded native overlap-matcher experiment, while keeping the published JS and Dart implementations as the default.
The approval covers the shared compatibility contract, pilot boundary, measurement protocol and numerical acceptance budgets below.
These budgets are engineering limits, not measured results or promised speedups.
Production adoption remains the separate decision in [issue #70](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/issues/70).

## Compatibility contract

The [merge specification](long-receipt-ocr-merge.md) owns matching behavior; the versioned corpus under `src/__tests__/fixtures/ocr-contracts/v1/` owns shared examples.
This pilot must preserve deepest-first exact matching of adjacent original pages, at least two distinct normalized lines, and a minimum of 12 UTF-16 code units including one space per joined line boundary.
The approved Flutter alignment changes its former acceptance of shorter overlaps; it does not change public result shapes.
Host runtimes retain their existing Unicode lowercase and whitespace rules, including the separately characterized Greek final-sigma difference.
Shared corpus conformance does not promise identical behavior for every Unicode string or equal OCR output from Vision and ML Kit.

TS and Dart retain normalization, original trimmed output, rejected-page text, page assembly, URI ordering, diagnostics, quality calculation and OCR-floor enforcement.
Absent confidence stays compatible; an explicit confidence threshold still applies when confidence exists.
RN retains its acquisition support and returned-page completeness meaning; Flutter retains camera-only merging and its discarded-page completeness adjustment.
Geometry, image acquisition, OCR replacement and receipt-domain parsing are outside this pilot.

## Native boundary and ownership

The only native operation computes the number of overlapping lines between two already normalized pages.
The C ABI accepts borrowed UTF-16 buffers, explicit buffer lengths, and line offset/length descriptors with explicit counts.
It returns a status and writes an overlap count to caller-owned storage; success with count zero means no proven overlap.
The call is synchronous, retains no pointers, performs no heap allocation, exposes no callbacks or result handles, and uses no mutable global state.
Adapters own the buffers and keep them valid until the call returns.

ABI v1 must use fixed-width integer fields and document descriptor layout, calling convention and exported symbols in the canonical header.
Validate descriptor ranges and arithmetic before reading input; null input is allowed only for its zero-length range, and the output-count pointer is required.
Empty pages return success with count zero; malformed descriptors return an invalid-input status and zero the output when its pointer is valid.
The function must not let C++ exceptions cross the ABI.
Bounds validation covers declared ranges; callers remain responsible for supplying accessible storage of the declared size.
Expose an ABI-version query and reject an unsupported version before calling the matcher.
Any incompatible layout or semantic change requires a new ABI version and adapter compatibility review.

## Measurement protocol

Freeze the benchmark generator, seed, baseline commits, candidate commits and these criteria before collecting results.
Use only synthetic text; the shared corpus is the correctness oracle, not a representative performance sample.
Measure both adapters against their own unchanged production matcher, with the same inputs and build configuration.

| Workload | Pages | Lines per page | UTF-16 units per line | Purpose                |
| -------- | ----- | -------------- | --------------------- | ---------------------- |
| Small    | 2     | 20             | 24                    | Ordinary call overhead |
| Typical  | 5     | 100            | 48                    | Multi-page use         |
| Stress   | 20    | 500            | 96                    | Larger comparison cost |

For each workload, measure no overlap, a valid half-page overlap, a deepest full-page overlap, and repeated near-matches that fail at different depths.
Include ASCII, Korean, supplementary characters and whitespace/case variants in fixed, separately reported input sets.
Keep core-only timings as diagnostic data; acceptance reads the complete host operation from raw page text through normalization, conversion, allocation, native calls, cleanup and final merge result.
Run the equivalent complete JS/Dart operation as the baseline, and consume outputs to prevent dead-code elimination.

Use optimized release builds on one identified Android device and one identified iOS device for each adapter.
Simulator runs establish integration, not device performance acceptance.
After 100 warm-up operations, collect 1,000 operations per input set in five independent paired baseline/candidate runs, alternating execution order.
Report each run's median and p95, raw samples, device/OS, runtime/compiler versions, thermal conditions and timing method.
Compare candidate and baseline within each pair; apply the budgets below to the median of the five paired comparisons.
If the direction of the result changes across runs or timer resolution obscures a budget, report it as inconclusive and retain the default implementations.

## Acceptance budgets

Every correctness, safety and integration requirement must pass for both adapters on both mobile platforms.
Performance and cost budgets apply separately to each adapter/platform combination; a favorable average cannot hide one failing target.

| Gate                  | Acceptance                                                                                                                                                                                                                                                                |
| --------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Correctness           | All shared merge and quality cases remain unchanged through each host reader and adapter; malformed-input tests pass; intentional expectation corruption fails.                                                                                                           |
| Native safety         | ASan and UBSan host runs pass; repeated concurrent calls agree with serial results; no owned buffers or handles remain after wrapper cleanup.                                                                                                                             |
| Memory stability      | After warm-up, run five batches of 10,000 calls on a fixed typical input; retained managed-plus-native memory after quiescence stays within 1 MiB of the warm baseline and shows no cumulative growth across batches. Record profiler limitations as incomplete evidence. |
| Small/typical latency | For every input set, candidate median and p95 each increase by no more than the larger of 10% of baseline or 0.1 ms per complete operation.                                                                                                                               |
| Stress benefit        | The geometric mean of candidate/baseline median-time ratios across stress input sets is at most 0.80; no stress set's p95 increases by more than 10%.                                                                                                                     |
| Distribution size     | Packed source-package growth is at most 256 KiB per package; stripped release app native-code growth is at most 128 KiB per shipped architecture. Report compressed artifact growth separately.                                                                           |
| Build time            | Median of five paired clean builds and five paired no-op builds, with dependencies already cached, increases by no more than the larger of 10% or 15 seconds for clean builds and 10% or 1 second for no-op builds.                                                       |
| Consumer integration  | Packed-package consumers pass Android and iOS Simulator parity calls, native source inclusion and applicable Android 16 KiB page-size checks; RN web imports remain native-free.                                                                                          |

These gates qualify the pilot for the #70 adoption review; they do not authorize a production switch.
Missing device measurements, unreadable memory coverage or incomplete consumer packaging keep the result incomplete.
Failure or inconclusive evidence defaults to retaining the shared fixtures and JS/Dart matchers; any further experiment needs an explicit bounded follow-up decision.
Do not loosen a threshold after seeing measurements to relabel a failed experiment as passing.

## Distribution and rollback

Keep the canonical header, implementation and host tests under RN `cpp/receipt_core`, as scoped by #67.
Vendor source into the Flutter pilot with an immutable upstream commit, license and SHA-256 manifest; do not fetch source during a consumer build or distribute an opaque prebuilt-only core.
Record the ABI version and exact source revision in both adapter reports, and verify packed source contents independently of the checkout.
RN and Flutter adapters must run as explicit pilot harnesses while ordinary package calls continue using JS/Dart.

Rollback during the pilot removes the experimental adapter/build wiring and vendored core without changing the public API or removing shared fixtures.
If #70 later approves adoption, scope a separate production-switch PR that amends ADR-008, defines release compatibility, and retains a tested route back to the existing host matcher.
ADR-008 remains the production decision throughout this experiment.

## Dependency outcomes

- Shared contract work #63/#64 remains useful independently of the pilot; assess delivery in the respective repositories.
- Geometry fixtures #65/#66 remain separate and are neither prerequisites nor implied implementation scope here.
- This decision approves planning #67, followed by the Flutter #68 and RN #69 adapter pilots; it does not execute those issues as part of #62.
- No pilot follow-up is declined by this decision; #67/#68/#69/#70 remain separate tracked work.
- After both adapter reports exist, #70 records adoption, a bounded follow-up or rejection, with every incomplete gate named.

## Decision delivery

The operator approved this specification and its numerical budgets before measurements on 2026-11-09.
Future changes to the acceptance criteria require a new recorded decision before another measurement campaign.
For the documentation PR, run `yarn typecheck`, `yarn lint`, `yarn test`, scoped `trunk fmt`, and `trunk check`; these verify repository health and document formatting, not the future pilot's performance.
No native build or device write is required to approve this specification.
