const assert = require("node:assert/strict");
const { createHash } = require("node:crypto");
const { readFileSync } = require("node:fs");
const path = require("node:path");

// The optional directory is a vendored copy. Ordinary tests need only this checkout.
const source = path.join(__dirname, "../src/__tests__/fixtures/ocr-contracts/v1");
const target = process.argv[2] ? path.resolve(process.argv[2]) : source;
const manifest = JSON.parse(readFileSync(path.join(source, "manifest.json"), "utf8"));
assert.equal(manifest.schemaVersion, 1, "unsupported OCR contract manifest version");

for (const name of ["contract.json", "schema.json"]) {
  for (const directory of new Set([source, target])) {
    const bytes = readFileSync(path.join(directory, name));
    const digest = createHash("sha256").update(bytes).digest("hex");
    assert.equal(digest, manifest.files[name], `${name} checksum mismatch in ${directory}`);
    if (name === "contract.json") {
      assert.equal(
        JSON.parse(bytes.toString("utf8")).schemaVersion,
        1,
        "unsupported OCR contract corpus version"
      );
    }
  }
}

assert.deepEqual(
  JSON.parse(readFileSync(path.join(target, "manifest.json"), "utf8")),
  manifest,
  "manifest differs from the canonical snapshot"
);
console.log("OCR contract v1 verified");
