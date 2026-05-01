export type ScanReceiptOptions = {
  source?: "camera" | "gallery";
  maxPages?: number;
  quality?: number;
  includeExif?: boolean;
  includeGpsExif?: boolean;
  ocr?: boolean;
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

export type ReceiptImage = {
  uri: string;
  width: number;
  height: number;
  fileName: string;
  mimeType: "image/jpeg";
  fileSize: number;
  ocrText?: string;
  exif?: ReceiptExif;
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
};
