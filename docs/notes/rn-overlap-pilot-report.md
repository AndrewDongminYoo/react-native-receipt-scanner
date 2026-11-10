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
| Candidate           | Release-device revision not frozen; source-package snapshot identified below     |
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
| Stripped release native-code growth by architecture                 | Not measured                                                                 |
| Five paired cached-dependency clean/no-op build comparisons         | Not measured                                                                 |

No performance, retained-memory, stripped native-code size or build-time acceptance is claimed.
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
Passing this distribution gate supplies no evidence for the remaining physical-device, stripped native-code or paired build-time gates and leaves #69 incomplete.
