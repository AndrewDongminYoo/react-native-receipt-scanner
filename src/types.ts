export type AndroidCameraOptions = {
  /** Allow the user to import from the photo library inside the GMS Document Scanner UI.
   *  When false (default) the scanner is camera-only; gallery access goes through the
   *  custom CropEditorActivity launched by source:"gallery". */
  allowGalleryImport?: boolean;
  /** GMS Document Scanner mode. "full" enables all ML-enhanced features; "base" is lighter.
   *  Defaults to "full". */
  scannerMode?: "full" | "base";
};

export type ScanReceiptOptions = {
  source?: "camera" | "gallery";
  maxPages?: number;
  quality?: number;
  includeExif?: boolean;
  includeGpsExif?: boolean;
  ocr?: boolean;
  cropAutoConfirm?: boolean;
  /** Android-only options forwarded to GmsDocumentScannerOptions. Ignored on iOS. */
  androidCameraOptions?: AndroidCameraOptions;
};

export type ReceiptExif = {
  orientation?: number;
  dateTimeOriginal?: string;
  make?: string;
  model?: string;
  gps?: {
    latitude: number;
    longitude: number;
  };
};

export type ImageOrigin = "camera" | "screenshot" | "download" | "unknown";

export type ReceiptImage = {
  uri: string;
  width: number;
  height: number;
  fileName: string;
  mimeType: "image/jpeg";
  fileSize: number;
  ocrText?: string;
  exif?: ReceiptExif;
  imageOrigin: ImageOrigin;
};

export type ScanReceiptResult = {
  status: "success" | "cancelled";
  images: ReceiptImage[];
};

export const DEFAULT_SCAN_OPTIONS: Required<ScanReceiptOptions> = {
  source: "camera",
  maxPages: 1,
  quality: 0.82,
  includeExif: true,
  includeGpsExif: false,
  ocr: true,
  cropAutoConfirm: false,
  androidCameraOptions: {},
};
