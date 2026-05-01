import { describe, it, expect } from "@jest/globals";
import { DEFAULT_SCAN_OPTIONS } from "../types";

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
