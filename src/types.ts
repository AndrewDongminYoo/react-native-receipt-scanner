/**
 * Acceptance thresholds applied to OCR output. When any field falls below its
 * floor, the corresponding image is moved from `images` into `rejectedImages`.
 *
 * @see {@link DEFAULT_OCR_FLOOR} for the package-default values.
 * @see {@link ScanReceiptOptions.ocrFloor} for how to override per call.
 */
export type OcrFloor = {
  /** Minimum trimmed text length (in characters). Defaults to 12. */
  minTextLength?: number;
  /** Minimum non-empty line count. Defaults to 2. */
  minLines?: number;
  /**
   * Minimum mean OCR confidence (0.0–1.0). Defaults to 0.
   * Populated on both platforms when OCR runs — iOS from Vision's per-observation
   * confidence, Android from ML Kit's per-line `getConfidence()`.
   * It can still be undefined (OCR disabled, or no text recognized); in that case
   * this threshold is treated as satisfied so the gate never penalizes a missing
   * field. Confidence remains reporting-only — not a cross-platform enforcement
   * signal until its distributions are validated comparable.
   */
  minConfidence?: number;
};

/**
 * Options accepted by {@link scan}. Every field is optional — missing fields
 * are filled from {@link DEFAULT_SCAN_OPTIONS} before being forwarded to the
 * native module.
 */
export type ScanReceiptOptions = {
  /**
   * Acquisition path. `"camera"` opens the platform document scanner
   * (VisionKit on iOS, ML Kit GMS scanner on Android). `"gallery"` opens
   * a system photo picker followed by the in-package crop editor.
   *
   * @defaultValue `"camera"`
   */
  source?: "camera" | "gallery";
  /**
   * Maximum number of pages the user is allowed to capture. Clamped to
   * `1..10` natively on both platforms (values above 10 are capped to bound
   * memory use). The camera path enforces this as a page limit; the gallery
   * path enforces it as a multi-select limit.
   *
   * @defaultValue `1`
   */
  maxPages?: number;
  /**
   * JPEG compression quality in `[0.0, 1.0]`. Translated to a 1–100 scale on
   * Android and to `kCGImageDestinationLossyCompressionQuality` on iOS.
   *
   * @defaultValue `0.82`
   */
  quality?: number;
  /**
   * Read EXIF / TIFF / GPS metadata from the source image and attach the
   * normalized white-list to {@link ReceiptImage.exif}. When `false`, the
   * `exif` field is omitted entirely.
   *
   * @defaultValue `true`
   */
  includeExif?: boolean;
  /**
   * Copy the GPS dictionary onto `exif.gps` (and into `exif.raw` when raw
   * passthrough is enabled). The package never reads `CLLocationManager`;
   * this only forwards GPS already embedded in the source image.
   *
   * @defaultValue `false`
   */
  includeGpsExif?: boolean;
  /**
   * Run on-device OCR (Vision `VNRecognizeTextRequest` on iOS, ML Kit
   * Korean recognizer on Android) and attach the joined text to
   * {@link ReceiptImage.ocrText}. Required for {@link ocrFloor} and
   * {@link autoRotate} — without OCR there is no signal to act on.
   *
   * @defaultValue `true`
   */
  ocr?: boolean;
  /**
   * Ordered BCP 47 language hints for on-device OCR. The first entry has the
   * highest priority on platforms that support ordered languages. Empty arrays
   * and empty entries are invalid when OCR is enabled.
   *
   * @defaultValue `["ko-KR", "en-US"]`
   */
  ocrLanguages?: readonly string[];
  /**
   * Skip the in-package crop editor when the document detector reports a
   * high-confidence quadrilateral. Effective only for `source: "gallery"`
   * — the camera path uses the platform scanner's own confirm UI.
   *
   * @defaultValue `false`
   */
  cropAutoConfirm?: boolean;
  /**
   * Reject images whose OCR result falls below the floor. Pass `false` to
   * disable the check, or an `OcrFloor` object to override defaults. The
   * floor only applies when `ocr === true` — there is nothing to measure
   * otherwise.
   *
   * @defaultValue {@link DEFAULT_OCR_FLOOR}
   */
  ocrFloor?: OcrFloor | false;
  /**
   * Detect 90° / 180° / 270° content rotation via OCR confidence and rotate
   * the output JPEG pixels to the natural upright orientation. Effective
   * only when `ocr === true` (the OCR pass is what supplies the rotation
   * signal). When disabled, OCR text is still corrected for 180° flips but
   * pixels are not rotated.
   *
   * @see `docs/specs/ocr-orientation-correction.md` for the algorithm.
   * @defaultValue `true`
   */
  autoRotate?: boolean;
  /**
   * Include the full raw EXIF / TIFF / GPS dictionary on each image's
   * `exif.raw` field. Off by default to keep IPC payloads small; enable when
   * the consumer needs platform-specific or domain-specific tags beyond the
   * cross-platform white-list. Binary fields (Thumbnail, MakerNote, etc.)
   * are always excluded.
   *
   * GPS keys are excluded from `raw` whenever `includeGpsExif === false`.
   * Effective only when `includeExif === true`.
   *
   * @defaultValue `false`
   */
  includeRawExif?: boolean;
  /**
   * **iOS only.** Vision `minimumTextHeight` as a fraction of image height in
   * `(0, 1]` — text shorter than this is skipped during recognition. Lowering
   * it (e.g. `0.02`) can recover small receipt line items at the cost of more
   * noise. `0` uses the package default (1/32). Android (ML Kit) has no
   * equivalent and ignores this field.
   *
   * @defaultValue `0` (package default, ≈ 1/32)
   */
  minimumTextHeight?: number;
  /**
   * Attach per-line OCR bounding boxes to {@link ReceiptImage.ocrLines}.
   * Effective only when `ocr === true` — without a recognition pass there is
   * no geometry to report. Off by default because a long receipt can carry
   * hundreds of lines and most consumers only need {@link ReceiptImage.ocrText}.
   *
   * Enable it to draw text-region overlays on the returned image.
   *
   * @see `docs/specs/ocr-line-geometry.md` for the coordinate contract.
   * @defaultValue `false`
   */
  ocrGeometry?: boolean;
};

/**
 * Per-image EXIF metadata. The white-list fields are populated when the
 * platform exposes them (iOS via ImageIO `kCGImageProperty*`, Android via
 * `androidx.exifinterface.media.ExifInterface`). Values are normalized
 * across platforms — ISO is reported as a single number, exposure time as
 * seconds, etc.
 *
 * Reach into `raw` (with `includeRawExif: true`) for tags outside the
 * white-list. Note that `raw.Orientation` is the *original* EXIF value
 * whereas the white-list `orientation` is always `1` (output pixels are
 * orientation-normalized).
 */
export type ReceiptExif = {
  // ── Image metadata ──
  /** Always `1` — output pixels are orientation-normalized. See `raw.Orientation` for the original value. */
  orientation?: number;
  /** EXIF `ColorSpace` tag. `1` = sRGB, `65535` = uncalibrated. */
  colorSpace?: number;
  /** EXIF `LightSource` tag. Numeric enum per the EXIF specification. */
  lightSource?: number;
  /** EXIF version string, e.g. `"0220"` or `"0231"`. */
  exifVersion?: string;

  // ── Device + software ──
  /** TIFF `Make` tag — manufacturer string (e.g. `"Apple"`, `"samsung"`). */
  make?: string;
  /** TIFF `Model` tag — device model string (e.g. `"iPhone 15 Pro"`). */
  model?: string;
  /**
   * TIFF Software tag.
   * - iOS camera: the OS version, e.g. `"17.0"`, `"26.4.2"`.
   * - Android camera: usually a vendor / firmware identifier
   *   (e.g. `"MIUI Camera"`, `"F741NKSS3CZCS"`).
   * - Editors / generators: their own name (e.g. `"Photoshop 24.0"`).
   * - Screenshots: usually absent on both platforms.
   *
   * Use the *value* — not mere presence — for fraud filtering.
   */
  software?: string;

  // ── Timestamps ──
  /** TIFF `DateTime` tag (file modification time). Format `"yyyy:MM:dd HH:mm:ss"`. */
  dateTime?: string;
  /** EXIF `DateTimeOriginal` tag (shutter moment). Strongest "this came from a camera" signal. */
  dateTimeOriginal?: string;
  /** EXIF `DateTimeDigitized` tag (digitization moment). */
  dateTimeDigitized?: string;

  // ── Camera settings ──
  /** Exposure time in seconds (e.g. `0.0167` for 1/60s). */
  exposureTime?: number;
  /** Aperture f-number (e.g. `1.8`). */
  fNumber?: number;
  /** ISO sensitivity, normalized to a single number across platforms. */
  iso?: number;
  /** Focal length in millimeters. */
  focalLength?: number;
  /** EXIF `Flash` tag. Bitfield per the EXIF specification. */
  flash?: number;
  /** EXIF `WhiteBalance` tag. `0` = auto, `1` = manual. */
  whiteBalance?: number;
  /** EXIF `ExposureMode` tag. `0` = auto, `1` = manual, `2` = auto-bracket. */
  exposureMode?: number;
  /** EXIF `ExposureProgram` tag. Numeric enum (e.g. `2` = normal program). */
  exposureProgram?: number;
  /** EXIF `MeteringMode` tag. Numeric enum per the EXIF specification. */
  meteringMode?: number;

  // ── GPS (populated only when includeGpsExif === true) ──
  /**
   * GPS coordinates copied from the source image. Latitude and longitude are
   * signed decimals (negative = S/W). Populated only when
   * {@link ScanReceiptOptions.includeGpsExif} is `true` *and* the source had
   * GPS metadata.
   */
  gps?: {
    /** Signed decimal degrees (negative = south). */
    latitude: number;
    /** Signed decimal degrees (negative = west). */
    longitude: number;
    /** Meters above sea level; negative = below. */
    altitude?: number;
    /** UTC GPS timestamp string. */
    timestamp?: string;
    /** Speed over ground (units come from the source — usually km/h). */
    speed?: number;
    /** Image direction or destination bearing in degrees, whichever the source had. */
    heading?: number;
  };

  /**
   * Raw EXIF / TIFF / GPS attributes the platform exposes, flattened into a
   * single map keyed by standard EXIF tag name (e.g. `"Make"`, `"Software"`,
   * `"FNumber"`, `"ExposureTime"`, `"GPSLatitude"`). Populated only when
   * `options.includeRawExif === true`. GPS-prefixed keys are absent unless
   * `options.includeGpsExif === true`. Binary fields are excluded.
   */
  raw?: Record<string, string | number | Array<string | number>>;
};

/**
 * Best-effort classification of where the image came from.
 *
 * - `"camera"` — captured by the device camera (strong EXIF signal).
 * - `"screenshot"` — system screenshot. **Android only**: detected from the
 *   MediaStore bucket name. iOS cannot report this — the gallery flow is a
 *   permissionless `PHPickerViewController` with no PHAsset subtype signal, so
 *   EXIF-less images (screenshots included) are classified as `"download"`.
 * - `"download"` — saved from a network source; no camera-style EXIF.
 * - `"unknown"` — no determinative signal available.
 *
 * Used by consumers as a coarse fraud / source filter. iOS classifies from
 * EXIF only (`"camera"` / `"download"` / `"unknown"`); Android adds the
 * MediaStore bucket name (also yielding `"screenshot"`). See the
 * `imageOrigin` platform behavior table in `docs/specs/api-contract.md`.
 */
export type ImageOrigin = "camera" | "screenshot" | "download" | "unknown";

/**
 * Quality metrics derived from the raw OCR result. Populated whenever OCR
 * ran (`options.ocr === true`). Use to layer custom acceptance rules on top
 * of the package's `ocrFloor` gate.
 */
export type OcrQuality = {
  /** Character count of the trimmed `ocrText`. */
  textLength: number;
  /** Number of non-empty lines in `ocrText`. */
  lineCount: number;
  /**
   * Mean OCR confidence (0.0–1.0) across recognized lines/observations.
   * Populated on both platforms when OCR runs — iOS from Vision, Android from
   * ML Kit per-line `getConfidence()`. Undefined when OCR is disabled
   * or no text was recognized.
   */
  confidence?: number;
};

/**
 * One recognized text line and where it sits on the returned image.
 * Populated only when {@link ScanReceiptOptions.ocrGeometry} is `true`.
 *
 * `frame` is expressed in the **output JPEG's pixel space** — the same space
 * as {@link ReceiptImage.width} / {@link ReceiptImage.height} — with a
 * top-left origin, so an overlay only needs the displayed-to-actual scale
 * factor. Frames are clamped to the image bounds by the native layer.
 */
export type OcrLine = {
  /** Recognized text for this line. Never empty — blank lines are dropped. */
  text: string;
  /** Axis-aligned bounding box in output-image pixels, top-left origin. */
  frame: { x: number; y: number; width: number; height: number };
  /** Per-line confidence in `[0, 1]`, when the platform reports one. */
  confidence?: number;
};

/**
 * One image returned by {@link scan}. Backed by a JPEG file in the app
 * cache directory; the `uri` is stable until the next `scan()` call (which
 * deletes prior session files) and does not survive app restarts.
 */
export type ReceiptImage = {
  /** `file://`-scheme URI to the cached JPEG. Percent-encoded by the native layer. */
  uri: string;
  /** Pixel width of the output (post-rotation, post-crop). */
  width: number;
  /** Pixel height of the output (post-rotation, post-crop). */
  height: number;
  /** File name segment of `uri` (e.g. `"receipt_1700000000000.jpg"`). */
  fileName: string;
  /** Always `"image/jpeg"` — the package only emits JPEG today. */
  mimeType: "image/jpeg";
  /** File size in bytes (read post-encoding). */
  fileSize: number;
  /** OCR text joined by newlines. Present only when {@link ScanReceiptOptions.ocr} is `true`. */
  ocrText?: string;
  /** Derived OCR quality metrics. Present whenever OCR ran. */
  ocrQuality?: OcrQuality;
  /**
   * Per-line OCR geometry in output-image pixel space. Present only when both
   * {@link ScanReceiptOptions.ocr} and {@link ScanReceiptOptions.ocrGeometry}
   * are `true`. Lines the platform could not place (no bounding box, or a box
   * that clamps to zero area) are omitted, so this does not line up index-wise
   * with `ocrText` — read {@link OcrLine.text} for each box instead.
   */
  ocrLines?: OcrLine[];
  /** EXIF white-list. Present only when {@link ScanReceiptOptions.includeExif} is `true`. */
  exif?: ReceiptExif;
  /** Origin classification — see {@link ImageOrigin}. Always present. */
  imageOrigin: ImageOrigin;
};

/**
 * Result returned by {@link scan}. Status is the primary discriminator;
 * `images` and `rejectedImages` are always arrays for interface symmetry.
 */
export type ScanReceiptResult = {
  /**
   * - `"success"` — the user completed the scan and at least one image
   *   passed the OCR floor (or no floor was applied).
   * - `"cancelled"` — the user dismissed the scanner / picker.
   * - `"rejected"` — the user completed the scan but every image fell
   *   below the OCR floor. `images` is empty; `rejectedImages` carries
   *   the offending captures so the consumer can prompt the user.
   */
  status: "success" | "cancelled" | "rejected";
  /** Images that passed the OCR floor (or all images, when no floor applied). */
  images: ReceiptImage[];
  /**
   * Images that were captured but did not meet the OCR floor. Always an
   * array (empty when nothing was rejected) for interface symmetry with
   * `images` — consumers can read `result.rejectedImages.length` without
   * a null-check.
   */
  rejectedImages: ReceiptImage[];
};

/** Languages used for OCR when callers do not provide an override. */
export const DEFAULT_OCR_LANGUAGES = ["ko-KR", "en-US"] as const;

/** Runtime availability of one native OCR script model. */
export type OcrModelState = {
  /** Unicode script identifier such as "Latn", "Kore", "Jpan", "Hans", "Hant", or "Deva". */
  readonly script: string;
  /** Whether recognition can run immediately or requires a model download. */
  readonly status: "ready" | "download-required";
};

/** OCR capability reported by the active iOS Vision request configuration. */
export type IosOcrCapabilities = {
  readonly platform: "ios";
  readonly defaultLanguages: readonly ["ko-KR", "en-US"];
  /** Exact identifiers returned by the active Vision request revision and accurate recognition level. */
  readonly supportedLanguages: readonly string[];
};

/** OCR capability reported by the Android ML Kit integration. */
export type AndroidOcrCapabilities = {
  readonly platform: "android";
  readonly defaultLanguages: readonly ["ko-KR", "en-US"];
  /** Script capabilities exposed by the installed package version, not a package-defined country list. */
  readonly models: readonly OcrModelState[];
};

/** Capability reported by the unsupported web fallback. */
export type WebOcrCapabilities = {
  readonly platform: "web";
  readonly defaultLanguages: readonly ["ko-KR", "en-US"];
  /** The web fallback does not provide native OCR. */
  readonly supported: false;
};

/** Platform-specific OCR capability result. */
export type OcrCapabilities = IosOcrCapabilities | AndroidOcrCapabilities | WebOcrCapabilities;

/**
 * Package-default OCR floor, applied when {@link ScanReceiptOptions.ocrFloor}
 * is omitted. Override per-call by passing a partial `OcrFloor` — missing
 * fields fall back to these values.
 */
export const DEFAULT_OCR_FLOOR: Required<OcrFloor> = {
  minTextLength: 12,
  minLines: 2,
  minConfidence: 0,
};

/**
 * Package defaults applied to {@link ScanReceiptOptions} before delegation
 * to the native module. Marked `Required<>` because every field has a value
 * after merging — useful when forwarding options over the bridge.
 */
export const DEFAULT_SCAN_OPTIONS: Required<ScanReceiptOptions> = {
  source: "camera",
  maxPages: 1,
  quality: 0.82,
  includeExif: true,
  includeGpsExif: false,
  ocr: true,
  ocrLanguages: DEFAULT_OCR_LANGUAGES,
  cropAutoConfirm: false,
  ocrFloor: DEFAULT_OCR_FLOOR,
  autoRotate: true,
  includeRawExif: false,
  minimumTextHeight: 0,
  ocrGeometry: false,
};
