# ADR-007: v0.4.2 and v0.4.3 Code-Diff Notes

## Status

Accepted

## Context

`v0.4.2` and `v0.4.3` were both Android gallery-flow patch releases.
Neither release changed the public JavaScript API. The user-visible symptom was the same:
after selecting images from the Android system Photo Picker, JS could receive
`{ status: "cancelled", images: [] }` even though the user had selected images.

This ADR records the code-level differences so team members can understand what each
release fixed and why `v0.4.3` was needed after `v0.4.2`.

## Decision

Treat the two releases as separate fixes in the same incident chain:

- `v0.4.2` hardened file access and logging. It fixed cases where a selected
  `content://` URI could not be opened through `openInputStream`, and it made the
  crop activity log the real exception instead of silently returning cancelled.
- `v0.4.3` replaced the nested gallery picker launch with AndroidX Activity Result API.
  It fixed cases where the picker result path itself could report cancellation back to
  the parent module while `CropEditorActivity` had already received selected URIs.

## v0.4.2

### Summary

`v0.4.2` compares `v0.4.1...v0.4.2`.

The functional Android change is in `ImageProcessor.decodeBitmapSampled`: content URIs now
fall back from `ContentResolver.openInputStream` to `openFileDescriptor` before failing.
This matters for Photo Picker URIs such as `content://media/picker/...`, where some vendor
providers can return `null` from `openInputStream` even when the file descriptor path works.

The crop editor also stopped swallowing exceptions. The behavior is still conservative
(`RESULT_CANCELED` on unrecoverable failure), but the log now contains the failing URI and
stack trace.

### Diff

```diff
diff --git a/android/src/main/java/com/receiptscanner/ImageProcessor.kt b/android/src/main/java/com/receiptscanner/ImageProcessor.kt
index 18d41d6..cbc1511 100644
--- a/android/src/main/java/com/receiptscanner/ImageProcessor.kt
+++ b/android/src/main/java/com/receiptscanner/ImageProcessor.kt
@@ -3,21 +3,25 @@ package com.receiptscanner
 import android.content.Context
 import android.graphics.Bitmap
 import android.graphics.BitmapFactory
 import android.graphics.Canvas
 import android.graphics.Matrix
 import android.graphics.Paint
 import android.net.Uri
 import android.os.Build
+import android.os.ParcelFileDescriptor
 import android.provider.MediaStore
+import android.util.Log
 import androidx.core.graphics.createBitmap
 import androidx.exifinterface.media.ExifInterface
 import java.io.File
 import java.io.FileOutputStream
+import java.io.IOException
+import java.io.InputStream
 import java.text.SimpleDateFormat
 import java.util.Date
 import java.util.Locale
 import kotlin.math.sqrt
@@ -529,16 +533,18 @@ class ImageProcessor(

   companion object {
+    private const val LOG_TAG = "ReceiptScanner.Image"
+
     private const val GALLERY_MAX_DIM = 3072
@@ -594,19 +600,19 @@ class ImageProcessor(
     internal fun decodeBitmapSampled(
       context: Context,
       uri: Uri,
       maxDim: Int,
     ): Pair<Bitmap, Int> {
       val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
       if (uri.scheme == "content") {
-        context.contentResolver.openInputStream(uri)?.use {
+        openContentStream(context, uri).use {
           BitmapFactory.decodeStream(it, null, boundsOpts)
-        } ?: throw IllegalArgumentException("Failed to open content stream: $uri")
+        }
       } else {
         val path = requireNotNull(uri.path) { "URI has no path: $uri" }
         BitmapFactory.decodeFile(path, boundsOpts)
       }
@@ -614,25 +620,50 @@ class ImageProcessor(

       val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
       val bitmap =
         if (uri.scheme == "content") {
-          context.contentResolver.openInputStream(uri)?.use {
+          openContentStream(context, uri).use {
             BitmapFactory.decodeStream(it, null, decodeOpts)
           }
         } else {
           BitmapFactory.decodeFile(requireNotNull(uri.path), decodeOpts)
         } ?: throw IllegalArgumentException("Failed to decode image: $uri")
       return Pair(bitmap, sample)
     }

+    /**
+     * Open a `content://` URI as an [InputStream], falling back to
+     * [ContentResolver.openFileDescriptor] when `openInputStream` returns null.
+     *
+     * The Photo Picker provider (`com.android.providers.media.photopicker`) is
+     * known to graceful-null on `openInputStream` for some URIs even when the
+     * underlying file is readable through the FD-based path.
+     */
+    private fun openContentStream(
+      context: Context,
+      uri: Uri,
+    ): InputStream {
+      val cr = context.contentResolver
+      cr.openInputStream(uri)?.let { return it }
+      Log.w(
+        LOG_TAG,
+        "openInputStream returned null; falling back to openFileDescriptor uri=$uri",
+      )
+      val pfd =
+        cr.openFileDescriptor(uri, "r")
+          ?: throw IOException("Cannot open content URI: $uri (mimeType=${cr.getType(uri)})")
+      return ParcelFileDescriptor.AutoCloseInputStream(pfd)
+    }
```

```diff
diff --git a/android/src/main/java/com/receiptscanner/CropEditorActivity.kt b/android/src/main/java/com/receiptscanner/CropEditorActivity.kt
index 779cf14..390da91 100644
--- a/android/src/main/java/com/receiptscanner/CropEditorActivity.kt
+++ b/android/src/main/java/com/receiptscanner/CropEditorActivity.kt
@@ -351,17 +351,18 @@ internal class CropEditorActivity : Activity() {
             applyDefaultInsetCorners(rect)
             detectCornersFromText(oriented)
           }
         }
-      } catch (_: Exception) {
+      } catch (e: Exception) {
+        Log.e(LOG_TAG, "loadAndDisplayImage failed for uri=$uri", e)
         runOnUiThread {
           setResult(RESULT_CANCELED)
           finish()
         }
       }
@@ -452,17 +453,18 @@ internal class CropEditorActivity : Activity() {
       exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
         ?: ExifInterface.ORIENTATION_NORMAL
-    } catch (_: Exception) {
+    } catch (e: Exception) {
+      Log.w(LOG_TAG, "readExifOrientation failed for uri=$uri; falling back to ORIENTATION_NORMAL", e)
       ExifInterface.ORIENTATION_NORMAL
     }
@@ -498,17 +500,18 @@ internal class CropEditorActivity : Activity() {
           }
           return@Thread
         }
-      } catch (_: Exception) {
+      } catch (e: Exception) {
+        Log.e(LOG_TAG, "onConfirmTapped: failed to copy picker URI to cache uri=$uri", e)
         runOnUiThread {
           setResult(RESULT_CANCELED)
           finish()
         }
         return@Thread
       }
```

```diff
diff --git a/package.json b/package.json
index b3ba5a6..f3a5156 100644
--- a/package.json
+++ b/package.json
@@ -1,11 +1,11 @@
 {
   "name": "react-native-receipt-scanner",
-  "version": "0.4.1",
+  "version": "0.4.2",
@@ -34,17 +34,16 @@
     "prepare": "bob build",
     "typecheck": "tsc --noEmit",
     "test": "jest",
-    "release": "release-it --only-version",
     "web": "vite",
@@ -66,28 +65,26 @@
-    "@release-it/conventional-changelog": "^11.0.0",
@@
-    "release-it": "^20.0.1",
```

### Effect

`v0.4.2` reduced false cancellation caused by unreadable picker streams and made the
remaining failures diagnosable from `adb logcat -s ReceiptScanner.Gallery ReceiptScanner.Image`.
It did not change how the picker activity itself was launched.

## v0.4.3

### Summary

`v0.4.3` compares `v0.4.2...v0.4.3`.

The main change is that `CropEditorActivity` now extends `ComponentActivity` and uses
`registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia)`.
Previously, `ReceiptScannerModule` launched `CropEditorActivity`, and that activity then
launched the system picker with `startActivityForResult`. On Android 14+ devices, this
nested result setup could send `RESULT_CANCELED` to the module even after the crop flow
had started.

The picker is now registered during `onCreate` and launched once from `onPostResume`.
Selected URIs are handled inside `CropEditorActivity` until the final crop result is returned.

### Diff

```diff
diff --git a/android/build.gradle b/android/build.gradle
index 3dffb7e..8671338 100644
--- a/android/build.gradle
+++ b/android/build.gradle
@@ -69,11 +69,14 @@ dependencies {
   implementation "com.google.mlkit:text-recognition-korean:16.0.1"

   // EXIF metadata read/write
   implementation "androidx.exifinterface:exifinterface:1.4.2"

+  // Activity Result API for the Android gallery picker flow
+  implementation "androidx.activity:activity-ktx:1.10.1"
+
   // Unit tests
   testImplementation "junit:junit:4.13.2"
 }
```

```diff
diff --git a/android/src/main/java/com/receiptscanner/CropEditorActivity.kt b/android/src/main/java/com/receiptscanner/CropEditorActivity.kt
index 390da91..413afa8 100644
--- a/android/src/main/java/com/receiptscanner/CropEditorActivity.kt
+++ b/android/src/main/java/com/receiptscanner/CropEditorActivity.kt
@@ -1,52 +1,56 @@
 package com.receiptscanner

-import android.app.Activity
 import android.content.Intent
@@
-import android.provider.MediaStore
+import android.os.ParcelFileDescriptor
 import android.util.Log
@@
+import androidx.activity.ComponentActivity
+import androidx.activity.result.ActivityResultLauncher
+import androidx.activity.result.PickVisualMediaRequest
+import androidx.activity.result.contract.ActivityResultContracts
@@
-internal class CropEditorActivity : Activity() {
+internal class CropEditorActivity : ComponentActivity() {
@@
-    /** `requestCode` for the system photo picker round-trip. */
-    private const val PICK_REQUEST_CODE = 0x9003
-
     private const val PREVIEW_MAX_DIM = 2048
   }

   private lateinit var imageView: ImageView
   private lateinit var cropView: QuadCropView
   private lateinit var confirmBtn: Button
+  private lateinit var pickImagesLauncher: ActivityResultLauncher<PickVisualMediaRequest>
@@
   private var hasBuiltUI = false
   private var totalImageCount = 0
+  private var requestedMaxImages = 1
+  private var galleryPickerLaunched = false
@@
   override fun onCreate(savedInstanceState: Bundle?) {
     super.onCreate(savedInstanceState)
     window.navigationBarColor = Color.BLACK
     window.statusBarColor = Color.BLACK
-    val maxImages = intent.getIntExtra(EXTRA_MAX_IMAGES, 1)
-    val pickIntent = buildPickIntent(maxImages)
+    requestedMaxImages = intent.getIntExtra(EXTRA_MAX_IMAGES, 1).coerceAtLeast(1)
+    pickImagesLauncher =
+      registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(requestedMaxImages)) { uris ->
+        handlePickedUris(uris)
+      }
     Log.i(
       LOG_TAG,
-      "onCreate maxImages=$maxImages sdk=${Build.VERSION.SDK_INT} action=${pickIntent.action}",
+      "onCreate maxImages=$requestedMaxImages sdk=${Build.VERSION.SDK_INT} contract=PickMultipleVisualMedia",
     )
-    startActivityForResult(pickIntent, PICK_REQUEST_CODE)
   }

-  @Suppress("DEPRECATION")
-  private fun buildPickIntent(maxImages: Int): Intent =
-    if (maxImages > 1 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
-      Intent(MediaStore.ACTION_PICK_IMAGES).apply {
-        type = "image/*"
-        putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, maxImages)
-      }
-    } else {
-      Intent(Intent.ACTION_GET_CONTENT).apply {
-        type = "image/*"
-        if (maxImages > 1) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
-      }
+  override fun onPostResume() {
+    super.onPostResume()
+    if (!galleryPickerLaunched) {
+      galleryPickerLaunched = true
+      launchGalleryPicker()
     }
+  }

-  @Deprecated("Deprecated in API 33")
-  override fun onActivityResult(
-    requestCode: Int,
-    resultCode: Int,
-    data: Intent?,
-  ) {
-    @Suppress("DEPRECATION")
-    super.onActivityResult(requestCode, resultCode, data)
-    if (requestCode != PICK_REQUEST_CODE) return
-
-    val clipCount = data?.clipData?.itemCount
+  private fun launchGalleryPicker() {
+    Log.i(LOG_TAG, "launchGalleryPicker maxImages=$requestedMaxImages")
+    pickImagesLauncher.launch(
+      PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
+    )
+  }
+
+  private fun handlePickedUris(uris: List<Uri>) {
     Log.i(
       LOG_TAG,
-      "picker result resultCode=$resultCode dataNull=${data == null} " +
-        "clipDataCount=$clipCount dataUri=${data?.data}",
+      "picker result uris=${uris.size}",
     )

-    if (resultCode != RESULT_OK || data == null) {
-      Log.i(LOG_TAG, "picker cancelled or returned no data; finishing with RESULT_CANCELED")
-      setResult(RESULT_CANCELED)
-      finish()
-      return
-    }
-
-    val uris =
-      data.clipData?.let { clip ->
-        (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
-      } ?: listOfNotNull(data.data)
-
-    Log.i(LOG_TAG, "picker parsed uris=${uris.size}")
-
     if (uris.isEmpty()) {
-      Log.w(LOG_TAG, "picker returned RESULT_OK but no URIs could be parsed; cancelling")
+      Log.i(LOG_TAG, "picker cancelled or returned no URIs; finishing with RESULT_CANCELED")
       setResult(RESULT_CANCELED)
       finish()
       return
     }

+    Log.i(LOG_TAG, "picker parsed uris=${uris.size}")
     totalImageCount = uris.size
     pendingUris.addAll(uris)
     loadNextImage()
   }

   @Deprecated("Deprecated in API 33")
   override fun onBackPressed() {
-    @Suppress("DEPRECATION")
-    super.onBackPressed()
     setResult(RESULT_CANCELED)
+    finish()
   }
```

```diff
diff --git a/android/src/main/java/com/receiptscanner/CropEditorActivity.kt b/android/src/main/java/com/receiptscanner/CropEditorActivity.kt
index 390da91..413afa8 100644
--- a/android/src/main/java/com/receiptscanner/CropEditorActivity.kt
+++ b/android/src/main/java/com/receiptscanner/CropEditorActivity.kt
@@ -491,39 +470,50 @@ internal class CropEditorActivity : Activity() {
     Thread {
       val index = processedOriginalUris.size
       val cachedFile = File(cacheDir, "receipt_pick_${System.currentTimeMillis()}_$index.jpg")
       try {
-        contentResolver.openInputStream(uri)?.use { input ->
+        openPickedUriInputStream(uri).use { input ->
           FileOutputStream(cachedFile).use { output -> input.copyTo(output) }
-        } ?: run {
-          runOnUiThread {
-            setResult(RESULT_CANCELED)
-            finish()
-          }
-          return@Thread
         }
       } catch (e: Exception) {
         Log.e(LOG_TAG, "onConfirmTapped: failed to copy picker URI to cache uri=$uri", e)
@@
       runOnUiThread { loadNextImage() }
     }.start()
   }

+  private fun openPickedUriInputStream(uri: Uri): InputStream {
+    if (uri.scheme != "content") {
+      val path = requireNotNull(uri.path) { "URI has no path: $uri" }
+      return File(path).inputStream()
+    }
+
+    contentResolver.openInputStream(uri)?.let { return it }
+    Log.w(
+      LOG_TAG,
+      "openInputStream returned null; falling back to openFileDescriptor uri=$uri",
+    )
+    val pfd =
+      contentResolver.openFileDescriptor(uri, "r")
+        ?: throw IllegalArgumentException("Cannot open picker URI: $uri (mimeType=${contentResolver.getType(uri)})")
+    return ParcelFileDescriptor.AutoCloseInputStream(pfd)
+  }
+
   private fun returnAllResults() {
@@
   override fun onDestroy() {
     super.onDestroy()
+    Log.i(LOG_TAG, "onDestroy isFinishing=$isFinishing isChangingConfigurations=$isChangingConfigurations")
     if (::imageView.isInitialized) imageView.setImageDrawable(null)
     displayedBitmap?.recycle()
     displayedBitmap = null
   }
 }
```

```diff
diff --git a/src/__tests__/android-gallery-activity-result.test.tsx b/src/__tests__/android-gallery-activity-result.test.tsx
new file mode 100644
index 0000000..feafe70
--- /dev/null
+++ b/src/__tests__/android-gallery-activity-result.test.tsx
@@ -0,0 +1,43 @@
+/// <reference types="node" />
+
+import { describe, expect, it } from "@jest/globals";
+import fs from "node:fs";
+import path from "node:path";
+
+const androidSource = (...segments: string[]) =>
+  fs.readFileSync(
+    path.join(
+      __dirname,
+      "..",
+      "..",
+      "android",
+      "src",
+      "main",
+      "java",
+      "com",
+      "receiptscanner",
+      ...segments
+    ),
+    "utf8"
+  );
+
+describe("Android gallery picker activity result flow", () => {
+  it("uses Activity Result API instead of nested startActivityForResult for gallery picking", () => {
+    const cropEditorActivity = androidSource("CropEditorActivity.kt");
+
+    expect(cropEditorActivity).toContain("registerForActivityResult");
+    expect(cropEditorActivity).toContain("ActivityResultContracts.PickMultipleVisualMedia");
+    expect(cropEditorActivity).not.toContain("startActivityForResult(pickIntent");
+    expect(cropEditorActivity).not.toContain("override fun onActivityResult");
+  });
+
+  it("does not launch the gallery picker directly from onCreate", () => {
+    const cropEditorActivity = androidSource("CropEditorActivity.kt");
+    const onCreateBody =
+      cropEditorActivity.match(/override fun onCreate[\s\S]*?\n {2}}/)?.[0] ?? "";
+
+    expect(onCreateBody).not.toContain("launchGalleryPicker()");
+    expect(cropEditorActivity).toContain("override fun onPostResume()");
+    expect(cropEditorActivity).toContain("launchGalleryPicker()");
+  });
+});
```

```diff
diff --git a/example/ios/Podfile b/example/ios/Podfile
index e12d17c..e3948de 100644
--- a/example/ios/Podfile
+++ b/example/ios/Podfile
@@ -1,16 +1,16 @@
 require Pod::Executable.execute_command('node', ['-p',
   'require.resolve(
     "react-native/scripts/react_native_pods.rb",
     {paths: [process.argv[1]]},
   )', __dir__]).strip

-platform :ios, min_ios_version_supported
+platform :ios, '16.0'
 prepare_react_native_project!
```

```diff
diff --git a/package.json b/package.json
index f3a5156..e02e9c7 100644
--- a/package.json
+++ b/package.json
@@ -1,11 +1,11 @@
 {
   "name": "react-native-receipt-scanner",
-  "version": "0.4.2",
+  "version": "0.4.3",
```

### Effect

`v0.4.3` changes the activity-result ownership model. The system picker result no longer
travels through the deprecated nested `startActivityForResult` path. The final result sent
back to `ReceiptScannerModule` is now only the crop editor's own result: either the packed
URI/corner payload after all selected images are confirmed, or an explicit cancel from the
picker or crop UI.

## Consequences

- `v0.4.2` is still valuable because FD fallback is needed anywhere a picker URI must be
  decoded or copied.
- `v0.4.3` is the safer production target for Android gallery scans because it removes the
  result-routing race that `v0.4.2` did not address.
- Both versions remain drop-in upgrades from the caller's perspective. There are no option
  changes, no result-shape changes, and no TurboModule contract changes.
- The new Jest test in `v0.4.3` is intentionally structural. It protects against accidental
  reintroduction of nested `startActivityForResult` in the Android gallery picker path.

## Related

- ADR-005 — Android gallery uses `CropEditorActivity`, not GMS gallery import.
- ADR-006 — Android content-rotation detection and platform asymmetry notes.
- `CHANGELOG.md` entries for `0.4.2` and `0.4.3`.
