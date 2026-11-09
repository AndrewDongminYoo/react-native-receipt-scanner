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
    if (
      result.status === 2 &&
      !result.signal &&
      !result.error &&
      /^PILOT_INVALID_INPUT: [^\r\n]+\r?\n?$/.test(result.stderr)
    ) {
      throw new Error(result.stderr.trim());
    }
    if (result.status !== 0 || result.signal || result.error) {
      throw new Error(`Driver failed: ${result.error || result.signal || result.stderr}`);
    }
    if (result.stderr.trim() !== "") throw new Error(`Driver diagnostics: ${result.stderr}`);
    const output = result.stdout.trim();
    if (!/^(0|[1-9][0-9]*)$/.test(output) || !Number.isSafeInteger(Number(output))) {
      throw new Error("Driver returned no valid overlap count");
    }
    return Number(output);
  },
};
console.log(JSON.stringify(runParity(matcher, corpus)));
