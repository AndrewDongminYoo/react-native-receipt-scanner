# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
yarn prepare          # Build the library (react-native-builder-bob → lib/)
yarn typecheck        # TypeScript type check
yarn lint             # ESLint across all JS/TS/TSX files
yarn test             # Run Jest test suite
yarn test --testPathPattern=src/__tests__/index  # Run a single test file
yarn clean            # Delete all build artifacts (lib/, example/android/build, etc.)
yarn watchman-reset   # Reset Watchman watch (use after Metro cache issues)

trunk check           # All trunk-managed linters (ktlint, osv-scanner, shellcheck, yamllint, …)
trunk fmt             # Auto-format every file the trunk formatters own
```

### Verification before declaring a job complete

Run these in order before claiming any task is done. The Yarn commands cover the JS/TS surface; `trunk` covers the rest of the repo (Kotlin via ktlint, shell, YAML, Markdown, security advisories, secret scan, etc.) and the pre-commit / pre-push hooks (`.trunk/trunk.yaml`) run the same checks, so passing them locally is a prerequisite for committing.

```bash
yarn typecheck && yarn lint && yarn test && trunk fmt && trunk check
```

`trunk fmt` rewrites files in place — run it **before** `trunk check`; otherwise the formatters report unformatted files as failures.

### Do not "fix" the iOS `-Wconversion` warnings

`-Wconversion` is **deliberately not enabled** — it appears in no podspec, Podfile, or trunk config. Running clang with it by hand surfaces 17 warnings across five `.m` files (`RNCropEditorViewController`, `RNOcrProcessor`, `RNDocumentCameraDelegate`, `RNGalleryPickerDelegate`, `RNImageProcessor`); `ReceiptScanner.mm` is unmeasured because it needs React Native headers to compile, so the true total is higher.

Every one audited so far is benign: `NSInteger`/`NSUInteger` → `double` on line and array counts (a double holds integers exactly to 2^53), and one `double` → `float` where Vision's `minimumTextHeight` property genuinely is `float` and the value is `1/32`, exactly representable. Adding casts changes no runtime behaviour.

Patching a handful of sites is the worst option — nothing enforces the flag, so the count silently grows back. Either leave them (current decision, 2026-07-19) or, as its own task, fix every site **and** add the flag to `ReceiptScanner.podspec` so it stays enforced. Scope that task off a real build, not a standalone `clang -fsyntax-only` sweep, so `.mm` files are counted.

### Example App

```bash
yarn example start          # Start Metro bundler
yarn example android        # Run on Android emulator
yarn example ios            # Run on iOS simulator
```

## Tooling / Platform Versions

- **Yarn 4.11.0** — pinned via the `packageManager` field in `package.json` (corepack-managed). Do not run `npm install`; it will desync the lockfile.
- **Node ≥ 22.11.0** — enforced by `example/package.json#engines`.
- **ESLint flat config** — `eslint.config.mjs` (ESM).
- **Android** — Kotlin 2.0.21, `minSdk 24`, `targetSdk 36`, `compileSdk 36`. Key deps: `play-services-mlkit-document-scanner` (16.0.0), `text-recognition-korean` (16.0.1), `androidx.exifinterface` (1.4.2).
- **iOS** — deployment target pinned at **16.0** in `ReceiptScanner.podspec` (Korean OCR via `VNRecognizeTextRequest` requires iOS 16; the package ships no Latin-only fallback — see ADR-006). Linked frameworks: `VisionKit`, `Vision`, `PhotosUI`, `ImageIO`, `CoreImage`, `CoreGraphics`, `UniformTypeIdentifiers`.

### Host App Permissions

The library does not declare runtime permissions for its host. Consuming apps must add:

- iOS `Info.plist` — `NSCameraUsageDescription` only. The gallery flow uses `PHPickerViewController`, which reads no Photos authorization, so `NSPhotoLibraryUsageDescription` is **not** required. No location permission is required either: `includeGpsExif` only copies the EXIF GPS dictionary already present in the source image; there is no `CLLocationManager` call.
- Android `AndroidManifest.xml` — only `android.permission.INTERNET`. ML Kit Document Scanner handles camera grant via Play Services, and the custom gallery flow uses the system photo picker (no `READ_MEDIA_IMAGES`).

## Architecture

This is a **React Native TurboModule library** (new architecture). The root is the library; `example/` is a separate Yarn workspace that consumes it.

### JS Layer

- `src/NativeReceiptScanner.ts` — TurboModule spec. Defines the `Spec` interface and registers the module as `"ReceiptScanner"`. Uses `Object` for options/result in Phase 1; codegen reads this to generate native base classes.
- `src/scan.native.tsx` — Native platform entry: merges options with `DEFAULT_SCAN_OPTIONS` and delegates to the TurboModule.
- `src/scan.tsx` — Web/JS fallback: pure JavaScript implementation.
- `src/types.ts` — All public types and defaults. Source of truth for the API contract; `docs/specs/api-contract.md` is the normative reference and lists every field. Exports: `ScanReceiptOptions` (`source`, `maxPages`, `quality`, `includeExif`, `includeGpsExif`, `includeRawExif`, `ocr`, `ocrLanguages`, `ocrFloor`, `ocrGeometry`, `autoRotate`, `minimumTextHeight`, `cropAutoConfirm`), `ScanReceiptResult`, `ReceiptImage` (carries `imageOrigin`, `ocrQuality`, `ocrLines`), `ReceiptExif` (`orientation`, `dateTimeOriginal`, `make`, `model`, `software`, `gps`), `ImageOrigin` (`"camera" | "screenshot" | "download" | "unknown"`), `OcrFloor`, `OcrQuality`, `OcrLine`, `OcrModelState`, the `OcrCapabilities` union (`IosOcrCapabilities` | `AndroidOcrCapabilities` | `WebOcrCapabilities`), and the `DEFAULT_SCAN_OPTIONS` / `DEFAULT_OCR_FLOOR` / `DEFAULT_OCR_LANGUAGES` constants.
- `src/index.tsx` — Public re-exports.
- `src/__tests__/index.test.tsx` — Jest spec for the JS surface. Mocks `NativeReceiptScanner` and asserts `DEFAULT_SCAN_OPTIONS` propagation through `scan.native.tsx`.

Metro resolves `.native.tsx` over `.tsx` on iOS/Android automatically.

### Android Layer (`android/src/main/java/com/receiptscanner/`)

- `ReceiptScannerModule.kt` — TurboModule entry. Implements `NativeReceiptScannerSpec`, holds `pendingPromise`/`pendingOptions`, launches ML Kit scanner via `startIntentSenderForResult`, and dispatches `onActivityResult` to the executor thread. For `source: "gallery"` it routes to `CropEditorActivity` instead of the GMS scanner.
- `ImageProcessor.kt` — Orientation normalisation + JPEG recompress + EXIF read/strip. Takes the raw ML Kit URI, writes a processed JPEG to the app cache dir.
- `OcrProcessor.kt` — Wraps a `TextRecognizer` **injected by `OcrModelManager`** (no longer constructs the Korean client itself). Must be `close()`d after use to release the ML Kit client.
- `OcrLanguageResolver.kt` — Validates BCP 47 tags and resolves the list to exactly one ML Kit script family (Latin / Korean / Japanese / Chinese / Devanagari).
- `OcrModelManager.kt` — Builds the recognizer for the resolved script, checks and installs the Play-services module, and backs `getOcrCapabilities()`.
- `GalleryCacheCopier.kt` — Copies picked `content://` items into the cache dir before processing.
- `OcrGeometry.kt` / `QuadGeometry.kt` — Pure geometry helpers (box rotate/clamp; quadrilateral maths) kept free of Android framework types so they are unit-testable.
- `ResultBuilder.kt` — Builds the `WritableMap` shapes for `ReceiptImage` and `ScanReceiptResult`.
- `ScanOptions.kt` — Parses `ReadableMap` from JS into a typed data class.
- `ReceiptScannerPackage.kt` — Registers the module with `isTurboModule = true`.
- `CropEditorActivity.kt` — Custom gallery flow Activity. Picks a photo, runs MLKit document/segmentation detection, then hosts `QuadCropView` so the user can adjust corners before perspective correction. Mirrors the iOS `RNGalleryPickerDelegate` + `RNCropEditorViewController` stack.
- `QuadCropView.kt` — Custom `View` with four draggable corner handles and a confirm/cancel button bar. Handles touch hit-testing, edge clamping, and emits the final quadrilateral back to `CropEditorActivity`.

### iOS Layer (`ios/`)

Each component lives in a separate `.h` / `.m` pair (only `ReceiptScanner` itself is `.mm` for the C++ TurboModule glue).
Minimum deployment target is iOS 16 (Korean OCR via `VNRecognizeTextRequest`).

- `ReceiptScanner.{h,mm}` — TurboModule entry. Routes to camera (`RNDocumentCameraDelegate`) or gallery (`RNGalleryPickerDelegate`) on the main queue. Holds strong references to delegates to keep them alive; clears them in the wrapped resolve/reject blocks.
- `RNDocumentCameraDelegate.{h,m}` — `VNDocumentCameraViewControllerDelegate`. Writes each scanned page to temp dir and hands off to `RNImageProcessor`.
- `RNGalleryPickerDelegate.{h,m}` — `PHPickerViewControllerDelegate`. Calls `VNDetectRectanglesRequest` (and document-segmentation when available) then presents `RNCropEditorViewController`.
- `RNCropEditorViewController.{h,m}` — Custom crop editor with four draggable corner handles and a confirm/cancel button bar. See ADR-004 for critical implementation constraints.
- `RNImageProcessor.{h,m}` — Orientation normalize + JPEG recompress + EXIF read via `ImageIO`/`CoreGraphics`. Always writes EXIF orientation as `kCGImagePropertyOrientationUp (1)` in output; `exif.orientation` on the JS side will always be `1`.
- `RNOcrProcessor.{h,m}` — `VNRecognizeTextRequest` configured with the caller's ordered `ocrLanguages` (default `["ko-KR", "en-US"]`). Validates the tags as BCP 47 and against Vision's supported set before any scanner UI opens; also backs `getOcrCapabilities()`.
- `RNScanOptions.{h,m}` — Parses the JS options dictionary into a typed Obj-C object.

**Read `ios/AGENTS.md` before modifying any iOS file.** It contains the symbol-based "where to look" map (e.g. the `cropAutoConfirm` threshold is `kCropAutoConfirmMinConfidence` in `RNGalleryPickerDelegate.m`), the strong-ref / single-scan-guard / background-thread / `CIContext`-per-call conventions, and the full anti-pattern list that ADR-004 only summarises.

### Adding a New Native Method

1. Add the method signature to `Spec` in `src/NativeReceiptScanner.ts`.
2. Implement it in `src/scan.native.tsx` (native) and `src/scan.tsx` (web fallback).
3. Implement it in `ReceiptScannerModule.kt` (Android) and `ReceiptScanner.mm` (iOS).
4. Run `yarn example android`/`ios` — codegen re-generates native spec files automatically on build.

The module name string `"ReceiptScanner"` must remain identical in `NativeReceiptScanner.ts`, `ReceiptScannerPackage.kt`, and `ReceiptScanner.mm`.

## Architectural Decision Records

Full ADRs live in `docs/notes/`. Read the relevant one before changing the corresponding subsystem.

| ID      | File                                                     | Topic                                                                                                                                        |
| ------- | -------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------- |
| ADR-001 | `docs/notes/adr-001-android-mlkit.md`                    | Why Android uses GMS ML Kit Document Scanner for the camera path.                                                                            |
| ADR-002 | `docs/notes/adr-002-ios-gallery-crop.md`                 | iOS gallery crop strategy (Vision detect → custom 4-handle editor → CIPerspectiveCorrection).                                                |
| ADR-003 | `docs/notes/adr-003-package-boundaries.md`               | Package responsibility boundary — image primitives only, no receipt domain logic. Summarised below.                                          |
| ADR-004 | `docs/notes/adr-004-ios-crop-editor-realdevice-fixes.md` | iOS crop editor real-device implementation fixes. Summarised below.                                                                          |
| ADR-005 | `docs/notes/adr-005-android-gallery-strategy.md`         | Android `source: "gallery"` uses `CropEditorActivity`, not GMS gallery import. EXIF + origin notes.                                          |
| ADR-006 | `docs/notes/adr-006-design-audit-and-ios16-baseline.md`  | 2026-05-09 design audit outcomes — iOS 16 baseline, `exif.software`, dropped `AndroidCameraOptions`, OCR intent.                             |
| ADR-007 | `docs/notes/adr-007-v042-v043-code-diff.md`              | v0.4.2 → v0.4.3 code diff of record. Uses the then-current `GALLERY_MAX_DIM`; the rename to `MAX_PROCESSING_DIM` landed later, in `ca58d01`. |

**Cross-cutting reference.** `docs/notes/platform-asymmetries.md` is a living document tracking every iOS/Android difference (EXIF semantics, OCR rotation invariance, `rotationDegrees` direction, etc.). Read it before designing any feature that spans both platforms.

## Package Scope Boundary (ADR-003)

This package owns **image primitive operations only**: acquisition, crop, orientation normalisation, JPEG compression, EXIF extraction, and on-device OCR (raw string). Receipt domain logic — parsing store name/amount, upload transport, duplicate detection, Azure OCR integration — belongs in the consuming app or a separate package. PRs that add domain logic should be rejected.

## iOS Implementation Constraints (ADR-004)

These constraints apply specifically to `RNCropEditorViewController` and `RNGalleryPickerDelegate`:

- **Use `UIButton` not `UIBarButtonItem`**: `UIBarButtonItem` target-action silently fails in some React Native modal presentation paths. Use `UIButton` with `UIControlEventTouchUpInside` directly.
- **Button bar bottom offset**: Anchor the button bar to `view.bottomAnchor constant:-34`, not `safeAreaLayoutGuide.bottomAnchor`. The safe area guide can report `0` when presented via `RCTPresentedViewController`, placing the bar inside the home-indicator gesture zone.
- **Subview Z-order for hit-test**: Add all four handle views before the button bar in `viewDidLoad`. UIKit checks subviews in reverse order; adding `buttonBar` last gives it the highest hit-test priority.
- **`VNImageRequestHandler` — use `initWithCGImage:orientation:`**: `initWithCIImage:` ignores the embedded orientation transform. Pass the explicit `CGImagePropertyOrientation` so Vision processes pixels in the correct orientation.
- **`CIPerspectiveCorrection` — bake orientation first**: Use `initWithCGImage:` + `imageByApplyingOrientation:` before passing to `CIPerspectiveCorrection`. `initWithImage:` embeds orientation lazily; some CIFilters operate on raw (un-oriented) pixel data, producing crops that include content outside the selected quadrilateral.

## Temp File Lifecycle

Output `file://` URIs are stable until the **next `scan()` call**, which deletes the previous session's temp files. URIs do not survive app restarts (OS clears the cache directory).

## Build Output

`yarn prepare` produces:

- `lib/module/` — ESM JavaScript (used by Metro/bundlers)
- `lib/typescript/` — Type declarations

The `lib/` directory is gitignored; it is rebuilt on `yarn install` via the `prepare` script.
