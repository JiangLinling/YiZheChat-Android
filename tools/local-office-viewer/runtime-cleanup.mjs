import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";

// Apply to immutable, reviewed dependency inputs at bundle time, as with the geometry patch.
export const RUNTIME_SOURCE_HASHES = {
  "viewer.js": "bd20934b3816583ff02eab968eb517139b5dfa71089299e479f94deab4c562cb",
  "client.js": "00627f2037517f8b9c7e463e4d664be8102767c78ebe60e8032b233b809c654b",
  "registry.js": "843222d37a5d5f08ff593ed590e5ca76b25728bded816f39043f7706a52ff603",
  "fonts.js": "942f6262d62f52b06b7b60e86fbef00de5005713cfd30b9e37de65f5f5f3479a",
  "adapters/office.js": "b54199e712738447a03253a4ee9cc9cfa35f2fa4e7b0a4a0476fcc4e8d503f58",
  "adapters/csv.js": "5664a928d1187016dc9db045e87411ceeaf78a0e43cbcda004657b5a7f66ed97",
};

export function transformRuntimeCleanup(name, bytes) {
  if (createHash("sha256").update(bytes).digest("hex") !== RUNTIME_SOURCE_HASHES[name]) {
    throw new Error(`Unreviewed Office runtime source: ${name}`);
  }
  let source = bytes.toString("utf8");
  const replace = (before, after) => {
    if (source.split(before).length !== 2) throw new Error(`Runtime cleanup patch context differs: ${name}`);
    source = source.replace(before, after);
  };
  if (name === "viewer.js") {
    replace("export class DocumentViewer {", `export class DocumentViewer {
    #closing = false;
    #work = new Set();
    #trackWork(action) {
        this.#assertAlive();
        const work = Promise.resolve().then(action);
        this.#work.add(work);
        work.then(() => this.#work.delete(work), () => this.#work.delete(work));
        return work;
    }`);
    for (const [method, params, args] of [
      ["load", "source, options = {}", "source, options"],
      ["renderPage", "pageIndex, target, options = {}", "pageIndex, target, options"],
      ["renderSheetViewport", "sheetIndex, target, range, options = {}", "sheetIndex, target, range, options"],
      ["getPageText", "pageIndex, signal", "pageIndex, signal"],
      ["search", "query, options = {}", "query, options"],
      ["selectText", "range", "range"],
      ["copySelection", "", ""],
    ]) {
      replace(`    async ${method}(${params}) {`, `    async ${method}(${params}) {
        return this.#trackWork(() => this.#${method}Owned(${args}));
    }
    async #${method}Owned(${params}) {`);
    }
    replace("    async #getTextRuns(pageIndex, signal) {", `    async #getTextRuns(pageIndex, signal) {
        return this.#trackWork(() => this.#getTextRunsOwned(pageIndex, signal));
    }
    async #getTextRunsOwned(pageIndex, signal) {`);
    replace(`    async destroy() {
        if (this.#state.status === "destroyed")
            return;`, `    async destroy() {
        if (this.#state.status === "destroyed")
            return;
        this.#closing = true;`);
    replace(`        await this.#closeDocument();
        this.#viewport?.destroy();`, `        while (this.#work.size) await Promise.allSettled([...this.#work]);
        await this.#closeDocument();
        this.#viewport?.destroy();`);
    replace(`    #assertAlive() {
        if (this.#state.status === "destroyed")`, `    #assertAlive() {
        if (this.#closing || this.#state.status === "destroyed")`);
    replace(`        this.#adapter = undefined;
        this.#handle = undefined;`, `        // Keep the original handle if its close fails; the next destroy retries it.
        if (adapter && handle !== undefined) await adapter.close(handle);
        this.#original?.fill(0);
        this.#adapter = undefined;
        this.#handle = undefined;`);
    replace(`        if (adapter && handle !== undefined)
            await adapter.close(handle);
    }
    #assertReady()`, `    }
    #assertReady()`);
  } else if (name === "client.js" || name === "fonts.js") {
    replace("    #destroyed = false;", "    #destroyed = false;\n    #cleanupComplete = false;");
    replace(`    async destroy() {
        if (this.#destroyed)`, `    async destroy() {
        if (this.#cleanupComplete)`);
    const last = name === "client.js" ? "        await this.fonts.destroy();" : "        this.#loads.clear();";
    replace(last, `${last}\n        this.#cleanupComplete = true;`);
  } else if (name === "registry.js") {
    replace("        this.#adapters.clear();\n", "");
    replace("        await Promise.all([...unique].map(async (adapter) => adapter.destroy?.()));",
      "        await Promise.all([...unique].map(async (adapter) => adapter.destroy?.()));\n        this.#adapters.clear();");
  } else if (name === "adapters/office.js") {
    replace("    #options;", "    #options;\n    #ownedBackends = new Set();\n    #ownedBuffers = new Set();");
    replace("        let data = input;", "        let data = input;\n        this.#ownedBuffers.add(input);");
    replace("            data = await this.#convertLegacy(input, format, context);",
      "            data = await this.#convertLegacy(input, format, context);\n            this.#ownedBuffers.add(data);");
    replace("        const buffer = exactArrayBuffer(data);",
      "        const buffer = exactArrayBuffer(data);\n        this.#ownedBuffers.add(new Uint8Array(buffer));");
    for (const engine of ["Docx", "Pptx", "Xlsx"]) {
      const original = `const backend = await this.#load${engine}(buffer, engineOptions);`;
      replace(original, `${original}\n            this.#ownedBackends.add(backend);`);
    }
    // An aborted/failed open still belongs to this adapter until destroy has really closed it.
    source = source.replaceAll("throwIfAborted(context.signal, backend);", "throwIfAborted(context.signal);");
    replace(`    close(handle) {
        handle.backend.destroy();
    }`, `    async close(handle) {
        if (!this.#ownedBackends.has(handle.backend)) return;
        await handle.backend.destroy();
        this.#ownedBackends.delete(handle.backend);
    }
    async destroy() {
        const failures = [];
        for (const backend of this.#ownedBackends) {
            try {
                await backend.destroy();
                this.#ownedBackends.delete(backend);
            } catch (error) { failures.push(error); }
        }
        if (failures.length) throw new AggregateError(failures, "Office backend cleanup pending");
        for (const bytes of this.#ownedBuffers) bytes.fill(0);
        this.#ownedBuffers.clear();
    }`);
  } else if (name === "adapters/csv.js") {
    replace("    close() { }", `    close(handle) {
        for (const row of handle.parsed.rows) row.fill("");
        handle.parsed.rows.length = 0;
    }`);
  }
  return source;
}

export function runtimeCleanupPlugin() {
  return {
    name: "yizhe-office-runtime-cleanup",
    setup(build) {
      build.onLoad({ filter: /[\\/]@zrimo[\\/]viewer[\\/]dist[\\/](?:adapters[\\/])?(?:viewer|client|registry|fonts|office|csv)\.js$/ }, async args => {
        const name = args.path.split(/[/\\]dist[/\\]/).at(-1).replaceAll("\\", "/");
        return { contents: transformRuntimeCleanup(name, await readFile(args.path)), loader: "js" };
      });
    },
  };
}
