// Integration test against the local bundle or a packaged worker.
// Usage: node scripts/test/logseq/web-server-cache.mjs [logseq-web-binary]
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { spawn } from "node:child_process";
import { once } from "node:events";
import { mkdtemp, mkdir, readFile, stat, utimes, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { setTimeout as delay } from "node:timers/promises";

const binary = process.argv[2] || process.execPath;
const workerArgs = process.argv[2] ? [] : ["./static/db-worker-node.js"];
const root = await mkdtemp(join(tmpdir(), "logseq-cache-test-"));
const ui = join(root, "ui");
await mkdir(ui);
await writeFile(join(ui, "index.html"), "<!doctype html><title>Cache test</title>");
const asset = join(ui, "app.js");
await writeFile(asset, "const value = 1;\n");
let output = "";
const worker = spawn(binary, [...workerArgs, "--root-dir", root, "--repo", "cache-test", "--ui-dir", ui]);
worker.stdout.on("data", data => { output += data; });
worker.stderr.on("data", data => { output += data; });
const exited = once(worker, "exit");
let base;
try {
  for (let attempt = 0; attempt < 200 && !base; attempt++) {
    assert.equal(worker.exitCode, null, output);
    try {
      const entries = await readFile(join(root, "server-list"), "utf8");
      const port = entries.trim().split(/\s+/)[1];
      const url = `http://127.0.0.1:${port}`;
      if ((await fetch(`${url}/healthz`)).ok) base = url;
    } catch { /* Wait for this isolated worker to publish its port. */ }
    if (!base) await delay(100);
  }
  assert.ok(base, `Worker did not start: ${output}`);
  const first = await fetch(`${base}/app.js`);
  assert.equal(first.status, 200);
  assert.equal(first.headers.get("cache-control"), "private, no-cache");
  const body = await first.text();
  const etag = first.headers.get("etag");
  assert.equal(etag, `"${createHash("sha256").update(body).digest("hex")}"`);
  for (const method of ["GET", "HEAD"]) {
    for (const validator of [etag, `W/${etag}`, `"unrelated", W/${etag}`, "*"]) {
      const hit = await fetch(`${base}/app.js`, {
        method, headers: { "If-None-Match": validator },
      });
      assert.equal(hit.status, 304, `${method} ${validator}`);
      assert.equal(hit.headers.get("etag"), etag);
      assert.equal(await hit.text(), "");
    }
  }
  const head = await fetch(`${base}/app.js`, { method: "HEAD" });
  assert.equal(head.status, 200);
  assert.equal(head.headers.get("etag"), etag);
  assert.equal(Number(head.headers.get("content-length")), Buffer.byteLength(body));
  assert.equal(await head.text(), "");

  // Same-size replacement with the old mtime must still invalidate the cache.
  const before = await stat(asset);
  await delay(20);
  await writeFile(asset, "const value = 2;\n");
  await utimes(asset, before.atime, before.mtime);
  const changed = await fetch(`${base}/app.js`, { headers: { "If-None-Match": etag } });
  assert.equal(changed.status, 200);
  assert.notEqual(changed.headers.get("etag"), etag);
  assert.equal(await changed.text(), "const value = 2;\n");

  for (const path of ["/", "/static/app.js"]) {
    const response = await fetch(`${base}${path}`);
    assert.equal(response.status, 200);
    const hit = await fetch(`${base}${path}`, {
      headers: { "If-None-Match": response.headers.get("etag") },
    });
    assert.equal(hit.status, 304, path);
  }
  for (const path of ["/web-server-config.js", "/healthz"]) {
    const response = await fetch(`${base}${path}`, { headers: { "If-None-Match": "*" } });
    assert.equal(response.status, 200);
    assert.equal(response.headers.get("etag"), null);
    if (path === "/web-server-config.js") {
      assert.equal(response.headers.get("cache-control"), "no-store");
    }
  }
  const invoke = await fetch(`${base}/v1/invoke`, {
    method: "POST",
    headers: { "Content-Type": "application/json", "If-None-Match": "*" },
    body: JSON.stringify({
      method: "thread-api/q",
      argsTransit: JSON.stringify(["cache-test", [[
        "~:find", "~$?e", "~:where", ["~$?e", "~:db/ident", "~:logseq.class/Tag"],
      ]]]),
    }),
  });
  assert.equal(invoke.status, 200);
  assert.equal(invoke.headers.get("etag"), null);
  assert.equal((await invoke.json()).ok, true);
  const missing = await fetch(`${base}/missing.js`, { headers: { "If-None-Match": "*" } });
  assert.equal(missing.status, 404);
  assert.equal(missing.headers.get("etag"), null);
  console.log("PASS: cache hits, HEAD, weak/list/wildcard validators, changed assets, and uncached API/config");
} finally {
  worker.kill("SIGTERM");
  const killTimer = setTimeout(() => worker.kill("SIGKILL"), 5000);
  await exited;
  clearTimeout(killTimer);
}
