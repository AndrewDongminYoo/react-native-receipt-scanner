import { beforeEach, describe, expect, it, jest } from "@jest/globals";
import {
  mergeOcrPagesInApp,
  mergeScanResultInApp,
  validateAppMergeOptions,
} from "../../example/src/receiptMerge";
import NativeReceiptScanner from "../NativeReceiptScanner";
import { scan } from "../scan.native";
import type { ReceiptImage, ScanReceiptResult } from "../types";
import corpus from "./fixtures/ocr-contracts/v1/contract.json";

jest.mock("../NativeReceiptScanner", () => ({
  __esModule: true,
  default: { scan: jest.fn() },
}));
const native = NativeReceiptScanner as jest.Mocked<typeof NativeReceiptScanner>;
const { scan: scanWeb } = jest.requireActual<{ scan: typeof scan }>("../scan.tsx");
const page = (index: number, text: string | null): ReceiptImage => ({
  uri: `file:///app/page-${index}.jpg`,
  fileName: `page-${index}.jpg`,
  mimeType: "image/jpeg",
  width: 1,
  height: 1,
  fileSize: 1,
  imageOrigin: "camera",
  ...(text === null ? {} : { ocrText: text }),
});

describe("app-owned OCR page merge", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  for (const fixture of corpus.mergeCases) {
    it(`preserves shared merge behavior: ${fixture.id}`, () => {
      const pages = fixture.pages.map((text, index) => page(index, text));
      if (fixture.expectedError) {
        expect(() => mergeOcrPagesInApp(pages, fixture.rejectedPageIndexes)).toThrow(RangeError);
      } else {
        expect(mergeOcrPagesInApp(pages, fixture.rejectedPageIndexes)).toEqual({
          ...fixture.expected,
          pageUris: pages.map((image) => image.uri),
        });
      }
    });
  }

  it("retains the JavaScript final-sigma behavior", () => {
    expect(
      mergeOcrPagesInApp([
        page(0, "START\nΟΣ 1000\nTOTAL 1000"),
        page(1, "ος 1000\nTOTAL 1000\nEND"),
      ])
    ).toMatchObject({
      text: "START\nΟΣ 1000\nTOTAL 1000\nEND",
      isComplete: true,
      unmatchedBoundaryIndexes: [],
    });
  });

  it("uses original order rather than accepted/rejected array order", async () => {
    const pages = [
      page(0, "First full page\nSecond full line"),
      page(1, "short"),
      page(2, "Last full page\nAnother full line"),
    ];
    native.scan.mockResolvedValue({ status: "success", images: pages });
    const result = await scan({ mergeOcrPages: false });
    expect(result.mergedOcr).toBeUndefined();
    expect(mergeScanResultInApp(result)).toEqual({
      text: "First full page\nSecond full line\nshort\nLast full page\nAnother full line",
      isComplete: false,
      pageUris: pages.map((image) => image.uri),
      rejectedPageIndexes: [1],
      unmatchedBoundaryIndexes: [0, 1],
    });
    const legacy = await scan({ mergeOcrPages: true, maxPages: 3 });
    expect(mergeScanResultInApp(result)).toEqual(legacy.mergedOcr);
  });

  it("keeps rejected text and diagnostics when every page is rejected", async () => {
    native.scan.mockResolvedValueOnce({
      status: "success",
      images: [page(0, "short"), page(1, null)],
    });
    const result = await scan();
    expect(result.status).toBe("rejected");
    expect(mergeScanResultInApp(result)).toEqual({
      text: "short",
      isComplete: false,
      pageUris: ["file:///app/page-0.jpg", "file:///app/page-1.jpg"],
      rejectedPageIndexes: [0, 1],
      unmatchedBoundaryIndexes: [0],
    });
  });

  it("leaves cancellation without a merged result on native and web", async () => {
    native.scan.mockResolvedValueOnce({ status: "cancelled", images: [] });
    for (const result of [await scan(), await scanWeb()]) {
      expect(result.pageUris).toEqual([]);
      expect(mergeScanResultInApp(result)).toBeUndefined();
    }
  });

  it("does not mutate partition arrays or the supplied original order", () => {
    const first = page(0, "First full page\nSecond full line"),
      last = page(1, "Last full page\nAnother full line");
    const result: ScanReceiptResult = {
      status: "success",
      images: [last, first],
      rejectedImages: [],
      pageUris: [first.uri, last.uri],
    };
    mergeScanResultInApp(result);
    expect(result.images).toEqual([last, first]);
    expect(result.pageUris).toEqual([first.uri, last.uri]);
  });

  it("refuses to guess original order for an older result", () => {
    expect(() =>
      mergeScanResultInApp({ status: "success", images: [page(0, "Text")], rejectedImages: [] })
    ).toThrow(/order/);
  });

  it.each(["duplicate order", "duplicate image", "missing page", "unlisted page"])(
    "reports structural %s rather than dropping content",
    (problem) => {
      const first = page(0, "First full page\nSecond full line"),
        last = page(1, "Last full page\nAnother full line");
      const result: ScanReceiptResult = {
        status: "success",
        images: [first, last],
        rejectedImages: [],
        pageUris: [first.uri, last.uri],
      };
      if (problem === "duplicate order") result.pageUris = [first.uri, first.uri];
      if (problem === "duplicate image") result.rejectedImages = [first];
      if (problem === "missing page") result.images = [last];
      if (problem === "unlisted page") result.pageUris = [first.uri];
      expect(() => mergeScanResultInApp(result)).toThrow(/page|URI/);
    }
  );

  it("preserves invalid merge option error codes in the app", () => {
    for (const [ocr, maxPages] of [
      [false, 2],
      [true, 1],
      [true, NaN],
      [true, Infinity],
      [true, 2.5],
    ] as const) {
      expect(() => validateAppMergeOptions(ocr, maxPages)).toThrow(
        expect.objectContaining({ code: "INVALID_MERGE_OPTION" })
      );
    }
    expect(() => validateAppMergeOptions(true, 2)).not.toThrow();
  });

  it("does not add duplicate rejection to scan when legacy merging is disabled", async () => {
    native.scan.mockResolvedValueOnce({
      status: "success",
      images: [page(0, "same"), page(0, "same")],
    });
    const result = await scan({ ocr: false });
    expect(result.pageUris).toEqual(["file:///app/page-0.jpg", "file:///app/page-0.jpg"]);
    expect(() => mergeScanResultInApp(result)).toThrow(/Duplicate/);
  });
});
