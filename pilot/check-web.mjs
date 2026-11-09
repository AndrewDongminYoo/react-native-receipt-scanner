import assert from "node:assert/strict";
import path from "node:path";
import { createRequire } from "node:module";
import { runInNewContext } from "node:vm";
const require = createRequire(import.meta.url);
const consumer = path.resolve(process.argv[2]);
const vite = require.resolve("vite", { paths: [consumer] });
const { build } = require(require.resolve("esbuild", { paths: [vite] }));
const nativeFree = {
  name: "native-free",
  setup(builder) {
    builder.onResolve({ filter: /NativeReceipt|\.native(\.|$)/ }, (args) => {
      throw new Error(`Native import forbidden: ${args.path}`);
    });
  },
};

async function main() {
  const options = {
    absWorkingDir: consumer,
    bundle: true,
    platform: "browser",
    format: "cjs",
    write: false,
    metafile: true,
    logLevel: "silent",
    plugins: [nativeFree],
  };
  // Prove the graph reader rejects a native import before trusting its pass.
  await assert.rejects(
    build(
      Object.assign({}, options, {
        stdin: {
          contents:
            'import "./node_modules/react-native-receipt-scanner/src/NativeReceiptOverlapPilot";',
          resolveDir: consumer,
        },
      })
    ),
    /Native import forbidden/
  );
  for (const conditions of [[], ["source"]]) {
    const result = await build(
      Object.assign({}, options, {
        conditions,
        stdin: {
          contents: 'export {scan, getOcrCapabilities} from "react-native-receipt-scanner";',
          resolveDir: consumer,
        },
      })
    );
    const module = { exports: {} };
    runInNewContext(result.outputFiles[0].text, { module, exports: module.exports });
    assert.deepEqual(JSON.parse(JSON.stringify(await module.exports.scan())), {
      status: "cancelled",
      images: [],
      rejectedImages: [],
    });
    assert.equal((await module.exports.getOcrCapabilities()).platform, "web");
    console.log(
      JSON.stringify({ conditions, inputs: Object.keys(result.metafile.inputs), nativeFree: true })
    );
  }
}
main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
