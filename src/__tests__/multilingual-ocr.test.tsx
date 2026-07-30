import { beforeEach, describe, expect, it, jest } from "@jest/globals";
import { DEFAULT_OCR_LANGUAGES } from "../index";
import type {
  AndroidOcrCapabilities,
  IosOcrCapabilities,
  OcrCapabilities,
  OcrModelState,
  WebOcrCapabilities,
} from "../index";
import NativeReceiptScanner from "../NativeReceiptScanner";
import { getOcrCapabilities, scan } from "../scan.native";

jest.mock("../NativeReceiptScanner", () => ({
  __esModule: true,
  default: {
    scan: jest.fn(),
    getOcrCapabilities: jest.fn(),
  },
}));

const mockNative = NativeReceiptScanner as jest.Mocked<typeof NativeReceiptScanner>;
const { getOcrCapabilities: getWebOcrCapabilities } = jest.requireActual<{
  getOcrCapabilities: () => Promise<OcrCapabilities>;
}>("../scan.tsx");

describe("multilingual OCR", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it("exports the Korean and English default language order", () => {
    expect(DEFAULT_OCR_LANGUAGES).toEqual(["ko-KR", "en-US"]);
  });

  it("forwards the Korean and English default language order", async () => {
    mockNative.scan.mockResolvedValueOnce({ status: "cancelled", images: [] });

    await scan();

    expect(mockNative.scan).toHaveBeenCalledWith(
      expect.objectContaining({ ocrLanguages: ["ko-KR", "en-US"] })
    );
  });

  it("uses the default language order when the optional value is undefined", async () => {
    mockNative.scan.mockResolvedValueOnce({ status: "cancelled", images: [] });

    await scan({ ocrLanguages: undefined });

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

  it("returns the typed native OCR capability payload", async () => {
    const expected: IosOcrCapabilities = {
      platform: "ios",
      defaultLanguages: ["ko-KR", "en-US"],
      supportedLanguages: ["en-US", "ko-KR", "es-ES"],
    };
    mockNative.getOcrCapabilities.mockResolvedValueOnce(expected);

    const result: OcrCapabilities = await getOcrCapabilities();

    expect(result).toEqual(expected);
  });

  it("returns the unsupported web capability payload", async () => {
    const expected: WebOcrCapabilities = {
      platform: "web",
      defaultLanguages: ["ko-KR", "en-US"],
      supported: false,
    };

    await expect(getWebOcrCapabilities()).resolves.toEqual(expected);
  });

  it("retains the Android model capability shape in the public contract", () => {
    const model: OcrModelState = { script: "Kore", status: "ready" };
    const capabilities: AndroidOcrCapabilities = {
      platform: "android",
      defaultLanguages: ["ko-KR", "en-US"],
      models: [model],
    };
    const result: OcrCapabilities = capabilities;

    expect(result).toEqual(capabilities);
  });
});
