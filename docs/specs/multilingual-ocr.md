# Multilingual OCR

## Status

Implemented. Shipped in 0.8.0 (PR #16); the execution record is [`../plans/phase-8-multilingual-ocr.md`](../plans/phase-8-multilingual-ocr.md).
This document remains the normative reference for the behaviour — read it as the contract, not as a proposal.

## Objective

Expand `react-native-receipt-scanner` from a Korean-first receipt scanner into an on-device scanner for multilingual receipts and bills without adding receipt parsing, country inference, cloud OCR, or document-domain output.
The package continues to return normalized JPEG images, raw OCR text, OCR quality, optional OCR geometry, and EXIF metadata.

The expansion is additive.
Calls that omit the new language option must preserve the current Korean and English behavior.
`ocrFloor` remains optional and independent from language selection.

## Product boundary

The package targets receipts and bills written in any language that the active native OCR provider supports.
It does not publish a fixed list of target countries or application-level languages.
Consumers provide BCP 47 language hints, and the native platform reports or resolves its actual capability at runtime.

The following remain out of scope:

- Merchant, total, tax, currency, date, or line-item extraction.
- Country, locale, or language inference from receipt contents.
- Translation or transliteration.
- Cloud OCR, upload, retry, or server validation.
- Combining OCR output from multiple non-Latin script models.
- Renaming the package or the existing `ScanReceipt*` and `ReceiptImage` public types.
- Changing crop-editor localization based on OCR language hints.

This boundary extends ADR-003 without changing it: multilingual raw OCR is an image primitive, while interpreting the text remains an app or server responsibility.

## Design principles

1. Preserve the current default path.
2. Accept standard language tags instead of exposing provider-specific model names.
3. Report native capability rather than promising false cross-platform parity.
4. Fail explicitly when a requested model is unsupported or cannot be prepared.
5. Do not return an empty OCR result merely because an Android model download has not completed.
6. Keep one OCR pass and one geometry source per image.
7. Treat accuracy claims separately from engine support.

## Public API

### `ScanReceiptOptions.ocrLanguages`

Add one optional field:

```ts
export type ScanReceiptOptions = {
  // Existing fields remain unchanged.

  /**
   * Ordered BCP 47 language hints for on-device OCR.
   *
   * Omit this field to preserve the package default.
   * The first entry has the highest priority on platforms that support ordered languages.
   * An empty array is invalid.
   *
   * @defaultValue `["ko-KR", "en-US"]`
   */
  ocrLanguages?: readonly string[];
};
```

`ocrLanguages` is effective only when `ocr === true`.
The option does not change crop-editor strings, the system scanner UI, `ocrFloor`, EXIF processing, or result filtering.

The JS wrapper must trim each tag, reject empty entries, and remove exact duplicates while preserving the first occurrence.
Native code remains responsible for canonicalization and provider capability validation because JavaScript runtime locale support is not part of this package's contract.

When `ocrLanguages` is omitted, the JS wrapper must forward `["ko-KR", "en-US"]`.
This explicit default keeps the native boundary deterministic and preserves the current iOS language order and Android Korean recognizer.

### `getOcrCapabilities()`

Add a read-only capability query:

```ts
export type OcrModelState = {
  /** Unicode script identifier such as "Latn", "Kore", "Jpan", "Hans", "Hant", or "Deva". */
  script: string;
  /** Whether recognition can run immediately or requires a model download. */
  status: "ready" | "download-required";
};

export type IosOcrCapabilities = {
  platform: "ios";
  defaultLanguages: readonly ["ko-KR", "en-US"];
  /** Exact identifiers returned by the active Vision request revision and accurate recognition level. */
  supportedLanguages: string[];
};

export type AndroidOcrCapabilities = {
  platform: "android";
  defaultLanguages: readonly ["ko-KR", "en-US"];
  /** Script capabilities exposed by the installed package version, not a package-defined country list. */
  models: OcrModelState[];
};

export type WebOcrCapabilities = {
  platform: "web";
  defaultLanguages: readonly ["ko-KR", "en-US"];
  /** The web fallback does not provide native OCR. */
  supported: false;
};

export type OcrCapabilities = IosOcrCapabilities | AndroidOcrCapabilities | WebOcrCapabilities;

export function getOcrCapabilities(): Promise<OcrCapabilities>;
```

The query must not request a model download or open UI.
It reports capability for OCR regardless of the `ocr` value used in a later `scan()` call.

On iOS, `supportedLanguages` must come from `VNRecognizeTextRequest` configured with the same revision and `.accurate` recognition level used by scanning.
Apple documents runtime language discovery because available languages can vary with request configuration.

On Android, `models` must contain the five ML Kit Text Recognition v2 script families shipped by this package: Latin, Korean, Japanese, Chinese, and Devanagari.
Chinese capability may be represented by separate `"Hans"` and `"Hant"` entries that share one provider model.
The query must use Google Play services module availability for dynamically delivered models.

### Result contract

`ScanReceiptResult`, `ReceiptImage`, `OcrQuality`, and `OcrLine` remain unchanged.
The package does not add a detected language field because neither platform supplies a cross-platform, document-level language value with equivalent semantics.
The caller already knows the requested hints and receives raw recognized text.

## Language resolution

### Shared validation

Before presenting scanner or picker UI, native code must canonicalize every tag using the platform locale API.
A tag is invalid when it is empty after trimming or the native locale API cannot produce a language identifier.

The first tag is the highest-priority hint.
Duplicate canonical tags are removed while preserving order.
At least one canonical tag is required when OCR is enabled.

Invalid input rejects with `INVALID_OCR_LANGUAGE`.
Unsupported but syntactically valid input rejects with `OCR_LANGUAGE_NOT_SUPPORTED`.

### iOS

`RNOcrProcessor` must receive the resolved language list instead of using a fixed array.
It must configure every accurate and fast orientation-probe request with the same languages.
Changing languages between the primary pass and probe passes would make line-count comparisons invalid.

The processor must:

1. Query the recognition languages supported by the request revision and `.accurate` level.
2. Reject the scan before UI presentation if any requested tag is unsupported after canonicalization.
3. Set `recognitionLanguages` in caller-provided priority order.
4. Leave `automaticallyDetectsLanguage` disabled because the caller has supplied explicit hints and deterministic ordering is required.
5. Preserve the existing recognition level, language correction, minimum text height, rotation detection, confidence, and geometry behavior.

The default `["ko-KR", "en-US"]` must produce the same request configuration as the current implementation.

### Android

Android ML Kit chooses a text recognizer by script rather than by BCP 47 language priority.
The resolver must convert each canonical language tag to its likely Unicode script with `android.icu.util.ULocale.addLikelySubtags`.
No maintained country or language allowlist is added to TypeScript or Kotlin.

The resolver maps scripts to provider models as follows:

| Unicode script         | ML Kit recognizer |
| ---------------------- | ----------------- |
| `Latn`                 | Latin             |
| `Kore`                 | Korean            |
| `Jpan`, `Hira`, `Kana` | Japanese          |
| `Hans`, `Hant`, `Hani` | Chinese           |
| `Deva`                 | Devanagari        |

Latin may accompany one non-Latin script because each non-Latin ML Kit recognizer handles the Latin characters commonly mixed into receipts.
For example, `["ko-KR", "en-US"]` resolves to the Korean recognizer, `["ja-JP", "en-US"]` resolves to the Japanese recognizer, and `["es-ES", "en-US"]` resolves to the Latin recognizer.

A tag that passes BCP 47 syntax validation but resolves to no language — a private-use tag such as `x-private`, for which `ULocale` reports a blank language — rejects with `OCR_LANGUAGE_NOT_SUPPORTED`, not `INVALID_OCR_LANGUAGE`.
The syntax check owns malformed identifiers; anything past it that cannot be served is a capability failure.
iOS reaches the same code for the same input, because `x-private` canonicalizes non-empty and then misses the Vision supported-language set.

A request containing more than one non-Latin script family, such as Chinese plus Japanese, rejects with `OCR_LANGUAGE_COMBINATION_NOT_SUPPORTED`.
The package must not run multiple recognizers and merge their text, confidence, reading order, or geometry in this phase.

Language priority within one Android script model has no provider-level effect.
The accepted order is retained only for API parity and future provider changes.

The current Korean recognizer remains bundled and is selected for the default language list.
Latin, Japanese, Chinese, and Devanagari use the Google Play services dynamically delivered recognizers so expanding capability does not add every bundled model to the host APK.
Google documents an approximate application-size increase of 260 KB per script architecture for dynamically delivered recognizers versus approximately 4 MB per script architecture for bundled recognizers.

Before opening scan UI, the module must:

1. Construct the recognizer for the resolved script.
2. Check its optional module with `ModuleInstallClient.areModulesAvailable`.
3. Continue immediately when the module is ready.
4. Trigger immediate installation when the module is available for download.
5. Wait for a terminal installed state before starting the scan.
6. Unregister the install listener on success, failure, or cancellation.
7. Reject with `OCR_MODEL_INSTALL_FAILED` when installation fails or the module is unknown.

The existing `SCAN_IN_PROGRESS` guard must cover model preparation as well as scanner UI and image processing.
A second call must not start another model installation while the first scan owns `pendingPromise`.

When `ocr === false`, Android must not resolve, check, or download an OCR model.

## Error contract

Add the following public error codes:

| Code                                     | Condition                                                                                          |
| ---------------------------------------- | -------------------------------------------------------------------------------------------------- |
| `INVALID_OCR_LANGUAGE`                   | The provided array is empty, contains an empty tag, or contains an invalid BCP 47 identifier.      |
| `OCR_LANGUAGE_NOT_SUPPORTED`             | The active iOS Vision request or Android script resolver cannot serve a valid requested language.  |
| `OCR_LANGUAGE_COMBINATION_NOT_SUPPORTED` | Android received more than one non-Latin script family.                                            |
| `OCR_MODEL_INSTALL_FAILED`               | A required Android Google Play services OCR module could not be installed or was reported unknown. |

These are configuration or capability failures and must reject before camera or gallery UI appears.
They must not be converted into `status: "cancelled"`, `status: "rejected"`, an omitted `ocrText`, or an empty `ocrText`.

An OCR recognition failure after capture retains the existing best-effort behavior unless implementation work discovers that the current public error contract says otherwise.
This specification changes model-selection failures, not post-capture recognition failure semantics.

## Native and JavaScript changes

The implementation is expected to touch the following surfaces:

| Surface                          | Required change                                                                                                                                                |
| -------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `src/types.ts`                   | Add `ocrLanguages`, capability types, and the explicit default language list.                                                                                  |
| `src/NativeReceiptScanner.ts`    | Add the codegen capability method and forward the language array in scan options.                                                                              |
| `src/index.tsx`                  | Export `getOcrCapabilities` and the new public types.                                                                                                          |
| `src/scan.native.tsx`            | Normalize the JS array, preserve defaults, and delegate the capability query.                                                                                  |
| `src/scan.tsx`                   | Return a web capability result consistent with the existing unsupported scan behavior, without claiming native language support.                               |
| Android `build.gradle`           | Add only the dynamically delivered non-default ML Kit recognizer dependencies and regenerate the lockfile only if a JavaScript manifest changes.               |
| Android `ScanOptions.kt`         | Parse and retain language tags.                                                                                                                                |
| Android OCR layer                | Add script resolution, recognizer construction, capability reporting, and model readiness handling while preserving the existing result and rotation pipeline. |
| `ReceiptScannerModule.kt`        | Validate and prepare the selected model before launching UI and expose the capability method.                                                                  |
| iOS `RNScanOptions`              | Parse and retain language tags.                                                                                                                                |
| `RNOcrProcessor`                 | Apply the resolved list to every recognition request and report supported languages.                                                                           |
| `ReceiptScanner.mm`              | Validate language capability before launching UI and expose the capability method.                                                                             |
| `src/__tests__`                  | Cover defaults, normalization, forwarding, exports, and web behavior.                                                                                          |
| Native tests                     | Cover Android script resolution and iOS option parsing where the existing native test harness permits it.                                                      |
| `docs/specs/api-contract.md`     | Document the implemented option, capability method, and errors after code lands.                                                                               |
| `docs/specs/scan-pipeline.md`    | Document language resolution and Android model preparation after code lands.                                                                                   |
| Platform asymmetry documentation | Record iOS language priority versus Android script selection and dynamic model delivery.                                                                       |

The implementation may split Android OCR responsibilities into a resolver and model provider if doing so makes them independently testable.
It must not introduce a generic OCR engine abstraction or provider plug-in system in this phase.

## Compatibility requirements

The following behavior is mandatory:

- Existing applications that omit `ocrLanguages` receive the same default Korean recognizer on Android and the same `["ko-KR", "en-US"]` Vision configuration on iOS.
- `DEFAULT_SCAN_OPTIONS` remains a fully populated `Required<ScanReceiptOptions>` value.
- `ocrFloor: false` continues to disable filtering for every language.
- A custom `ocrFloor` is evaluated against multilingual text using the existing character and line counts without language-specific thresholds.
- `ocr: false` bypasses all language validation and model preparation because the option is irrelevant when OCR does not run.
- Auto-rotation uses the same selected recognizer as the final OCR pass.
- OCR geometry is generated only from the recognizer whose text is returned.
- Existing Korean and English fixture results must not lose recognized non-whitespace text, line count, confidence presence, or valid geometry compared with the baseline captured before implementation.
- No result field is renamed or removed.

## Accuracy and support claims

Engine support is not an accuracy guarantee.
The package may document that a language or script is accepted only when the native provider reports or supplies the capability.
It must not claim that a receipt language is validated without a representative fixture corpus and recorded measurements.

The initial release may ship without foreign-language receipt fixtures.
In that case:

- Korean and English fixtures provide regression evidence for the unchanged default.
- Unit tests provide deterministic evidence for BCP 47 normalization, script resolution, capability responses, error routing, and model-selection behavior.
- Manual device checks provide evidence that one ready model and one dynamically downloaded model complete a real scan.
- Other advertised scripts are labeled provider-supported and uncalibrated.
- Release notes must not claim improved OCR accuracy for uncalibrated languages.

Future fixture additions should be organized by script and source, not by inferred receipt country.
Fixtures must not contain unredacted personal, payment, loyalty, tax, or precise location data.

## Verification

### Automated

The implementation must pass the repository verification pipeline:

```bash
yarn typecheck && yarn lint && yarn test && trunk fmt && trunk check
```

Android native changes additionally require the example Android application to build:

```bash
yarn example android
```

iOS native changes additionally require the example iOS application to build:

```bash
yarn example ios
```

Only one heavy mobile build runs at a time.

### Required test cases

1. Omitted `ocrLanguages` forwards `["ko-KR", "en-US"]`.
2. Duplicate and whitespace-padded tags normalize without changing priority.
3. An empty array and empty tag reject with `INVALID_OCR_LANGUAGE`.
4. `ocr: false` ignores an otherwise invalid or unsupported language list.
5. iOS returns the languages supported by the active accurate Vision request.
6. iOS rejects a valid but unsupported tag before presenting UI.
7. Android resolves Korean plus Latin to Korean.
8. Android resolves Japanese plus Latin to Japanese.
9. Android resolves only Latin languages to Latin.
10. Android rejects two different non-Latin script families.
11. Android reports bundled Korean as ready.
12. Android reports an absent dynamic model as download-required without downloading it.
13. Android scan waits for successful model installation before presenting UI.
14. Android installation failure rejects and releases pending scan state.
15. A second scan during model preparation rejects with `SCAN_IN_PROGRESS`.
16. Existing Korean and English fixtures retain their baseline text, quality, rotation, and geometry behavior.
17. `ocrFloor: false` returns recognized images without threshold filtering.
18. Web capability reporting does not claim native OCR support.

### Manual QA

Manual QA requires physical or emulated platform surfaces that can run the provider:

1. Run a default Korean and English receipt scan on Android and iOS.
2. Confirm the existing OCR text, auto-rotation, optional floor, and optional geometry still behave as before.
3. On Android, clear or use a device without one dynamic OCR model, request a language that resolves to that model, and observe that scanning begins only after installation completes.
4. Repeat the Android request offline and confirm it rejects with `OCR_MODEL_INSTALL_FAILED` instead of returning empty OCR text.
5. On iOS, request one language returned by `getOcrCapabilities()` and complete a scan.
6. On iOS, request one syntactically valid unsupported language and confirm rejection occurs before UI presentation.

## Acceptance criteria

The feature is complete when all of the following are true:

- The additive API is implemented without changing existing result shapes.
- Default Korean and English behavior passes automated regression and manual device checks.
- Consumers can provide ordered BCP 47 hints without importing provider-specific script enums.
- Consumers can inspect current OCR capability without triggering downloads.
- iOS validates against the active Vision request's runtime languages.
- Android selects exactly one script model, permits accompanying Latin text, and rejects unsupported multi-script combinations.
- Android never starts OCR before a required dynamic model is installed.
- OCR-disabled scans do not perform language or model work.
- Documentation distinguishes provider support from measured receipt accuracy.
- Public API and pipeline documentation are updated only when the implementation lands.

## Deferred work

The following require a separate design and are not implementation follow-ups implied by this specification:

- Multi-model OCR and cross-model result merging.
- Automatic script or language detection before recognizer selection on Android.
- Per-language OCR quality floors or calibration defaults.
- Structured receipt or bill extraction.
- Searchable PDF generation.
- Pluggable OCR providers.
- Package or public type renaming.

## Sources

- [ML Kit Text Recognition v2 overview](https://developers.google.com/ml-kit/vision/text-recognition/v2)
- [ML Kit Text Recognition v2 for Android](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)
- [ML Kit Text Recognition v2 supported languages](https://developers.google.com/ml-kit/vision/text-recognition/v2/languages)
- [Google Play services ModuleInstallClient](https://developers.google.com/android/reference/com/google/android/gms/common/moduleinstall/ModuleInstallClient)
- [Apple VNRecognizeTextRequest](https://developer.apple.com/documentation/vision/vnrecognizetextrequest)
- [Apple text recognition sample](https://developer.apple.com/documentation/vision/locating-and-displaying-recognized-text)
