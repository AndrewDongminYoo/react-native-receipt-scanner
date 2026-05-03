# Phase 2 — Android ML Kit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Android `scan()` stub with a full implementation that uses ML Kit Document Scanner for camera/gallery capture, `ExifInterface` for EXIF extraction, Bitmap recompression for quality control, and ML Kit Text Recognition for on-device OCR.

**Architecture:** Logic is split into four single-responsibility classes — `ScanOptions` (ReadableMap → typed options), `ImageProcessor` (JPEG compress + EXIF), `OcrProcessor` (ML Kit OCR), `ResultBuilder` (WritableMap assembly). `ReceiptScannerModule` orchestrates them using `ActivityEventListener` for the ML Kit scanner result and a single-thread executor for all blocking I/O and ML Kit operations.

**Tech Stack:** Kotlin, ML Kit Document Scanner (`play-services-mlkit-document-scanner:16.0.0-beta1`), ML Kit Text Recognition Korean (`text-recognition-korean:16.0.0`), AndroidX ExifInterface (`exifinterface:1.3.7`), JUnit 4 + `JavaOnlyMap` for unit tests, `com.google.android.gms:play-services-tasks` (transitive) for `Tasks.await()`

---

## File Map

| Action | Path                                                               | Responsibility                                                      |
| ------ | ------------------------------------------------------------------ | ------------------------------------------------------------------- |
| Modify | `android/build.gradle`                                             | Add ML Kit + ExifInterface + test deps                              |
| Create | `android/src/main/java/com/receiptscanner/ScanOptions.kt`          | Parse `ReadableMap` → typed options; apply defaults                 |
| Create | `android/src/main/java/com/receiptscanner/ImageProcessor.kt`       | JPEG recompress, dimension read, EXIF extract/strip                 |
| Create | `android/src/main/java/com/receiptscanner/OcrProcessor.kt`         | ML Kit text recognition (blocking call on background thread)        |
| Create | `android/src/main/java/com/receiptscanner/ResultBuilder.kt`        | Build `WritableMap` / `WritableArray` result                        |
| Modify | `android/src/main/java/com/receiptscanner/ReceiptScannerModule.kt` | Full implementation: ActivityEventListener + executor orchestration |
| Create | `android/src/test/java/com/receiptscanner/ScanOptionsTest.kt`      | Unit tests for options parsing                                      |

---

### Task 1: Add Gradle dependencies

**Files:**

- Modify: `android/build.gradle`

- [ ] **Step 1: Replace the `dependencies` block in `android/build.gradle`**

```groovy
dependencies {
  implementation "com.facebook.react:react-android"

  // ML Kit Document Scanner (camera + gallery import + auto-crop)
  implementation "com.google.android.gms:play-services-mlkit-document-scanner:16.0.0-beta1"

  // ML Kit Text Recognition — Korean model covers Korean + Latin characters
  implementation "com.google.mlkit:text-recognition-korean:16.0.0"

  // EXIF metadata read/write
  implementation "androidx.exifinterface:exifinterface:1.3.7"

  // Unit tests
  testImplementation "junit:junit:4.13.2"
}
```

- [ ] **Step 2: Commit**

```bash
git add android/build.gradle
git commit -m "feat(android): add ML Kit Document Scanner, Text Recognition, ExifInterface deps"
```

---

### Task 2: ScanOptions data class (TDD)

**Files:**

- Create: `android/src/main/java/com/receiptscanner/ScanOptions.kt`
- Create: `android/src/test/java/com/receiptscanner/ScanOptionsTest.kt`

- [ ] **Step 1: Create the test directory and write failing tests**

```bash
mkdir -p android/src/test/java/com/receiptscanner
```

Create `android/src/test/java/com/receiptscanner/ScanOptionsTest.kt`:

```kotlin
package com.receiptscanner

import com.facebook.react.bridge.JavaOnlyMap
import org.junit.Assert.assertEquals
import org.junit.Test

class ScanOptionsTest {

  @Test
  fun `from applies all defaults when map is empty`() {
    val opts = ScanOptions.from(JavaOnlyMap())

    assertEquals("camera", opts.source)
    assertEquals(1, opts.maxPages)
    assertEquals(0.82, opts.quality, 0.001)
    assertEquals(true, opts.includeExif)
    assertEquals(false, opts.includeGpsExif)
    assertEquals(true, opts.ocr)
  }

  @Test
  fun `from reads source gallery`() {
    val map = JavaOnlyMap().apply { putString("source", "gallery") }
    assertEquals("gallery", ScanOptions.from(map).source)
  }

  @Test
  fun `from reads maxPages`() {
    val map = JavaOnlyMap().apply { putInt("maxPages", 5) }
    assertEquals(5, ScanOptions.from(map).maxPages)
  }

  @Test
  fun `from reads quality`() {
    val map = JavaOnlyMap().apply { putDouble("quality", 0.5) }
    assertEquals(0.5, ScanOptions.from(map).quality, 0.001)
  }

  @Test
  fun `from reads includeExif false`() {
    val map = JavaOnlyMap().apply { putBoolean("includeExif", false) }
    assertEquals(false, ScanOptions.from(map).includeExif)
  }

  @Test
  fun `from reads includeGpsExif true`() {
    val map = JavaOnlyMap().apply { putBoolean("includeGpsExif", true) }
    assertEquals(true, ScanOptions.from(map).includeGpsExif)
  }

  @Test
  fun `from reads ocr false`() {
    val map = JavaOnlyMap().apply { putBoolean("ocr", false) }
    assertEquals(false, ScanOptions.from(map).ocr)
  }

  @Test
  fun `from reads all fields together`() {
    val map = JavaOnlyMap().apply {
      putString("source", "gallery")
      putInt("maxPages", 3)
      putDouble("quality", 0.6)
      putBoolean("includeExif", false)
      putBoolean("includeGpsExif", true)
      putBoolean("ocr", false)
    }
    val opts = ScanOptions.from(map)

    assertEquals("gallery", opts.source)
    assertEquals(3, opts.maxPages)
    assertEquals(0.6, opts.quality, 0.001)
    assertEquals(false, opts.includeExif)
    assertEquals(true, opts.includeGpsExif)
    assertEquals(false, opts.ocr)
  }
}
```

- [ ] **Step 2: Create `android/src/main/java/com/receiptscanner/ScanOptions.kt`**

```kotlin
package com.receiptscanner

import com.facebook.react.bridge.ReadableMap

data class ScanOptions(
  val source: String,
  val maxPages: Int,
  val quality: Double,
  val includeExif: Boolean,
  val includeGpsExif: Boolean,
  val ocr: Boolean,
) {
  companion object {
    fun from(map: ReadableMap): ScanOptions = ScanOptions(
      source = if (map.hasKey("source")) map.getString("source") ?: "camera" else "camera",
      maxPages = if (map.hasKey("maxPages")) map.getInt("maxPages") else 1,
      quality = if (map.hasKey("quality")) map.getDouble("quality") else 0.82,
      includeExif = if (map.hasKey("includeExif")) map.getBoolean("includeExif") else true,
      includeGpsExif = if (map.hasKey("includeGpsExif")) map.getBoolean("includeGpsExif") else false,
      ocr = if (map.hasKey("ocr")) map.getBoolean("ocr") else true,
    )
  }
}
```

- [ ] **Step 3: Commit**

```bash
git add android/src/main/java/com/receiptscanner/ScanOptions.kt \
        android/src/test/java/com/receiptscanner/ScanOptionsTest.kt
git commit -m "feat(android): add ScanOptions with defaults and unit tests"
```

Note: Android unit tests are verified in Task 6's integration build. There is no `yarn test` equivalent for Android — tests run via `./gradlew :react-native-receipt-scanner:test` inside the `example/` project or via Android Studio.

---

### Task 3: ImageProcessor

**Files:**

- Create: `android/src/main/java/com/receiptscanner/ImageProcessor.kt`

No unit tests for this class — it depends on `android.graphics.Bitmap` and `android.graphics.BitmapFactory` which require a real Android runtime. Verified through integration test in Task 7.

- [ ] **Step 1: Create `android/src/main/java/com/receiptscanner/ImageProcessor.kt`**

```kotlin
package com.receiptscanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

class ImageProcessor(private val context: Context) {

  data class ExifData(
    val orientation: Int?,
    val dateTimeOriginal: String?,
    val make: String?,
    val model: String?,
    val gpsLatitude: Double?,
    val gpsLongitude: Double?,
  )

  data class ProcessedImage(
    val file: File,
    val width: Int,
    val height: Int,
    val exifData: ExifData?,
  )

  fun process(
    sourceUri: Uri,
    quality: Double,
    includeExif: Boolean,
    includeGpsExif: Boolean,
  ): ProcessedImage {
    val sourcePath = requireNotNull(sourceUri.path) { "URI has no path: $sourceUri" }

    // Read dimensions without loading the full bitmap into memory
    val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(sourcePath, boundsOpts)
    val width = boundsOpts.outWidth
    val height = boundsOpts.outHeight

    // Decode the bitmap and recompress at target quality
    val bitmap = requireNotNull(BitmapFactory.decodeFile(sourcePath)) {
      "Failed to decode image: $sourcePath"
    }
    val outFile = File(context.cacheDir, "receipt_${System.currentTimeMillis()}.jpg")
    FileOutputStream(outFile).use { out ->
      bitmap.compress(
        Bitmap.CompressFormat.JPEG,
        (quality * 100).toInt().coerceIn(1, 100),
        out,
      )
    }
    bitmap.recycle()

    val exifData = if (includeExif) readExif(sourcePath, includeGpsExif) else null

    return ProcessedImage(outFile, width, height, exifData)
  }

  private fun readExif(filePath: String, includeGps: Boolean): ExifData {
    val exif = ExifInterface(filePath)

    val rawOrientation = exif.getAttributeInt(
      ExifInterface.TAG_ORIENTATION,
      ExifInterface.ORIENTATION_UNDEFINED,
    )

    val gps = if (includeGps) {
      val latLon = FloatArray(2)
      if (exif.getLatLong(latLon)) Pair(latLon[0].toDouble(), latLon[1].toDouble()) else null
    } else null

    return ExifData(
      orientation = rawOrientation.takeIf { it != ExifInterface.ORIENTATION_UNDEFINED },
      dateTimeOriginal = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
      make = exif.getAttribute(ExifInterface.TAG_MAKE),
      model = exif.getAttribute(ExifInterface.TAG_MODEL),
      gpsLatitude = gps?.first,
      gpsLongitude = gps?.second,
    )
  }

  /** Delete JPEG files written to cacheDir by a previous scan() session. */
  fun deletePreviousSessionFiles() {
    context.cacheDir
      .listFiles { file -> file.name.startsWith("receipt_") && file.name.endsWith(".jpg") }
      ?.forEach { it.delete() }
  }
}
```

- [ ] **Step 2: Commit**

```bash
git add android/src/main/java/com/receiptscanner/ImageProcessor.kt
git commit -m "feat(android): add ImageProcessor — JPEG recompress, EXIF extract, cache cleanup"
```

---

### Task 4: OcrProcessor

**Files:**

- Create: `android/src/main/java/com/receiptscanner/OcrProcessor.kt`

No unit tests — requires ML Kit runtime and Play Services. Verified through integration test in Task 7.

- [ ] **Step 1: Create `android/src/main/java/com/receiptscanner/OcrProcessor.kt`**

```kotlin
package com.receiptscanner

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions

class OcrProcessor(private val context: Context) {

  val recognizer = TextRecognition.getClient(
    KoreanTextRecognizerOptions.Builder().build(),
  )

  /**
   * Perform text recognition on [imageUri].
   * MUST be called on a background thread — uses [Tasks.await] which blocks.
   */
  fun recognize(imageUri: Uri): String {
    val image = InputImage.fromFilePath(context, imageUri)
    val result = Tasks.await(recognizer.process(image))
    return result.text
  }

  fun close() {
    recognizer.close()
  }
}
```

- [ ] **Step 2: Commit**

```bash
git add android/src/main/java/com/receiptscanner/OcrProcessor.kt
git commit -m "feat(android): add OcrProcessor — ML Kit Korean text recognition"
```

---

### Task 5: ResultBuilder

**Files:**

- Create: `android/src/main/java/com/receiptscanner/ResultBuilder.kt`

- [ ] **Step 1: Create `android/src/main/java/com/receiptscanner/ResultBuilder.kt`**

```kotlin
package com.receiptscanner

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap
import java.io.File

object ResultBuilder {

  fun buildImage(
    file: File,
    width: Int,
    height: Int,
    ocrText: String?,
    exifData: ImageProcessor.ExifData?,
  ): WritableMap = Arguments.createMap().apply {
    putString("uri", "file://${file.absolutePath}")
    putInt("width", width)
    putInt("height", height)
    putString("fileName", file.name)
    putString("mimeType", "image/jpeg")
    putDouble("fileSize", file.length().toDouble())

    if (ocrText != null) putString("ocrText", ocrText)

    if (exifData != null) {
      putMap("exif", Arguments.createMap().apply {
        exifData.orientation?.let { putInt("orientation", it) }
        exifData.dateTimeOriginal?.let { putString("dateTimeOriginal", it) }
        exifData.make?.let { putString("make", it) }
        exifData.model?.let { putString("model", it) }

        if (exifData.gpsLatitude != null && exifData.gpsLongitude != null) {
          putMap("gps", Arguments.createMap().apply {
            putDouble("latitude", exifData.gpsLatitude)
            putDouble("longitude", exifData.gpsLongitude)
          })
        }
      })
    }
  }

  fun buildSuccess(images: List<WritableMap>) = Arguments.createMap().apply {
    putString("status", "success")
    putArray("images", Arguments.createArray().also { arr ->
      images.forEach { arr.pushMap(it) }
    })
  }

  fun buildCancelled() = Arguments.createMap().apply {
    putString("status", "cancelled")
    putArray("images", Arguments.createArray())
  }
}
```

- [ ] **Step 2: Commit**

```bash
git add android/src/main/java/com/receiptscanner/ResultBuilder.kt
git commit -m "feat(android): add ResultBuilder — assembles WritableMap result for JS"
```

---

### Task 6: ReceiptScannerModule — full implementation

**Files:**

- Modify: `android/src/main/java/com/receiptscanner/ReceiptScannerModule.kt`

- [ ] **Step 1: Replace `android/src/main/java/com/receiptscanner/ReceiptScannerModule.kt`**

```kotlin
package com.receiptscanner

import android.app.Activity
import android.content.Intent
import com.facebook.react.bridge.ActivityEventListener
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReadableMap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.documentscanner.GmsDocumentScanner
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import java.util.concurrent.Executors

class ReceiptScannerModule(
  reactContext: ReactApplicationContext,
) : NativeReceiptScannerSpec(reactContext), ActivityEventListener {

  private val executor = Executors.newSingleThreadExecutor()
  private var pendingPromise: Promise? = null
  private var pendingOptions: ScanOptions? = null

  init {
    reactContext.addActivityEventListener(this)
  }

  override fun scan(options: ReadableMap, promise: Promise) {
    if (pendingPromise != null) {
      promise.reject("SCAN_IN_PROGRESS", "A scan is already in progress")
      return
    }

    val activity = currentActivity ?: run {
      promise.reject("NO_ACTIVITY", "No foreground activity found")
      return
    }

    val scanOptions = ScanOptions.from(options)

    // Clean up files from the previous session on a background thread
    executor.execute {
      ImageProcessor(reactApplicationContext).deletePreviousSessionFiles()
    }

    pendingPromise = promise
    pendingOptions = scanOptions

    val scannerOptions = GmsDocumentScannerOptions.Builder()
      .setGalleryImportAllowed(scanOptions.source == "gallery")
      .setPageLimit(scanOptions.maxPages)
      .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
      .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
      .build()

    val scanner: GmsDocumentScanner = GmsDocumentScanning.getClient(scannerOptions)
    scanner.getStartScanIntent(activity)
      .addOnSuccessListener { intentSender ->
        activity.startIntentSenderForResult(intentSender, SCAN_REQUEST_CODE, null, 0, 0, 0)
      }
      .addOnFailureListener { e ->
        pendingPromise = null
        pendingOptions = null
        promise.reject("SCANNER_INIT_FAILED", e.message ?: "Failed to initialise ML Kit scanner", e)
      }
  }

  override fun onActivityResult(
    activity: Activity,
    requestCode: Int,
    resultCode: Int,
    data: Intent?,
  ) {
    if (requestCode != SCAN_REQUEST_CODE) return

    val promise = pendingPromise ?: return
    val scanOptions = pendingOptions ?: return
    pendingPromise = null
    pendingOptions = null

    if (resultCode == Activity.RESULT_CANCELED) {
      promise.resolve(ResultBuilder.buildCancelled())
      return
    }

    if (resultCode != Activity.RESULT_OK || data == null) {
      promise.reject("SCAN_FAILED", "Unexpected result code: $resultCode")
      return
    }

    val scanningResult = GmsDocumentScanningResult.fromActivityResultIntent(data)
    val pages = scanningResult?.pages ?: emptyList()

    executor.execute {
      try {
        val imageProcessor = ImageProcessor(reactApplicationContext)
        val ocrProcessor = if (scanOptions.ocr) OcrProcessor(reactApplicationContext) else null

        val imageResults = pages.map { page ->
          val processed = imageProcessor.process(
            page.imageUri,
            scanOptions.quality,
            scanOptions.includeExif,
            scanOptions.includeGpsExif,
          )

          val ocrText = if (ocrProcessor != null) {
            try {
              ocrProcessor.recognize(android.net.Uri.fromFile(processed.file))
            } catch (e: Exception) {
              null
            }
          } else null

          ResultBuilder.buildImage(
            file = processed.file,
            width = processed.width,
            height = processed.height,
            ocrText = ocrText,
            exifData = processed.exifData,
          )
        }

        ocrProcessor?.close()
        promise.resolve(ResultBuilder.buildSuccess(imageResults))
      } catch (e: Exception) {
        promise.reject("PROCESSING_FAILED", e.message ?: "Image processing failed", e)
      }
    }
  }

  override fun onNewIntent(intent: Intent?) = Unit

  override fun invalidate() {
    super.invalidate()
    executor.shutdown()
    reactApplicationContext.removeActivityEventListener(this)
  }

  companion object {
    const val NAME = NativeReceiptScannerSpec.NAME
    private const val SCAN_REQUEST_CODE = 0x9001
  }
}
```

- [ ] **Step 2: Commit**

```bash
git add android/src/main/java/com/receiptscanner/ReceiptScannerModule.kt
git commit -m "feat(android): implement scan() with ML Kit Document Scanner, OCR, EXIF, JPEG recompress"
```

---

### Task 7: Integration verification

No automated test covers the full Android flow — it requires a running emulator or device with Play Services.

- [ ] **Step 1: Start Metro bundler**

```bash
yarn example start
```

- [ ] **Step 2: Launch example app on Android**

```bash
yarn example android
```

Expected: app builds and launches on emulator/device. First launch may download the ML Kit document scanner model (~10 MB).

- [ ] **Step 3: Test camera scan**

1. Tap "Scan Receipt"
2. ML Kit camera UI opens
3. Scan a receipt or any document
4. Confirm crop
5. Expected result displayed: `Status: success`, image dimensions, OCR text

- [ ] **Step 4: Test gallery import**

Update `example/src/App.tsx` temporarily:

```tsx
const scanResult = await scan({ source: "gallery", ocr: true });
```

Repeat scan flow. Expected: gallery picker opens, select a photo, document crop UI appears.

Revert the change after testing:

```tsx
const scanResult = await scan({ source: "camera", ocr: true });
```

- [ ] **Step 5: Verify EXIF and GPS defaults**

Check that `exif` is present in result and `exif.gps` is absent (since `includeGpsExif` defaults to `false`).

- [ ] **Step 6: Verify file URI (not base64)**

Check that `uri` in the result starts with `file://`.

- [ ] **Step 7: Commit integration notes if anything needed fixing**

```bash
git add -p
git commit -m "fix(android): <describe any fix found during integration>"
```

Only commit if changes were needed. Skip this step if everything passed.
