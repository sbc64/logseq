// Measure cold, repeat, and browser-restart loads of the deployed journal.
// Requires Playwright; PLAYWRIGHT_MODULE and CHROMIUM_EXECUTABLE can select
// existing Nix packages without installing browser dependencies.
import { mkdtemp } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

const { chromium } = await import(process.env.PLAYWRIGHT_MODULE || "playwright");
const url = process.argv[2];
if (!url) throw new Error("Usage: node scripts/test/logseq/journal-load.mjs URL");
const profile = await mkdtemp(join(tmpdir(), "logseq-cache-browser-"));
const options = { headless: true };
if (process.env.CHROMIUM_EXECUTABLE) {
  options.executablePath = process.env.CHROMIUM_EXECUTABLE;
}
for (let session = 0; session < 2; session++) {
  const context = await chromium.launchPersistentContext(profile, options);
  try {
    const page = await context.newPage();
    const errors = [];
    page.on("pageerror", error => errors.push(error.message));
    for (let run = 0; run < (session === 0 ? 3 : 1); run++) {
      const start = Date.now();
      await page.goto(url, { waitUntil: "domcontentloaded" });
      await page.locator("#journals .ls-block").first().waitFor({ timeout: 60000 });
      console.log(JSON.stringify({
        session, run, journalMs: Date.now() - start,
        ...await page.evaluate(() => {
          const resources = performance.getEntriesByType("resource");
          const main = resources.find(entry => new URL(entry.name).pathname === "/js/main.js");
          return {
            mainMs: Math.round(main.duration),
            mainBytes: main.transferSize,
            totalBytes: resources.reduce((sum, entry) => sum + entry.transferSize, 0),
          };
        }),
        errors,
      }));
    }
    if (errors.length) throw new Error(`Browser errors: ${JSON.stringify(errors)}`);
  } finally {
    await context.close();
  }
}
