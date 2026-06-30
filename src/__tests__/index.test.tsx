import { describe, it, expect, beforeEach, jest } from "@jest/globals";
import { DEFAULT_OCR_FLOOR, DEFAULT_SCAN_OPTIONS } from "../types";
import type { ReceiptImage } from "../types";
import { scan } from "../scan.native";
import NativeReceiptScanner from "../NativeReceiptScanner";

jest.mock("../NativeReceiptScanner", () => ({
  __esModule: true,
  default: {
    scan: jest.fn(),
  },
}));

const mockNative = NativeReceiptScanner as jest.Mocked<typeof NativeReceiptScanner>;

const baseImage = (overrides: Partial<ReceiptImage>): ReceiptImage => ({
  uri: "file:///tmp/receipt.jpg",
  width: 1080,
  height: 1920,
  fileName: "receipt.jpg",
  mimeType: "image/jpeg",
  fileSize: 123456,
  imageOrigin: "camera",
  ...overrides,
});

describe("DEFAULT_SCAN_OPTIONS", () => {
  it("has expected default values", () => {
    expect(DEFAULT_SCAN_OPTIONS).toEqual({
      source: "camera",
      maxPages: 1,
      quality: 0.82,
      includeExif: true,
      includeGpsExif: false,
      ocr: true,
      cropAutoConfirm: false,
      ocrFloor: DEFAULT_OCR_FLOOR,
      autoRotate: true,
      includeRawExif: false,
      minimumTextHeight: 0,
    });
  });
});

describe("DEFAULT_OCR_FLOOR", () => {
  it("uses conservative floor — 12 chars / 2 lines / no confidence threshold", () => {
    expect(DEFAULT_OCR_FLOOR).toEqual({
      minTextLength: 12,
      minLines: 2,
      minConfidence: 0,
    });
  });
});

describe("scan (native)", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it("resolves with native module result when text meets the floor", async () => {
    mockNative.scan.mockResolvedValueOnce({
      status: "success",
      images: [
        baseImage({
          ocrText: "GS25 강남점\n2024-01-15 14:30\n합계 5,400원",
        }),
      ],
    });

    const result = await scan();

    expect(result.status).toBe("success");
    expect(result.images).toHaveLength(1);
    expect(result.images[0]!.ocrQuality).toEqual({
      textLength: 35,
      lineCount: 3,
    });
    expect(result.rejectedImages).toEqual([]);
  });

  it("propagates native module rejection", async () => {
    mockNative.scan.mockRejectedValueOnce(new Error("camera permission denied"));

    await expect(scan()).rejects.toThrow("camera permission denied");
  });

  it("merges provided options with defaults", async () => {
    mockNative.scan.mockResolvedValueOnce({ status: "cancelled", images: [] });

    await scan({ quality: 0.5, ocr: false });

    expect(mockNative.scan).toHaveBeenCalledWith(
      expect.objectContaining({
        source: "camera",
        maxPages: 1,
        quality: 0.5,
        includeExif: true,
        includeGpsExif: false,
        ocr: false,
      })
    );
  });

  it("rejects images that fall below the OCR floor", async () => {
    mockNative.scan.mockResolvedValueOnce({
      status: "success",
      images: [baseImage({ ocrText: "226" })],
    });

    const result = await scan();

    expect(result.status).toBe("rejected");
    expect(result.images).toHaveLength(0);
    expect(result.rejectedImages).toHaveLength(1);
    expect(result.rejectedImages![0]!.ocrQuality).toEqual({
      textLength: 3,
      lineCount: 1,
    });
  });

  it("splits passed and rejected images on partial reject", async () => {
    mockNative.scan.mockResolvedValueOnce({
      status: "success",
      images: [
        baseImage({ uri: "file:///a.jpg", ocrText: "GS25\n2024-01-15\n합계 5400" }),
        baseImage({ uri: "file:///b.jpg", ocrText: "blur" }),
      ],
    });

    const result = await scan();

    expect(result.status).toBe("success");
    expect(result.images).toHaveLength(1);
    expect(result.images[0]!.uri).toBe("file:///a.jpg");
    expect(result.rejectedImages).toHaveLength(1);
    expect(result.rejectedImages![0]!.uri).toBe("file:///b.jpg");
  });

  it("skips floor when ocr is false", async () => {
    mockNative.scan.mockResolvedValueOnce({
      status: "success",
      images: [baseImage({})],
    });

    const result = await scan({ ocr: false });

    expect(result.status).toBe("success");
    expect(result.images).toHaveLength(1);
    expect(result.rejectedImages).toEqual([]);
  });

  it("skips floor when ocrFloor is false", async () => {
    mockNative.scan.mockResolvedValueOnce({
      status: "success",
      images: [baseImage({ ocrText: "226" })],
    });

    const result = await scan({ ocrFloor: false });

    expect(result.status).toBe("success");
    expect(result.images).toHaveLength(1);
    expect(result.images[0]!.ocrQuality).toEqual({ textLength: 3, lineCount: 1 });
    expect(result.rejectedImages).toEqual([]);
  });

  it("honours a custom floor override", async () => {
    mockNative.scan.mockResolvedValueOnce({
      status: "success",
      images: [baseImage({ ocrText: "ABCDE" })],
    });

    const result = await scan({ ocrFloor: { minTextLength: 5, minLines: 1 } });

    expect(result.status).toBe("success");
    expect(result.images).toHaveLength(1);
  });

  it("treats absent OCR confidence as satisfying minConfidence (Android compatibility)", async () => {
    mockNative.scan.mockResolvedValueOnce({
      status: "success",
      images: [baseImage({ ocrText: "GS25 강남점\n합계 5,400원" })],
    });

    const result = await scan({ ocrFloor: { minConfidence: 0.9 } });

    expect(result.status).toBe("success");
    expect(result.images).toHaveLength(1);
  });

  it("passes through cancelled status without floor evaluation", async () => {
    mockNative.scan.mockResolvedValueOnce({ status: "cancelled", images: [] });

    const result = await scan();

    expect(result.status).toBe("cancelled");
    expect(result.images).toHaveLength(0);
    expect(result.rejectedImages).toEqual([]);
  });
});
