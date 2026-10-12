import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { build } from "esbuild";
import { RUNTIME_SOURCE_HASHES, runtimeCleanupPlugin, transformRuntimeCleanup } from "./runtime-cleanup.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const upstream = resolve(here, "node_modules/@zrimo/viewer/dist");
const result = await build({
  stdin: { contents: `export {ViewerClient} from ${JSON.stringify(resolve(upstream, "client.js"))};
    export {OfficeDocumentAdapter} from ${JSON.stringify(resolve(upstream, "adapters/office.js"))};
    export {CsvDocumentAdapter} from ${JSON.stringify(resolve(upstream, "adapters/csv.js"))};`, resolveDir: here },
  bundle: true, write: false, platform: "node", format: "esm", packages: "external", plugins: [runtimeCleanupPlugin()],
});
const { ViewerClient, OfficeDocumentAdapter, CsvDocumentAdapter } = await import(
  `data:text/javascript;base64,${Buffer.from(result.outputFiles[0].contents).toString("base64")}`
);
const deferred = () => {
  let resolve;
  const promise = new Promise(done => { resolve = done; });
  return { promise, resolve };
};
const cases = [];
const check = async (name, action) => {
  await action();
  cases.push({ name, pass: true });
};
const bytes = () => new TextEncoder().encode("a,b\n1,2");
const options = { fileName: "sample.csv", contentType: "text/csv" };
const fakeAdapter = extra => ({
  id: "test", formats: ["csv"], open: async () => ({}),
  getInfo: async () => ({ format: "csv", unit: "page", pageCount: 1, pageSizes: [{ width: 10, height: 10 }] }),
  render: async () => {}, close: async () => {}, ...extra,
});
const context = signal => ({
  format: "xlsx", signal, limits: { maxZipEntryBytes: 1024, maxOperationMs: 1000 },
  reportProgress() {}, reportWarning() {},
});

await check("All six pinned source inputs reject drift", async () => {
  for (const name of Object.keys(RUNTIME_SOURCE_HASHES)) {
    const original = await readFile(resolve(upstream, name));
    assert.throws(() => transformRuntimeCleanup(name, Buffer.concat([original, Buffer.from("\n")])), /Unreviewed/);
  }
});
await check("Failed close retains original viewer handle and client cleanup can retry", async () => {
  let count = 0;
  const handle = {};
  const source = bytes();
  const client = ViewerClient.create({ adapters: [fakeAdapter({
    open: async () => handle,
    close: async received => { assert.equal(received, handle); if (++count === 1) throw Error("close failed"); },
  })] });
  const viewer = client.createViewer({});
  await viewer.load(source, options);
  await assert.rejects(client.destroy(), /close failed/);
  assert.ok(source.some(value => value !== 0));
  await client.destroy();
  assert.equal(count, 2);
  assert.ok(source.every(value => value === 0));
  assert.equal(viewer.state.status, "destroyed");
});
await check("Destroy waits for a late open and closes the actual returned handle", async () => {
  const entered = deferred(), pending = deferred();
  let closed = false, completed = false;
  const handle = {};
  const client = ViewerClient.create({ adapters: [fakeAdapter({
    open: async () => { entered.resolve(); return pending.promise; },
    close: async received => { assert.equal(received, handle); closed = true; },
  })] });
  const viewer = client.createViewer({});
  const loading = viewer.load(bytes(), options);
  const loadResult = Promise.allSettled([loading]);
  await entered.promise;
  const closing = client.destroy().then(() => { completed = true; });
  await Promise.resolve();
  assert.equal(completed, false);
  assert.equal(closed, false);
  pending.resolve(handle);
  await loadResult;
  await closing;
  assert.equal(closed, true);
});
await check("Backend is not closed while a renderer is still writing its canvas", async () => {
  const entered = deferred(), pending = deferred();
  let rendering = false, closed = false;
  const source = bytes();
  const client = ViewerClient.create({ adapters: [fakeAdapter({
    render: async () => { rendering = true; entered.resolve(); await pending.promise; rendering = false; },
    close: async () => { assert.equal(rendering, false); closed = true; },
  })] });
  const viewer = client.createViewer({});
  await viewer.load(source, options);
  const drawing = Promise.allSettled([viewer.renderPage(0, {})]);
  await entered.promise;
  const closing = client.destroy();
  await Promise.resolve();
  assert.equal(closed, false);
  assert.ok(source.some(value => value !== 0));
  pending.resolve();
  await drawing;
  await closing;
  assert.equal(closed, true);
  assert.ok(source.every(value => value === 0));
});
await check("Office backend created before a failed open stays owned with its buffer copy", async () => {
  let closed = 0, copy;
  const source = bytes();
  const adapter = new OfficeDocumentAdapter({ engines: { xlsx: async data => {
    copy = new Uint8Array(data);
    return { sheetCount: 1, getWorksheet: async () => { throw Error("parse failed"); }, destroy() { closed++; } };
  } } });
  await assert.rejects(adapter.open(source, context(new AbortController().signal)));
  assert.equal(closed, 0);
  await adapter.destroy();
  assert.equal(closed, 1);
  assert.ok(source.every(value => value === 0));
  assert.ok(copy.every(value => value === 0));
});
await check("Failed Office backend destruction preserves original buffers for cleanup retry", async () => {
  let attempts = 0, copy;
  const source = bytes();
  const adapter = new OfficeDocumentAdapter({ engines: { xlsx: async data => {
    copy = new Uint8Array(data);
    return { sheetCount: 0, destroy() { if (++attempts === 1) throw Error("busy"); } };
  } } });
  await adapter.open(source, context(new AbortController().signal));
  await assert.rejects(adapter.destroy(), /cleanup pending/);
  assert.ok(copy.some(value => value !== 0));
  await adapter.destroy();
  assert.equal(attempts, 2);
  assert.ok(copy.every(value => value === 0));
});
await check("Aborted engine creation is still destroyed after its late result", async () => {
  const abort = new AbortController();
  const entered = deferred(), pending = deferred();
  let closed = 0;
  const adapter = new OfficeDocumentAdapter({ engines: { xlsx: async () => { entered.resolve(); return pending.promise; } } });
  const loading = adapter.open(bytes(), context(abort.signal));
  const rejected = assert.rejects(loading);
  await entered.promise;
  abort.abort();
  pending.resolve({ destroy() { closed++; } });
  await rejected;
  await adapter.destroy();
  assert.equal(closed, 1);
});
await check("Registry retains a failing adapter for the same client retry", async () => {
  let attempts = 0;
  const client = ViewerClient.create({ adapters: [fakeAdapter({ destroy() { if (++attempts === 1) throw Error("adapter busy"); } })] });
  await assert.rejects(client.destroy(), /adapter busy/);
  await client.destroy();
  assert.equal(attempts, 2);
});
await check("Client retries its original font cleanup after failure", async () => {
  let attempts = 0;
  const client = ViewerClient.create({ adapters: [] });
  client.fonts.destroy = async () => { if (++attempts === 1) throw Error("font busy"); };
  await assert.rejects(client.destroy(), /font busy/);
  await client.destroy();
  assert.equal(attempts, 2);
});
await check("CSV close clears rows retained by the original handle", async () => {
  const rows = [["a", "b"]], firstRow = rows[0];
  new CsvDocumentAdapter().close({ parsed: { rows } });
  assert.deepEqual(firstRow, ["", ""]);
  assert.equal(rows.length, 0);
});
console.log(JSON.stringify({ status: "PASS", tests: cases.length, cases }));
