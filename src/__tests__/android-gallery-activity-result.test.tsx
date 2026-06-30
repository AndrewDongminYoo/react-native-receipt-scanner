/// <reference types="node" />

import { describe, expect, it } from "@jest/globals";
import fs from "node:fs";
import path from "node:path";

const androidSource = (...segments: string[]) =>
  fs.readFileSync(
    path.join(
      __dirname,
      "..",
      "..",
      "android",
      "src",
      "main",
      "java",
      "com",
      "receiptscanner",
      ...segments
    ),
    "utf8"
  );

const androidRes = (...segments: string[]) =>
  fs.readFileSync(
    path.join(__dirname, "..", "..", "android", "src", "main", "res", ...segments),
    "utf8"
  );

describe("Android gallery picker activity result flow", () => {
  it("uses Activity Result API instead of nested startActivityForResult for gallery picking", () => {
    const cropEditorActivity = androidSource("CropEditorActivity.kt");

    expect(cropEditorActivity).toContain("registerForActivityResult");
    expect(cropEditorActivity).toContain("ActivityResultContracts.PickMultipleVisualMedia");
    expect(cropEditorActivity).not.toContain("startActivityForResult(pickIntent");
    expect(cropEditorActivity).not.toContain("override fun onActivityResult");
  });

  it("does not launch the gallery picker directly from onCreate", () => {
    const cropEditorActivity = androidSource("CropEditorActivity.kt");
    const onCreateBody =
      cropEditorActivity.match(/override fun onCreate[\s\S]*?\n {2}}/)?.[0] ?? "";

    expect(onCreateBody).not.toContain("launchGalleryPicker()");
    expect(cropEditorActivity).toContain("override fun onPostResume()");
    expect(cropEditorActivity).toContain("launchGalleryPicker()");
  });
});

describe("Android gallery crop editor", () => {
  it("starts detected document crops with a wider corner selection", () => {
    const cropEditorActivity = androidSource("CropEditorActivity.kt");

    expect(cropEditorActivity).toContain("DETECTED_CROP_EXPANSION_FACTOR");
    expect(cropEditorActivity).toContain("expandedDetectedCorners");
    expect(cropEditorActivity).toContain("clampedToImageRect");
  });

  it("shows localized guidance for choosing the document corners", () => {
    const cropEditorActivity = androidSource("CropEditorActivity.kt");
    const koreanStrings = androidRes("values", "strings.xml");
    const englishStrings = androidRes("values-en", "strings.xml");

    expect(cropEditorActivity).toContain("RNReceiptScanner_cropInstruction");
    expect(cropEditorActivity).toContain("TextView(this)");
    expect(koreanStrings).toContain("문서의 네 모서리를 맞춰 주세요");
    expect(englishStrings).toContain("Drag the corners to frame the document");
  });
});
