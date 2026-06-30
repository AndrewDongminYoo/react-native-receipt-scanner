# ML Kit Korean Recognizer — Rotation-Invariance Regression Check

**Status:** Procedure
**작성일:** 2026-06-09
**Applies to:** `com.google.mlkit:text-recognition-korean` (Android)

## Why this exists

`OcrProcessor.kt`'s single-pass rotation routing (v1.3) assumes the ML Kit Korean recognizer is _rotation-invariant_ — it returns the same recognized lines (and the same `lineCount` / `lineAspect` / `textLength`) regardless of how the input bitmap is rotated.
Rotation is therefore inferred from the _geometry_ of recognized line boxes (line-aspect vs image-aspect), not by re-running OCR at each orientation.

This invariance was field-validated only on `text-recognition-korean:16.0.0`.
The build currently pins `16.0.1` (one patch ahead).
If a future version stops ignoring rotation hints, the single-pass routing silently makes wrong rotation decisions, and there is no automated gate — so this manual check is required on every version bump.

## When to run

- Before merging any bump of `text-recognition-korean` (or `play-services-mlkit-document-scanner`) in `android/build.gradle`.
- When investigating an Android orientation regression.

## Procedure

1. Build the example app in DEBUG: `yarn example android`.
2. Take one representative Korean receipt and capture/import it four times, rotated 0° / 90° / 180° / 270°.
3. For each, read the `OcrProcessor` probe logs (logcat tag `ReceiptScanner.Ocr`): `lineCount`, `lineAspect`, `textLength`.
4. **Pass:** recognized text and `lineCount` are essentially identical across all four orientations (the recognizer ignored the rotation hint). The v1.3 single-pass assumption holds.
5. **Fail:** `lineCount` / text differ materially by orientation (the recognizer now honors rotation). The v1.3 assumption is broken.

## If it fails

- Do not ship the bump as-is — pin back to the last validated version, or
- Revisit `OcrProcessor.recognizeWithRotationDetection`: a rotation-honoring recognizer means the aspect-mismatch heuristic must be replaced with an actual multi-pass probe (compare recognized-line counts across rotations) or ML Kit v2 `Text.Element.getAngle()`.
- Update the validated-version comment in `android/build.gradle` and this note.

## References

- `android/build.gradle` — version pin + inline `ROTATION-INVARIANCE GUARD` comment
- `android/src/main/java/com/receiptscanner/OcrProcessor.kt` — `recognizeWithRotationDetection` (v1.3 single-pass)
- `docs/specs/portrait-rotation-detection.md` — v1.3 algorithm spec
