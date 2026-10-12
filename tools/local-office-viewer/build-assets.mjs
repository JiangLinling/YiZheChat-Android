import { build } from "esbuild";
import { spreadsheetPixelGeometryPlugin } from "./spreadsheet-pixel-geometry.mjs";
import { runtimeCleanupPlugin } from "./runtime-cleanup.mjs";
import { cp, mkdir, realpath, rm } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const output = resolve(here, "../../libraries/localofficeviewer/impl/src/main/assets/local-office-viewer");
const zrimo = resolve(here, "node_modules/@zrimo/viewer");
const ooxml = resolve(here, "node_modules/@silurus/ooxml/dist");

// Do not traverse a replaced/junction output into a different checkout during the rebuild.
const actualOutput = await realpath(output).catch(error => { if (error.code === "ENOENT") return output; throw error; });
if (actualOutput.toLowerCase() !== output.toLowerCase()) throw new Error("Unexpected Office asset output directory");
await rm(output, { recursive: true, force: true });
await mkdir(resolve(output, "bundle"), { recursive: true });
await build({
  plugins: [spreadsheetPixelGeometryPlugin(), runtimeCleanupPlugin()],
  entryPoints: [resolve(here, "src/viewer-entry.js")],
  outdir: resolve(output, "bundle"),
  entryNames: "app",
  chunkNames: "chunks/[name]-[hash]",
  assetNames: "assets/[name]-[hash]",
  bundle: true,
  splitting: true,
  format: "esm",
  platform: "browser",
  target: ["chrome120"],
  minify: true,
  sourcemap: false,
  loader: { ".wasm": "file", ".woff2": "file" },
});
await cp(resolve(here, "src/index.html"), resolve(output, "index.html"));
// @silurus/ooxml resolves these URLs at runtime relative to the generated chunks. esbuild
// preserves those URL literals, so keep the three parser binaries beside the chunks.
await Promise.all(["docx", "xlsx", "pptx"].map(format =>
  cp(
    resolve(ooxml, `${format}_parser_bg.wasm`),
    resolve(output, `bundle/chunks/${format}_parser_bg.wasm`),
  ),
));
await cp(resolve(zrimo, "dist/assets/legacy"), resolve(output, "zrimo/assets/legacy"), { recursive: true });
await cp(resolve(zrimo, "dist/fonts"), resolve(output, "zrimo/fonts"), { recursive: true });
await mkdir(resolve(output, "zrimo/workers"), { recursive: true });
await cp(resolve(zrimo, "dist/workers/csv-worker.js"), resolve(output, "zrimo/workers/csv-worker.js"));
await cp(resolve(zrimo, "dist/workers/legacy-converter-worker.js"), resolve(output, "zrimo/workers/legacy-converter-worker.js"));
await cp(resolve(zrimo, "LICENSE-MIT"), resolve(output, "LICENSE-ZRIMO-MIT"));
await cp(resolve(zrimo, "LICENSE-APACHE"), resolve(output, "LICENSE-ZRIMO-APACHE"));
await cp(resolve(zrimo, "THIRD_PARTY_NOTICES.md"), resolve(output, "THIRD_PARTY_NOTICES.md"));
