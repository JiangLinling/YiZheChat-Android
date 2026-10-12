import assert from "node:assert/strict";
import vm from "node:vm";
import { build } from "esbuild";

const bundle = await build({
  entryPoints: ["src/viewer-entry.js"], bundle: true, write: false, format: "iife", platform: "browser",
  external: ["@silurus/*"],
  plugins: [{ name: "test-viewer-boundary", setup(build) {
    build.onResolve({ filter: /^@zrimo\/viewer/ }, args => ({ path: args.path, namespace: "test-viewer" }));
    build.onLoad({ filter: /.*/, namespace: "test-viewer" }, args => ({
      contents: args.path.endsWith(".css") ? "" : `export const ViewerClient = { create: options => globalThis.__createClient(options) };
        export class OfficeDocumentAdapter {} export class CsvDocumentAdapter {}`,
      loader: "js",
    }));
  } }],
});
const program = new vm.Script(bundle.outputFiles[0].text);
const deferred = () => { let resolve; const promise = new Promise(done => { resolve = done; }); return { promise, resolve }; };
const tick = () => new Promise(resolve => setImmediate(resolve));
function page({ body, load, destroyClient, preclosed = false } = {}) {
  const calls = { fetch: 0, created: 0, viewerDestroy: 0, clientDestroy: 0, removed: 0 };
  const source = new Uint8Array([97, 44, 98]);
  const element = { style: {}, replaceChildren() { calls.removed++; } };
  const fakeViewer = {
    state: { zoom: 1 }, on() {},
    async load(bytes, options) { calls.bytes = bytes; calls.loadSignal = options.signal; await load?.(bytes, options); },
    async destroy() { calls.viewerDestroy++; },
  };
  const context = vm.createContext({
    AbortController, Request, URL, URLSearchParams, TextDecoder, TextEncoder, setTimeout, clearTimeout,
    addEventListener() {}, removeEventListener() {}, innerHeight: 800,
    __YiZheOfficeCloseRequested: preclosed,
    location: { href: "https://appassets.androidplatform.net/assets/local-office-viewer/index.html", origin: "https://appassets.androidplatform.net",
      search: "?token=test-token&name=sample.csv&type=text%2Fcsv" },
    document: { documentElement: element, body: element, querySelector: query => query === "#viewer" ? element : null },
    async fetch(_, init) { calls.fetch++; calls.fetchSignal = init.signal; return { ok: true, arrayBuffer: async () => body ? body.promise : source.buffer }; },
    __createClient() {
      calls.created++;
      return { createViewer: () => fakeViewer, async destroy() { calls.clientDestroy++; await destroyClient?.(); } };
    },
  });
  program.runInContext(context);
  return { calls, source, api: context.YiZheOffice };
}
const cases = [];
const check = async (name, action) => { await action(); cases.push({ name, pass: true }); };
await check("Native preinitialization fence prevents content acquisition", async () => {
  const p = page({ preclosed: true });
  await p.api.destroy();
  assert.equal(p.calls.fetch, 0);
  assert.equal(p.calls.created, 0);
  assert.equal(p.api.requestDestroy(), "complete");
});
await check("Destroy waits for late response body and zeros it without creating a viewer", async () => {
  const body = deferred();
  const p = page({ body });
  await tick();
  assert.equal(p.api.requestDestroy(), "pending");
  await tick();
  assert.equal(p.api.requestDestroy(), "pending");
  assert.equal(p.calls.fetchSignal.aborted, true);
  body.resolve(p.source.buffer);
  await p.api.destroy();
  assert.equal(p.calls.created, 0);
  assert.ok(p.source.every(value => value === 0));
  assert.equal(p.api.requestDestroy(), "complete");
});
await check("Destroy waits for original renderer load and does not report an early completion", async () => {
  const pending = deferred();
  const p = page({ load: () => pending.promise });
  await tick();
  const closing = p.api.destroy();
  await tick();
  assert.equal(p.api.requestDestroy(), "pending");
  assert.equal(p.calls.loadSignal.aborted, true);
  assert.ok(p.source.some(value => value !== 0));
  pending.resolve();
  await closing;
  assert.ok(p.source.every(value => value === 0));
  assert.equal(p.calls.clientDestroy, 1);
});
await check("Failed client cleanup preserves source bytes and retries the same client", async () => {
  let attempts = 0;
  const p = page({ destroyClient: async () => { if (++attempts === 1) throw Error("busy"); } });
  await tick();
  await assert.rejects(p.api.destroy(), /busy/);
  assert.ok(p.source.some(value => value !== 0));
  await p.api.destroy();
  assert.equal(p.calls.created, 1);
  assert.equal(p.calls.clientDestroy, 2);
  assert.ok(p.source.every(value => value === 0));
});
await check("Concurrent disposal shares one attempt and repeated disposal is idempotent", async () => {
  const pending = deferred();
  const p = page({ destroyClient: () => pending.promise });
  await tick();
  const first = p.api.destroy(), second = p.api.destroy();
  assert.equal(first, second);
  pending.resolve();
  await first;
  await p.api.destroy();
  assert.equal(p.calls.clientDestroy, 1);
  assert.equal(p.calls.removed, 1);
});
console.log(JSON.stringify({ status: "PASS", tests: cases.length, cases }));
