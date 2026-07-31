# react-native-receipt-scanner — Knowledge Base

Freshness is deliberately not stamped here — `git log -1 -- AGENTS.md` is the honest answer, and it cannot rot.
A hand-written "generated at commit X" line is precisely the rotting premise this file warns about under CONVENTIONS: it stays valid Markdown while quietly becoming a lie, and it reads as a freshness guarantee it never had. This one sat at `2026-05-03 / 872976e` for three months across two feature releases.

## OVERVIEW

React Native TurboModule for receipt scanning. Wraps **ML Kit Document Scanner** (Android) and **VisionKit + Vision** (iOS) behind a single `scan()` API. Output: `file://` JPEG URIs, EXIF, on-device OCR. New Architecture only — no legacy bridge, no base64.

OCR languages are **caller-provided BCP 47 hints** (`ScanReceiptOptions.ocrLanguages`, default `["ko-KR", "en-US"]`), not a fixed pair. Android resolves the list to exactly one ML Kit script family (Latin / Korean / Japanese / Chinese / Devanagari); iOS passes the ordered list to Vision. `getOcrCapabilities()` reports what the platform can serve without opening scanner UI. See `docs/specs/multilingual-ocr.md`.

## STRUCTURE

```plaintext
.
├── src/                    TS API surface, TurboModule spec, native/web split
├── android/                Kotlin TurboModule + helpers (ML Kit, EXIF, OCR)
├── ios/                    Obj-C++ delegates, custom crop editor, Vision
├── docs/                   ADRs, phase plans, public/internal specs (see docs/AGENTS.md)
├── example/                Yarn-workspace consumer app (the integration test)
├── .github/workflows/      ci.yml (lint+typecheck+test) + release.yml (multi-platform build)
├── bob.config.js           react-native-builder-bob → lib/{module,typescript}
├── ReceiptScanner.podspec  iOS pod; uses install_modules_dependencies(s)
├── turbo.json              Turborepo for caching example builds
└── CLAUDE.md               Architecture detail + coding rules (read alongside this)
```

## WHERE TO LOOK

| Task                       | Files                                                                                                                                                                                                                                                                                      |
| -------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Public API contract        | `src/types.ts`, `docs/specs/api-contract.md`                                                                                                                                                                                                                                               |
| Cross-platform flow trace  | `docs/specs/scan-pipeline.md`                                                                                                                                                                                                                                                              |
| Add a scan option          | `src/types.ts` → `android/.../ScanOptions.kt` → `ios/RNScanOptions.{h,m}` → consumer files                                                                                                                                                                                                 |
| Add a JS-only scan option  | `src/types.ts` → `src/scan.native.tsx`. Do **not** mirror it into `ScanOptions.kt` / `RNScanOptions.h` — both read a key whitelist, so an unmirrored key is inert, and mirroring it would pull derived-signal logic into native code. `ocrFloor` and `mergeOcrPages` are the two examples. |
| Change result shape        | `src/types.ts` → `android/.../ResultBuilder.kt` → `ios/RN{Document,Gallery}*Delegate.m`                                                                                                                                                                                                    |
| Modify OCR                 | `android/.../OcrProcessor.kt` / `ios/RNOcrProcessor.m`                                                                                                                                                                                                                                     |
| Adjust iOS crop UI         | `ios/RNCropEditorViewController.m` (**read ADR-004 first**)                                                                                                                                                                                                                                |
| Change JPEG/EXIF           | `android/.../ImageProcessor.kt` / `ios/RNImageProcessor.m`                                                                                                                                                                                                                                 |
| TurboModule wiring         | `src/NativeReceiptScanner.ts`, `package.json#codegenConfig`, `android/.../ReceiptScannerPackage.kt`, `ios/ReceiptScanner.{h,mm}`                                                                                                                                                           |
| ADRs (decisions of record) | `docs/notes/adr-*.md`                                                                                                                                                                                                                                                                      |

## CODE MAP

| Symbol                       | Type             | Location                              | Role                                                                   |
| ---------------------------- | ---------------- | ------------------------------------- | ---------------------------------------------------------------------- |
| `scan(options?)`             | function         | `src/scan.native.tsx`, `src/scan.tsx` | Public API; native impl + web fallback                                 |
| `Spec.scan`                  | TurboModule spec | `src/NativeReceiptScanner.ts`         | Codegen input; module name `"ReceiptScanner"`                          |
| `DEFAULT_SCAN_OPTIONS`       | const            | `src/types.ts`                        | Option defaults (mirrored in `ScanOptions.kt` + `RNScanOptions.m`)     |
| `mergeOcrPages`              | function         | `src/mergeOcrPages.ts`                | Pure cross-page OCR merge; JS-only by design (ADR-008)                 |
| `ReceiptScannerModule`       | class            | `android/.../ReceiptScannerModule.kt` | Android entry; scan lifecycle + OCR model prep                         |
| `PendingScanLifecycle`       | class            | `android/.../ReceiptScannerModule.kt` | Single-scan token; owns the Promise hand-off (see `android/AGENTS.md`) |
| `ImageProcessor`             | class            | `android/.../ImageProcessor.kt`       | Android JPEG + EXIF + temp cleanup                                     |
| `OcrProcessor`               | class            | `android/.../OcrProcessor.kt`         | Wraps an **injected** `TextRecognizer` (close after use)               |
| `OcrLanguageResolver`        | object           | `android/.../OcrLanguageResolver.kt`  | BCP 47 validation → one ML Kit script family                           |
| `OcrModelManager`            | class            | `android/.../OcrModelManager.kt`      | Recognizer construction, Play-services module install, capabilities    |
| `ReceiptScanner`             | class            | `ios/ReceiptScanner.mm`               | iOS entry; camera vs gallery dispatch                                  |
| `RNCropEditorViewController` | class            | `ios/RNCropEditorViewController.m`    | Custom 4-handle perspective crop editor                                |
| `RNImageProcessor`           | class            | `ios/RNImageProcessor.m`              | iOS JPEG + EXIF + perspective correction                               |

## CONVENTIONS

- **Yarn 4 (Berry)** with `nodeLinker: node-modules`. `yarnPath: .yarn/releases/yarn-4.11.0.cjs`. No npm/pnpm.
- **Node**: `.nvmrc` = `v24.15.0` (CI reads this); example app engines: `>= 22.11.0`.
- **TypeScript strict + extras**: `noUncheckedIndexedAccess`, `noUnusedLocals/Parameters`, `verbatimModuleSyntax`, `customConditions: ["react-native-strict-api"]`.
- **ESLint flat config only** (`eslint.config.mjs`); extends `@react-native` + `prettier`; Prettier violations = errors.
- **Prettier**: 100-col, double quotes, semicolons, trailing-comma `es5`, LF.
- **Jest** preset `@react-native/jest-preset`, no setup files. Tests live in `src/__tests__/` only (not colocated); `.ts` and `.tsx` both run, and `src/__tests__/fixtures/` is ignored.
- **Pre-commit/push** is **Trunk** (`.trunk/trunk.yaml`) — not Husky/Lefthook.
- **Commits**: Conventional Commits + emoji (`feat: ✨`, `fix: 🐛`, `chore: 🔨`, `docs: 📝`); `release-it` consumes them. Do NOT add `Co-authored-by: Claude` trailers.
- **Module name string `"ReceiptScanner"`** must stay identical in `NativeReceiptScanner.ts`, `ReceiptScannerPackage.kt`, and `ReceiptScanner.mm`.
- **Codegen** (`package.json#codegenConfig`): `name: ReceiptScannerSpec`, `jsSrcsDir: src`, Android package `com.receiptscanner`. Android emits `NativeReceiptScannerSpec` under `android/build/generated/...`.
- **Build artifacts**: `lib/module/` (ESM) + `lib/typescript/` via `bob build`. Both gitignored; `prepare` regenerates.
- **`.native.tsx` / `.tsx` split**: Metro auto-resolves `.native.tsx` on iOS/Android; the `.tsx` variant is the web/JS fallback.
- **Fix the prose your change falsifies, in the same commit.** This repo's docs state _premises_, not just descriptions — "this package ships the bundled recognizer", "the repository is private", "confidence is populated on both platforms". A premise stays syntactically valid after it becomes false, so **nothing mechanical catches it**: not the compiler, not the tests, not `trunk check`. It surfaces later as a confidently wrong instruction to the next agent.
  This is the single highest-yield defect class here — **5 of the findings across the PR #16 / #17 reviews were exactly this**, and none were caught by any gate: `android/AGENTS.md`'s promise hand-off rule (superseded by `PendingScanLifecycle`), `OcrProcessor.meanLineConfidence`'s "this package ships the bundled recognizer, so values are real" precondition, `deriveQuality` / `meetsFloor` JSDoc, the 1,005-line phase-8 plan, and `CONTRIBUTING.md`'s "the repository is private ... so provenance attestations are not generated".
  Load-bearing sites, in the order they go stale: **doc comments that state a precondition** (KDoc/JSDoc — the worst offenders, because the claim sits inches from the code that broke it), `AGENTS.md` (root + `android/` + `ios/`), `docs/specs/*.md`, `docs/notes/platform-asymmetries.md`, `CONTRIBUTING.md`, `README.md`.
  Practical test before committing: for each behaviour you changed, grep the repo for the old behaviour's _claim_, not its identifier.
- **AGENTS.md is the cross-agent surface.** Codex and other non-Claude agents read `AGENTS.md`; they do **not** read `CLAUDE.md`, `~/.claude/rules/`, or any Claude-side memory. Any convention that must hold regardless of which agent is driving belongs here — putting it only in `CLAUDE.md` guarantees drift the moment a different agent picks up the work.

## ANTI-PATTERNS (THIS PROJECT)

- ❌ **Receipt-domain logic** (parse store name/total/date, dedupe, fraud) — out of scope; belongs in app/server (ADR-003).
- ❌ **Upload, retry, Azure OCR** integration — out of scope (ADR-003).
- ❌ **Base64 / `data:` URIs** — always `file://` (`fileURL.absoluteString` on iOS, never manual `"file://" + path` for paths with spaces/non-ASCII).
- ❌ **`UIBarButtonItem`** for the iOS crop editor toolbar — use `UIButton` + `UIControlEventTouchUpInside` (ADR-004).
- ❌ **`safeAreaLayoutGuide.bottomAnchor`** for the iOS crop editor button bar — anchor to `view.bottomAnchor constant:-34` (ADR-004).
- ❌ **`VNImageRequestHandler initWithCIImage:`** for orientation-bearing photos — use `initWithCGImage:orientation:` (ADR-004).
- ❌ **`[CIImage initWithImage:]` → `CIPerspectiveCorrection`** without `imageByApplyingOrientation:` first (ADR-004).
- ❌ **Adding handle subviews after the iOS crop editor button bar** — UIKit hit-tests subviews in reverse; bar MUST be added last (ADR-004).
- ❌ **`UIImageJPEGRepresentation`** on iOS — strips EXIF/TIFF; use `CGImageDestination`.
- ❌ **`startActivityForResult`** on Android — deprecated; use `startIntentSenderForResult`.
- ❌ **`Tasks.await(...)` on the main thread** — Android `OcrProcessor.recognize` MUST run on the executor.
- ❌ **`as any` / `@ts-ignore` / `@ts-expect-error`** — suppression is never the fix.
- ❌ **New `docs/` subdirectories** or **date-prefixed doc copies** (see `docs/AGENTS.md`).

## COMMANDS

```bash
yarn                           # Install (Yarn Berry; no npm/pnpm)
yarn prepare                   # Build library to lib/{module,typescript}
yarn typecheck                 # tsc --noEmit
yarn lint                      # ESLint over **/*.{js,ts,tsx}
yarn test                      # Jest over src/__tests__ (.ts and .tsx; fixtures/ is ignored)

trunk check                    # All trunk-managed linters (ktlint, osv-scanner, shellcheck, yamllint, …)
trunk fmt                      # Auto-format every file the trunk formatters own

yarn example start             # Metro for the example app
yarn example android           # Build + run on Android emulator
yarn example ios               # Build + run on iOS simulator
yarn example web               # Web fallback via Vite

yarn release                   # release-it --only-version → npm + GitHub release
yarn watchman-reset            # Reset Watchman watch on cache pain
yarn clean                     # Delete lib/ + example native build dirs
```

## VERIFICATION (before declaring a job complete)

Run this exact pipeline before claiming any task is done. Yarn covers the JS/TS surface; `trunk` covers Kotlin (ktlint), shell, YAML, Markdown, secret scan, and OSV-Scanner advisories. The pre-commit / pre-push hooks (`.trunk/trunk.yaml`) run the same checks — passing them locally is a prerequisite for committing.

```bash
yarn typecheck && yarn lint && yarn test && trunk fmt && trunk check
```

- `trunk fmt` rewrites files in place — run it **before** `trunk check`; otherwise the formatters are reported as failures.
- A `trunk check` failure that is genuinely a false positive belongs in `.trunk/configs/<linter>.toml` with a written justification (see `.trunk/configs/osv-scanner.toml` for the canonical pattern). Never silence findings inline.
- For native (`example/android` / `example/ios`) changes, also run `yarn example android` / `yarn example ios` — the example app is the integration test (no automated E2E).
- **No gate checks prose.** Before declaring done, re-read the docs your change touched the premises of and fix them in the same commit — see the "Fix the prose your change falsifies" convention. `trunk check` will pass either way, which is exactly why this step is listed here.

## NOTES

- **Temp file lifecycle**: `scan()` deletes the previous session's `receipt_*.jpg` files first. URIs are stable until the next `scan()` call; the OS clears `cacheDir`/`NSCachesDirectory` on app restart.
- **iOS camera path produces synthetic UIImages** — original shutter EXIF unavailable. `RNImageProcessor` synthesizes `make`/`model`/`dateTimeOriginal` from `UIDevice` + now.
- **Android camera path also synthesizes EXIF** — ML Kit re-encodes pages and strips originals; `ImageProcessor.process(synthesizeDeviceInfo = true)` injects `Build.MANUFACTURER`/`Build.MODEL`/now() **only for camera scans**. Gallery images honestly report null.
- **`imageOrigin` field**: `"camera"` for camera, `"unknown"` for Android gallery, omitted (or `"camera"`) for iOS camera.
- **iOS `exif.orientation` is always `1`** — output JPEGs are pixel-up; the orientation tag is fixed at `kCGImagePropertyOrientationUp`.
- **iOS deployment target is 16.0** (`ReceiptScanner.podspec`) — Korean OCR via `VNRecognizeTextRequest` requires it, and the package ships **no** Latin-only fallback for older iOS (ADR-006). There is no iOS 13–15 path.
- **Android non-Korean OCR models ship via Play services** — Latin/Japanese/Chinese/Devanagari download on first use; Korean stays bundled. `scan()` waits for a terminal install state before opening UI, and rejects `OCR_MODEL_INSTALL_FAILED` on failure.
- **Android requires Google Play Services** — ML Kit Document Scanner downloads its model on first use; AOSP / Play-less emulators cannot use this library.
- **Cancelled state** is `{ status: "cancelled", images: [] }` — not a rejection. Errors reject with `SCAN_IN_PROGRESS`, `NO_ACTIVITY`, `NOT_SUPPORTED`, `SCANNER_INIT_FAILED`, `SCAN_FAILED`, `SCAN_RESULT_ERROR`, `PROCESSING_FAILED`, `CAMERA_FAILED`, `INVALID_OCR_LANGUAGE`, `OCR_LANGUAGE_NOT_SUPPORTED`, `OCR_LANGUAGE_COMBINATION_NOT_SUPPORTED`, or `OCR_MODEL_INSTALL_FAILED`. The complete table is in `README.md`; `docs/specs/api-contract.md` covers only the multilingual-OCR subset.
- **`example/` IS the integration test** — there are no automated E2E tests. Build it locally to verify native changes.
