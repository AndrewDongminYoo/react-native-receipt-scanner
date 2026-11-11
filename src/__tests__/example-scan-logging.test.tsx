/// <reference types="node" />

import { afterEach, beforeEach, describe, expect, it, jest } from "@jest/globals";
import path from "node:path";
import { isValidElement } from "react";
import { scan } from "react-native-receipt-scanner";
import type { ReceiptImage, ScanReceiptResult } from "../types";

const stateWrites = jest.fn<(value: unknown) => void>();
let appMergeOptions: { mergeOcrPages?: boolean; maxPages?: number } = {};

// Exercise the real example handler without mounting native UI or using a device.
// Resolve React from the example workspace, which may have its own peer instance.
jest.doMock(require.resolve("react", { paths: [path.join(__dirname, "../../example")] }), () => ({
  ...jest.requireActual<typeof import("react")>("react"),
  useState: (initial: unknown) => [
    initial !== null && typeof initial === "object" && "mergeOcrPages" in initial
      ? { ...initial, ...appMergeOptions }
      : initial,
    stateWrites,
  ],
  useEffect: jest.fn(),
}));

jest.mock(
  "react-native-receipt-scanner",
  () => ({
    scan: jest.fn(),
    getOcrCapabilities: jest.fn(),
  }),
  { virtual: true }
);

const App =
  jest.requireActual<typeof import("../../example/src/App")>("../../example/src/App").default;

const syntheticImage: ReceiptImage = {
  uri: "file:///synthetic/receipt.jpg",
  width: 100,
  height: 200,
  fileName: "synthetic-receipt.jpg",
  mimeType: "image/jpeg",
  fileSize: 1234,
  imageOrigin: "camera",
  ocrText: "SYNTHETIC RECEIPT\nTOTAL 123",
  ocrQuality: { textLength: 27, lineCount: 2, confidence: 0.9 },
  ocrLines: [{ text: "SYNTHETIC LINE", frame: { x: 0, y: 0, width: 10, height: 10 } }],
  exif: {
    make: "SYNTHETIC DEVICE",
    gps: { latitude: 0, longitude: 0 },
    raw: { ImageDescription: "SYNTHETIC RAW METADATA", GPSLatitude: 0 },
  },
};

const mergedOcr = {
  text: "SYNTHETIC MERGED OCR",
  pageUris: [syntheticImage.uri],
  isComplete: false,
  unmatchedBoundaryIndexes: [],
  rejectedPageIndexes: [0],
};

const results: ScanReceiptResult[] = [
  { status: "success", images: [syntheticImage], rejectedImages: [], mergedOcr },
  {
    status: "success",
    images: [syntheticImage],
    rejectedImages: [syntheticImage, syntheticImage],
    mergedOcr,
  },
  { status: "rejected", images: [], rejectedImages: [syntheticImage], mergedOcr },
  { status: "cancelled", images: [], rejectedImages: [] },
];

describe("example scan logging", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    appMergeOptions = {};
  });

  afterEach(() => {
    jest.restoreAllMocks();
  });

  it.each(results)("logs only status and counts for $status", async (result) => {
    jest.mocked(scan).mockResolvedValueOnce(result);
    const debug = jest.spyOn(console, "debug").mockImplementation(() => {});
    const app = App();
    if (!isValidElement<{ onScan: () => Promise<void> }>(app)) {
      throw new Error("Expected the example scan page");
    }

    await app.props.onScan();

    // Exact arguments exclude images, OCR (including merged text), URIs and EXIF.
    expect(debug.mock.calls).toEqual([
      [
        "[🧾] Scan summary:",
        {
          status: result.status,
          imageCount: result.images.length,
          rejectedImageCount: result.rejectedImages.length,
        },
      ],
    ]);
  });

  it("uses app-owned merging without forwarding the legacy option", async () => {
    appMergeOptions = { mergeOcrPages: true, maxPages: 3 };
    const first = {
      ...syntheticImage,
      uri: "file:///synthetic/first.jpg",
      ocrText: "FIRST PAGE\nROW 100",
    };
    const middle = { ...syntheticImage, uri: "file:///synthetic/middle.jpg", ocrText: "short" };
    const last = {
      ...syntheticImage,
      uri: "file:///synthetic/last.jpg",
      ocrText: "LAST PAGE\nROW 200",
    };
    jest.mocked(scan).mockResolvedValueOnce({
      status: "success",
      images: [last, first],
      rejectedImages: [middle],
      pageUris: [first.uri, middle.uri, last.uri],
    });
    jest.spyOn(console, "debug").mockImplementation(() => {});
    const app = App();
    if (!isValidElement<{ onScan: () => Promise<void> }>(app))
      throw new Error("Expected scan page");
    await app.props.onScan();
    expect(jest.mocked(scan).mock.calls[0]?.[0]).not.toHaveProperty("mergeOcrPages");
    expect(stateWrites.mock.calls).toContainEqual([
      expect.objectContaining({
        mergedOcr: {
          text: "FIRST PAGE\nROW 100\nshort\nLAST PAGE\nROW 200",
          pageUris: [first.uri, middle.uri, last.uri],
          isComplete: false,
          rejectedPageIndexes: [1],
          unmatchedBoundaryIndexes: [0, 1],
        },
      }),
    ]);
  });

  it("reports invalid app merge requests before starting acquisition", async () => {
    appMergeOptions = { mergeOcrPages: true, maxPages: 1 };
    const app = App();
    if (!isValidElement<{ onScan: () => Promise<void> }>(app))
      throw new Error("Expected scan page");
    await app.props.onScan();
    expect(scan).not.toHaveBeenCalled();
    expect(stateWrites.mock.calls).toContainEqual([
      expect.objectContaining({ code: "INVALID_MERGE_OPTION" }),
    ]);
  });
});
