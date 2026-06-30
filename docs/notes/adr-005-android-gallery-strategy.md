# ADR-005: Android Gallery Uses CropEditorActivity, Not GMS Gallery Import

## Status

Accepted

## Context

ADR-001 chose ML Kit Document Scanner (`GmsDocumentScannerOptions.setGalleryImportAllowed(true)`)
to cover both camera and gallery flows on Android.
In practice the implementation diverged from that decision: `source: "gallery"` now routes to a custom `CropEditorActivity` (system photo picker → MLKit text-block-based corner detection → `QuadCropView` → perspective correction), while only `source: "camera"` invokes GMS Document Scanner.

GMS Document Scanner's gallery import is still enabled inside the camera flow
(`setGalleryImportAllowed(true)`), so users who tap "Import from gallery" inside
the GMS UI take the GMS path.

The pivot was driven by three observations:

1. **EXIF preservation.** GMS Document Scanner re-encodes imported gallery images into fresh JPEGs and strips most EXIF tags in the process.
   The package needs the original EXIF (`make`, `model`, `dateTimeOriginal`, `Software`, optionally GPS) for `imageOrigin` classification and abuse-prevention signals — losing it would collapse the fraud surface to "everything looks like a camera capture."
2. **Origin classification.** GMS does not surface the original `content://` URI of the gallery import, so `MediaStore.Images.Media.BUCKET_DISPLAY_NAME`-based `imageOrigin` detection is impossible from inside the GMS callback.
   Without it `imageOrigin` collapses to a single value (effectively `"camera"`).
3. **Multi-image flow.** The product wants up to N images per scan (`maxPages`) with a per-image crop confirm step.
   GMS only exposes one bulk callback after all pages are scanned, which doesn't give us a clean point to insert per-image user-adjustable corner editing.

## Decision

`source: "gallery"` routes to a hand-rolled `CropEditorActivity` that:

- Picks images via `MediaStore.ACTION_PICK_IMAGES` (API 33+) or `Intent.ACTION_GET_CONTENT` (older)
- Loads bytes through the host app's `ContentResolver`, preserving the original `content://` URI
- Runs `KoreanTextRecognizerOptions` text-block detection to seed default crop corners
- Presents `QuadCropView` (4-handle perspective editor) for user adjustment
- Returns the original URIs + per-image corner sets back to `ReceiptScannerModule`
- `ImageProcessor.processGallery(originalUri, corners, …)` then reads EXIF from the original URI, applies perspective correction, and writes a fresh JPEG to cache

`source: "camera"` uses GMS Document Scanner with **`setGalleryImportAllowed(false)`**. The in-camera gallery affordance is removed, so every non-camera image goes through `source: "gallery"` → `CropEditorActivity`, which preserves the original `content://` URI and EXIF.

## Consequences

- **EXIF symmetry with iOS** (read-side).
  Android gallery imports now expose the same EXIF surface — `make`, `model`, `dateTimeOriginal`, `Software`, optional `gps` — that iOS gallery imports do.
- **`imageOrigin` classification works.** `MediaStore` bucket lookup returns `"screenshot"`, `"camera"`, `"download"`, or `"unknown"` based on the original source folder; the bucket name is the strongest signal Android exposes.
- **Single gallery code path** (2026-06-09).
  The in-camera GMS "Import" affordance is disabled via `setGalleryImportAllowed(false)`, so all non-camera input flows through `source: "gallery"` → `CropEditorActivity`.
  The previously-accepted "two gallery paths" UX leak — GMS import losing EXIF and collapsing `imageOrigin` to `"unknown"` — is now closed.
- **More implementation surface to own.** `CropEditorActivity`, `QuadCropView`, the perspective transform, the corner-detection heuristic, and the multi-image state machine are all package code, mirroring iOS's `RNCropEditorViewController`.
- **Output JPEG carries file-level EXIF** (2026-06-10).
  `Bitmap.compress(JPEG, …)` writes a bare JPEG, so `ImageProcessor.writeExifToFile` now runs after the final compression (post-`rotateFileInPlace`) and copies the parsed structured tags — make / model / software / dateTimes / GPS lat·lng·altitude — back onto the output file, with `orientation = NORMAL` to match the iOS output-EXIF invariant.
  Only runs when `includeExif` is true; the flat `raw` map and GPS speed/heading/timestamp are not written back (v1 scope).
  This closes the previously-accepted "empty EXIF block on Android" gap; server-side file readers now see EXIF on both platforms.

## Related

- ADR-001 — original ML Kit decision (still accurate for the camera path).
- ADR-002 — iOS gallery custom-editor decision; this ADR brings Android's gallery flow into structural parity.
- `docs/specs/api-contract.md` — `imageOrigin` Android logic and EXIF asymmetry note.
