# RN Overlap Matcher Pilot Report

## Status and scope

Issue [#69](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/issues/69), tracking repository `AndrewDongminYoo/react-native-receipt-scanner`.
This is an internal experiment against the [accepted protocol](../specs/ocr-overlap-pilot.md), with production `scan()` and JS matching unchanged.
The result is **incomplete** until every required mobile integration and release-device measurement below has evidence.
It does not authorize the separate [#70 adoption decision](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/issues/70).

## Source identity

| Component           | Identity                                                                         |
| ------------------- | -------------------------------------------------------------------------------- |
| Production baseline | `992a68d084b061066a7ca225bf982ee10405945a`                                       |
| Canonical core      | `fb37f24a86014829a31897ca891d45a78208aa6c`, ABI v1                               |
| Candidate           | Measurement revision not frozen; implementation is at the PR head                |
| Generator           | `pilot/workloads.js`, v1, seed `0x69c0ffee`                                      |
| Host environment    | macOS, Apple Clang, Node 24; exact executable versions belong with each campaign |

Freeze the candidate commit, generator/harness/archive SHA-256 and baseline before device measurements.
The fixture manifest and canonical core manifest have separate pins; verify each independently.

## Evidence

| Gate                              | Result                                                                                                | What the evidence reads                                                                                                                          |
| --------------------------------- | ----------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------ |
| JS repository checks              | 135 Jest tests passed; typecheck and lint passed on Node 24.20.0                                      | Public scanner behavior, quality fixtures and pilot host regressions                                                                             |
| Numeric transport                 | Passed under host ASan/UBSan                                                                          | Non-finite/fractional/negative/overflow values, descriptors, count bounds, ABI mismatch, empty buffers and concurrent local ownership            |
| Core safety                       | CTest 5/5 including transport, core, C linkage, corpus and source snapshot                            | Shared sanitizer build; mobile memory retention is a separate measurement                                                                        |
| Host parity                       | 29 merge fixtures, 11 malformed numeric cases, 48 workloads passed                                    | Real TS host normalization/assembly through the numeric C++ helper and pinned core                                                               |
| Negative controls                 | Corrupted corpus expectation and surrogate-replacement mutation both failed                           | The readers reject wrong expectations and distinguish different lone surrogates                                                                  |
| Source provenance                 | `vendor.py --check --upstream-check` passed                                                           | Canonical manifest checksums and immutable reachable revision                                                                                    |
| Codegen and discovery             | Passed                                                                                                | RN 0.88 generated both specs, the iOS provider map and packed-consumer Android configuration with Kotlin plus C++ modules                        |
| Android packed build/parity       | Passed, arm64 Android 17/API 37 emulator, Debug/Hermes                                                | Actual JSI: 29 merge fixtures, 11 malformed numeric cases, 9 malformed JS-type cases and 48 workloads; acquisition module present                |
| iOS Simulator packed build/parity | Passed, arm64 iPhone 17 Simulator/iOS 26.5, Release/Hermes                                            | Same 29/11/9/48 cases and acquisition-module presence                                                                                            |
| Source example integration        | Android Release build, install and startup passed; iOS Release build and direct native startup passed | Existing acquisition example renders with both internal modules linked; no scanner flow was exercised                                            |
| Native-free web import            | Passed for default and `source` conditions                                                            | Bundled and executed packed web fallbacks; deliberate native import rejected                                                                     |
| Android 16 KiB                    | Passed for the packed arm64 Debug APK                                                                 | All 11 uncompressed shared objects passed LOAD alignment, RELRO-tail checks and `zipalign -c -P 16 -v 4`; invalid ELF/ZIP controls were rejected |

Code review covered transport and platform registration independently; no implementation blocker was found.
Review identified a surrogate-test weakness; the additional distinct-surrogate case detects a deliberately lossy replacement mutation.
Host and Codegen passes do not prove mobile linkage or runtime registration.
The mobile parity rows above separately establish those properties through the installed archive consumer.
The Android emulator used 4 KiB pages; static packaging checks do not claim execution on a 16 KiB device.
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
| Packed source and compressed size growth                            | Pending                                                                      |
| Stripped release native-code growth by architecture                 | Not measured                                                                 |
| Five paired cached-dependency clean/no-op build comparisons         | Not measured                                                                 |

No performance, retained-memory, binary-size or build-time acceptance is claimed.
The approved budgets remain unchanged; simulator integration cannot substitute for device measurements.
