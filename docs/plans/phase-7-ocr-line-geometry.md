# Phase 7 — OCR Line Geometry Exposure

## Goal

Expose opt-in per-line OCR bounding boxes through `ReceiptImage.ocrLines` so consumers can render post-capture overlays in the final JPEG pixel space.
Keep rendering, animation, and receipt interpretation outside the package boundary defined by ADR-003.

The normative API and coordinate contracts are in [`../specs/ocr-line-geometry.md`](../specs/ocr-line-geometry.md).
This document identifies implementation scope, sequencing, and release gates.

## Success Criteria

1. `scan({ ocr: true, ocrGeometry: true })` returns valid `ocrLines` on Android and iOS without changing results for existing callers.
2. Every returned frame uses the final JPEG's top-left pixel space, stays within `ReceiptImage.width × ReceiptImage.height`, and has positive width and height.
3. Empty text, missing boxes, and zero-area clamped frames are omitted; no positional correspondence with `ocrText` is promised.
4. Geometry follows the OCR pass that produced the returned text, including the iOS fast-pass fallback.
5. Automated JS and Android geometry tests pass, both example apps build, and all 16 platform/rotation/`autoRotate` combinations visually align.

## Implementation Scope

### JavaScript contract

Modify `src/types.ts` to add `ScanReceiptOptions.ocrGeometry`, `OcrLine`, `ReceiptImage.ocrLines`, and the `ocrGeometry: false` default.
Re-export `OcrLine` as a type from `src/index.tsx`.

Extend `src/__tests__/index.test.tsx` to verify default and explicit option forwarding.
Type-only export availability is verified by `yarn typecheck`, not a Jest runtime assertion.

### Android

Modify `ScanOptions.kt` and `ScanOptionsTest.kt` to parse and cover `ocrGeometry`, defaulting to `false` when the bridge input omits it.
Pass the option into `OcrProcessor.recognizeWithRotationDetection` so line objects are collected only when both OCR and geometry are enabled.
Extend `OcrResult` with `lines: List<OcrLineData>` and populate it from the same Pass 0 recognition result used for text and rotation metrics, skipping blank text and null `line.boundingBox` values without starting another OCR pass.

Add `OcrGeometry.kt` and `OcrGeometryTest.kt` for pure 0°/90°/180°/270° remapping, bounds intersection, and zero-area rejection.
Use an asymmetric test rectangle so width/height and 90°/270° mistakes are observable.

In both camera and gallery paths in `ReceiptScannerModule.kt`, apply the forward remap only when `applyAutoRotateIfNeeded` returns rotated output dimensions.
If rotation is disabled or fails, preserve the input frame.
Clamp against the final dimensions and pass the surviving lines to `ResultBuilder.buildImage`, which serializes `ocrLines` only when requested.

### iOS

Modify `RNScanOptions.h` and `RNScanOptions.m` to parse `ocrGeometry`, defaulting to `NO`.
Pass the option into `RNOcrProcessor` so it retains observations only when geometry is requested.

Extend `RNOcrResult` with line data derived from the observations that produced its final `text`.
This must cover the initial accurate pass, the selected rotated accurate pass, and the selected fast pass when the accurate rerun fails.
Convert Vision's normalized bottom-left boxes using the corresponding OCR pass's `CIImage.extent` pixel dimensions, not `UIImage.size` points.

Provide one shared iOS rectangle-remapping helper for both delegates instead of duplicating formulas.
In `RNDocumentCameraDelegate.m` and `RNGalleryPickerDelegate.m`, keep pass-space frames unchanged when detected rotation is baked into the JPEG, apply the inverse rotation when `autoRotate` is disabled, then clamp against the processed output dimensions before serialization.

### Example and documentation

Modify `example/src/App.tsx` to add the `ocrGeometry` toggle, expose raw line data, and render line boxes over the existing `ImageDetailCard` preview.
The overlay must account for the actual contained-image rectangle, including horizontal or vertical letterboxing; scaling by preview width alone is insufficient when the container and image aspect ratios differ.
Run the scan-line and staggered reveal only as a demonstration and QA aid.

Update `README.md`, `docs/specs/api-contract.md`, and `docs/notes/platform-asymmetries.md` in the same change as the behavior.
After device verification, replace the provisional rotation-direction note in the normative spec and record the confirmed platform behavior in `platform-asymmetries.md`.

## Sequence and Verification

1. Implement the JavaScript contract and option parsers. Verify with `yarn typecheck && yarn test` and the existing native option-parser tests.
2. Implement and unit-test Android capture, remapping, and serialization. Verify with:

   ```bash
   cd example/android
   ./gradlew :react-native-receipt-scanner:testDebugUnitTest --tests '*OcrGeometryTest*' --tests '*ScanOptionsTest*'
   ```

3. Implement iOS capture, shared remapping, and serialization. Verify the native integration build with `yarn example ios`.
4. Add the example overlay and verify unrotated output with `yarn example android` and `yarn example ios` before running the full matrix.
5. Run the device matrix using the fixtures referenced by `threshold-calibration.md`: Android and iOS × 0°/90°/180°/270° content × `autoRotate` on/off. Confirm that every box visually matches its text and use the 90°/270° cases to settle the provisional degree convention.
6. Run the repository gate:

   ```bash
   yarn typecheck && yarn lint && yarn test && trunk fmt && trunk check
   ```

Native changes remain QA-pending until both example builds and all 16 device-matrix cells pass.

## Risks

- Android `line.boundingBox` is nullable, so `ocrLines` cannot mirror `ocrText` by index.
- Vision geometry belongs to the specific OCR pass frame; retaining only text while discarding the producing observations would make rotation remapping ambiguous.
- The current 90°/270° formulas depend on the native pixel-rotation direction and remain provisional until device fixtures confirm them.
- `resizeMode="contain"` introduces letterboxing when aspect ratios differ; ignoring the rendered image offset makes correct native coordinates appear wrong in the example overlay.
- Long receipts can contain many lines, so geometry remains opt-in and must not trigger another OCR pass.
