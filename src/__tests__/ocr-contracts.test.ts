import { beforeEach, describe, expect, it, jest } from "@jest/globals";
import { execFileSync, spawnSync } from "node:child_process";
import { mkdtempSync, readFileSync, cpSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import NativeReceiptScanner from "../NativeReceiptScanner";
import { mergeOcrPages } from "../mergeOcrPages";
import { scan } from "../scan.native";
import type { OcrFloor, ReceiptImage } from "../types";
import corpus from "./fixtures/ocr-contracts/v1/contract.json";

jest.mock("../NativeReceiptScanner", () => ({
  __esModule: true,
  default: { scan: jest.fn() },
}));

const native = NativeReceiptScanner as jest.Mocked<typeof NativeReceiptScanner>;
const fixtureDirectory = path.join(__dirname, "fixtures/ocr-contracts/v1");
const checker = path.join(__dirname, "../../scripts/check-ocr-contracts.js");
const pageUri = (index: number): string => `file:///contract/page-${index}.jpg`;

function page(index: number, text: string | null, confidence: number | null = null): ReceiptImage {
  return {
    uri: pageUri(index),
    fileName: `page-${index}.jpg`,
    mimeType: "image/jpeg",
    width: 1,
    height: 1,
    fileSize: 1,
    imageOrigin: "camera",
    ...(text === null ? {} : { ocrText: text }),
    ...(confidence === null ? {} : { ocrQuality: { textLength: 0, lineCount: 0, confidence } }),
  };
}

describe("shared OCR contract v1", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it("verifies the canonical snapshot checksums", () => {
    expect(execFileSync(process.execPath, [checker], { encoding: "utf8" })).toContain(
      "OCR contract v1 verified"
    );
  });

  it("rejects a copied snapshot whose data or manifest drifted", () => {
    const copy = mkdtempSync(path.join(tmpdir(), "receipt-ocr-contract-"));
    try {
      cpSync(fixtureDirectory, copy, { recursive: true });
      execFileSync(process.execPath, [checker, copy]);
      const fixture = path.join(copy, "contract.json");
      const original = readFileSync(fixture, "utf8");
      writeFileSync(fixture, original.replace('"schemaVersion": 1', '"schemaVersion": 2'));
      const changedData = spawnSync(process.execPath, [checker, copy], { encoding: "utf8" });
      expect(changedData.status).not.toBe(0);
      expect(changedData.stderr).toContain("contract.json checksum mismatch");

      writeFileSync(fixture, original);
      const manifest = path.join(copy, "manifest.json");
      writeFileSync(
        manifest,
        readFileSync(manifest, "utf8").replace('"schemaVersion": 1', '"schemaVersion": 2')
      );
      const changedManifest = spawnSync(process.execPath, [checker, copy], { encoding: "utf8" });
      expect(changedManifest.status).not.toBe(0);
      expect(changedManifest.stderr).toContain("manifest differs from the canonical snapshot");
    } finally {
      rmSync(copy, { recursive: true, force: true });
    }
  });

  for (const fixture of corpus.mergeCases) {
    it(`merge: ${fixture.id}`, () => {
      const pages = fixture.pages.map((text, index) => page(index, text));
      if (fixture.expectedError === "RangeError") {
        expect(() => mergeOcrPages(pages, fixture.rejectedPageIndexes)).toThrow(RangeError);
        return;
      }
      expect(mergeOcrPages(pages, fixture.rejectedPageIndexes)).toEqual({
        ...fixture.expected,
        pageUris: pages.map((image) => image.uri),
      });
    });
  }

  for (const fixture of corpus.qualityCases) {
    it(`quality: ${fixture.id}`, async () => {
      const { input } = fixture;
      const pages = input.pages.map((item, index) => page(index, item.text, item.confidence));
      native.scan.mockResolvedValueOnce({
        status: input.status,
        images: pages,
        rejectedImages: [],
      });
      const result = await scan({
        ocr: input.ocr,
        ocrFloor: (input.floor ?? undefined) as OcrFloor | false | undefined,
      });
      const imagesByUri = new Map(
        [...result.images, ...result.rejectedImages].map((image) => [image.uri, image])
      );
      expect({
        status: result.status,
        acceptedPageIndexes: result.images.map((image) =>
          pages.findIndex((p) => p.uri === image.uri)
        ),
        rejectedPageIndexes: result.rejectedImages.map((image) =>
          pages.findIndex((p) => p.uri === image.uri)
        ),
        qualities: pages.map((image) => {
          const quality = imagesByUri.get(image.uri)?.ocrQuality;
          return quality ? { ...quality, confidence: quality.confidence ?? null } : null;
        }),
      }).toEqual(fixture.expected);
    });
  }
});
