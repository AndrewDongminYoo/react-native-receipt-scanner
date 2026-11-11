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
The reader validates every tar header, both zero end blocks, zero-only trailing padding and the complete gzip CRC/size trailer before reporting totals.
The reader never extracts archive members and rejects links, duplicate/noncanonical paths, paths outside `package/`, corrupt payloads and a missing or mismatched package name.

Build both revisions with the same Node/Yarn and dependency versions, run `yarn prepare` before packing, and preserve both archives plus logs.
Record exact source commits and tool versions alongside the report; archive checksums identify the measured bytes but do not attest that those bytes came from a claimed commit.
Use a baseline before the pilot and name any other intervening changes, rather than attributing their total growth to the adapter alone.
This gate measures distribution payload only; it supplies no stripped native-code, build-time, latency or memory evidence.

## Release native-code size

Prepare separate baseline and candidate packed consumers using the same current application template and locked dependencies.
Replace both generated `index.js` files with the same entry that imports the public `scan` function, retains it on `global.receiptScan`, and registers a blank component.
The generated parity entry imports pilot-only files absent from the baseline and is unsuitable for this comparison.
Preserve identical app/native configuration and resolved dependency versions, excluding the package archive locator and the library's own generated sources.
Freeze revisions, archive hashes, application-entry hash and toolchain before building.

Build sequentially in each consumer, without installing either app:

```bash
cd android
./gradlew :app:assembleRelease --no-daemon --max-workers=2 --console=plain \
  -PreactNativeArchitectures=arm64-v8a,armeabi-v7a,x86,x86_64
cd ../ios
RCT_USE_RN_DEP=1 RCT_USE_PREBUILT_RNCORE=1 bundle exec pod install
xcodebuild -workspace ReceiptScannerExample.xcworkspace -scheme ReceiptScannerExample \
  -configuration Release -sdk iphoneos -destination 'generic/platform=iOS' \
  -derivedDataPath ../ios-release -jobs 2 CODE_SIGNING_ALLOWED=NO \
  DEPLOYMENT_POSTPROCESSING=YES STRIP_INSTALLED_PRODUCT=YES STRIP_STYLE=all build
```

Use the pinned Node/Yarn and the installed Android SDK/NDK; resolve CocoaPods through the example's Gemfile and lockfile.
To reuse local iOS caches, set `RCT_TESTONLY_RNCORE_TARBALL_PATH`, `RCT_USE_LOCAL_RN_DEP` and `HERMES_ENGINE_TARBALL_PATH` to the corresponding official Release tarballs before installing pods.
Verify their complete checksums against the official artifacts and use the same archives on both sides.
These existing RN switches skip Debug/Release replacement phases, so supplying Release archives, including their matching Hermes compiler, is essential.
Retain complete logs and confirm Release, architecture coverage and compiler settings from the actual build output.
For Android, read every `lib/<abi>/*.so` in the final APK, verify its ELF machine using the NDK's `llvm-readelf --file-header --sections`, and reject `.symtab` or `.debug_*` sections.
Include every library, even unchanged runtimes, rather than only `libappmodules.so` or the core archive.
Require the app image for every expected ABI.

For iOS, copy the unsigned device `.app` before normalization.
Find its executable through `Info.plist` and include every embedded Mach-O framework/dylib.
Apply `xcrun strip -S -x` to those copied images consistently on both sides; verify `xcrun lipo -archs`, the device `IOS` platform with `xcrun vtool -show-build`, absence of local symbols with `xcrun nm -m`, and absence of `__DWARF` segments with `xcrun otool -l`.
Reject missing app images or unexpected architectures.
Count complete stripped image file bytes per architecture, including headers, data, relocation/link metadata and alignment; this conservative measure is broader than executable sections alone.
Subtract baseline totals from candidate totals and apply the existing 131,072-byte budget independently to each architecture.
Keep per-image sizes and SHA-256 alongside totals and demonstrate rejection of malformed, unstripped, missing-image and wrong-architecture controls before relying on the reader.

Report whole APK byte growth separately.
For iOS, archive each normalized app under the same `Payload/<name>.app` layout using ZIP deflate level 9 and fixed member timestamps/permissions; report that archive's growth separately.
These unsigned local archives do not predict App Store encryption, thinning, signing or download size.
Keep compression parameters identical and preserve the original artifacts, normalized artifacts and their hashes.
This build-only measurement requires no physical-device write and supplies no runtime latency, memory or paired build-time result.

## Cached-dependency build time

Use the same frozen packed consumers, application entry and Release configurations as the native-code size comparison.
Verify archive hashes and all installed library source bytes before measuring; dependency installation and archive preparation remain outside the timer.
Run one mobile job at a time and queue each command until the one-minute host load is no greater than the logical core count.
Record queue time separately from elapsed build time.
Alternate baseline/candidate order for five pairs on each platform.

For Android, run `./gradlew clean` outside the timed interval, including native clean for all four ABIs.
Time the Release assemble command above with `--offline --no-build-cache --no-configuration-cache` added, retaining `--no-daemon --max-workers=2` and the same architecture list.
Then time that exact assemble command again without changing inputs or cleaning products.
Keep downloaded dependencies and Gradle plugin compilation caches; this is a cached-dependency clean build, not a cold SDK/toolchain installation.

For iOS, run the unsigned Release `xcodebuild` command above with its final `build` action replaced by `clean`, outside the timer.
Time the original `build` command, then repeat it unchanged for the no-op sample.
Retain the same derived-data directory, module/compiler caches, installed Pods and checksum-verified Release RN/Hermes prebuilts.
A no-op means an unchanged build invocation; always-run React Native scripts may still execute.

Capture monotonic wall-clock time, exact command, exit status, start timestamp and host load for every invocation, preserving complete logs.
Collect all five pairs even if an early pair exceeds the budget; a failed build makes the campaign incomplete rather than a timing sample to discard.
Report baseline/candidate medians, individual paired differences and the unchanged clean/no-op allowances from the accepted protocol.
Document shared-host variability and mixed directions without claiming a consistent speedup.
This build-only campaign neither installs applications nor supplies device latency or retained-memory evidence.
