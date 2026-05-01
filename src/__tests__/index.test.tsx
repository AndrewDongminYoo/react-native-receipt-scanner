import { describe, it, expect, beforeEach, jest } from "@jest/globals";
import { DEFAULT_SCAN_OPTIONS } from "../types";
import { scan } from "../scan.native";
import NativeReceiptScanner from "../NativeReceiptScanner";

jest.mock("../NativeReceiptScanner", () => ({
  __esModule: true,
  default: {
    scan: jest.fn(),
  },
}));

const mockNative = NativeReceiptScanner as jest.Mocked<typeof NativeReceiptScanner>;

describe("DEFAULT_SCAN_OPTIONS", () => {
  it("has expected default values", () => {
    expect(DEFAULT_SCAN_OPTIONS).toEqual({
      source: "camera",
      maxPages: 1,
      quality: 0.82,
      includeExif: true,
      includeGpsExif: false,
      ocr: true,
    });
  });
});

describe("scan (native)", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it("resolves with native module result", async () => {
    const mockResult = {
      status: "success",
      images: [
        {
          uri: "file:///tmp/receipt.jpg",
          width: 1080,
          height: 1920,
          fileName: "receipt.jpg",
          mimeType: "image/jpeg",
          fileSize: 123456,
        },
      ],
    };
    mockNative.scan.mockResolvedValueOnce(mockResult);

    const result = await scan();

    expect(result.status).toBe("success");
    expect(result.images).toHaveLength(1);
    expect(result.images[0]!.uri).toBe("file:///tmp/receipt.jpg");
  });

  it("applies default options when none provided", async () => {
    mockNative.scan.mockResolvedValueOnce({ status: "cancelled", images: [] });

    await scan();

    expect(mockNative.scan).toHaveBeenCalledWith(
      expect.objectContaining({
        source: "camera",
        maxPages: 1,
        quality: 0.82,
        includeExif: true,
        includeGpsExif: false,
        ocr: true,
      })
    );
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
});
