# android/ — Native Android (Kotlin TurboModule)

## OVERVIEW

ML Kit Document Scanner integration. All native classes live in `src/main/java/com/receiptscanner/`. Java package `com.receiptscanner` matches `package.json#codegenConfig.android.javaPackageName`. minSdk 24, compile/target SDK 36, Kotlin 2.0.21, AGP 8.7.2.

## STRUCTURE

```plaintext
android/
├── build.gradle             SDK levels, ML Kit doc scanner + text-recognition-korean + ExifInterface
├── src/main/AndroidManifest.xml  empty manifest (no extra perms declared)
└── src/main/java/com/receiptscanner/
    ├── ReceiptScannerModule.kt   Module entry; pendingPromise lifecycle, executor, ActivityEventListener
    ├── ReceiptScannerPackage.kt  Registers module with isTurboModule = true
    ├── ScanOptions.kt            ReadableMap → typed data class (defaults baked in)
    ├── ImageProcessor.kt         Bitmap decode → JPEG recompress → EXIF read + synthesize
    ├── OcrProcessor.kt           ML Kit Korean text recognizer (close after use)
    └── ResultBuilder.kt          Arguments.createMap shapes for ReceiptImage
```

## WHERE TO LOOK

| Task                                              | File:Lines                                                     |
| ------------------------------------------------- | -------------------------------------------------------------- |
| `SCAN_IN_PROGRESS` guard / promise lifecycle      | `ReceiptScannerModule.kt:47-65`                                |
| ML Kit scanner config (gallery, page limit, mode) | `ReceiptScannerModule.kt:79-92`                                |
| Camera result + page processing                   | `ReceiptScannerModule.kt:126-194`                              |
| Gallery result + sampled decode + OOM catch       | `ReceiptScannerModule.kt:196-272`                              |
| Synthesize EXIF on camera scans                   | `ImageProcessor.kt:117-146`                                    |
| `content://` vs `file://` URI decode              | `ImageProcessor.kt:148-156`                                    |
| Sampled decode helper (bounded peak memory)       | `ImageProcessor.kt:158-202, 595` (`GALLERY_MAX_DIM`)           |
| Gallery perspective correction with corner scale  | `ImageProcessor.kt:391-440`                                    |
| Add a result field                                | `ResultBuilder.kt:25-103`                                      |
| Add an option                                     | `ScanOptions.kt` + every consumer in `ReceiptScannerModule.kt` |

## CONVENTIONS

- **Single-thread executor**: `Executors.newSingleThreadExecutor()` runs ALL post-activity work (image decode, OCR, EXIF). Don't touch `pendingPromise` from another thread once it's set.
- **Promise hand-off rule**: Capture `pendingPromise`/`pendingOptions` into local vals BEFORE `executor.execute { }`, then null the fields. Prevents races with a new `scan()` call.
- **OCR is opt-in per call**: Construct `OcrProcessor` only when `scanOptions.ocr == true`, ALWAYS call `close()` after the page loop. Failing to close leaks the ML Kit client.
- **EXIF synthesis is camera-only**: `ImageProcessor.process(synthesizeDeviceInfo = isCamera)` injects `Build.MANUFACTURER`/`Build.MODEL`/`SimpleDateFormat` only when source is the camera. Gallery images honestly report `null` rather than inheriting the device's identity (commit `872976e`).
- **Temp files**: `receipt_<millis>.jpg` in `cacheDir`. `deletePreviousSessionFiles()` runs at the start of every `scan()`, on the executor.
- **Gallery decode is downsampled** to `GALLERY_MAX_DIM = 3072` on the longer side via `ImageProcessor.decodeBitmapSampled`. `processGallery` then scales the incoming corners by `1 / sample` before perspective correction. Full-resolution decode + `applyExifRotation`'s transient second allocation has been observed to OOM the executor on batch scans (6+ images from modern phone cameras), and OOM is `Error`, not `Exception` — `handleGalleryResult` now also catches `OutOfMemoryError` and rejects the promise instead of letting the process die. **Do not raise `GALLERY_MAX_DIM` above 4096 without measuring peak memory on a low-RAM device under batch load.**
- **`SCAN_REQUEST_CODE = 0x9001`** — single magic number for activity routing. Don't reuse for other intents.
- **`invalidate()` shuts down the executor** AND removes the activity listener. Keep both in any cleanup edit.

## ANTI-PATTERNS

- ❌ `startActivityForResult(...)` — deprecated; ML Kit only supports `startIntentSenderForResult(...)`.
- ❌ `Tasks.await(...)` on the main thread — `OcrProcessor.recognize` MUST run on the executor (it blocks).
- ❌ Forgetting `OcrProcessor.close()` after the page loop — leaks the ML Kit client.
- ❌ Leaving `pendingPromise` non-null after a code path completes — null it before resolve/reject so the next `scan()` is not blocked by `SCAN_IN_PROGRESS`.
- ❌ Using `BitmapFactory.decodeFile(uri.toString())` for `content://` URIs — only `file://` paths have a usable `path`. Use `contentResolver.openInputStream(...)` for content URIs (already implemented in `ImageProcessor.decodeBitmap`).
- ❌ Adding receipt-parsing/upload logic here — out of scope (ADR-003).
- ❌ Bumping `minSdkVersion` below 24 — ML Kit Document Scanner requires it.
- ❌ Replacing `androidx.exifinterface` with `android.media.ExifInterface` — the support-lib version handles `content://` streams reliably.
- ❌ **Calling `decodeBitmap()` (full-res) from a path that handles batch input**. Use `decodeBitmapSampled()` and propagate the `sample` factor to any pixel-space coordinates. Full-resolution decode + `applyExifRotation`'s second allocation peaks at 2-3× the raw bitmap; six images in a row will exhaust the executor's heap on mid-range devices.
- ❌ **`catch (e: Exception)` alone around bitmap work** — `OutOfMemoryError` is `Error`, not `Exception`, and slips through. Layer an explicit `catch (e: OutOfMemoryError)` _above_ the generic catch on any code path that decodes or transforms large bitmaps in a loop.
