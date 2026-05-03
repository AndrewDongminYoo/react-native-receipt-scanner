# react-native-receipt-scanner — Knowledge Base

**Generated:** 2026-05-03
**Commit:** 872976e
**Branch:** main

## OVERVIEW

React Native TurboModule for receipt scanning. Wraps **ML Kit Document Scanner** (Android) and **VisionKit + Vision** (iOS) behind a single `scan()` API. Output: `file://` JPEG URIs, EXIF, on-device OCR (Korean + Latin). New Architecture only — no legacy bridge, no base64.

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

| Task                       | Files                                                                                                                            |
| -------------------------- | -------------------------------------------------------------------------------------------------------------------------------- |
| Public API contract        | `src/types.ts`, `docs/specs/api-contract.md`                                                                                     |
| Cross-platform flow trace  | `docs/specs/scan-pipeline.md`                                                                                                    |
| Add a scan option          | `src/types.ts` → `android/.../ScanOptions.kt` → `ios/RNScanOptions.{h,m}` → consumer files                                       |
| Change result shape        | `src/types.ts` → `android/.../ResultBuilder.kt` → `ios/RN{Document,Gallery}*Delegate.m`                                          |
| Modify OCR                 | `android/.../OcrProcessor.kt` / `ios/RNOcrProcessor.m`                                                                           |
| Adjust iOS crop UI         | `ios/RNCropEditorViewController.m` (**read ADR-004 first**)                                                                      |
| Change JPEG/EXIF           | `android/.../ImageProcessor.kt` / `ios/RNImageProcessor.m`                                                                       |
| TurboModule wiring         | `src/NativeReceiptScanner.ts`, `package.json#codegenConfig`, `android/.../ReceiptScannerPackage.kt`, `ios/ReceiptScanner.{h,mm}` |
| ADRs (decisions of record) | `docs/notes/adr-001..004-*.md`                                                                                                   |

## CODE MAP

| Symbol                       | Type             | Location                              | Role                                                               |
| ---------------------------- | ---------------- | ------------------------------------- | ------------------------------------------------------------------ |
| `scan(options?)`             | function         | `src/scan.native.tsx`, `src/scan.tsx` | Public API; native impl + web fallback                             |
| `Spec.scan`                  | TurboModule spec | `src/NativeReceiptScanner.ts`         | Codegen input; module name `"ReceiptScanner"`                      |
| `DEFAULT_SCAN_OPTIONS`       | const            | `src/types.ts`                        | Option defaults (mirrored in `ScanOptions.kt` + `RNScanOptions.m`) |
| `ReceiptScannerModule`       | class            | `android/.../ReceiptScannerModule.kt` | Android entry; `pendingPromise` lifecycle                          |
| `ImageProcessor`             | class            | `android/.../ImageProcessor.kt`       | Android JPEG + EXIF + temp cleanup                                 |
| `OcrProcessor`               | class            | `android/.../OcrProcessor.kt`         | ML Kit Korean text recognizer (close after use)                    |
| `ReceiptScanner`             | class            | `ios/ReceiptScanner.mm`               | iOS entry; camera vs gallery dispatch                              |
| `RNCropEditorViewController` | class            | `ios/RNCropEditorViewController.m`    | Custom 4-handle perspective crop editor                            |
| `RNImageProcessor`           | class            | `ios/RNImageProcessor.m`              | iOS JPEG + EXIF + perspective correction                           |

## CONVENTIONS

- **Yarn 4 (Berry)** with `nodeLinker: node-modules`. `yarnPath: .yarn/releases/yarn-4.11.0.cjs`. No npm/pnpm.
- **Node**: `.nvmrc` = `v24.15.0` (CI reads this); example app engines: `>= 22.11.0`.
- **TypeScript strict + extras**: `noUncheckedIndexedAccess`, `noUnusedLocals/Parameters`, `verbatimModuleSyntax`, `customConditions: ["react-native-strict-api"]`.
- **ESLint flat config only** (`eslint.config.mjs`); extends `@react-native` + `prettier`; Prettier violations = errors.
- **Prettier**: 100-col, double quotes, semicolons, trailing-comma `es5`, LF.
- **Jest** preset `@react-native/jest-preset`, no setup files. Tests in `src/__tests__/*.test.tsx` only (not colocated).
- **Pre-commit/push** is **Trunk** (`.trunk/trunk.yaml`) — not Husky/Lefthook.
- **Commits**: Conventional Commits + emoji (`feat: ✨`, `fix: 🐛`, `chore: 🔨`, `docs: 📝`); `release-it` consumes them. Do NOT add `Co-authored-by: Claude` trailers.
- **Module name string `"ReceiptScanner"`** must stay identical in `NativeReceiptScanner.ts`, `ReceiptScannerPackage.kt`, and `ReceiptScanner.mm`.
- **Codegen** (`package.json#codegenConfig`): `name: ReceiptScannerSpec`, `jsSrcsDir: src`, Android package `com.receiptscanner`. Android emits `NativeReceiptScannerSpec` under `android/build/generated/...`.
- **Build artifacts**: `lib/module/` (ESM) + `lib/typescript/` via `bob build`. Both gitignored; `prepare` regenerates.
- **`.native.tsx` / `.tsx` split**: Metro auto-resolves `.native.tsx` on iOS/Android; the `.tsx` variant is the web/JS fallback.

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
yarn test                      # Jest (only src/__tests__/*.test.tsx)

yarn example start             # Metro for the example app
yarn example android           # Build + run on Android emulator
yarn example ios               # Build + run on iOS simulator
yarn example web               # Web fallback via Vite

yarn release                   # release-it --only-version → npm + GitHub release
yarn watchman-reset            # Reset Watchman watch on cache pain
yarn clean                     # Delete lib/ + example native build dirs
```

## NOTES

- **Temp file lifecycle**: `scan()` deletes the previous session's `receipt_*.jpg` files first. URIs are stable until the next `scan()` call; the OS clears `cacheDir`/`NSCachesDirectory` on app restart.
- **iOS camera path produces synthetic UIImages** — original shutter EXIF unavailable. `RNImageProcessor` synthesizes `make`/`model`/`dateTimeOriginal` from `UIDevice` + now.
- **Android camera path also synthesizes EXIF** — ML Kit re-encodes pages and strips originals; `ImageProcessor.process(synthesizeDeviceInfo = true)` injects `Build.MANUFACTURER`/`Build.MODEL`/now() **only for camera scans**. Gallery images honestly report null.
- **`imageOrigin` field**: `"camera"` for camera, `"unknown"` for Android gallery, omitted (or `"camera"`) for iOS camera.
- **iOS `exif.orientation` is always `1`** — output JPEGs are pixel-up; the orientation tag is fixed at `kCGImagePropertyOrientationUp`.
- **iOS Korean OCR requires iOS 16+** — falls back to `en-US` only on iOS 13–15.
- **Android requires Google Play Services** — ML Kit Document Scanner downloads its model on first use; AOSP / Play-less emulators cannot use this library.
- **Cancelled state** is `{ status: "cancelled", images: [] }` — not a rejection. Errors reject with codes documented in `docs/specs/api-contract.md` (`SCAN_IN_PROGRESS`, `NO_ACTIVITY`, `NOT_SUPPORTED`, `SCANNER_INIT_FAILED`, `SCAN_FAILED`, `PROCESSING_FAILED`, `CAMERA_FAILED`).
- **`example/` IS the integration test** — there are no automated E2E tests. Build it locally to verify native changes.
