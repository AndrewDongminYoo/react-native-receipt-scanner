# RN Overlap Matcher Pilot Report

## Status and scope

Issue [#69](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/issues/69), tracking repository `AndrewDongminYoo/react-native-receipt-scanner`.
This is an internal experiment against the [accepted protocol](../specs/ocr-overlap-pilot.md), with production `scan()` and JS matching unchanged.
The result is **incomplete** until every required mobile integration and release-device measurement below has evidence.
The operator approved delivering the implementation and cross-platform package/JSI validation first, while leaving issue #69 open for measurements.
It does not authorize the separate [#70 adoption decision](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/issues/70).

## Source identity

| Component           | Identity                                                                         |
| ------------------- | -------------------------------------------------------------------------------- |
| Production baseline | `992a68d084b061066a7ca225bf982ee10405945a`                                       |
| Canonical core      | `fb37f24a86014829a31897ca891d45a78208aa6c`, ABI v1                               |
| Candidate           | Runtime-measurement revision not frozen; package/native-size snapshots below     |
| Generator           | `pilot/workloads.js`, v1, seed `0x69c0ffee`                                      |
| Host environment    | macOS, Apple Clang, Node 24; exact executable versions belong with each campaign |

Freeze the candidate commit, generator/harness/archive SHA-256 and baseline before device measurements.
The fixture manifest and canonical core manifest have separate pins; verify each independently.

## Evidence

| Gate                                   | Result                                                                                                | What the evidence reads                                                                                                                          |
| -------------------------------------- | ----------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------ |
| JS repository checks                   | 137 Jest tests passed; typecheck and lint passed on Node 24.20.0                                      | Public scanner behavior, quality fixtures and pilot host regressions                                                                             |
| Numeric transport                      | Passed under host ASan/UBSan                                                                          | Non-finite/fractional/negative/overflow values, descriptors, count bounds, ABI mismatch, empty buffers and concurrent local ownership            |
| Core safety                            | CTest 5/5 including transport, core, C linkage, corpus and source snapshot                            | Shared sanitizer build; mobile memory retention is a separate measurement                                                                        |
| Host parity                            | 29 merge fixtures, 11 malformed numeric cases, 48 workloads passed                                    | Real TS host normalization/assembly through the numeric C++ helper and pinned core                                                               |
| Negative controls                      | Corrupted corpus expectation and surrogate-replacement mutation both failed                           | The readers reject wrong expectations and distinguish different lone surrogates                                                                  |
| Source provenance                      | `vendor.py --check --upstream-check` passed                                                           | Canonical manifest checksums and immutable reachable revision                                                                                    |
| Codegen and discovery                  | Passed                                                                                                | RN 0.88 generated both specs, the iOS provider map and packed-consumer Android configuration with Kotlin plus C++ modules                        |
| Android packed build/parity            | Passed, arm64 Android 17/API 37 emulator, Debug/Hermes                                                | Actual JSI: 29 merge fixtures, 11 malformed numeric cases, 9 malformed JS-type cases and 48 workloads; acquisition module present                |
| iOS Simulator packed build/parity      | Passed, arm64 iPhone 17 Simulator/iOS 26.5, Release/Hermes                                            | Same 29/11/9/48 cases and acquisition-module presence                                                                                            |
| Source example integration (`05b1bb9`) | Android Release build, install and startup passed; iOS Release build and direct native startup passed | Existing acquisition example renders with both internal modules linked; no scanner flow was exercised                                            |
| Native-free web import                 | Passed for default and `source` conditions                                                            | Bundled and executed packed web fallbacks; deliberate native import rejected                                                                     |
| Android 16 KiB                         | Passed for the packed arm64 Debug APK                                                                 | All 11 uncompressed shared objects passed LOAD alignment, RELRO-tail checks and `zipalign -c -P 16 -v 4`; invalid ELF/ZIP controls were rejected |

Independent code reviews covered transport and platform registration.
Review identified a surrogate-test weakness; the additional distinct-surrogate case detects a deliberately lossy replacement mutation.
Hosted review additionally identified an iOS provider compatibility regression and weak host error classification.
The provider is excluded on RN 0.77/0.78 and enabled on RN 0.79+; actual upstream version headers passed preprocessing and syntax checks, with unrelated native dependencies stubbed.
Full legacy-app linking and runtime were not tested.
Host validation now distinguishes explicit adapter rejection from process failures and missing output; run IDs prevent stale mobile results from replacing current evidence.
Host and Codegen passes do not prove mobile linkage or runtime registration.
The mobile parity rows above separately establish those properties through the installed archive consumer.
The Android emulator used 4 KiB pages; static packaging checks do not claim execution on a 16 KiB device.
macOS host runs cover address and undefined-behavior sanitizers with leak detection disabled because that runtime does not support it; Linux CI separately enables leak detection.
The ELF/RELRO and ZIP readers follow the [Android page-size guide](https://developer.android.com/guide/practices/page-sizes) and [zipalign reference](https://developer.android.com/tools/zipalign).

## Local integration conditions

The standalone consumer used its own installed tarball, Babel configuration, Metro configuration and generated native project.
Archive contents were compared byte-for-byte with the candidate source; native builds did not resolve the library from the source workspace.
Cold RN/Hermes downloads were checksum-verified against the official artifact service and served through a temporary loopback Maven cache for CocoaPods installation.
No dependency source or version changed.
The source workspace then passed official-source CocoaPods `--deployment`; three temporary mirror checksum changes matched the existing official-source metadata and were removed.

Xcode 26.6 selected unavailable runtime build `23F81a` despite installed iOS 26.5 runtime `23F73`.
An explicitly approved temporary SDK/runtime match made simulator destinations available; the default match was restored after iOS verification.
The repository example compiled in Release and its startup screen rendered after direct native installation.
RN CLI's custom build-folder lookup and subsequent `PLATFORM_NAME` lookup failed in its installation helper; no CLI dependency patch was made.
Android Debug initially waited for React Native's local-network permission prompt; the permission was granted only in the disposable AVD to reach its dedicated Metro server.
No physical-device installation or launch occurred.

## Measurements

| Required result                                                     | Status                                                                       |
| ------------------------------------------------------------------- | ---------------------------------------------------------------------------- |
| Identified Android release device, thermal state and versions       | Unavailable; operator confirmed no physical Android device                   |
| Identified iOS release device, thermal state and versions           | iPhone 16 Pro, iOS 27.0.1 identified; no write authorization or measurements |
| Five paired latency runs, raw samples, median/p95                   | Not collected                                                                |
| Small/typical latency budgets                                       | Not evaluated                                                                |
| Stress geometric-mean benefit and p95 budget                        | Not evaluated                                                                |
| Five quiescent memory batches with managed/native profiler coverage | Not collected                                                                |
| Packed source and compressed size growth                            | Passed for the identified archive pair below; +62,208 unpacked bytes         |
| Stripped release native-code growth by architecture                 | Passed for four Android ABIs and iOS device arm64 in the campaign below      |
| Five paired cached-dependency clean/no-op build comparisons         | Numerical budgets met on both platforms; variability recorded below          |

No latency or retained-memory acceptance is claimed; the build-time observations below do not establish a consistent speedup.
The approved budgets remain unchanged; simulator integration cannot substitute for device measurements.

## Source-package distribution measurement

The offline `pilot/package-size.py` reader compared two `yarn pack` archives on 2026-11-11 using Node 24.22.2 and Yarn 4.11.0.
Both packages were built with `bob build` (`yarn prepare`) and the primary workspace's installed dependencies; this is not a dependency-reproducibility or build-time measurement.
The baseline is the accepted pre-pilot revision `992a68d084b061066a7ca225bf982ee10405945a`, exported with `git archive` into a temporary directory.
The frozen candidate is `84073e73c2f3aee0334529845499cdcb0f3800c6`, which includes the shipped `pilot/package-size.py` and `pilot/README.md`; repository-only tests and report edits are excluded by package-file rules.
The comparison was repeated after freezing that commit, and all archived source files were verified against it (allowing Yarn's package.json trailing-newline normalization); generated library files were verified against the local `bob build` output.
The measured script SHA-256 is `aeceb26e41b405f258545da8655e55c3ae2a03a873468b0d534e811f31f0d4b9`, and the measured pilot README SHA-256 is `165e1c8765aa560a9f3a0eb78dd411098ff6f6e4f277f69377cc6319bcf7d01c`.
The frozen commit, source-content verification and archive checksum identify the measured candidate.
The comparison includes all intervening distributed changes, including OCR confidence policy and iOS JPEG metadata fixes; it does not attribute all growth to the adapter.

| Metric                     | Baseline | Candidate | Growth |
| -------------------------- | -------- | --------- | ------ |
| Regular-file payload bytes | 513,376  | 575,584   | 62,208 |
| Compressed archive bytes   | 152,348  | 172,370   | 20,022 |

The source-package growth passes the existing 262,144-byte budget.
Payload accounting includes all regular files in the distributed package, including generated JS/types; tar metadata and padding are excluded, while compressed growth is reported separately without a threshold.
Baseline archive SHA-256: `0512ed264c2da97ecc840780287492f7aa1b87fadc448ee8f6610406cb1f3ee9`.
Candidate archive SHA-256: `a48add1316b430967b430bc88196357c82b9fd61ea44a5ac6684a531f6367a7a`.
The local review deliverable retains both archives, per-file JSON accounting and pack logs; those binary artifacts are not repository sources.
Jest exercises actual synthetic archives below, at and above the budget, shrinking payloads, corrupt archives, duplicate/noncanonical paths, traversal and links.
Additional negative controls damage a header after the valid manifest, remove tar end blocks, hide payload after end blocks, and remove or corrupt gzip trailers; each must fail without emitting partial totals.
Passing this source-package gate supplies no evidence for physical-device, stripped native-code or paired build-time gates; the separate native-code campaign below covers only the native-code size gate.

## Release native-code size campaign

Measured on 2026-11-11 against baseline `992a68d084b061066a7ca225bf982ee10405945a` and frozen candidate `293e2f085bcbd4df96aef5478416de0e5a924ebe`.
Canonical core remains `fb37f24a86014829a31897ca891d45a78208aa6c`, ABI v1.
The candidate archive is byte-identical to the source-package snapshot above; intervening commits changed no distributed bytes.
Baseline and candidate archive SHA-256 remain `0512ed264c2da97ecc840780287492f7aa1b87fadc448ee8f6610406cb1f3ee9` and `a48add1316b430967b430bc88196357c82b9fd61ea44a5ac6684a531f6367a7a`, respectively.
Every installed library file matched its archive.

Both independent consumers use the same current example template, application entry, Android app configuration, Podfile and dependency versions, except the archive locator and the library's generated sources.
Yarn reported 1,304 identical resolved package locators after excluding the measured library.
The identical entry retains the public `scan` function and registers a blank component; it does not call the pilot or scanner.
Entry SHA-256 is `df6f5a73c6750e762da3dbb655289e9bdfce6e6dc99857d211dfb7dc5caab6a9`.
These are whole-revision app comparisons, including intervening production fixes and generated registration changes, not an isolated attribution to the C++ core or adapter.

The shared host is macOS 26.7.3 build 25G342 with Node 24.20.0, Yarn 4.11.0 and React Native 0.88.0/Hermes.
Android uses Java 21.0.13.0.3, Gradle 9.4.1, NDK 27.1.12297006/Clang 18.0.2 and build tools/compile SDK 37; Release minification remains disabled on both sides.
Release APKs retain the template's debug-keystore signing configuration.
iOS uses Xcode 26.6 build 17F113, iPhoneOS SDK 26.5, Apple Clang 21.0.0 and CocoaPods 1.17.0 with prebuilt RN core/dependencies enabled.
The existing RN local-archive switches select the same official, checksum-verified Release core, dependencies and Hermes archives on both sides, avoiding redundant CocoaPods HTTP downloads.
The [harness guide](../../pilot/README.md#release-native-code-size) records the build and accounting procedure.

### Android results

Both packed consumers built successfully in Release for all four ABIs without installation or launch.
Each APK contains 11 shared objects per ABI.
The reader checked each ELF's actual machine and absence of `.symtab` and `.debug_*` sections, then counted complete image bytes, including headers, data and alignment.
All ten non-app libraries per ABI are byte-identical across the pair; only `libappmodules.so` changed.
The linked candidate contains the pilot's defined constructor and `findOverlap` symbols.

| Architecture | Baseline native bytes | Candidate native bytes | Growth | 131,072-byte budget |
| ------------ | --------------------- | ---------------------- | ------ | ------------------- |
| arm64-v8a    | 23,782,208            | 23,804,664             | 22,456 | Pass                |
| armeabi-v7a  | 15,542,384            | 15,558,524             | 16,140 | Pass                |
| x86          | 25,341,860            | 25,364,484             | 22,624 | Pass                |
| x86_64       | 24,762,736            | 24,785,528             | 22,792 | Pass                |

Whole four-ABI APK bytes increase from 98,480,240 to 98,561,644, a separate increase of 81,404 bytes without an acceptance threshold.
APK SHA-256 is `fc3233cceccf34b2662b8387465fe83d209b609c46c6889dc81f5ba7f12dcd21` for baseline and `b71e5ab8ee041db696fc21c86f942686b6e5b7fab16f00aee22537b494563089` for candidate.
This includes the APK's actual compression/alignment, JS, DEX, resources and signing; it is not a per-device download estimate.
Controls rejected a missing app image, an invalid ELF, a mislabeled ABI and an unstripped app image.
Real ELF fixtures with appended padding verified native growth of 131,071 and 131,072 bytes passes, while 131,073 bytes fails.

### iOS results

Both packed consumers built successfully with the device SDK in unsigned Release for arm64, without installation or launch.
Their Podfile locks are identical after excluding the library's own podspec checksum.
The measurement includes the app executable and all three embedded frameworks: React, ReactNativeDependencies and Hermes.
Copies of all four images were normalized with `xcrun strip -S -x`; the original build products remain preserved.
The reader confirmed thin arm64 Mach-O images, the `IOS` device platform, absence of local symbols and absence of `__DWARF` segments before summing full file bytes.
All three normalized frameworks are byte-identical across the pair; the app executable increases from 193,736 to 213,280 bytes and contains the pilot's defined `findOverlap` symbol only in the candidate.

| Architecture | Baseline native bytes | Candidate native bytes | Growth | 131,072-byte budget |
| ------------ | --------------------- | ---------------------- | ------ | ------------------- |
| arm64        | 9,801,592             | 9,821,136              | 19,544 | Pass                |

The separate unsigned app archives use identical `Payload/ReceiptScannerExample.app` layout, ZIP deflate level 9, fixed 1980 timestamps and fixed file permissions.
Archive bytes increase from 4,209,438 to 4,214,565, a separate increase of 5,127 bytes without an acceptance threshold.
Their SHA-256 values are `312c012103b372c8c221c8325b3d24000a618ebe679c971bc9ab2642fec483e5` for baseline and `2dba9db91ccc4dac81c410735cdb60dc7291e935ba08aa48141c0b2b01185ab4` for candidate.
These local archives exclude distribution signing, App Store encryption and thinning; they do not establish a store download size.
Controls rejected missing/invalid app images, a missing framework, a real unstripped device Mach-O and an arm64 Simulator executable; the stripped device fixture passed.

### Verification and remaining gates

All five measured architecture/platform combinations pass the unchanged 128 KiB native-code budget.
The local review deliverable retains source/package identities, both APKs, original iOS build products, normalized unsigned archives, per-image accounting/checksums, platform-tool reader and controls, and complete build logs.
The report and harness-guide changes do not modify native compilation inputs; the measurements remain bound to the frozen source candidate above.
Repository typecheck, lint and all 172 Jest tests passed; the existing Watchman recrawl warning did not fail the suite.
Neither this build-only campaign nor its elapsed build logs supply release-device latency, quiescent retained-memory measurements, five paired clean/no-op build comparisons or new runtime-parity results.
Issue #69 remains incomplete and open; production JS matching and the separate #70 adoption boundary remain unchanged.

## Cached-dependency build-time campaign

Measured on 2026-11-11 using the same frozen baseline `992a68d084b061066a7ca225bf982ee10405945a`, candidate `293e2f085bcbd4df96aef5478416de0e5a924ebe` and archives identified in the native-code size campaign above.
Canonical core remains `fb37f24a86014829a31897ca891d45a78208aa6c`, ABI v1.
Before measurement, all 88 baseline and 124 candidate installed library files matched their archives byte-for-byte, and both application entries matched the recorded SHA-256.
Application configuration and resolved dependencies remain those of the native-code size consumers; this is a whole-revision comparison, including intervening production changes, rather than an isolated attribution to the adapter.

The same macOS/Xcode/Java/Gradle/NDK toolchain described above was used.
Android shell and Metro use Node 24.22.2; iOS's existing `.xcode.env.local` pins Node 24.20.0, matching `.nvmrc`.
Each baseline/candidate pair uses the same platform-specific Node executable; these results do not compare Node versions.
Both builds use RN 0.88.0/Hermes and the same application sources.
Android covers all four ABIs; iOS uses unsigned device arm64 Release with the same checksum-verified Release RN/Hermes prebuilts.
No application was installed or launched.

Five pairs per platform alternate baseline-first on pairs 1/3/5 and candidate-first on 2/4.
Each side runs an untimed clean, a timed Release build and an identical timed invocation without input changes or product reset.
The Python runner uses `time.perf_counter_ns()` around the entire child process, records exit status, timestamp and host load, and preserves complete stdout/stderr logs.
Each command waits outside the timer until the one-minute load is at most the host's 10 logical cores; the shared host is not otherwise isolated.
Build phases can raise load above that entry threshold.
Start timestamps and elapsed samples preserve inter-command gaps, including queue waits.

Android uses `--offline --no-daemon --no-build-cache --no-configuration-cache --max-workers=2` with the four-ABI Release assemble command.
Gradle clean includes native clean for all four ABIs; downloaded dependencies, Gradle plugin products and toolchain caches remain warm.
iOS uses `xcodebuild clean` followed by the documented unsigned Release build, retaining its derived-data location, module/compiler caches and installed Pods.
The iOS clean logs show 20 baseline versus 23 candidate C/Obj-C compilation actions; no-op logs show zero, while always-run RN scripts and relinking still execute.
Dependency installation, package preparation, SDK downloads and untimed clean operations are excluded.
These are cached-dependency rebuild costs, not cold-machine installation costs.

All 40 measured build invocations and 20 untimed clean commands exited successfully; complete logs contain their expected success markers and no compiler error lines.
The table reports medians of five baseline/candidate times and the median of five paired candidate-minus-baseline differences.
Allowances remain the accepted larger of 10% or 15 seconds for clean, and 10% or 1 second for no-op.

| Platform / build | Baseline median (s) | Candidate median (s) | Difference of medians (s) | Median paired difference (s) | Allowance (s) |
| ---------------- | ------------------- | -------------------- | ------------------------- | ---------------------------- | ------------- |
| android / clean  | 52.464              | 54.182               | +1.718                    | +1.718                       | 15.000        |
| android / noop   | 9.566               | 9.563                | -0.003                    | +0.365                       | 1.000         |
| ios / clean      | 18.479              | 18.081               | -0.397                    | +0.878                       | 15.000        |
| ios / noop       | 8.744               | 9.418                | +0.674                    | +0.648                       | 1.000         |

Both the difference-of-medians check and the median paired excess over each baseline's allowance are within the numerical budgets on both platforms.
No threshold was changed after measurement.
Individual samples below are seconds in pair order 1 through 5, with no outlier removal.

| Platform / build | Baseline samples (s)                   | Candidate samples (s)                  |
| ---------------- | -------------------------------------- | -------------------------------------- |
| android / clean  | 61.994, 48.103, 55.125, 52.464, 44.463 | 57.937, 49.533, 60.041, 54.182, 46.634 |
| android / noop   | 9.106, 14.057, 10.135, 9.566, 8.844    | 9.444, 9.563, 10.691, 9.931, 9.514     |
| ios / clean      | 18.895, 18.479, 17.902, 18.801, 16.891 | 18.081, 17.982, 20.358, 19.699, 17.769 |
| ios / noop       | 8.615, 9.241, 8.744, 8.770, 7.993      | 8.870, 8.164, 11.653, 9.418, 9.516     |

Paired differences change direction, and two iOS no-op pairs individually exceed one second of growth.
The five-pair medians meet the numerical cost limits, but consistent speedup or a stable attribution to the pilot is inconclusive on this shared host.
This is not evidence to adopt the native matcher.
The local review deliverable preserves frozen identities, installed-file verification, the timer/aggregation scripts, all raw samples, complete logs and a full-log audit.
Aggregation controls reject missing/duplicate pairs, failed builds and non-finite times; an above-budget synthetic sample fails its budget check.

Physical Android/iOS release-device latency and quiescent managed-plus-native retained-memory measurements remain unavailable.
Issue #69 remains incomplete and open; production JS matching and the separate #70 adoption decision are unchanged.
