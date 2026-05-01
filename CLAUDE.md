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

- `src/NativeReceiptScanner.ts` — TurboModule spec. Defines the `Spec` interface and registers the module as `"ReceiptScanner"`. This is the source of truth; codegen reads it to generate native base classes (not committed to source control).
- `src/multiply.native.tsx` — Native platform entry: delegates to the TurboModule.
- `src/multiply.tsx` — Web/JS fallback: pure JavaScript implementation.
- `src/index.tsx` — Public re-exports.

Metro resolves `.native.tsx` over `.tsx` on iOS/Android automatically.

### Native Layer

- **Android:** `android/src/main/java/com/receiptscanner/ReceiptScannerModule.kt` implements the codegen'd `NativeReceiptScannerSpec`. `ReceiptScannerPackage.kt` registers it with `isTurboModule = true`.
- **iOS:** `ios/ReceiptScanner.mm` conforms to `<NativeReceiptScannerSpec>` and wires JSI via `getTurboModule:`.

### Adding a New Native Method

1. Add the method signature to `Spec` in `src/NativeReceiptScanner.ts`.
2. Implement it in `src/multiply.native.tsx` (native) and `src/multiply.tsx` (web fallback).
3. Implement it in `ReceiptScannerModule.kt` (Android) and `ReceiptScanner.mm` (iOS).
4. Run `yarn example android`/`ios` — codegen re-generates native spec files automatically on build.

The module name string `"ReceiptScanner"` must remain identical in `NativeReceiptScanner.ts`, `ReceiptScannerPackage.kt`, and `ReceiptScanner.mm`.

## Build Output

`yarn prepare` produces:

- `lib/module/` — ESM JavaScript (used by Metro/bundlers)
- `lib/typescript/` — Type declarations

The `lib/` directory is gitignored; it is rebuilt on `yarn install` via the `prepare` script.
