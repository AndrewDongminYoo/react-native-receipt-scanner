# Phase 1 — JS Wrapper & Type Unification

## Goal

Establish a stable public interface before any native implementation work begins.
The app can import `react-native-receipt-scanner` and call `scan()` — it returns
a cancelled result for now, but the types and contract are locked in.

This makes the library boundary real and prevents external scanner imports from
leaking into app code.

## Tasks

### Remove placeholder

- [ ] Delete `src/multiply.tsx`, `src/multiply.native.tsx`
- [ ] Remove `multiply` from `src/NativeReceiptScanner.ts`
- [ ] Remove `multiply` from `android/src/main/java/com/receiptscanner/ReceiptScannerModule.kt`
- [ ] Remove `multiply` from `ios/ReceiptScanner.mm` and `ios/ReceiptScanner.h`

### Define types

- [ ] Create `src/types.ts` with:
  - `ScanReceiptOptions` (with defaults constant `DEFAULT_SCAN_OPTIONS`)
  - `ScanReceiptResult`
  - `ReceiptImage`
  - `ReceiptExif`

### JS entry point

- [ ] Create `src/scan.ts` — applies defaults, calls native module
- [ ] Create `src/scan.web.ts` — stub that always resolves `{ status: 'cancelled', images: [] }`
- [ ] Update `src/index.tsx` to export `scan` and all types

### TurboModule spec

- [ ] Update `src/NativeReceiptScanner.ts`:
  - Method: `scan(options: Object): Promise<Object>`
  - Keep module name `'ReceiptScanner'`

### Native stubs (compile-only, no logic yet)

- [ ] `ReceiptScannerModule.kt`: add `scan(options: ReadableMap, promise: Promise)` — resolves `null` for now
- [ ] `ReceiptScanner.mm`: add `scan:(NSDictionary*)options resolve:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject` — resolves `nil`

### Example app

- [ ] Update `example/src/App.tsx` to call `scan()` and display result status

## Definition of Done

- [ ] `yarn typecheck` passes with no errors
- [ ] `yarn lint` passes
- [ ] `yarn test` passes
- [ ] `scan()` importable and callable in example app (returns `cancelled`)
- [ ] No reference to `multiply` remains anywhere in the repo
- [ ] All types exported from package root (`import type { ReceiptImage } from 'react-native-receipt-scanner'`)
