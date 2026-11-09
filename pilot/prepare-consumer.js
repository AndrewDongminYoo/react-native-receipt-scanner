// Creates a disposable, standalone app. The library comes only from the tarball.
const { cpSync, existsSync, mkdirSync, readFileSync, writeFileSync } = require("node:fs");
const path = require("node:path");
const { randomUUID } = require("node:crypto");
const root = path.resolve(__dirname, "..");
const archive = path.resolve(process.argv[2]);
const target = path.resolve(process.argv[3]);
if (!existsSync(archive) || existsSync(target))
  throw new Error("Require archive and a new target directory");
mkdirSync(target, { recursive: true });
const runId = randomUUID();
writeFileSync(path.join(target, "pilot-run.json"), `${JSON.stringify({ runId })}\n`);
const example = path.join(root, "example");
for (const name of ["android", "ios", "app.json", "babel.config.js", "Gemfile", "Gemfile.lock"]) {
  cpSync(path.join(example, name), path.join(target, name), {
    recursive: true,
    filter: (source) =>
      ![
        "build",
        "Pods",
        ".gradle",
        ".cxx",
        "keystore.properties",
        "local.properties",
        ".xcode.env.local",
      ].includes(path.basename(source)),
  });
}
const manifest = JSON.parse(readFileSync(path.join(example, "package.json"), "utf8"));
const library = JSON.parse(readFileSync(path.join(root, "package.json"), "utf8"));
manifest.dependencies[library.name] = `file:${archive}`;
// Reuse the already locked toolchain, without reaching into checkout modules.
manifest.devDependencies = Object.assign({}, library.devDependencies, manifest.devDependencies);
delete manifest.workspaces;
writeFileSync(path.join(target, "package.json"), `${JSON.stringify(manifest, null, 2)}\n`);
cpSync(path.join(root, "yarn.lock"), path.join(target, "yarn.lock"));
writeFileSync(path.join(target, ".yarnrc.yml"), "nodeLinker: node-modules\n");
writeFileSync(
  path.join(target, "metro.config.js"),
  'module.exports = require("@react-native/metro-config").getDefaultConfig(__dirname);\n'
);
writeFileSync(
  path.join(target, "babel.config.js"),
  'module.exports = { presets: ["module:@react-native/babel-preset"] };\n'
);
writeFileSync(path.join(target, "react-native.config.js"), "module.exports = {};\n");
cpSync(
  path.join(root, "src/__tests__/fixtures/ocr-contracts/v1/contract.json"),
  path.join(target, "contract.json")
);
writeFileSync(
  path.join(target, "index.js"),
  `
import { AppRegistry, Platform, TurboModuleRegistry } from "react-native";
import { runParity } from "./node_modules/react-native-receipt-scanner/pilot/harness";
import checkRawInputs from "./node_modules/react-native-receipt-scanner/pilot/raw-inputs";
import corpus from "./contract.json";
import { name } from "./app.json";
AppRegistry.registerComponent(name, () => () => null);
setTimeout(async () => {
  let result;
  try {
    const matcher = require("./node_modules/react-native-receipt-scanner/src/NativeReceiptOverlapPilot").default;
    if (!TurboModuleRegistry.get("ReceiptScanner")) throw new Error("Acquisition module missing");
    result = { ok: true, platform: Platform.OS, result: runParity(matcher, corpus), rawInputCases: checkRawInputs(matcher), acquisitionPresent: true };
  }
  catch (error) { result = { ok: false, platform: Platform.OS, error: String(error), stack: error.stack }; }
  result.runId = ${JSON.stringify(runId)};
  console.log("OVERLAP_PILOT", JSON.stringify(result));
  const host = Platform.OS === "android" ? "10.0.2.2" : "127.0.0.1";
  try {
    const response = await fetch("http://" + host + ":18769/result", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(result) });
    if (!response.ok) throw new Error("Result receiver HTTP " + response.status);
  } catch (error) {
    console.error("OVERLAP_PILOT_DELIVERY_ERROR", String(error));
  }
}, 1000);
`
);
console.log(target);
