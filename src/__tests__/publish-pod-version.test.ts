/// <reference types="node" />

import { afterEach, beforeEach, describe, expect, it } from "@jest/globals";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

const repoRoot = path.join(__dirname, "..", "..");
const packageJson: { version: string; scripts: Record<string, string> } = JSON.parse(
  fs.readFileSync(path.join(repoRoot, "package.json"), "utf8")
);
let fixtureRoot: string;

beforeEach(() => {
  fixtureRoot = fs.mkdtempSync(path.join(os.tmpdir(), "receipt-scanner-publish-"));
  fs.mkdirSync(path.join(fixtureRoot, "scripts"));
  fs.mkdirSync(path.join(fixtureRoot, "example", "ios"), { recursive: true });
  fs.copyFileSync(
    path.join(repoRoot, "scripts", "check-pod-version.js"),
    path.join(fixtureRoot, "scripts", "check-pod-version.js")
  );
  fs.writeFileSync(path.join(fixtureRoot, "package.json"), JSON.stringify(packageJson));
});

afterEach(() => {
  fs.rmSync(fixtureRoot, { recursive: true });
});

const writeLockfile = (contents: string) =>
  fs.writeFileSync(path.join(fixtureRoot, "example", "ios", "Podfile.lock"), contents);

const runGuard = () =>
  spawnSync(process.execPath, [path.join(fixtureRoot, "scripts", "check-pod-version.js")], {
    encoding: "utf8",
    cwd: os.tmpdir(),
  });

describe("publish Podfile.lock version guard", () => {
  it("accepts the matching version in the PODS section, including prereleases", () => {
    fs.writeFileSync(
      path.join(fixtureRoot, "package.json"),
      JSON.stringify({ ...packageJson, version: "1.0.0-rc.1" })
    );
    writeLockfile("PODS:\r\n  - ReceiptScanner (1.0.0-rc.1):\r\n    - React-Core\r\n");

    const result = runGuard();
    expect(result.status).toBe(0);
    expect(result.stdout).toContain("ReceiptScanner version matches package.json: 1.0.0-rc.1");
  });

  it("rejects a stale ReceiptScanner version", () => {
    writeLockfile("PODS:\n  - ReceiptScanner (0.0.0):\n    - React-Core\n");

    const result = runGuard();
    expect(result.status).toBe(1);
    expect(result.stderr).toContain(
      `ReceiptScanner version mismatch: package.json=${packageJson.version}, Podfile.lock=0.0.0`
    );
  });

  it("rejects a missing PODS entry even when DEPENDENCIES contains ReceiptScanner", () => {
    writeLockfile(
      `PODS:\n  - React-Core (0.85.2)\n\nDEPENDENCIES:\n  - ReceiptScanner (${packageJson.version})\n`
    );

    const result = runGuard();
    expect(result.status).toBe(1);
    expect(result.stderr).toContain(
      "Expected exactly one ReceiptScanner entry in the PODS section"
    );
  });

  it("rejects duplicate ReceiptScanner PODS entries", () => {
    writeLockfile(
      `PODS:\n  - ReceiptScanner (${packageJson.version}):\n  - ReceiptScanner (0.0.0):\n`
    );

    expect(runGuard().status).toBe(1);
  });

  it("rejects a missing lockfile", () => {
    const result = runGuard();
    expect(result.status).toBe(1);
    expect(result.stderr).toContain("Podfile.lock");
  });

  it("blocks the actual prepublishOnly command when the lockfile is stale", () => {
    writeLockfile("PODS:\n  - ReceiptScanner (0.0.0):\n");

    const result = spawnSync(process.execPath, ["--run", "prepublishOnly"], {
      cwd: fixtureRoot,
      encoding: "utf8",
    });
    expect(result.status).toBe(1);
    expect(result.stderr).toContain("ReceiptScanner version mismatch");
  });

  it("allows the actual prepublishOnly command when versions match", () => {
    writeLockfile(`PODS:\n  - ReceiptScanner (${packageJson.version}):\n`);

    const result = spawnSync(process.execPath, ["--run", "prepublishOnly"], {
      cwd: fixtureRoot,
      encoding: "utf8",
    });
    expect(result.status).toBe(0);
    expect(result.stdout).toContain(
      `ReceiptScanner version matches package.json: ${packageJson.version}`
    );
  });

  it("checks the repository's current package and lockfile", () => {
    const result = spawnSync(
      process.execPath,
      [path.join(repoRoot, "scripts", "check-pod-version.js")],
      {
        encoding: "utf8",
      }
    );
    expect(result.stderr).toBe("");
    expect(result.status).toBe(0);
  });
});
