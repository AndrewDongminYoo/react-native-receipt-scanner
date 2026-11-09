const { spawnSync } = require("node:child_process");
const { readFileSync } = require("node:fs");
const path = require("node:path");
const ts = require("typescript");
// Host-only TypeScript reader; the mobile consumer uses Metro and Hermes.
require.extensions[".ts"] = (module, filename) => {
  const source = readFileSync(filename, "utf8");
  const { outputText } = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  });
  module._compile(outputText, filename);
};
const { runParity } = require("./harness.ts");
const driver = path.resolve(process.argv[2]);
const fixture =
  process.argv[3] ||
  path.join(__dirname, "../src/__tests__/fixtures/ocr-contracts/v1/contract.json");
const corpus = JSON.parse(readFileSync(fixture, "utf8"));
const matcher = {
  getAbiVersion: () => 1,
  findOverlap(left, leftRanges, right, rightRanges) {
    const encode = (units, ranges) => [units.length, ranges.length, ...units, ...ranges].join(" ");
    const result = spawnSync(driver, [], {
      input: `${encode(left, leftRanges)}\n${encode(right, rightRanges)}\n`,
      encoding: "utf8",
    });
    if (result.status !== 0) throw new Error(result.stderr || `Driver failed: ${result.error}`);
    return Number(result.stdout.trim());
  },
};
console.log(JSON.stringify(runParity(matcher, corpus)));
