/// <reference types="node" />

import { afterEach, beforeEach, describe, expect, it, jest } from "@jest/globals";
import path from "node:path";
import { isValidElement } from "react";
import { scan } from "react-native-receipt-scanner";
import type { ReceiptImage, ScanReceiptResult } from "../types";

// Exercise the real example handler without mounting native UI or using a device.
// Resolve React from the example workspace, which may have its own peer instance.
jest.doMock(require.resolve("react", { paths: [path.join(__dirname, "../../example")] }), () => ({
  ...jest.requireActual<typeof import("react")>("react"),
  useState: (initial: unknown) => [initial, jest.fn()],
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
});
