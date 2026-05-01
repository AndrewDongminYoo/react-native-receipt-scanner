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

- [ ] **Step 1: Write the failing test**

Replace the entire contents of `src/__tests__/index.test.tsx` with:

```tsx
import { DEFAULT_SCAN_OPTIONS } from "../types";

describe("DEFAULT_SCAN_OPTIONS", () => {
  it("has expected default values", () => {
    expect(DEFAULT_SCAN_OPTIONS).toEqual({
      source: "camera",
      maxPages: 1,
      quality: 0.82,
      includeExif: true,
      includeGpsExif: false,
      ocr: true,
    });
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

```bash
yarn test --testPathPattern=src/__tests__/index
```

Expected output: `FAIL` — `Cannot find module '../types'`

- [ ] **Step 3: Create `src/types.ts`**

```ts
export type ScanReceiptOptions = {
  source?: "camera" | "gallery";
  maxPages?: number;
  quality?: number;
  includeExif?: boolean;
  includeGpsExif?: boolean;
  ocr?: boolean;
};

export type ReceiptExif = {
  orientation?: number;
  dateTimeOriginal?: string;
  make?: string;
  model?: string;
  gps?: {
    latitude: number;
    longitude: number;
  };
};

export type ReceiptImage = {
  uri: string;
  width: number;
  height: number;
  fileName: string;
  mimeType: "image/jpeg";
  fileSize: number;
  ocrText?: string;
  exif?: ReceiptExif;
};

export type ScanReceiptResult = {
  status: "success" | "cancelled";
  images: ReceiptImage[];
};

export const DEFAULT_SCAN_OPTIONS: Required<ScanReceiptOptions> = {
  source: "camera",
  maxPages: 1,
  quality: 0.82,
  includeExif: true,
  includeGpsExif: false,
  ocr: true,
};
```

- [ ] **Step 4: Run test to verify it passes**

```bash
yarn test --testPathPattern=src/__tests__/index
```

Expected output: `PASS` — 1 test passed

- [ ] **Step 5: Commit**

```bash
git add src/types.ts src/__tests__/index.test.tsx
git commit -m "feat: add ScanReceiptOptions, ScanReceiptResult, ReceiptImage, ReceiptExif types"
```

---

### Task 2: Update TurboModule spec

**Files:**

- Modify: `src/NativeReceiptScanner.ts`

- [ ] **Step 1: Replace `src/NativeReceiptScanner.ts`**

```ts
import { TurboModuleRegistry, type TurboModule } from "react-native";

export interface Spec extends TurboModule {
  scan(options: Object): Promise<Object>;
}

export default TurboModuleRegistry.getEnforcing<Spec>("ReceiptScanner");
```

- [ ] **Step 2: Commit**

```bash
git add src/NativeReceiptScanner.ts
git commit -m "feat: update TurboModule spec — replace multiply with scan"
```

---

### Task 3: Create scan() implementations

**Files:**

- Create: `src/scan.tsx`
- Create: `src/scan.native.tsx`
- Modify: `src/__tests__/index.test.tsx`

- [ ] **Step 1: Write failing tests — replace `src/__tests__/index.test.tsx` entirely**

```tsx
import { DEFAULT_SCAN_OPTIONS } from "../types";
import { scan } from "../scan.native";
import NativeReceiptScanner from "../NativeReceiptScanner";

jest.mock("../NativeReceiptScanner", () => ({
  default: {
    scan: jest.fn(),
  },
}));

const mockNative = NativeReceiptScanner as jest.Mocked<typeof NativeReceiptScanner>;

describe("DEFAULT_SCAN_OPTIONS", () => {
  it("has expected default values", () => {
    expect(DEFAULT_SCAN_OPTIONS).toEqual({
      source: "camera",
      maxPages: 1,
      quality: 0.82,
      includeExif: true,
      includeGpsExif: false,
      ocr: true,
    });
  });
});

describe("scan (native)", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it("resolves with native module result", async () => {
    const mockResult = {
      status: "success",
      images: [
        {
          uri: "file:///tmp/receipt.jpg",
          width: 1080,
          height: 1920,
          fileName: "receipt.jpg",
          mimeType: "image/jpeg",
          fileSize: 123456,
        },
      ],
    };
    mockNative.scan.mockResolvedValueOnce(mockResult);

    const result = await scan();

    expect(result.status).toBe("success");
    expect(result.images).toHaveLength(1);
    expect(result.images[0]!.uri).toBe("file:///tmp/receipt.jpg");
  });

  it("applies default options when none provided", async () => {
    mockNative.scan.mockResolvedValueOnce({ status: "cancelled", images: [] });

    await scan();

    expect(mockNative.scan).toHaveBeenCalledWith({
      source: "camera",
      maxPages: 1,
      quality: 0.82,
      includeExif: true,
      includeGpsExif: false,
      ocr: true,
    });
  });

  it("merges provided options with defaults", async () => {
    mockNative.scan.mockResolvedValueOnce({ status: "cancelled", images: [] });

    await scan({ quality: 0.5, ocr: false });

    expect(mockNative.scan).toHaveBeenCalledWith({
      source: "camera",
      maxPages: 1,
      quality: 0.5,
      includeExif: true,
      includeGpsExif: false,
      ocr: false,
    });
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

```bash
yarn test --testPathPattern=src/__tests__/index
```

Expected output: `FAIL` — `Cannot find module '../scan.native'`

- [ ] **Step 3: Create `src/scan.tsx` (web stub)**

```tsx
import type { ScanReceiptOptions, ScanReceiptResult } from "./types";

export async function scan(_options?: ScanReceiptOptions): Promise<ScanReceiptResult> {
  return { status: "cancelled", images: [] };
}
```

- [ ] **Step 4: Create `src/scan.native.tsx` (native)**

```tsx
import NativeReceiptScanner from "./NativeReceiptScanner";
import { DEFAULT_SCAN_OPTIONS } from "./types";
import type { ScanReceiptOptions, ScanReceiptResult } from "./types";

export async function scan(options?: ScanReceiptOptions): Promise<ScanReceiptResult> {
  const merged = { ...DEFAULT_SCAN_OPTIONS, ...options };
  return NativeReceiptScanner.scan(merged) as Promise<ScanReceiptResult>;
}
```

- [ ] **Step 5: Run tests to verify they pass**

```bash
yarn test --testPathPattern=src/__tests__/index
```

Expected output: `PASS` — 4 tests passed

- [ ] **Step 6: Commit**

```bash
git add src/scan.tsx src/scan.native.tsx src/__tests__/index.test.tsx
git commit -m "feat: implement scan() — web stub and native with default options merging"
```

---

### Task 4: Update index.tsx and delete multiply

**Files:**

- Modify: `src/index.tsx`
- Delete: `src/multiply.tsx`
- Delete: `src/multiply.native.tsx`

- [ ] **Step 1: Replace `src/index.tsx`**

```tsx
export { scan } from "./scan";
export type { ScanReceiptOptions, ScanReceiptResult, ReceiptImage, ReceiptExif } from "./types";
```

- [ ] **Step 2: Delete placeholder files**

```bash
rm src/multiply.tsx src/multiply.native.tsx
```

- [ ] **Step 3: Run typecheck and tests**

```bash
yarn typecheck && yarn test
```

Expected output: both pass with no errors. If typecheck reports errors about
`multiply` in `android/` or `ios/`, those are fixed in Tasks 5–6.

- [ ] **Step 4: Commit**

```bash
git add src/index.tsx
git rm src/multiply.tsx src/multiply.native.tsx
git commit -m "feat: export scan() from package root; remove multiply placeholder"
```

---

### Task 5: Update Android stub

**Files:**

- Modify: `android/src/main/java/com/receiptscanner/ReceiptScannerModule.kt`

Phase 1 only — resolves null immediately. Full implementation is Phase 2.

- [ ] **Step 1: Replace `ReceiptScannerModule.kt`**

```kotlin
package com.receiptscanner

import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReadableMap

class ReceiptScannerModule(
  reactContext: ReactApplicationContext,
) : NativeReceiptScannerSpec(reactContext) {

  override fun scan(options: ReadableMap, promise: Promise) {
    promise.resolve(null)
  }

  companion object {
    const val NAME = NativeReceiptScannerSpec.NAME
  }
}
```

`NativeReceiptScannerSpec` is generated by codegen during `./gradlew generateCodegenArtifactsFromSchema`.
The generated abstract method signature for `scan(options: Object): Promise<Object>` is
`scan(options: ReadableMap, promise: Promise)`. If the build fails with "does not override",
verify the generated file at `example/android/build/generated/source/codegen/java/com/receiptscanner/NativeReceiptScannerSpec.java`.

- [ ] **Step 2: Commit**

```bash
git add android/src/main/java/com/receiptscanner/ReceiptScannerModule.kt
git commit -m "feat: update Android stub — replace multiply with scan"
```

---

### Task 6: Update iOS stub

**Files:**

- Modify: `ios/ReceiptScanner.mm`

Phase 1 only — resolves nil immediately. Full implementation is Phase 3.

`ios/ReceiptScanner.h` is unchanged — it already declares `@interface ReceiptScanner : NSObject <NativeReceiptScannerSpec>`, which will pick up the codegen'd `scan` method automatically.

- [ ] **Step 1: Replace `ios/ReceiptScanner.mm`**

```objc
#import "ReceiptScanner.h"

@implementation ReceiptScanner

- (void)scan:(NSDictionary *)options
     resolve:(RCTPromiseResolveBlock)resolve
      reject:(RCTPromiseRejectBlock)reject
{
    resolve(nil);
}

- (std::shared_ptr<facebook::react::TurboModule>)getTurboModule:
    (const facebook::react::ObjCTurboModule::InitParams &)params
{
    return std::make_shared<facebook::react::NativeReceiptScannerSpecJSI>(params);
}

+ (NSString *)moduleName
{
    return @"ReceiptScanner";
}

@end
```

The `scan:resolve:reject:` signature matches what codegen generates for an async
`Promise<Object>` return. If the build fails with a protocol conformance error,
verify the generated protocol at
`example/ios/build/generated/ios/ReceiptScannerSpec/ReceiptScannerSpec.h`.

- [ ] **Step 2: Commit**

```bash
git add ios/ReceiptScanner.mm
git commit -m "feat: update iOS stub — replace multiply with scan"
```

---

### Task 7: Update example app

**Files:**

- Modify: `example/src/App.tsx`

- [ ] **Step 1: Replace `example/src/App.tsx`**

```tsx
import { useState } from "react";
import { Button, ScrollView, StyleSheet, Text, View } from "react-native";
import { scan } from "react-native-receipt-scanner";
import type { ScanReceiptResult } from "react-native-receipt-scanner";

export default function App() {
  const [result, setResult] = useState<ScanReceiptResult | null>(null);

  async function handleScan() {
    const scanResult = await scan({ source: "camera", ocr: true });
    setResult(scanResult);
  }

  return (
    <View style={styles.container}>
      <Button title="Scan Receipt" onPress={handleScan} />
      {result && (
        <ScrollView style={styles.result}>
          <Text>Status: {result.status}</Text>
          <Text>Images: {result.images.length}</Text>
          {result.images.map((img, i) => (
            <View key={i}>
              <Text>URI: {img.uri}</Text>
              <Text>
                {img.width}×{img.height} ({img.fileSize} bytes)
              </Text>
              {img.ocrText ? <Text>OCR: {img.ocrText}</Text> : null}
            </View>
          ))}
        </ScrollView>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    alignItems: "center",
    justifyContent: "center",
    padding: 16,
  },
  result: {
    marginTop: 16,
    width: "100%",
  },
});
```

- [ ] **Step 2: Commit**

```bash
git add example/src/App.tsx
git commit -m "feat: update example app to use scan() API"
```

---

### Task 8: Final verification

- [ ] **Step 1: Confirm no multiply references remain**

```bash
grep -r "multiply" src/ example/src/ android/src/ ios/ \
  --include="*.ts" --include="*.tsx" --include="*.kt" \
  --include="*.mm" --include="*.h"
```

Expected output: no output (zero matches).

- [ ] **Step 2: Run full test suite**

```bash
yarn test
```

Expected output: `PASS src/__tests__/index.test.tsx` — 4 tests passed

- [ ] **Step 3: Run lint**

```bash
yarn lint
```

Expected output: no errors or warnings.

- [ ] **Step 4: Run typecheck**

```bash
yarn typecheck
```

Expected output: no errors.

- [ ] **Step 5: Build library**

```bash
yarn prepare
```

Expected output: `lib/module/index.js` and `lib/typescript/src/index.d.ts` created.
Verify exports: `grep -r "scan" lib/typescript/src/index.d.ts` should show the `scan` function and all types.

- [ ] **Step 6: Final commit**

```bash
git add .
git commit -m "chore: phase 1 verification — all checks pass"
```
