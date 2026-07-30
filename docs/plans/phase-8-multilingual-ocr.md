# Phase 8 Multilingual OCR Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.
> Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add BCP 47 OCR language hints and runtime OCR capability reporting while preserving the existing Korean and English scan path.

**Architecture:** The JS layer owns additive public types, defaults, and cheap array normalization.
iOS validates ordered language hints against the active Vision request and uses the same list for every orientation pass.
Android resolves language tags to one ML Kit script model, keeps Korean bundled, prepares other models through Google Play services, and injects the selected recognizer into the existing OCR pipeline.

**Tech Stack:** React Native 0.85 TurboModules, TypeScript 6 strict mode, Jest 30, Kotlin 2, ML Kit Text Recognition v2, Google Play services ModuleInstallClient, Objective-C++, Vision, Yarn 4, Trunk.

## Global Constraints

- Normative behavior is defined in `docs/specs/multilingual-ocr.md` at commits `fc983b2` and `5738ed6`.
- Preserve the module name string `"ReceiptScanner"` in TypeScript, Kotlin, and Objective-C++.
- Preserve the default language order `["ko-KR", "en-US"]`.
- Preserve all existing `ScanReceiptResult`, `ReceiptImage`, `OcrQuality`, and `OcrLine` fields.
- `ocrFloor` remains optional and language-agnostic.
- `ocr: false` must bypass language validation and Android model work.
- Never add `as any`, `@ts-ignore`, or `@ts-expect-error`.
- Keep receipt parsing, language detection, translation, cloud OCR, and multi-model result merging out of scope.
- Do not add a public country or language allowlist.
- Keep Android Korean OCR bundled at `com.google.mlkit:text-recognition-korean:16.0.1`.
- Deliver Latin, Japanese, Chinese, and Devanagari OCR through Google Play services modules.
- Do not edit generated code under `android/build/generated`, `ios/build`, or `lib`; change codegen inputs and regenerate through the existing build.
- Do not change the Yarn lockfile unless `package.json` or another JavaScript dependency manifest changes.
- Run only one Android or iOS build at a time.

---

## File map

### New files

- `android/src/main/java/com/receiptscanner/OcrLanguageResolver.kt`: canonical Android script-family model and BCP 47 to likely-script resolution.
- `android/src/main/java/com/receiptscanner/OcrModelManager.kt`: recognizer construction, module availability reporting, and immediate dynamic-model preparation.
- `android/src/test/java/com/receiptscanner/OcrLanguageResolverTest.kt`: pure language-to-script tests.
- `android/src/test/java/com/receiptscanner/OcrModelManagerTest.kt`: ready, download, failure, and cleanup state tests through a fake installer boundary.

### Modified files

- `src/types.ts`: public option, capability union, default languages, and error-code documentation.
- `src/NativeReceiptScanner.ts`: TurboModule capability method.
- `src/scan.native.tsx`: language-array normalization and native capability delegation.
- `src/scan.tsx`: web capability result.
- `src/index.tsx`: public exports.
- `src/__tests__/index.test.tsx`: native wrapper defaults, normalization, bypass, capabilities, and exports.
- `android/build.gradle`: non-default Google Play services text-recognition artifacts.
- `android/src/main/java/com/receiptscanner/ScanOptions.kt`: retain `ocrLanguages`.
- `android/src/test/java/com/receiptscanner/ScanOptionsTest.kt`: bridge parsing coverage.
- `android/src/main/java/com/receiptscanner/OcrProcessor.kt`: accept the selected `TextRecognizer` instead of constructing Korean internally.
- `android/src/main/java/com/receiptscanner/ReceiptScannerModule.kt`: model preparation, pending-state ownership, capability method, and selected processor use.
- `ios/RNScanOptions.h`: retain copied OCR language hints.
- `ios/RNScanOptions.m`: defensive array parsing and defaults.
- `ios/RNOcrProcessor.h`: language-aware OCR and supported-language interfaces.
- `ios/RNOcrProcessor.m`: request validation and consistent language configuration across passes.
- `ios/RNDocumentCameraDelegate.m`: pass resolved languages to OCR.
- `ios/RNGalleryPickerDelegate.m`: pass resolved languages to OCR.
- `ios/ReceiptScanner.mm`: preflight validation, in-progress ownership, and capability method.
- `example/src/App.tsx`: manual-QA language input and capability display.
- `docs/specs/api-contract.md`: shipped public API and errors.
- `docs/specs/scan-pipeline.md`: platform language and model-preparation flow.
- `docs/notes/platform-asymmetries.md`: ordered iOS languages versus Android single-script selection.
- `README.md`: concise consumer usage and support caveat.
- `ios/AGENTS.md`: replace the fixed-language source-map claim after implementation.

---

### Task 1: JavaScript public contract and native wrapper

**Files:**

- Modify: `src/types.ts`
- Modify: `src/NativeReceiptScanner.ts`
- Modify: `src/scan.native.tsx`
- Modify: `src/scan.tsx`
- Modify: `src/index.tsx`
- Modify: `src/__tests__/index.test.tsx`

**Interfaces:**

- Produces: `DEFAULT_OCR_LANGUAGES`, `OcrModelState`, `IosOcrCapabilities`, `AndroidOcrCapabilities`, `WebOcrCapabilities`, `OcrCapabilities`, and `getOcrCapabilities()`.
- Produces: `ScanReceiptOptions.ocrLanguages?: readonly string[]`.
- Consumes: the existing `NativeReceiptScanner.scan(options: Object): Promise<Object>`.
- Provides to native tasks: `NativeReceiptScanner.getOcrCapabilities(): Promise<Object>`.

- [ ] **Step 1: Add failing tests for defaults and normalization**

Extend the native module mock with `getOcrCapabilities: jest.fn()`.
Add these cases to `src/__tests__/index.test.tsx`:

```tsx
it("forwards the Korean and English default language order", async () => {
  mockNative.scan.mockResolvedValueOnce({ status: "cancelled", images: [] });

  await scan();

  expect(mockNative.scan).toHaveBeenCalledWith(
    expect.objectContaining({ ocrLanguages: ["ko-KR", "en-US"] })
  );
});

it("trims and de-duplicates explicit OCR languages without reordering", async () => {
  mockNative.scan.mockResolvedValueOnce({ status: "cancelled", images: [] });

  await scan({ ocrLanguages: [" es-ES ", "en-US", "es-ES"] });

  expect(mockNative.scan).toHaveBeenCalledWith(
    expect.objectContaining({ ocrLanguages: ["es-ES", "en-US"] })
  );
});

it("rejects an empty OCR language list before calling native code", async () => {
  await expect(scan({ ocrLanguages: [] })).rejects.toMatchObject({
    code: "INVALID_OCR_LANGUAGE",
  });
  expect(mockNative.scan).not.toHaveBeenCalled();
});

it("bypasses OCR language validation when OCR is disabled", async () => {
  mockNative.scan.mockResolvedValueOnce({ status: "cancelled", images: [] });

  await expect(scan({ ocr: false, ocrLanguages: [] })).resolves.toMatchObject({
    status: "cancelled",
  });
  expect(mockNative.scan).toHaveBeenCalled();
});
```

- [ ] **Step 2: Add failing tests for native and web capabilities**

Import `getOcrCapabilities` from `../scan.native` and add:

```tsx
it("returns the typed native OCR capability payload", async () => {
  mockNative.getOcrCapabilities.mockResolvedValueOnce({
    platform: "ios",
    defaultLanguages: ["ko-KR", "en-US"],
    supportedLanguages: ["en-US", "ko-KR", "es-ES"],
  });

  await expect(getOcrCapabilities()).resolves.toEqual({
    platform: "ios",
    defaultLanguages: ["ko-KR", "en-US"],
    supportedLanguages: ["en-US", "ko-KR", "es-ES"],
  });
});
```

Add a web-entry test that imports `getOcrCapabilities` from `../scan` and expects:

```tsx
{
  platform: "web",
  defaultLanguages: ["ko-KR", "en-US"],
  supported: false,
}
```

Use a type-only import from `../index` for every new public type so `yarn typecheck` verifies the root exports.

- [ ] **Step 3: Run the focused tests and confirm the red state**

Run:

```bash
yarn test src/__tests__/index.test.tsx --runInBand
```

Expected: FAIL because `ocrLanguages`, `getOcrCapabilities`, and the native mock method do not exist.

- [ ] **Step 4: Add the public types and defaults**

In `src/types.ts`, add the exact shapes from `docs/specs/multilingual-ocr.md` and:

```ts
export const DEFAULT_OCR_LANGUAGES = ["ko-KR", "en-US"] as const;
```

Add `ocrLanguages?: readonly string[]` to `ScanReceiptOptions`.
Add `ocrLanguages: DEFAULT_OCR_LANGUAGES` to `DEFAULT_SCAN_OPTIONS`.
Keep `DEFAULT_SCAN_OPTIONS` typed as `Required<ScanReceiptOptions>`.

- [ ] **Step 5: Add the TurboModule method and JS delegates**

In `src/NativeReceiptScanner.ts`, add:

```ts
getOcrCapabilities(): Promise<Object>;
```

In `src/scan.native.tsx`, export:

```ts
export async function getOcrCapabilities(): Promise<OcrCapabilities> {
  return (await NativeReceiptScanner.getOcrCapabilities()) as OcrCapabilities;
}
```

Add a private error helper without suppressions:

```ts
type CodedError = Error & { code: string };

function invalidOcrLanguage(message: string): CodedError {
  const error = new Error(message) as CodedError;
  error.code = "INVALID_OCR_LANGUAGE";
  return error;
}
```

Normalize only when `merged.ocr` is true.
For OCR-disabled scans, forward the merged value without inspecting it.
For enabled OCR, reject an empty list or an entry that becomes empty after trimming, then de-duplicate trimmed strings with `Set`.

In `src/scan.tsx`, return the exact `WebOcrCapabilities` object.
In `src/index.tsx`, export `scan`, `getOcrCapabilities`, `DEFAULT_OCR_LANGUAGES`, and every new type.

- [ ] **Step 6: Run focused JS verification**

Run:

```bash
yarn typecheck && yarn test src/__tests__/index.test.tsx --runInBand
```

Expected: PASS.

- [ ] **Step 7: Commit the JS contract**

```bash
git add src/types.ts src/NativeReceiptScanner.ts src/scan.native.tsx src/scan.tsx src/index.tsx src/__tests__/index.test.tsx
git commit -m "feat(api): ✨ add multilingual OCR contract"
```

---

### Task 2: Android language and script resolution

**Files:**

- Create: `android/src/main/java/com/receiptscanner/OcrLanguageResolver.kt`
- Create: `android/src/test/java/com/receiptscanner/OcrLanguageResolverTest.kt`
- Modify: `android/src/main/java/com/receiptscanner/ScanOptions.kt`
- Modify: `android/src/test/java/com/receiptscanner/ScanOptionsTest.kt`

**Interfaces:**

- Consumes: `ScanOptions.ocrLanguages: List<String>`.
- Produces: internal `enum class OcrScript { LATIN, KOREAN, JAPANESE, CHINESE, DEVANAGARI }`.
- Produces: `OcrLanguageResolver.resolve(tags: List<String>): OcrScript`.
- Throws: `OcrLanguageException(code: String, message: String)` with the public codes `INVALID_OCR_LANGUAGE`, `OCR_LANGUAGE_NOT_SUPPORTED`, or `OCR_LANGUAGE_COMBINATION_NOT_SUPPORTED`.

- [ ] **Step 1: Write failing pure resolver tests**

Create `OcrLanguageResolverTest.kt` with:

```kotlin
class OcrLanguageResolverTest {
  @Test
  fun `Korean and Latin resolve to Korean`() {
    assertEquals(OcrScript.KOREAN, OcrLanguageResolver.resolve(listOf("ko-KR", "en-US")))
  }

  @Test
  fun `Japanese and Latin resolve to Japanese`() {
    assertEquals(OcrScript.JAPANESE, OcrLanguageResolver.resolve(listOf("ja-JP", "en-US")))
  }

  @Test
  fun `Latin languages resolve to Latin`() {
    assertEquals(OcrScript.LATIN, OcrLanguageResolver.resolve(listOf("es-ES", "fr-FR")))
  }

  @Test
  fun `Chinese and Japanese reject as multiple non Latin scripts`() {
    val error =
      assertThrows(OcrLanguageException::class.java) {
        OcrLanguageResolver.resolve(listOf("zh-Hant", "ja-JP"))
      }
    assertEquals("OCR_LANGUAGE_COMBINATION_NOT_SUPPORTED", error.code)
  }

  @Test
  fun `unsupported Arabic script rejects explicitly`() {
    val error =
      assertThrows(OcrLanguageException::class.java) {
        OcrLanguageResolver.resolve(listOf("ar"))
      }
    assertEquals("OCR_LANGUAGE_NOT_SUPPORTED", error.code)
  }
}
```

Also cover `hi-IN -> DEVANAGARI`, `zh-Hans -> CHINESE`, and an invalid tag.

- [ ] **Step 2: Add failing bridge-parser tests**

In `ScanOptionsTest.kt`, use `JavaOnlyArray` and assert that an omitted field defaults to `listOf("ko-KR", "en-US")` and an explicit array preserves order:

```kotlin
val languages = JavaOnlyArray().apply {
  pushString("ja-JP")
  pushString("en-US")
}
val map = JavaOnlyMap().apply { putArray("ocrLanguages", languages) }

assertEquals(listOf("ja-JP", "en-US"), ScanOptions.from(map).ocrLanguages)
```

- [ ] **Step 3: Run focused Android tests and confirm the red state**

Run:

```bash
cd example/android
./gradlew :react-native-receipt-scanner:testDebugUnitTest --tests '*OcrLanguageResolverTest*' --tests '*ScanOptionsTest*'
```

Expected: FAIL because the resolver, script enum, exception, and option field do not exist.

- [ ] **Step 4: Implement likely-script resolution**

Implement `OcrLanguageResolver.kt` with `android.icu.util.ULocale.forLanguageTag` and `ULocale.addLikelySubtags`.
Reject a tag when `forLanguageTag(tag).language` is blank.
Map likely scripts as follows:

```kotlin
private fun modelForScript(script: String): OcrScript =
  when (script) {
    "Latn" -> OcrScript.LATIN
    "Kore" -> OcrScript.KOREAN
    "Jpan", "Hira", "Kana" -> OcrScript.JAPANESE
    "Hans", "Hant", "Hani" -> OcrScript.CHINESE
    "Deva" -> OcrScript.DEVANAGARI
    else -> throw OcrLanguageException(
      "OCR_LANGUAGE_NOT_SUPPORTED",
      "OCR script $script is not supported on Android",
    )
  }
```

Discard `LATIN` when one non-Latin model is present.
Return `LATIN` when every tag is Latin.
Reject when the remaining non-Latin set has more than one entry.

- [ ] **Step 5: Parse language arrays defensively**

Add `ocrLanguages: List<String>` to `ScanOptions`.
Use `ReadableType.Array` and `ReadableType.String` checks before reading bridge values.
Fall back to `listOf("ko-KR", "en-US")` only when the key is absent or the bridge value has the wrong outer type.
Preserve an explicitly empty array so the resolver can reject it when OCR is enabled.

- [ ] **Step 6: Run focused Android verification**

Run:

```bash
cd example/android
./gradlew :react-native-receipt-scanner:testDebugUnitTest --tests '*OcrLanguageResolverTest*' --tests '*ScanOptionsTest*'
```

Expected: PASS.

- [ ] **Step 7: Commit Android language resolution**

```bash
git add android/src/main/java/com/receiptscanner/OcrLanguageResolver.kt android/src/main/java/com/receiptscanner/ScanOptions.kt android/src/test/java/com/receiptscanner/OcrLanguageResolverTest.kt android/src/test/java/com/receiptscanner/ScanOptionsTest.kt
git commit -m "feat(android): ✨ resolve OCR languages by script"
```

---

### Task 3: Android recognizer delivery and preparation

**Files:**

- Modify: `android/build.gradle`
- Create: `android/src/main/java/com/receiptscanner/OcrModelManager.kt`
- Create: `android/src/test/java/com/receiptscanner/OcrModelManagerTest.kt`
- Modify: `android/src/main/java/com/receiptscanner/OcrProcessor.kt`

**Interfaces:**

- Consumes: `OcrScript`.
- Produces: `OcrModelManager.capabilities(onResult: (List<OcrModelState>) -> Unit, onFailure: (Exception) -> Unit)`.
- Produces: `OcrModelManager.prepare(script: OcrScript, onReady: (OcrProcessor) -> Unit, onFailure: (Exception) -> Unit)`.
- Produces: `data class OcrModelState(val script: String, val status: String)`.
- Changes: `OcrProcessor` receives a `TextRecognizer` in its constructor and closes that recognizer in `close()`.

- [ ] **Step 1: Add the non-default dynamic recognizer dependencies**

Add:

```groovy
implementation "com.google.android.gms:play-services-mlkit-text-recognition:19.0.1"
implementation "com.google.android.gms:play-services-mlkit-text-recognition-japanese:16.0.1"
implementation "com.google.android.gms:play-services-mlkit-text-recognition-chinese:16.0.1"
implementation "com.google.android.gms:play-services-mlkit-text-recognition-devanagari:16.0.1"
```

Keep the existing Korean dependency unchanged.
Do not add the Play services Korean artifact and do not modify `yarn.lock`.

- [ ] **Step 2: Introduce a fakeable module-install boundary and write failing tests**

In `OcrModelManager.kt`, define:

```kotlin
internal interface OcrModuleInstaller {
  fun check(
    recognizer: TextRecognizer,
    onResult: (Boolean) -> Unit,
    onFailure: (Exception) -> Unit,
  )

  fun install(
    recognizer: TextRecognizer,
    onInstalled: () -> Unit,
    onFailure: (Exception) -> Unit,
  )
}
```

The production implementation wraps `ModuleInstall.getClient(context)`.
The unit test uses a fake installer and a fake recognizer factory.

Add tests proving:

- Korean returns `ready` without calling the installer.
- An available Latin module returns `ready`.
- An absent Japanese module returns `download-required` from capabilities without installing it.
- `prepare` installs an absent model and calls `onReady` only after the terminal installed callback.
- Installation failure calls `onFailure` and closes the recognizer.
- A capability check closes every temporary recognizer.

- [ ] **Step 3: Run the focused manager test and confirm the red state**

Run:

```bash
cd example/android
./gradlew :react-native-receipt-scanner:testDebugUnitTest --tests '*OcrModelManagerTest*'
```

Expected: FAIL because the manager and injectable boundaries do not exist.

- [ ] **Step 4: Implement recognizer construction**

Create exactly one recognizer for the resolved script:

```kotlin
private fun createRecognizer(script: OcrScript): TextRecognizer =
  when (script) {
    OcrScript.LATIN ->
      TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    OcrScript.KOREAN ->
      TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    OcrScript.JAPANESE ->
      TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    OcrScript.CHINESE ->
      TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    OcrScript.DEVANAGARI ->
      TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
  }
```

Treat Korean as always ready because it remains bundled.
For other scripts, ask `ModuleInstallClient.areModulesAvailable`.
Use `ModuleInstallRequest` plus an `InstallStatusListener` for immediate preparation.
Call `unregisterListener` on installed, failed, or canceled terminal states.

- [ ] **Step 5: Inject the selected recognizer into `OcrProcessor`**

Replace the fixed Korean construction with:

```kotlin
class OcrProcessor(
  private val recognizer: TextRecognizer,
) {
  // Existing recognition and rotation code remains unchanged.
}
```

Keep `close()` as the single lifecycle owner.
Do not change text measurement, rotation decisions, confidence, or geometry.

- [ ] **Step 6: Run Android unit verification**

Run:

```bash
cd example/android
./gradlew :react-native-receipt-scanner:testDebugUnitTest --tests '*OcrModelManagerTest*' --tests '*OcrLanguageResolverTest*' --tests '*OcrGeometryTest*'
```

Expected: PASS.

- [ ] **Step 7: Commit Android model delivery**

```bash
git add android/build.gradle android/src/main/java/com/receiptscanner/OcrModelManager.kt android/src/main/java/com/receiptscanner/OcrProcessor.kt android/src/test/java/com/receiptscanner/OcrModelManagerTest.kt
git commit -m "feat(android): ✨ prepare multilingual OCR models"
```

---

### Task 4: Android TurboModule integration

**Files:**

- Modify: `android/src/main/java/com/receiptscanner/ReceiptScannerModule.kt`
- Modify: `android/src/main/java/com/receiptscanner/OcrModelManager.kt`
- Modify: `android/src/test/java/com/receiptscanner/OcrModelManagerTest.kt`

**Interfaces:**

- Consumes: `OcrLanguageResolver.resolve(scanOptions.ocrLanguages)`.
- Consumes: `OcrModelManager.prepare(...)`.
- Produces: native `getOcrCapabilities(promise: Promise)`.
- Maintains: one `pendingPromise`, one `pendingOptions`, and one selected `OcrProcessor` for the full preparation and scan lifecycle.

- [ ] **Step 1: Add manager lifecycle tests for scan ownership**

Extend `OcrModelManagerTest.kt` with a controllable fake installer.
Prove that an installation callback cannot deliver more than one processor after success or failure and that closing a canceled preparation releases the recognizer and unregisters the listener.

- [ ] **Step 2: Run the focused test and confirm the red state**

Run:

```bash
cd example/android
./gradlew :react-native-receipt-scanner:testDebugUnitTest --tests '*OcrModelManagerTest*'
```

Expected: FAIL until preparation exposes a cancelable handle and terminal callbacks are idempotent.

- [ ] **Step 3: Make model preparation cancelable and idempotent**

Have `prepare` return:

```kotlin
internal fun interface OcrPreparation {
  fun cancel()
}
```

Guard terminal delivery with `AtomicBoolean`.
Cancellation must unregister the listener, close the recognizer, and prevent later callbacks.

- [ ] **Step 4: Integrate preparation before scanner UI**

In `ReceiptScannerModule.scan`:

1. Claim `pendingPromise` before starting model work.
2. Parse `ScanOptions`.
3. If `ocr` is false, launch the existing camera or gallery path immediately.
4. Resolve the requested script and reject its exact `OcrLanguageException.code` before UI.
5. Prepare the model.
6. Store the returned `OcrProcessor` for the current scan.
7. Launch the existing UI only from `onReady`.
8. On preparation failure, clear all pending fields and reject `OCR_MODEL_INSTALL_FAILED`.

Extract the existing camera/gallery launch body into a private `launchScan(activity, scanOptions)` method.
Do not alter its scanner options or request codes.

Replace both `OcrProcessor()` constructions in the result handlers with the prepared processor.
Move ownership into a `takePendingOcrProcessor()` helper so exactly one result path receives and closes it.
Ensure cancel, scanner initialization failure, result parsing failure, processing failure, and module teardown all close an owned processor.

- [ ] **Step 5: Expose Android capabilities**

Implement generated-spec method:

```kotlin
override fun getOcrCapabilities(promise: Promise)
```

Return:

```kotlin
Arguments.createMap().apply {
  putString("platform", "android")
  putArray("defaultLanguages", Arguments.fromList(listOf("ko-KR", "en-US")))
  putArray("models", modelStateArray)
}
```

Capability checks must not assign `pendingPromise`, install modules, or open UI.

- [ ] **Step 6: Regenerate codegen and run Android verification**

Run:

```bash
yarn prepare
cd example/android
./gradlew :react-native-receipt-scanner:testDebugUnitTest
./gradlew :app:assembleDebug
```

Expected: all Android unit tests pass and the example APK builds.

- [ ] **Step 7: Commit Android module integration**

```bash
git add android/src/main/java/com/receiptscanner/ReceiptScannerModule.kt android/src/main/java/com/receiptscanner/OcrModelManager.kt android/src/test/java/com/receiptscanner/OcrModelManagerTest.kt
git commit -m "feat(android): ✨ route scans through selected OCR model"
```

---

### Task 5: iOS option parsing and Vision request configuration

**Files:**

- Modify: `ios/RNScanOptions.h`
- Modify: `ios/RNScanOptions.m`
- Modify: `ios/RNOcrProcessor.h`
- Modify: `ios/RNOcrProcessor.m`
- Modify: `ios/RNDocumentCameraDelegate.m`
- Modify: `ios/RNGalleryPickerDelegate.m`

**Interfaces:**

- Produces: `RNScanOptions.ocrLanguages: NSArray<NSString *> *`.
- Produces: `+[RNOcrProcessor supportedRecognitionLanguages:]`.
- Produces: `+[RNOcrProcessor validateRecognitionLanguages:error:]`.
- Changes: `recognizeAndDetectRotationInImage:minimumTextHeight:languages:error:` receives one resolved ordered list.

- [ ] **Step 1: Add `ocrLanguages` to `RNScanOptions`**

Declare:

```objc
@property (nonatomic, copy) NSArray<NSString *> *ocrLanguages;
```

In both default branches of `+optionsFromDictionary:`, use:

```objc
opts.ocrLanguages = @[@"ko-KR", @"en-US"];
```

When the bridge value is an array, retain only `NSString` entries and preserve order.
Preserve an explicitly empty array so OCR-enabled preflight rejects it.
Do not canonicalize or check Vision support in the parser.

- [ ] **Step 2: Add Vision capability and validation helpers**

In `RNOcrProcessor`, construct a `VNRecognizeTextRequest` with `.accurate` level and the same request revision used by recognition.
Return `supportedRecognitionLanguagesAndReturnError:`.

Validation must:

- Reject an empty array with an `NSError` carrying code string `INVALID_OCR_LANGUAGE` in `userInfo`.
- Canonicalize with `NSLocale canonicalLanguageIdentifierFromString:`.
- Reject blank or invalid canonical identifiers with `INVALID_OCR_LANGUAGE`.
- Reject canonical identifiers absent from the active request's supported language set with `OCR_LANGUAGE_NOT_SUPPORTED`.
- De-duplicate canonical identifiers while preserving first occurrence.
- Return the canonical ordered array for the scan.

- [ ] **Step 3: Apply one language list to every OCR pass**

Change the public OCR selector to:

```objc
+ (nullable RNOcrResult *)recognizeAndDetectRotationInImage:(UIImage *)image
                                          minimumTextHeight:(double)minimumTextHeight
                                                  languages:(NSArray<NSString *> *)languages
                                                       error:(NSError **)error;
```

Thread `languages` through `runOcrOnCIImage`, `resultByRotating`, the initial accurate pass, every fast probe, and the final accurate pass.
Set:

```objc
request.recognitionLanguages = languages;
request.automaticallyDetectsLanguage = NO;
```

Do not change `recognitionLevel`, `usesLanguageCorrection`, `minimumTextHeight`, confidence aggregation, geometry, or rotation thresholds.

- [ ] **Step 4: Pass options from both delegates**

Update both OCR call sites:

```objc
[RNOcrProcessor recognizeAndDetectRotationInImage:image
                                minimumTextHeight:options.minimumTextHeight
                                        languages:options.ocrLanguages
                                             error:&ocrError];
```

Do not add per-delegate language logic.

- [ ] **Step 5: Build iOS integration**

Run:

```bash
yarn prepare
yarn example ios
```

Expected: codegen, pod compilation, and example launch succeed.
There is no existing iOS unit-test target in this repository, so parser and Vision helper behavior remains covered by compilation and Task 8 manual QA rather than an invented test harness.

- [ ] **Step 6: Commit iOS language-aware OCR**

```bash
git add ios/RNScanOptions.h ios/RNScanOptions.m ios/RNOcrProcessor.h ios/RNOcrProcessor.m ios/RNDocumentCameraDelegate.m ios/RNGalleryPickerDelegate.m
git commit -m "feat(ios): ✨ configure OCR recognition languages"
```

---

### Task 6: iOS preflight and capability integration

**Files:**

- Modify: `ios/ReceiptScanner.mm`
- Modify: `ios/ReceiptScanner.h`

**Interfaces:**

- Consumes: `RNOcrProcessor.validateRecognitionLanguages`.
- Produces: native `getOcrCapabilitiesWithResolve:reject:` generated from the TypeScript TurboModule spec.
- Maintains: a preparation flag in addition to delegate references so validation and UI presentation share the `SCAN_IN_PROGRESS` guard.

- [ ] **Step 1: Add preflight ownership**

Add:

```objc
@property (nonatomic, assign) BOOL preparingScan;
```

Treat `preparingScan || cameraDelegate || galleryDelegate` as in progress.
Set `preparingScan = YES` before language validation.
Clear it in every preflight rejection, wrapped resolve, wrapped reject, and immediately before assigning the selected delegate.

- [ ] **Step 2: Validate before looking up or presenting UI**

After parsing `RNScanOptions`:

- Skip validation when `scanOptions.ocr == NO`.
- Otherwise call the processor validation helper.
- Replace `scanOptions.ocrLanguages` with the canonical returned array.
- Reject using the helper's public code before dispatching presentation to the main queue.

Use a small function to read the code from `NSError.userInfo`; default only unexpected internal failures to `OCR_LANGUAGE_NOT_SUPPORTED`.

- [ ] **Step 3: Expose iOS capabilities**

Implement the generated TurboModule selector and return:

```objc
@{
  @"platform": @"ios",
  @"defaultLanguages": @[@"ko-KR", @"en-US"],
  @"supportedLanguages": supportedLanguages,
}
```

Reject capability-query failures with `OCR_LANGUAGE_NOT_SUPPORTED`.
The method must not set `preparingScan`, retain a delegate, or open UI.

- [ ] **Step 4: Build and inspect generated selector compatibility**

Run:

```bash
yarn prepare
yarn example ios
```

Expected: the Objective-C++ implementation conforms to `NativeReceiptScannerSpec` and the example launches.

- [ ] **Step 5: Commit iOS module integration**

```bash
git add ios/ReceiptScanner.h ios/ReceiptScanner.mm
git commit -m "feat(ios): ✨ validate and report OCR capabilities"
```

---

### Task 7: Example QA controls and shipped documentation

**Files:**

- Modify: `example/src/App.tsx`
- Modify: `README.md`
- Modify: `docs/specs/api-contract.md`
- Modify: `docs/specs/scan-pipeline.md`
- Modify: `docs/notes/platform-asymmetries.md`
- Modify: `ios/AGENTS.md`

**Interfaces:**

- Consumes: public `scan({ ocrLanguages })` and `getOcrCapabilities()`.
- Produces: a manual-QA surface for requested language order, capability state, and scan errors.
- Documents: only behavior implemented in Tasks 1 through 6.

- [ ] **Step 1: Add a minimal example language control**

Add one comma-separated text input initialized to:

```ts
const [ocrLanguageInput, setOcrLanguageInput] = useState("ko-KR,en-US");
```

Resolve it at scan time with:

```ts
const ocrLanguages = ocrLanguageInput.split(",").map((tag) => tag.trim());
```

Pass `ocrLanguages` to the existing `scan` call.
Do not add a country selector, provider-model selector, language auto-detection control, or per-language floor.

- [ ] **Step 2: Display capabilities and coded failures**

Call `getOcrCapabilities()` once from an existing mount effect or a new focused effect.
Render the JSON payload in the example's existing diagnostic area.
When a scan rejects, include `code` when the caught value is `Error & { code?: unknown }` without using a suppression.

- [ ] **Step 3: Update the public documentation**

In `README.md` and `docs/specs/api-contract.md`, document:

```ts
const capabilities = await getOcrCapabilities();
const result = await scan({
  ocrLanguages: ["es-ES", "en-US"],
  ocrFloor: false,
});
```

State that BCP 47 hints select native OCR but do not infer a receipt country or parse its contents.
Document all four new error codes and the web capability shape.
Label non-Korean and non-English scripts as provider-supported and uncalibrated until fixtures exist.

- [ ] **Step 4: Update internal pipeline and asymmetry records**

In `scan-pipeline.md`, add language validation before UI and Android model preparation before acquisition.
In `platform-asymmetries.md`, record:

- iOS uses ordered language identifiers from the active Vision request.
- Android selects one script recognizer.
- Latin may accompany one non-Latin script.
- Android non-default models may require download.
- Capability discovery never initiates installation.

Update `ios/AGENTS.md` so it no longer claims a fixed `ko-KR + en-US` implementation.

- [ ] **Step 5: Run JS and documentation verification**

Run:

```bash
yarn typecheck && yarn lint && yarn test && trunk fmt && trunk check
```

Expected: PASS with only intended formatting changes.
Reinspect `git status --short` after `trunk fmt` and do not stage unrelated files.

- [ ] **Step 6: Commit the QA surface and documentation**

```bash
git add example/src/App.tsx README.md docs/specs/api-contract.md docs/specs/scan-pipeline.md docs/notes/platform-asymmetries.md ios/AGENTS.md
git commit -m "docs: 📝 document multilingual OCR usage"
```

---

### Task 8: Exact-head integration and manual QA

**Files:**

- Modify only if a verified defect is found in Tasks 1 through 7.
- Do not add fixture files containing personal or payment data.

**Interfaces:**

- Verifies: the exact full commit SHA produced by Tasks 1 through 7.
- Produces: command output and manual observations for the final handoff.

- [ ] **Step 1: Capture the exact candidate SHA and clean state**

Run:

```bash
git rev-parse HEAD
git status --short
```

Expected: a full SHA and no uncommitted files.
If the worktree is not clean, classify every path before continuing.

- [ ] **Step 2: Run the full repository gate**

Run:

```bash
yarn typecheck && yarn lint && yarn test && trunk fmt && trunk check
```

Expected: PASS.
If `trunk fmt` changes tracked files, inspect, commit the intended formatting with its owning concern, capture the new SHA, and rerun the gate.

- [ ] **Step 3: Run Android unit and integration builds**

Run:

```bash
cd example/android
./gradlew :react-native-receipt-scanner:testDebugUnitTest
cd ../..
yarn example android
```

Expected: unit tests pass and the example launches on a Google Play services device or emulator.

- [ ] **Step 4: Run Android behavioral QA**

Observe all of the following:

1. Default `ko-KR,en-US` reports Korean ready and returns Korean plus Latin text.
2. `es-ES,en-US` selects Latin and completes a scan.
3. A not-yet-installed dynamic model delays UI until installation completes.
4. The same missing model while offline rejects with `OCR_MODEL_INSTALL_FAILED`.
5. `zh-Hant,ja-JP` rejects before UI with `OCR_LANGUAGE_COMBINATION_NOT_SUPPORTED`.
6. A second scan during model preparation rejects with `SCAN_IN_PROGRESS`.
7. `ocr: false` opens the scanner without language or model errors.
8. `ocrFloor: false` returns readable OCR output without threshold rejection.

- [ ] **Step 5: Run iOS integration build**

Run:

```bash
yarn example ios
```

Expected: the example launches on an iOS simulator or device.

- [ ] **Step 6: Run iOS behavioral QA**

Observe all of the following:

1. `getOcrCapabilities()` returns the active Vision language identifiers.
2. Default `ko-KR,en-US` completes a scan with existing Korean and English fixture behavior.
3. One returned non-default language completes a scan.
4. A syntactically valid unsupported language rejects before UI with `OCR_LANGUAGE_NOT_SUPPORTED`.
5. Duplicate and whitespace-padded tags arrive in canonical priority order.
6. `ocr: false` bypasses language validation.
7. Auto-rotation and `ocrGeometry` still use the text pass selected by the configured language list.

- [ ] **Step 7: Compare the Korean and English regression baseline**

For the existing fixtures, compare before and after outputs.
The candidate must not lose:

- Non-whitespace OCR text.
- Recognized line count.
- Confidence presence when text exists.
- Positive, in-bounds geometry when `ocrGeometry` is enabled.
- Correct final pixel orientation when `autoRotate` is enabled.

Record differences as evidence.
Do not claim foreign-language accuracy beyond the observed manual sample.

- [ ] **Step 8: Reconfirm the exact tested SHA**

Run:

```bash
git rev-parse HEAD
git status --short
git log --oneline fc983b2..HEAD
```

Expected: the final SHA matches the candidate tested after any fixes, the worktree is clean, and the commit list contains only the planned concerns.

---

## Final acceptance checklist

- [ ] `ocrLanguages` is additive and defaults to `["ko-KR", "en-US"]`.
- [ ] Public capability types cover iOS, Android, and web without false parity.
- [ ] JS normalization preserves order, removes duplicates, and bypasses validation when OCR is off.
- [ ] Android selects one script recognizer and accepts accompanying Latin.
- [ ] Android capabilities do not download models.
- [ ] Android scans do not open UI until the selected dynamic model is ready.
- [ ] Android terminal paths release listeners, recognizers, and pending state.
- [ ] iOS validates against the same accurate Vision request used for scanning.
- [ ] Every iOS orientation pass uses the same caller-provided language order.
- [ ] Existing OCR result, floor, rotation, geometry, EXIF, and cancellation shapes remain unchanged.
- [ ] README, API contract, pipeline, asymmetry notes, and local agent guidance match the shipped implementation.
- [ ] Full JS, Trunk, Android, iOS, and manual-QA gates pass at the final exact SHA.
