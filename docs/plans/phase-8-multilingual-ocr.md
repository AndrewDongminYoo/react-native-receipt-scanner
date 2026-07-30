# Phase 8 — Multilingual OCR

## Status: Executed. Shipped in 0.8.0.

The normative behaviour — public API, language-resolution rules, error contract, and acceptance criteria — lives in [`../specs/multilingual-ocr.md`](../specs/multilingual-ocr.md).
This document records scope, sequencing, and the decisions that shaped the implementation.
It is deliberately not a step-by-step script: the working code is the source tree, and per [`../AGENTS.md`](../AGENTS.md) persistent plans stay summaries.

## Goal

Widen the package's OCR reach from a hard-coded Korean + English pair to caller-provided BCP 47 language hints, and expose what each platform can actually recognise at runtime.
The package still ships raw OCR primitives only — no receipt parsing, no language detection, no cloud OCR, no translation (ADR-003).

The extension is deliberately shallow: it targets **languages**, not countries or receipt formats.
No public country or language allowlist is introduced, so the same Korean and English fixtures that passed before keep passing unchanged.

## Architecture

The JS layer owns the additive public types, the `["ko-KR", "en-US"]` default, and cheap order-preserving array normalisation.
Both native layers validate before any scanner UI opens, so an unsupported language fails fast instead of after the user has captured a page.

**iOS** passes the caller's ordered language list straight to `VNRecognizeTextRequest` and reuses that same list for every orientation pass, so the fast pass and the rotated re-read never disagree about language priority.
Validation runs against the same accurate Vision request used for scanning, rather than a separately-configured probe.

**Android** cannot mix scripts in one recognizer, so `OcrLanguageResolver` maps the tag list onto exactly one ML Kit script family — Latin, Korean, Japanese, Chinese, or Devanagari — accepting accompanying Latin (each non-Latin ML Kit model already recognises Latin characters) and rejecting genuinely mixed-script combinations.
Korean stays bundled; the other four arrive as Google Play services modules, so `OcrModelManager` checks availability and, when needed, installs the module **before** the scanner UI opens.
That introduces an async gap between `scan()` and the UI that the previous design did not have, which is why the single-scan guard had to change (below).

## Key decisions

**`ocrLanguages` is additive.** The default order, the result shape, `ocrFloor`, `ocrGeometry`, `autoRotate`, and the `ocr: false` bypass are all unchanged. Callers who never set the option observe no difference.

**`PendingScanLifecycle` replaced the capture-into-locals promise convention.** Android's old rule — capture `pendingPromise`/`pendingOptions` into locals before `executor.execute { }` — guarded only the window after an activity result. Model preparation opened an earlier window: `scan()` now returns having launched nothing, and the scanner starts from a Play services callback. A token minted by `tryBegin()` and released inside the same monitor that settles the Promise covers the whole span — model preparation, native UI, image processing, settlement — so a second `scan()` is rejected `SCAN_IN_PROGRESS` for the full duration. See [`../../android/AGENTS.md`](../../android/AGENTS.md) §CONVENTIONS.

**Capability reporting never downloads.** `getOcrCapabilities()` reports `ready` / `download-required` per script; it queries availability only. Installation happens on the scan path, where the user is already waiting on a deliberate action.

**Accuracy is not claimed beyond what was observed.** Foreign-language fixtures are hard to source; the shipped claim is that the language target is caller-controlled and the Korean/English baseline is preserved, not that any given language reaches a given accuracy. See the spec's §Accuracy and support claims.

## Scope

New: `OcrLanguageResolver.kt` (BCP 47 validation and script resolution) and `OcrModelManager.kt` (recognizer construction, availability reporting, module installation), each with a unit-test peer; `OcrModelManagerTest` drives a fake installer boundary rather than Play services.

Modified, by layer:

- **JS** — `types.ts` (option, capability types, defaults, error codes), `NativeReceiptScanner.ts` (capability method), `scan.native.tsx` (normalisation and delegation), `scan.tsx` (web capability result), `index.tsx` (exports), `__tests__/index.test.tsx`.
- **Android** — `build.gradle` (non-default Play services text-recognition artifacts), `ScanOptions.kt`, `OcrProcessor.kt` (accept an injected `TextRecognizer` instead of constructing Korean internally), `ReceiptScannerModule.kt` (preparation, pending-state ownership, capability method).
- **iOS** — `RNScanOptions.{h,m}`, `RNOcrProcessor.{h,m}` (language-aware OCR, supported-language query, validation), `RNDocumentCameraDelegate.m`, `RNGalleryPickerDelegate.m`, `ReceiptScanner.mm` (preflight validation, in-progress ownership, capability method).
- **Docs and QA** — `README.md`, `specs/api-contract.md`, `specs/scan-pipeline.md`, `notes/platform-asymmetries.md`, `ios/AGENTS.md`, `android/AGENTS.md`, and language/capability controls in `example/src/App.tsx`.

## Out of scope

Receipt parsing, totals, validation, fraud detection, upload, retry, cloud OCR, and UI locale selection all remain outside the package boundary (ADR-003).
Multi-model result merging and automatic language detection were considered and deferred — see the spec's §Deferred work.

## Verification

Gates: `yarn typecheck && yarn lint && yarn test && trunk fmt && trunk check`, the Android library unit suite, `:app:assembleDebug`, and an iOS simulator build of the `ReceiptScanner` pod target.
Required test cases and the manual-QA matrix are enumerated in the spec's §Verification; the acceptance criteria the implementation was checked against are its §Acceptance criteria.

Not observed, and not claimed: live foreign-language OCR accuracy against real fixtures, dynamic model installation on a device, offline installation failure, and concurrent-scan rejection under real overlapping taps.
