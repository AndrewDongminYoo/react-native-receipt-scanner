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
| `SCAN_IN_PROGRESS` guard / promise lifecycle      | `ReceiptScannerModule.kt:33-50`                                |
| ML Kit scanner config (gallery, page limit, mode) | `ReceiptScannerModule.kt:53-61`                                |
| Activity result + page processing                 | `ReceiptScannerModule.kt:75-145`                               |
| Synthesize EXIF on camera scans                   | `ImageProcessor.kt:98-110`                                     |
| `content://` vs `file://` URI decode              | `ImageProcessor.kt:59-67`                                      |
| Add a result field                                | `ResultBuilder.kt:8-48`                                        |
| Add an option                                     | `ScanOptions.kt` + every consumer in `ReceiptScannerModule.kt` |

## CONVENTIONS

- **Single-thread executor**: `Executors.newSingleThreadExecutor()` runs ALL post-activity work (image decode, OCR, EXIF). Don't touch `pendingPromise` from another thread once it's set.
- **Promise hand-off rule**: Capture `pendingPromise`/`pendingOptions` into local vals BEFORE `executor.execute { }`, then null the fields. Prevents races with a new `scan()` call.
- **OCR is opt-in per call**: Construct `OcrProcessor` only when `scanOptions.ocr == true`, ALWAYS call `close()` after the page loop. Failing to close leaks the ML Kit client.
- **EXIF synthesis is camera-only**: `ImageProcessor.process(synthesizeDeviceInfo = isCamera)` injects `Build.MANUFACTURER`/`Build.MODEL`/`SimpleDateFormat` only when source is the camera. Gallery images honestly report `null` rather than inheriting the device's identity (commit `872976e`).
- **Temp files**: `receipt_<millis>.jpg` in `cacheDir`. `deletePreviousSessionFiles()` runs at the start of every `scan()`, on the executor.
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
