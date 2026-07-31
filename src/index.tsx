/**
 * Public entry point for `react-native-receipt-scanner`.
 *
 * Re-exports the {@link scan} function and the option/result types. The
 * `.native.tsx` / `.tsx` resolution is handled by Metro automatically — JS
 * callers should not import `./scan.native` or `./scan` directly.
 */
export { getOcrCapabilities, scan } from "./scan";
export type {
  AndroidOcrCapabilities,
  IosOcrCapabilities,
  MergedOcrResult,
  OcrCapabilities,
  ScanReceiptOptions,
  ScanReceiptResult,
  ReceiptImage,
  ReceiptExif,
  ImageOrigin,
  OcrFloor,
  OcrQuality,
  OcrLine,
  OcrModelState,
  WebOcrCapabilities,
} from "./types";
export { DEFAULT_OCR_FLOOR, DEFAULT_OCR_LANGUAGES } from "./types";
