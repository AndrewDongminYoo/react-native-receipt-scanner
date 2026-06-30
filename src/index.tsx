/**
 * Public entry point for `react-native-receipt-scanner`.
 *
 * Re-exports the {@link scan} function and the option/result types. The
 * `.native.tsx` / `.tsx` resolution is handled by Metro automatically — JS
 * callers should not import `./scan.native` or `./scan` directly.
 */
export { scan } from "./scan";
export type {
  ScanReceiptOptions,
  ScanReceiptResult,
  ReceiptImage,
  ReceiptExif,
  ImageOrigin,
  OcrFloor,
  OcrQuality,
} from "./types";
export { DEFAULT_OCR_FLOOR } from "./types";
