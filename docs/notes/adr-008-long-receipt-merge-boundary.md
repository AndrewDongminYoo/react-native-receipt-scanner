# ADR-008: Long Receipts Merge OCR Text, Not Pixels

## Status

Accepted (2026-07-31)

## Context

A receipt too long for one frame has to be captured in several overlapping shots.
The obvious request — "join them back into one image" — runs into ADR-003, which restricts the package to image primitive operations and rejects receipt-domain logic.
Compositing N captures is arguably a primitive; deciding that two photos show the same receipt is closer to domain judgment.
The boundary as written does not resolve the case, so it needed a decision of its own.

Two constraints settled it before the boundary argument even mattered:

- Android caps the processed long edge at 3,072 pixels (`ImageProcessor.MAX_PROCESSING_DIM`), so a taller composite is downsampled and every character loses pixels.
- iOS Vision's `minimumTextHeight` is a fraction of the whole image height (package default 1/32), so a taller composite makes each character relatively smaller and drops more text from recognition.

Producing one tall bitmap therefore degrades the very output the feature exists to improve.
The sibling `flutter_receipt_scanner` package reached the same conclusion in its spec 0001 and lists a stitched JPEG, PDF, or bitmap as out of scope.

## Decision

The package merges **OCR text** across ordered pages and never composites pixels.

1. Page JPEGs are returned unchanged. There is no stitched image, PDF, or bitmap.
2. The merge removes only _proven_ duplicated text at adjacent seams. An unproven seam reports its boundary index and keeps both pages' text in full.
3. The package never classifies whether two images are the same receipt, never reorders pages, and never compares non-adjacent pages.
4. The implementation lives entirely in the JS layer, like `ocrFloor`. No native Kotlin, Objective-C, or TurboModule spec change.

`docs/specs/long-receipt-ocr-merge.md` is the normative behaviour reference.

## Consequences

- **ADR-003 holds without amendment.** The merge consumes the raw OCR string that ADR-003 already lists as in-scope and emits another raw string plus integer indexes. Placing it in JS keeps native code free of derived-signal logic, which is the same structural argument that put `ocrFloor` there.
- **Both acquisition paths are supported**, which diverges from the Flutter package's camera-only restriction. That restriction rests on gallery selection order being unreliable; in this codebase both gallery paths were verified strictly serial and order-preserving (`RNGalleryPickerDelegate.processNextQueuedItem`, `CropEditorActivity.loadNextImage`). Gallery support is what covers capturing a long electronic receipt as several screenshots. Where picker order is not the user's intended order, seams fail to match and are reported — no text is lost.
- **`isComplete` is narrower than its name.** Both iOS paths can silently drop a page that fails to process — the camera delegate `continue`s past a failed page and rejects only if every page failed, and the gallery delegate appends only non-nil results — and the JS layer cannot see it, so the field means "everything that came back joined up". Android is not affected: it rejects the whole scan on a per-image failure rather than returning a partial batch. Closing the iOS gap needs a native page-count signal and is deferred.
- **No length or aspect-ratio support claim.** The Flutter package's 11.0 ratio claim is backed by image fixtures and physical-device runs; this package has run neither and claims neither.
- **A guided overlap-capture UI stays rejected.** `VNDocumentCameraViewController` and the GMS document scanner expose no previous-frame overlay or per-frame callback, so guidance would require a custom camera on both platforms — reversing ADR-001 and ADR-002 for a UX affordance.
