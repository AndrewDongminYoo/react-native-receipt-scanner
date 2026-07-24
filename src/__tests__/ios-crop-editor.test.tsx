/// <reference types="node" />

import { describe, expect, it } from "@jest/globals";
import fs from "node:fs";
import path from "node:path";

const iosSource = (...segments: string[]) =>
  fs.readFileSync(path.join(__dirname, "..", "..", "ios", ...segments), "utf8");

describe("iOS gallery crop editor", () => {
  it("opens the system photo picker without requesting library authorization", () => {
    const receiptScanner = iosSource("ReceiptScanner.mm");

    expect(receiptScanner).toContain("[[PHPickerConfiguration alloc] init]");
    expect(receiptScanner).not.toContain("requestAuthorization");
    expect(receiptScanner).not.toContain("initWithPhotoLibrary");
  });

  it("starts detected document crops with a wider corner selection", () => {
    const cropEditor = iosSource("RNCropEditorViewController.m");

    expect(cropEditor).toContain("kDetectedCropExpansionFactor");
    expect(cropEditor).toContain("expandedDetectedCornersFromCorners");
    expect(cropEditor).toContain("clampedPoint:");
    expect(cropEditor).toContain("toImageSize:");
  });

  it("shows localized guidance for choosing the document corners", () => {
    const cropEditor = iosSource("RNCropEditorViewController.m");

    expect(cropEditor).toContain("RNReceiptScanner_cropInstruction");
    expect(cropEditor).toContain("Drag the corners to frame the document");
  });
});

describe("iOS scan options", () => {
  it("validates ocrGeometry before reading its boolean value", () => {
    const scanOptions = iosSource("RNScanOptions.m");

    expect(scanOptions).toContain('RNBoolFromValue(dict[@"ocrGeometry"], NO)');
    expect(scanOptions).toContain("[value isKindOfClass:[NSNumber class]]");
  });

  it("class-checks every bridged scalar option, not just ocrGeometry", () => {
    const scanOptions = iosSource("RNScanOptions.m");

    // Numeric options route through the same guarded helpers as the booleans.
    expect(scanOptions).toContain('RNIntegerFromValue(dict[@"maxPages"]');
    expect(scanOptions).toContain('RNDoubleFromValue(dict[@"quality"]');
    expect(scanOptions).toContain('RNBoolFromValue(dict[@"includeExif"]');
    // `source` must be class-checked before -isEqualToString: to avoid a crash
    // when a non-string is bridged in.
    expect(scanOptions).toContain("[src isKindOfClass:[NSString class]]");
  });
});
