# OCR Line Geometry Exposure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expose per-line OCR bounding boxes (`ReceiptImage.ocrLines`) in the final output JPEG pixel space, opt-in via `ScanReceiptOptions.ocrGeometry`, so consuming apps can render Clova-style post-capture text overlays.

**Architecture:** Capture line geometry at the existing OCR iteration points (`OcrProcessor.kt` Pass 0, `RNOcrProcessor.m` final-text pass), remap into the output frame through a pure per-platform rect-rotation helper, and serialize in the four result-assembly call sites. No new pipeline stages, no UI components in the package.

**Tech Stack:** TypeScript (JS contract), Kotlin + JUnit 4 (Android), Objective-C + Vision (iOS), Jest (JS tests).

**Spec:** `docs/specs/ocr-line-geometry.md` — the coordinate contract, remap table, and platform capture points defined there are normative; this plan only sequences the work.

## Global Constraints

- Coordinate contract (spec §좌표계 계약): `frame` is in final output JPEG pixel space, top-left origin, clamped to `ReceiptImage.width × height`; lines with empty text, missing box, or zero-size clamped frame are dropped. No index/length correspondence with `ocrText` is contracted (Android `line.boundingBox` is nullable).
- Rotation remap direction (spec §회전 리매핑 테이블) is PROVISIONAL until confirmed on device; the 90°/270° rows swap if the fixture check disproves the clockwise assumption. Keep the helper's degree convention identical on both platforms; record the confirmed direction in `platform-asymmetries.md`.
- Additive only: no changes to existing fields, defaults, or codegen spec (`NativeReceiptScanner.ts` stays `Object`-typed).
- Package scope (ADR-003): geometry primitives only — no overlay components, no text interpretation.
- Branch: `feat/ocr-line-geometry`. Conventional commits.
- Verification gate before done: `yarn typecheck && yarn lint && yarn test && trunk fmt && trunk check`, plus the Android JUnit tests (same runner as `QuadGeometryTest`) and the device matrix (Task 6).

---

### Task 1: JS contract — `ocrGeometry` option and `OcrLine` type

**Files:**

- Modify: `src/types.ts` (add `OcrLine`, `ScanReceiptOptions.ocrGeometry`, `ReceiptImage.ocrLines`, `DEFAULT_SCAN_OPTIONS.ocrGeometry: false`)
- Modify: `src/index.tsx` (re-export `OcrLine`)
- Test: `src/__tests__/index.test.tsx`

**Interfaces:** exactly as spec §API 계약 — copy the doc comments from the spec, matching the existing `ocrFloor`/`autoRotate` gating phrasing.

- [ ] **Step 1: Write the failing test** — extend the existing `DEFAULT_SCAN_OPTIONS` propagation spec: calling `scan()` with no options forwards `ocrGeometry: false` to the native module; calling with `{ ocr: true, ocrGeometry: true }` forwards both. Assert `OcrLine` is exported from the package root.
- [ ] **Step 2: Implement** — `Required<ScanReceiptOptions>` forces the `DEFAULT_SCAN_OPTIONS` addition to compile.
- [ ] **Step 3: Verify** — `yarn typecheck && yarn test`.

### Task 2: Android — capture, remap helper, serialization

**Files:**

- Create: `android/src/main/java/com/receiptscanner/OcrGeometry.kt` (pure remap helper)
- Test: `android/src/test/java/com/receiptscanner/OcrGeometryTest.kt`
- Modify: `android/src/main/java/com/receiptscanner/OcrProcessor.kt` (capture `lines` in `Result`)
- Modify: `android/src/main/java/com/receiptscanner/ScanOptions.kt` (parse `ocrGeometry`)
- Modify: `android/src/main/java/com/receiptscanner/ReceiptScannerModule.kt` (wire option; remap when `applyAutoRotateIfNeeded` rotated)
- Modify: `android/src/main/java/com/receiptscanner/ResultBuilder.kt` (serialize `ocrLines`)

**Interfaces:**

- Produces: `OcrGeometry.remap(rect: RectF, width: Int, height: Int, degrees: Int): RectF` — implements the spec remap table; `degrees` is the rotation the pixels received (0/90/180/270).
- Produces: `OcrProcessor.Result.lines: List<OcrLineData>` where `OcrLineData(text: String, box: Rect, confidence: Float?)` — captured in Pass 0, skipping null boxes and blank text.

- [ ] **Step 1: Write the failing remap tests** — identity (0°), the three table rows (90°/180°/270°) with an asymmetric rect so axis swaps are caught, and clamp-to-bounds + zero-size-drop behavior.
- [ ] **Step 2: Implement `OcrGeometry` + capture** — capture only when `ScanOptions.ocrGeometry && ocr`; reuse the existing Pass 0 loop at `OcrProcessor.kt:213-215`, do not add an extra recognition pass.
- [ ] **Step 3: Wire + serialize** — in both camera and gallery handlers: remap with the same `rotationDegrees` given to `applyAutoRotateIfNeeded` only when it actually rotated (it returns `null` on failure/skip — identity then); clamp against final dims; `ResultBuilder.buildImage` writes `ocrLines` as `WritableArray` of maps.
- [ ] **Step 4: Verify** — Android JUnit run + `yarn lint && trunk check`.

### Task 3: iOS — capture, conversion, serialization

**Files:**

- Modify: `ios/RNScanOptions.{h,m}` (parse `ocrGeometry`)
- Modify: `ios/RNOcrProcessor.{h,m}` (`RNOcrResult.lines`; capture from the final-text pass, including the fast-fallback case; normalized→pixel conversion per spec using the pass `CIImage.extent`)
- Modify: `ios/RNDocumentCameraDelegate.m`, `ios/RNGalleryPickerDelegate.m` (frame-matching cases, serialization)

**Interfaces:**

- Produces: `RNOcrResult.lines: NSArray<NSDictionary *> *` — `@{ @"text", @"frame": @{x,y,width,height}, @"confidence" }`, already converted to top-left pixel coordinates of the pass frame.
- Produces: a C-level rect remap (mirror of `OcrGeometry.remap`, same degree convention) used for the `autoRotate == false && rotationDegrees != 0` inverse case.

- [ ] **Step 1: Capture + convert in `RNOcrProcessor`** — only when options request geometry; take observations from whichever pass produced `RNOcrResult.text` (accurate rerun or fast fallback); skip empty top candidates; convert with `x = minX × W_p`, `y = (1 − maxY) × H_p`.
- [ ] **Step 2: Frame-matching in the delegates** — per spec §iOS ③: identity when `autoRotate` baked the detected rotation (output pixels == pass pixels) or when `rotationDegrees == 0`; inverse remap when rotation was detected but not applied. Clamp against `processed.width/height`; drop zero-size.
- [ ] **Step 3: Serialize** — add `ocrLines` to both delegates' result dicts.
- [ ] **Step 4: Verify** — `yarn example ios` build; visual spot-check happens in Task 6.

### Task 4: Example app — overlay demo (doubles as the verification tool)

**Files:**

- Modify: example scan-options UI (add `ocrGeometry` toggle to the existing option matrix)
- Create/Modify: example result view — scan-line sweep animation, then reveal line boxes ordered by `frame.y`, scaled by `displayedWidth / image.width`

- [ ] **Step 1: Toggle + raw dump** — surface `ocrLines` in the existing OCR fixture dump so coordinates can be eyeballed against the image.
- [ ] **Step 2: Overlay rendering** — absolutely-positioned boxes over the result `<Image>`; sweep + staggered reveal.
- [ ] **Step 3: Verify** — `yarn example android` / `yarn example ios` run; overlay visually hugs the text on an unrotated receipt.

### Task 5: Docs

- [ ] Update `README.md` API table and `docs/specs/api-contract.md` with `ocrGeometry` / `OcrLine`.
- [ ] Add the coordinate-convention asymmetry (Vision normalized bottom-left vs ML Kit pixel top-left; per-platform remap sites) to `docs/notes/platform-asymmetries.md`, including the device-confirmed rotation direction from Task 6.
- [ ] Verify — `trunk fmt && trunk check`.

### Task 6: Device matrix QA (gates release; native changes stay QA-pending until done)

- [ ] Run the spec §검증 계획 ③ matrix: platform(2) × content rotation(0/90/180/270) × `autoRotate`(on/off), using `threshold-calibration.md` fixtures; overlay must visually match text positions in every cell.
- [ ] The 90°/270° cells confirm (or swap) the remap-table direction — update the spec's PROVISIONAL marker and `platform-asymmetries.md` with the result.
- [ ] Full gate: `yarn typecheck && yarn lint && yarn test && trunk fmt && trunk check` + Android JUnit run.
