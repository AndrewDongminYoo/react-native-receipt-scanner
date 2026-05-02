# Phase 1 — JS Wrapper & Type Unification

## Goal

Establish a stable public interface before any native implementation work begins.
The app can import `react-native-receipt-scanner` and call `scan()` — it returns
a cancelled result for now, but the types and contract are locked in.

This makes the library boundary real and prevents external scanner imports from
leaking into app code.

## Tasks

### Remove placeholder

- [x] Delete `src/multiply.tsx`, `src/multiply.native.tsx`
- [x] Remove `multiply` from `src/NativeReceiptScanner.ts`
- [x] Remove `multiply` from `android/src/main/java/com/receiptscanner/ReceiptScannerModule.kt`
- [x] Remove `multiply` from `ios/ReceiptScanner.mm` and `ios/ReceiptScanner.h`

### Define types

- [x] Create `src/types.ts` with:
  - `ScanReceiptOptions` (with defaults constant `DEFAULT_SCAN_OPTIONS`)
  - `ScanReceiptResult`
  - `ReceiptImage`
  - `ReceiptExif`

### JS entry point

- [x] Create `src/scan.native.tsx` — applies defaults, calls native module
- [x] Create `src/scan.tsx` — stub that always resolves `{ status: 'cancelled', images: [] }`
- [x] Update `src/index.tsx` to export `scan` and all types

### TurboModule spec

- [x] Update `src/NativeReceiptScanner.ts`:
  - Method: `scan(options: Object): Promise<Object>`
  - Keep module name `'ReceiptScanner'`

### Native stubs (compile-only, no logic yet)

- [x] `ReceiptScannerModule.kt`: add `scan(options: ReadableMap, promise: Promise)` — resolves `null` for now
- [x] `ReceiptScanner.mm`: add `scan:(NSDictionary*)options resolve:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject` — resolves `nil`

### Example app

- [x] Update `example/src/App.tsx` to call `scan()` and display result status

## Definition of Done

- [x] `yarn typecheck` passes with no errors
- [x] `yarn lint` passes
- [x] `yarn test` passes
- [x] `scan()` importable and callable in example app (returns `cancelled`)
- [x] No reference to `multiply` remains anywhere in the repo
- [x] All types exported from package root (`import type { ReceiptImage } from 'react-native-receipt-scanner'`)
