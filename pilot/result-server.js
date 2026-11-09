const { createServer } = require("node:http");
const { readFileSync, writeFileSync } = require("node:fs");
const path = require("node:path");
const directory = path.resolve(process.argv[2]);
const { runId } = JSON.parse(readFileSync(path.resolve(process.argv[3]), "utf8"));
if (typeof runId !== "string" || !/^[0-9a-f-]{36}$/.test(runId)) {
  throw new Error("Require the generated consumer pilot-run.json");
}
createServer((request, response) => {
  if (request.method !== "POST" || request.url !== "/result") {
    response.writeHead(404).end();
    return;
  }
  let body = "";
  request.on("data", (chunk) => {
    body += chunk;
    if (body.length > 20 * 1024 * 1024) request.destroy();
  });
  request.on("end", () => {
    try {
      const result = JSON.parse(body);
      if (result.runId !== runId) throw new Error("Result belongs to another pilot run");
      if (!["android", "ios"].includes(result.platform)) throw new Error("Invalid platform");
      writeFileSync(
        path.join(directory, `${result.platform}-parity.json`),
        `${JSON.stringify(result, null, 2)}\n`
      );
      console.log(JSON.stringify(result));
      response.writeHead(200).end("saved");
    } catch (error) {
      console.error(error);
      response.writeHead(400).end();
    }
  });
}).listen(18769, "127.0.0.1", () => console.log("Pilot result receiver on 18769"));
