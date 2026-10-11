const fs = require("node:fs");
const path = require("node:path");

const repoRoot = path.join(__dirname, "..");

try {
  const { version } = JSON.parse(fs.readFileSync(path.join(repoRoot, "package.json"), "utf8"));
  const lockfile = fs.readFileSync(path.join(repoRoot, "example", "ios", "Podfile.lock"), "utf8");
  const sections = lockfile.split(/^PODS:\r?$/m);
  const pods = sections.length > 1 ? sections[1].split(/^\S/m)[0] : "";
  const entries = [...pods.matchAll(/^ {2}- ReceiptScanner \(([^()\r\n]+)\):?\r?$/gm)];

  if (entries.length !== 1) {
    throw new Error(
      "Expected exactly one ReceiptScanner entry in the PODS section of Podfile.lock"
    );
  }

  const podVersion = entries[0][1];
  if (podVersion !== version) {
    throw new Error(
      `ReceiptScanner version mismatch: package.json=${version}, Podfile.lock=${podVersion}`
    );
  }

  console.log(`ReceiptScanner version matches package.json: ${version}`);
} catch (error) {
  console.error(error.message);
  console.error(
    "Run bundle exec pod install --project-directory=ios from example/, then commit Podfile.lock."
  );
  process.exitCode = 1;
}
