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
```

### Example App

```bash
yarn example start          # Start Metro bundler
yarn example android        # Run on Android emulator
yarn example ios            # Run on iOS simulator
```

## Architecture

This is a **React Native TurboModule library** (new architecture). The root is the library; `example/` is a separate Yarn workspace that consumes it.

### JS Layer

- `src/NativeReceiptScanner.ts` — TurboModule spec. Defines the `Spec` interface and registers the module as `"ReceiptScanner"`. Uses `Object` for options/result in Phase 1; codegen reads this to generate native base classes.
- `src/scan.native.tsx` — Native platform entry: merges options with `DEFAULT_SCAN_OPTIONS` and delegates to the TurboModule.
- `src/scan.tsx` — Web/JS fallback: pure JavaScript implementation.
- `src/types.ts` — All public types (`ScanReceiptOptions`, `ReceiptImage`, `ScanReceiptResult`) and `DEFAULT_SCAN_OPTIONS`. Source of truth for the API contract.
- `src/index.tsx` — Public re-exports.

Metro resolves `.native.tsx` over `.tsx` on iOS/Android automatically.

### Android Layer (`android/src/main/java/com/receiptscanner/`)

- `ReceiptScannerModule.kt` — TurboModule entry. Implements `NativeReceiptScannerSpec`, holds `pendingPromise`/`pendingOptions`, launches ML Kit scanner via `startIntentSenderForResult`, and dispatches `onActivityResult` to the executor thread.
- `ImageProcessor.kt` — Orientation normalisation + JPEG recompress + EXIF read/strip. Takes the raw ML Kit URI, writes a processed JPEG to the app cache dir.
- `OcrProcessor.kt` — Wraps `TextRecognition.getClient(KoreanTextRecognizerOptions())`. Must be `close()`d after use to release the ML Kit client.
- `ResultBuilder.kt` — Builds the `WritableMap` shapes for `ReceiptImage` and `ScanReceiptResult`.
- `ScanOptions.kt` — Parses `ReadableMap` from JS into a typed data class.
- `ReceiptScannerPackage.kt` — Registers the module with `isTurboModule = true`.

### iOS Layer (`ios/`)

- `ReceiptScanner.mm` — TurboModule entry. Routes to camera (`RNDocumentCameraDelegate`) or gallery (`RNGalleryPickerDelegate`) on the main queue. Holds strong references to delegates to keep them alive; clears them in the wrapped resolve/reject blocks.
- `RNDocumentCameraDelegate` — `VNDocumentCameraViewControllerDelegate`. Writes each scanned page to temp dir and hands off to `RNImageProcessor`.
- `RNGalleryPickerDelegate` — `PHPickerViewControllerDelegate`. Calls `VNDetectRectanglesRequest` then presents `RNCropEditorViewController`.
- `RNCropEditorViewController` — Custom crop editor with four draggable corner handles and a confirm/cancel button bar. See ADR-004 for critical implementation constraints.
- `RNImageProcessor` — Orientation normalize + JPEG recompress + EXIF read via `ImageIO`/`CoreGraphics`. Always writes EXIF orientation as `kCGImagePropertyOrientationUp (1)` in output; `exif.orientation` on the JS side will always be `1`.
- `RNOcrProcessor` — `VNRecognizeTextRequest` with `recognitionLanguages: ["ko-KR", "en-US"]`. Korean OCR requires iOS 16+; falls back to Latin only on older versions.
- `RNScanOptions` — Parses the JS options dictionary into a typed Obj-C object.

### Adding a New Native Method

1. Add the method signature to `Spec` in `src/NativeReceiptScanner.ts`.
2. Implement it in `src/scan.native.tsx` (native) and `src/scan.tsx` (web fallback).
3. Implement it in `ReceiptScannerModule.kt` (Android) and `ReceiptScanner.mm` (iOS).
4. Run `yarn example android`/`ios` — codegen re-generates native spec files automatically on build.

The module name string `"ReceiptScanner"` must remain identical in `NativeReceiptScanner.ts`, `ReceiptScannerPackage.kt`, and `ReceiptScanner.mm`.

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
