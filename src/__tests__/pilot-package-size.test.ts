/// <reference types="node" />

import { afterEach, beforeEach, expect, it } from "@jest/globals";
import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

let fixture: string;
const script = path.resolve(__dirname, "../../pilot/package-size.py");
beforeEach(() => {
  fixture = fs.mkdtempSync(path.join(os.tmpdir(), "receipt-size-"));
});
afterEach(() => fs.rmSync(fixture, { recursive: true }));

function archive(name: string, bytes: number, extra = "") {
  const target = path.join(fixture, `${name}.tgz`);
  const result = spawnSync(
    "python3",
    [
      "-c",
      `import io, json, sys, tarfile
with tarfile.open(sys.argv[1], 'w:gz') as tar:
    for name, data in [('package/package.json', b'{"name":"react-native-receipt-scanner"}'), ('package/src/payload', b'x' * int(sys.argv[2]))]:
        info = tarfile.TarInfo(name)
        info.size = len(data)
        tar.addfile(info, io.BytesIO(data))
    if sys.argv[3]:
        info = tarfile.TarInfo(sys.argv[3])
        if sys.argv[3] == 'package/link':
            info.type = tarfile.SYMTYPE
            info.linkname = '/tmp/outside'
            tar.addfile(info)
        else:
            info.size = 1
            tar.addfile(info, io.BytesIO(b'x'))
`,
      target,
      String(bytes),
      extra,
    ],
    { encoding: "utf8" }
  );
  if (result.status !== 0) throw new Error(result.stderr);
  return target;
}
function compare(baseline: string, candidate: string) {
  return spawnSync("python3", [script, baseline, candidate], { encoding: "utf8" });
}

it.each([262143, 262144, 262145])("enforces the unpacked budget at %i bytes", (growth) => {
  const baseline = archive("baseline", 16);
  const candidate = archive("candidate", 16 + growth);
  const result = compare(baseline, candidate);
  // Missing tooling must not look like an intentional budget rejection.
  expect(result.stderr).toBe("");
  expect(result.status).toBe(growth <= 262144 ? 0 : 1);
  const report = JSON.parse(result.stdout);
  expect(report.growth.unpackedBytes).toBe(growth);
  expect(report.candidate.unpackedBytes - report.baseline.unpackedBytes).toBe(growth);
  expect(report.growth.compressedBytes).toBe(
    fs.statSync(candidate).size - fs.statSync(baseline).size
  );
  expect(report.candidate.sha256).toBe(
    createHash("sha256").update(fs.readFileSync(candidate)).digest("hex")
  );
});
it("reports shrinking packages without clamping negative growth", () => {
  const result = compare(archive("baseline", 100), archive("candidate", 5));
  expect(result.status).toBe(0);
  expect(JSON.parse(result.stdout).growth.unpackedBytes).toBe(-95);
});
it.each([
  "../escape",
  "package/../escape",
  "other/file",
  "package/src/payload",
  "package/./src/payload",
  "package/link",
])("rejects unsafe or duplicate archive member %s", (member) => {
  const result = compare(archive("baseline", 1), archive("candidate", 1, member));
  expect(result.status).toBe(2);
  expect(result.stdout).toBe("");
  expect(result.stderr).toMatch(/member/i);
});
it("rejects a corrupt archive instead of recording zero bytes", () => {
  const bad = path.join(fixture, "corrupt.tgz");
  fs.writeFileSync(bad, "not a package");
  const result = compare(archive("baseline", 1), bad);
  expect(result.status).toBe(2);
  expect(result.stdout).toBe("");
});
