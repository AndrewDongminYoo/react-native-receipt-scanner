# Phase 1 — JS Wrapper & Type Unification — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the `multiply` placeholder with a stable `scan()` API and locked-in TypeScript types; native stubs compile and resolve null but contain no real logic yet.

**Architecture:** All shared types live in `src/types.ts`. Platform resolution follows the existing pattern: `src/scan.tsx` is the web stub (always returns `{ status: 'cancelled', images: [] }`), `src/scan.native.tsx` applies defaults and calls the TurboModule. Android and iOS stubs override the codegen'd abstract method and immediately resolve null.

**Tech Stack:** TypeScript 6, React Native TurboModule (new architecture), Jest 30 + @react-native/jest-preset, Kotlin (Android stub), Objective-C++ (iOS stub), react-native-builder-bob

---

## File Map

| Action  | Path                                                               | Responsibility                                                                                   |
| ------- | ------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------ |
| Create  | `src/types.ts`                                                     | `ScanReceiptOptions`, `ScanReceiptResult`, `ReceiptImage`, `ReceiptExif`, `DEFAULT_SCAN_OPTIONS` |
| Create  | `src/scan.tsx`                                                     | Web stub — always `{ status: 'cancelled', images: [] }`                                          |
| Create  | `src/scan.native.tsx`                                              | Native — merge defaults, call TurboModule                                                        |
| Modify  | `src/NativeReceiptScanner.ts`                                      | Replace `multiply` with `scan(options: Object): Promise<Object>`                                 |
| Modify  | `src/index.tsx`                                                    | Export `scan` + all types; remove `multiply`                                                     |
| Replace | `src/__tests__/index.test.tsx`                                     | Tests for types and `scan()`                                                                     |
| Delete  | `src/multiply.tsx`                                                 | Removed                                                                                          |
| Delete  | `src/multiply.native.tsx`                                          | Removed                                                                                          |
| Modify  | `android/src/main/java/com/receiptscanner/ReceiptScannerModule.kt` | Replace `multiply` with `scan` stub                                                              |
| Modify  | `ios/ReceiptScanner.h`                                             | Keep interface declaration (no method changes needed)                                            |
| Modify  | `ios/ReceiptScanner.mm`                                            | Replace `multiply` with `scan` stub                                                              |
| Modify  | `example/src/App.tsx`                                              | Call `scan()` instead of `multiply()`                                                            |

---

### Task 1: Define types

**Files:**

- Create: `src/types.ts`
- Modify: `src/__tests__/index.test.tsx`

- [x] **Step 1: Write the failing test**
- [x] **Step 2: Run test to verify it fails**
- [x] **Step 3: Create `src/types.ts`**
- [x] **Step 4: Run test to verify it passes**
- [x] **Step 5: Commit**

---

### Task 2: Update TurboModule spec

**Files:**

- Modify: `src/NativeReceiptScanner.ts`

- [x] **Step 1: Replace `src/NativeReceiptScanner.ts`**
- [x] **Step 2: Commit**

---

### Task 3: Create scan() implementations

**Files:**

- Create: `src/scan.tsx`
- Create: `src/scan.native.tsx`
- Modify: `src/__tests__/index.test.tsx`

- [x] **Step 1: Write failing tests**
- [x] **Step 2: Run test to verify it fails**
- [x] **Step 3: Create `src/scan.tsx` (web stub)**
- [x] **Step 4: Create `src/scan.native.tsx` (native)**
- [x] **Step 5: Run tests to verify they pass**
- [x] **Step 6: Commit**

---

### Task 4: Update index.tsx and delete multiply

**Files:**

- Modify: `src/index.tsx`
- Delete: `src/multiply.tsx`
- Delete: `src/multiply.native.tsx`

- [x] **Step 1: Replace `src/index.tsx`**
- [x] **Step 2: Delete placeholder files**
- [x] **Step 3: Run typecheck and tests**
- [x] **Step 4: Commit**

---

### Task 5: Update Android stub

**Files:**

- Modify: `android/src/main/java/com/receiptscanner/ReceiptScannerModule.kt`

- [x] **Step 1: Replace `ReceiptScannerModule.kt`**
- [x] **Step 2: Commit**

---

### Task 6: Update iOS stub

**Files:**

- Modify: `ios/ReceiptScanner.mm`

- [x] **Step 1: Replace `ios/ReceiptScanner.mm`**
- [x] **Step 2: Commit**

---

### Task 7: Update example app

**Files:**

- Modify: `example/src/App.tsx`

- [x] **Step 1: Replace `example/src/App.tsx`**
- [x] **Step 2: Commit**

---

### Task 8: Final verification

- [x] **Step 1: Confirm no multiply references remain**
- [x] **Step 2: Run full test suite** — 5 tests passed
- [x] **Step 3: Run lint** — no errors
- [x] **Step 4: Run typecheck** — no errors
- [x] **Step 5: Build library**
- [x] **Step 6: Final commit**
