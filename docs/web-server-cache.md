# Web server browser cache

The disk-backed web server implements static-file caching in
`src/main/frontend/worker/web_server.cljs`. No deployment-time source patch is
needed.

Static application files use content-based SHA-256 ETags and
`Cache-Control: private, no-cache`. Browsers retain a private copy and revalidate
it on subsequent loads. Unchanged files return HTTP 304 without retransmitting
their contents; changed files return HTTP 200 with a new ETag. The server reuses
computed hashes until a file's size, modification time, or change time changes.

This speeds up repeated journal loads by avoiding downloads of the application
bundles. Journal data and edits still use the live database API; this is not an
offline journal cache. `web-server-config.js` remains `no-store`.

## Verification

Build the Node worker and run the HTTP integration test from the repository root:

```sh
pnpm db-worker-node:release:bundle
pnpm test:web-server-cache
```

To test an installed package instead:

```sh
pnpm test:web-server-cache /path/to/bin/logseq-web
```

The test starts an isolated temporary graph and checks cache hits, GET/HEAD,
weak/list/wildcard validators, asset changes (including preserved size and
mtime), and uncached API/config responses.

With Playwright and Chromium installed, measure cold, repeat, and
browser-restart journal loads using:

```sh
node scripts/test/logseq/journal-load.mjs https://your-logseq-server/
```

`PLAYWRIGHT_MODULE` and `CHROMIUM_EXECUTABLE` can select existing installations.
The benchmark reports journal render time, transferred bytes, and browser errors.
