# src/ — JS API Layer

## OVERVIEW

Public API surface, TurboModule spec, and native/web split. Compiles to `lib/module/` (ESM) and `lib/typescript/` via `bob build`. The `.tsx` variant is the web fallback; Metro auto-prefers `.native.tsx` on iOS/Android.

## STRUCTURE

```plaintext
src/
├── index.tsx                Re-exports `scan` + 4 types (the entire public surface)
├── types.ts                 ScanReceiptOptions, ReceiptImage, ReceiptExif, DEFAULT_SCAN_OPTIONS
├── NativeReceiptScanner.ts  TurboModule spec; module name "ReceiptScanner"
├── scan.native.tsx          Native path: merges defaults, calls TurboModule
├── scan.tsx                 Web/JS fallback: returns {status:"cancelled", images:[]}
└── __tests__/index.test.tsx Jest tests; mocks NativeReceiptScanner default export
```

## WHERE TO LOOK

| Task                                        | File                                                       |
| ------------------------------------------- | ---------------------------------------------------------- |
| Add a public option                         | `types.ts` (`ScanReceiptOptions` + `DEFAULT_SCAN_OPTIONS`) |
| Add/rename a public field on `ReceiptImage` | `types.ts`                                                 |
| Add a public re-export                      | `index.tsx`                                                |
| Tighten the TurboModule spec (Phase 2)      | `NativeReceiptScanner.ts`                                  |
| Update the web fallback                     | `scan.tsx`                                                 |
| Adjust default-merging behavior             | `scan.native.tsx`                                          |

## CONVENTIONS

- **Don't import `./scan.native` directly** — always import from `./scan` so Metro/Vite can pick the right variant.
- The TurboModule spec deliberately uses `Object` for options/result (Phase 1 contract). Tightening requires regenerating the native spec AND updating `ReceiptScannerModule.kt` + `ReceiptScanner.mm` in the same change.
- `DEFAULT_SCAN_OPTIONS` is the source of truth for defaults on the JS side. Native sides have parallel copies (`ScanOptions.kt`, `RNScanOptions.m`) — keep all three in sync when adding/changing a default.
- Tests use `jest.mock("../NativeReceiptScanner", () => ({ __esModule: true, default: { scan: jest.fn() } }))` pattern. There is no `__mocks__/` directory.
- The `ImageOrigin` type is JS-only metadata; native sides emit `"camera"` / `"unknown"` strings (no enum on the wire).

## ANTI-PATTERNS

- ❌ Importing `react-native` (or any native module) at top level in `scan.tsx` — the web fallback must stay tree-shakeable for Metro Web/Vite.
- ❌ Adding business logic in `scan.native.tsx` — it's a 1-line dispatcher; merging options is the only allowed transform.
- ❌ Changing the codegen module name string `"ReceiptScanner"` — hard-coded in three places (this dir + Android + iOS); they must match exactly.
- ❌ Collocating tests next to source (`scan.test.tsx`) — repo convention is `src/__tests__/*.test.tsx`.
- ❌ Casting away the `Promise<Object>` return type with anything other than the documented `as Promise<ScanReceiptResult>` in `scan.native.tsx` — that one cast is the only allowed type narrowing until Phase 2.
