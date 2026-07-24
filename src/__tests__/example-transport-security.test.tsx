/// <reference types="node" />

import { describe, expect, it } from "@jest/globals";
import fs from "node:fs";
import path from "node:path";

const exampleFile = (...segments: string[]) =>
  fs.readFileSync(path.join(__dirname, "..", "..", "example", ...segments), "utf8");

describe("example app transport security", () => {
  it("disables cleartext traffic in Android release builds", () => {
    const buildGradle = exampleFile("android", "app", "build.gradle");
    const buildTypes = buildGradle.slice(buildGradle.indexOf("    buildTypes {"));
    const releaseBuildType = buildTypes.match(/release \{[\s\S]*?\n {8}}/)?.[0] ?? "";

    expect(releaseBuildType).toContain('manifestPlaceholders = [usesCleartextTraffic: "false"]');
  });

  it("disables arbitrary network loads in iOS builds", () => {
    const infoPlist = exampleFile("ios", "ReceiptScannerExample", "Info.plist");

    expect(infoPlist).toMatch(/<key>NSAllowsArbitraryLoads<\/key>\s*<false\/>/);
  });
});
