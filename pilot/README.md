# Internal RN Overlap Pilot

This opt-in harness exercises `ReceiptOverlapPilot`; ordinary package imports and `scan()` still use the production JS implementation.
The [accepted pilot](../docs/specs/ocr-overlap-pilot.md) owns all thresholds and the separate adoption decision.
No public export or subpath is added for this experiment.
The iOS pilot provider requires React Native 0.79 or later; receipt acquisition continues supporting React Native 0.77 and later.
Older iOS builds omit the experimental provider.

## Local verification

```bash
cmake -S cpp/overlap_pilot/tests -B /tmp/receipt-transport -D CMAKE_BUILD_TYPE=Release
cmake --build /tmp/receipt-transport --parallel 2
ctest --test-dir /tmp/receipt-transport --output-on-failure
node pilot/host-parity.js /tmp/receipt-transport/receipt_transport_driver
```

For native safety, configure a separate build with `-D RECEIPT_CORE_SANITIZERS=ON -D BUILD_SHARED_LIBS=ON`.
Host parity runs the TS host reader through the numeric C++ transport and canonical core; it does not establish JSI registration or device performance.
The source snapshot remains under `cpp/receipt_core`; do not modify it as part of an adapter change.

## Packed consumer

```bash
yarn prepare
yarn pack --out /tmp/receipt-pilot.tgz
node pilot/prepare-consumer.js /tmp/receipt-pilot.tgz /tmp/receipt-pilot-consumer
node .yarn/releases/yarn-4.11.0.cjs --cwd /tmp/receipt-pilot-consumer install --mode=skip-build
node pilot/result-server.js /tmp /tmp/receipt-pilot-consumer/pilot-run.json
node pilot/check-web.mjs /tmp/receipt-pilot-consumer
```

The generated app consumes the archive under its own `node_modules`, has its own Metro/autolinking configuration and copies only the synthetic corpus from this repository.
Build and launch that app on Android and iOS Simulator, one mobile job at a time.
Its blank component runs parity automatically, opens no scanner UI and posts an `ok` result to the local receiver on port 18769.
Android uses the emulator host alias `10.0.2.2`; iOS Simulator uses `127.0.0.1`.
Android parity needs a debug app with local cleartext HTTP permitted; release performance runs must store samples through an appropriate local transport.
The receiver writes `<platform>-parity.json` to the supplied existing directory.
It accepts only the generated consumer's run ID, preventing stale apps from replacing this run's evidence.
Parity logs and result-delivery errors are reported separately; a missing result file is incomplete evidence.
Use a separate consumer directory for each archive; do not replace a package inside an installed consumer without regenerating Codegen and rebuilding.

## Measurement

`workloads.js` defines generator v1 with seed `0x69c0ffee`, 48 fixed size/alphabet/overlap combinations and UTF-16 widths from the accepted protocol.
Freeze its SHA-256, the complete harness SHA-256, source archive checksum, core source pin, baseline revision and committed candidate revision before collecting measurements.
`measure(matcher)` executes complete raw-text normalization, conversion, native call, local buffer cleanup and result assembly, compared with the unchanged production JS function.
It emits raw samples, per-run median/p95 and a consumed-output checksum for five alternating paired runs, with 100 warm-ups and 1,000 operations per workload.
Record identified release devices, OS/compiler/runtime, thermal state and timer resolution alongside the samples.
Calling it with smaller counts is a smoke test, never accepted performance evidence.
Object reclamation after operations remains runtime-managed; use the specified quiescent memory protocol and managed/native profilers to inspect retained memory.
No memory or binary/build budget is inferred from latency samples.

Run five batches of 10,000 typical-input operations after warm-up, observing retained managed-plus-native memory after quiescence with a profiler.
Record stripped per-architecture native-code growth, compressed artifacts, source-package growth and five paired cached-dependency clean/no-op builds separately.
Simulator timings and missing profiler coverage stay incomplete in the report.

## Source-package size gate

Use Python 3 to compare two existing `yarn pack` archives offline:

```bash
python3 pilot/package-size.py /tmp/receipt-baseline.tgz /tmp/receipt-candidate.tgz > /tmp/receipt-package-size.json
```

The JSON report includes archive paths and SHA-256, compressed byte counts, each regular file's uncompressed byte count, total unpacked bytes and signed growth.
The 256 KiB source-package budget applies to total regular-file payload growth, including generated JS/types and all distributed sources; tar headers, padding and gzip overhead are excluded from that budget.
Compressed archive growth is reported separately and has no acceptance threshold.
Exit status is `0` within budget (including exactly 262,144 bytes), `1` over budget with a report, or `2` for invalid/unreadable input without a report.
The reader never extracts archive members and rejects links, duplicate/noncanonical paths, paths outside `package/`, corrupt payloads and a missing or mismatched package name.

Build both revisions with the same Node/Yarn and dependency versions, run `yarn prepare` before packing, and preserve both archives plus logs.
Record exact source commits and tool versions alongside the report; archive checksums identify the measured bytes but do not attest that those bytes came from a claimed commit.
Use a baseline before the pilot and name any other intervening changes, rather than attributing their total growth to the adapter alone.
This gate measures distribution payload only; it supplies no stripped native-code, build-time, latency or memory evidence.
