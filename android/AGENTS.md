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

| Task                                              | File:Lines                                                                           |
| ------------------------------------------------- | ------------------------------------------------------------------------------------ |
| `SCAN_IN_PROGRESS` guard / promise lifecycle      | `ReceiptScannerModule.kt:21-52` (`PendingScanLifecycle`), `527-568` (settle helpers) |
| ML Kit scanner config (gallery, page limit, mode) | `ReceiptScannerModule.kt:193-205`                                                    |
| OCR language resolution + model preparation       | `OcrLanguageResolver.kt`, `OcrModelManager.kt`, `ReceiptScannerModule.kt:130-171`    |
| Camera result + page processing                   | `ReceiptScannerModule.kt:287-394`                                                    |
| Gallery result + sampled decode + OOM catch       | `ReceiptScannerModule.kt:396-519`                                                    |
| Synthesize EXIF on camera scans                   | `ImageProcessor.kt:117-146`                                                          |
| `content://` vs `file://` URI decode              | `ImageProcessor.kt:148-156`                                                          |
| Sampled decode helper (bounded peak memory)       | `ImageProcessor.kt:158-202, 595` (`GALLERY_MAX_DIM`)                                 |
| Gallery perspective correction with corner scale  | `ImageProcessor.kt:391-440`                                                          |
| Add a result field                                | `ResultBuilder.kt:25-103`                                                            |
| Add an option                                     | `ScanOptions.kt` + every consumer in `ReceiptScannerModule.kt`                       |

## CONVENTIONS

- **Single-thread executor**: `Executors.newSingleThreadExecutor()` runs ALL post-activity work (image decode, OCR, EXIF). Don't touch `pendingPromise` from another thread once it's set.
- **Promise hand-off rule — a token, not a local capture.** `PendingScanLifecycle` owns the single-scan guard end to end: `tryBegin()` mints the token (or the scan is rejected `SCAN_IN_PROGRESS`), and `complete(token)` settles the Promise and releases the token **inside the same monitor**. A new `scan()` therefore cannot begin, let alone overwrite `pendingPromise`/`pendingOptions`, until the previous one has settled — which is why these fields are no longer captured into locals before `executor.execute { }` (the pre-`PendingScanLifecycle` convention). Every terminal path must go through `resolvePendingScan`/`rejectPendingScan`/`finishPendingScan` and carry its token; never settle `pendingPromise` directly.
- **Pass the token, re-read the Activity.** Any callback that resumes a scan after an async gap (OCR model install) must check `pendingScanLifecycle.isCurrent(token)` before touching pending state, and re-read `reactApplicationContext.getCurrentActivity()` rather than reusing the Activity captured in `scan()` — a first-time model download can outlive it. Reject `NO_ACTIVITY` when none is foregrounded.
- **`pending*` fields are `@Volatile`**: they are written on the caller's thread in `scan()` (outside the lifecycle monitor) and read from the UI thread and the executor. Keep the annotation on any field added to that set.
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
- ❌ Settling `pendingPromise` directly, or nulling it outside `finishPendingScan` — the token is released in the same monitor that settles the Promise, so a hand-rolled path leaks `SCAN_IN_PROGRESS` or double-settles.
- ❌ Using `BitmapFactory.decodeFile(uri.toString())` for `content://` URIs — only `file://` paths have a usable `path`. Use `contentResolver.openInputStream(...)` for content URIs (already implemented in `ImageProcessor.decodeBitmap`).
- ❌ Adding receipt-parsing/upload logic here — out of scope (ADR-003).
- ❌ Bumping `minSdkVersion` below 24 — ML Kit Document Scanner requires it.
- ❌ Replacing `androidx.exifinterface` with `android.media.ExifInterface` — the support-lib version handles `content://` streams reliably.
- ❌ **Calling `decodeBitmap()` (full-res) from a path that handles batch input**. Use `decodeBitmapSampled()` and propagate the `sample` factor to any pixel-space coordinates. Full-resolution decode + `applyExifRotation`'s second allocation peaks at 2-3× the raw bitmap; six images in a row will exhaust the executor's heap on mid-range devices.
- ❌ **`catch (e: Exception)` alone around bitmap work** — `OutOfMemoryError` is `Error`, not `Exception`, and slips through. Layer an explicit `catch (e: OutOfMemoryError)` _above_ the generic catch on any code path that decodes or transforms large bitmaps in a loop.
