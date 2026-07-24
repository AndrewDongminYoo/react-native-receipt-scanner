/// <reference types="node" />

import { describe, expect, it } from "@jest/globals";
import fs from "node:fs";
import path from "node:path";

const iosSource = (...segments: string[]) =>
  fs.readFileSync(path.join(__dirname, "..", "..", "ios", ...segments), "utf8");

describe("iOS gallery crop editor", () => {
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

  it("only submits document segmentation requests on supported iOS versions", () => {
    const galleryPicker = iosSource("RNGalleryPickerDelegate.m");

    expect(galleryPicker).toContain("if (@available(iOS 17.0, *))");
    expect(galleryPicker).toContain("performRequests:@[docRequest, rectRequest]");
    expect(galleryPicker).toContain("performRequests:@[rectRequest]");
  });
});
