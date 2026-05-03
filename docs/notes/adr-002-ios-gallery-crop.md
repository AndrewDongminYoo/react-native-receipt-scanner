# ADR-002: iOS Gallery Crop Strategy

## Status

Accepted

## Context

Android uses ML Kit Document Scanner which has built-in gallery import and crop.
iOS VisionKit's `VNDocumentCameraViewController` is camera-only — it provides no
API to load a photo from the gallery and run document detection on it.

Three options were evaluated:

### Option 1A: Custom Vision/CoreImage crop editor (chosen)

- `PHPickerViewController` → `VNDetectRectanglesRequest` → custom corner-adjustment UI
- `CIFilter(name: "CIPerspectiveCorrection")` for the warp
- Full control, no vendor dependency

### Option 1B: Commercial SDK (Scanbot / Docutain)

- Both provide gallery import + OCR + crop in a single React Native package
- Solves the problem completely, but introduces licensing cost and vendor lock-in
- Customization is limited to what the SDK exposes

### Option 1C: Fork `@dariyd/react-native-document-scanner`

- Faster start, but the fork becomes a de-facto in-house library as requirements grow
- Ends up at the same place as Option 1A but with more legacy debt

## Decision

**Option 1A** — build a custom gallery crop editor using Apple's frameworks only.

Rationale:

- No licensing cost
- Full control over UX and corner-adjustment interaction
- `VNDetectRectanglesRequest` is accurate enough for flat receipts at close range
- `PHPickerViewController` requires no permission prompt on iOS 14+

**Option 1B (commercial SDK) is deferred**, not rejected. If the custom crop editor
proves too costly to maintain or the quality is insufficient, Scanbot/Docutain should
be re-evaluated before investing further in the custom path.

## Consequences

- **More iOS implementation work** — the custom crop editor is the most time-consuming
  part of Phase 3.
- **No vendor lock-in** — the implementation can be iterated without licence negotiation.
- **iOS 16+ for Korean OCR** — `VNRecognizeTextRequest` supports `ko-KR` from iOS 16.
  Fallback to `en-US` on older versions; Korean text will not be recognized on iOS 15.
- **Camera and gallery are different code paths** on iOS but share the same post-processing
  pipeline (recompress → EXIF → OCR → result).
