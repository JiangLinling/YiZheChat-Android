import { CsvDocumentAdapter, OfficeDocumentAdapter, ViewerClient } from "@zrimo/viewer";
import "@zrimo/viewer/styles.css";
import { supplementOneCellImages } from "./xlsx-one-cell-images.js";

let client;
let viewer;
let removeTouchGestures;
let officePreviewAbort;
let opening;
let sourceBytes;
let destroyAttempt;
let destroyed = false;
let closeRequested = Boolean(globalThis.__YiZheOfficeCloseRequested);

const clamp = (value, minimum, maximum) => Math.min(maximum, Math.max(minimum, value));

/**
 * Zrimo 0.1.2 owns document zoom, but its Android pointer path can apply a very large first
 * scale delta and does not keep the fingers' midpoint anchored. Intercept touch pointers before
 * that path, then drive the public viewer zoom API while preserving the touched document point.
 * Mouse, keyboard, toolbar and accessibility controls remain owned by Zrimo.
 */
const installTouchGestures = activeViewer => {
  const viewport = document.querySelector('[data-zrimo="viewport"], [data-zrimo="spreadsheet-viewport"]');
  if (!viewport) return () => {};

  // Spreadsheet documents use a dedicated interactive layer whose cell-selection handler
  // otherwise consumes the first finger and paints a blue range before pinch can begin.
  // Local preview is read-only, so make that layer transparent to touch input and let the
  // single viewer gesture controller below own pan/pinch for every Office format.
  const cellSelectionLayer = viewport.querySelector('[data-zrimo-layer="cell-selection"]');
  const previousCellPointerEvents = cellSelectionLayer?.style.pointerEvents;
  if (cellSelectionLayer) cellSelectionLayer.style.pointerEvents = "none";
  viewport.style.touchAction = "none";
  activeViewer.clearSelection?.();

  const pointers = new Map();
  let lastPan;
  let pinch;

  const point = event => ({ x: event.clientX, y: event.clientY });
  const midpoint = () => {
    const [first, second] = [...pointers.values()];
    return { x: (first.x + second.x) / 2, y: (first.y + second.y) / 2 };
  };
  const distance = () => {
    const [first, second] = [...pointers.values()];
    return Math.hypot(second.x - first.x, second.y - first.y);
  };
  const stop = event => {
    event.preventDefault();
    event.stopImmediatePropagation();
  };
  const capture = event => {
    try { viewport.setPointerCapture(event.pointerId); } catch { /* Older WebView. */ }
  };
  const release = event => {
    try {
      if (viewport.hasPointerCapture(event.pointerId)) viewport.releasePointerCapture(event.pointerId);
    } catch { /* Pointer may already be released. */ }
  };

  const onPointerDown = event => {
    if (event.pointerType !== "touch") return;
    stop(event);
    pointers.set(event.pointerId, point(event));
    capture(event);
    if (pointers.size === 1) {
      lastPan = point(event);
      pinch = undefined;
    } else if (pointers.size === 2) {
      const center = midpoint();
      const bounds = viewport.getBoundingClientRect();
      const zoom = activeViewer.state.zoom;
      pinch = {
        distance: Math.max(1, distance()),
        zoom,
        documentX: (viewport.scrollLeft + center.x - bounds.left) / zoom,
        documentY: (viewport.scrollTop + center.y - bounds.top) / zoom,
      };
      lastPan = undefined;
    }
  };

  const onPointerMove = event => {
    if (event.pointerType !== "touch" || !pointers.has(event.pointerId)) return;
    stop(event);
    pointers.set(event.pointerId, point(event));
    if (pointers.size >= 2 && pinch) {
      const center = midpoint();
      const bounds = viewport.getBoundingClientRect();
      // Damping prevents small finger jitter from becoming a large document jump.
      const scale = Math.pow(distance() / pinch.distance, 0.72);
      const zoom = clamp(pinch.zoom * scale, 0.2, 4);
      activeViewer.setZoom(zoom);
      viewport.scrollLeft = pinch.documentX * zoom - (center.x - bounds.left);
      viewport.scrollTop = pinch.documentY * zoom - (center.y - bounds.top);
      return;
    }
    if (pointers.size === 1 && lastPan) {
      viewport.scrollLeft += lastPan.x - event.clientX;
      viewport.scrollTop += lastPan.y - event.clientY;
      lastPan = point(event);
    }
  };

  const onPointerUp = event => {
    if (event.pointerType !== "touch" || !pointers.has(event.pointerId)) return;
    stop(event);
    pointers.delete(event.pointerId);
    release(event);
    pinch = undefined;
    lastPan = pointers.size === 1 ? [...pointers.values()][0] : undefined;
  };

  viewport.addEventListener("pointerdown", onPointerDown, { capture: true, passive: false });
  viewport.addEventListener("pointermove", onPointerMove, { capture: true, passive: false });
  viewport.addEventListener("pointerup", onPointerUp, { capture: true, passive: false });
  viewport.addEventListener("pointercancel", onPointerUp, { capture: true, passive: false });
  return () => {
    viewport.removeEventListener("pointerdown", onPointerDown, { capture: true });
    viewport.removeEventListener("pointermove", onPointerMove, { capture: true });
    viewport.removeEventListener("pointerup", onPointerUp, { capture: true });
    viewport.removeEventListener("pointercancel", onPointerUp, { capture: true });
    if (cellSelectionLayer) cellSelectionLayer.style.pointerEvents = previousCellPointerEvents ?? "";
  };
};

const syncViewportHeight = () => {
  // Some Android System WebView builds report a usable innerHeight while
  // resolving 100%/100vh on the root element to zero. Use the actual visual
  // viewport so Zrimo's canvas is never laid out inside a zero-height host.
  const height = Math.max(1, Math.round(globalThis.visualViewport?.height ?? globalThis.innerHeight));
  for (const element of [document.documentElement, document.body, document.querySelector("#viewer")]) {
    if (element) element.style.height = `${height}px`;
  }
};

const notify = (method, value = "") => {
  const bridge = globalThis.YiZheOfficeBridge;
  if (bridge && typeof bridge[method] === "function") bridge[method](String(value));
};

const errorSummary = error => {
  const code = error && typeof error === "object" && "code" in error
    ? String(error.code)
    : "render-failed";
  const message = error instanceof Error || (error && typeof error === "object" && "message" in error)
    ? String(error.message)
    : String(error ?? "unknown error");
  return `${code}:${message}`;
};

const localFetch = async (input, init) => {
  const url = new URL(input instanceof Request ? input.url : input, location.href);
  const allowedPath = url.pathname.startsWith("/local-office-viewer/") ||
    url.pathname.startsWith("/assets/local-office-viewer/");
  if (url.origin !== location.origin || !allowedPath) {
    throw new Error("external-resource-blocked");
  }
  const controller = new AbortController();
  for (const signal of [officePreviewAbort?.signal, init?.signal]) {
    if (!signal) continue;
    if (signal.aborted) controller.abort();
    else signal.addEventListener("abort", () => controller.abort(), { once: true, signal: controller.signal });
  }
  return fetch(input, { ...init, signal: controller.signal, credentials: "omit", referrerPolicy: "no-referrer" });
};

function destroy() {
  if (destroyed) return Promise.resolve();
  if (destroyAttempt) return destroyAttempt;
  closeRequested = true;
  officePreviewAbort?.abort();
  destroyAttempt = (async () => {
    removeEventListener("resize", syncViewportHeight);
    globalThis.visualViewport?.removeEventListener("resize", syncViewportHeight);
    removeTouchGestures?.();
    removeTouchGestures = undefined;
    // Abort the renderer immediately, then wait for both its work and the original open.
    // A late body/engine result must settle before buffers or their owners can be retired.
    const results = await Promise.allSettled([opening, viewer?.destroy()]);
    if (results[1].status === "rejected") throw results[1].reason;
    if (viewer) await viewer.destroy();
    if (client) await client.destroy();
    sourceBytes?.fill(0);
    sourceBytes = undefined;
    document.querySelector("#viewer")?.replaceChildren();
    viewer = undefined;
    client = undefined;
    officePreviewAbort = undefined;
    destroyed = true;
  })();
  destroyAttempt.catch(() => { destroyAttempt = undefined; });
  return destroyAttempt;
}

async function open() {
  const previewAbort = new AbortController();
  officePreviewAbort = previewAbort;
  syncViewportHeight();
  addEventListener("resize", syncViewportHeight);
  globalThis.visualViewport?.addEventListener("resize", syncViewportHeight);
  const query = new URLSearchParams(location.search);
  const token = query.get("token");
  const fileName = query.get("name");
  const contentType = query.get("type") || undefined;
  if (!token || !fileName) throw new Error("invalid-session");

  const response = await localFetch(`/local-office-viewer/content/${encodeURIComponent(token)}`);
  previewAbort.signal.throwIfAborted();
  if (!response.ok) throw new Error(`source-${response.status}`);
  const bytes = new Uint8Array(await response.arrayBuffer());
  sourceBytes = bytes;
  previewAbort.signal.throwIfAborted();
  client = ViewerClient.create({
    adapters: [new OfficeDocumentAdapter({ engines: { xlsx: async (data, options) => {
      const { XlsxWorkbook } = await import("@silurus/ooxml/xlsx");
      const workbook = await XlsxWorkbook.load(data, { ...options, workerTimeoutMs: 60000 });
      try {
        await supplementOneCellImages(workbook, data, { signal: previewAbort.signal, maxOperationMs: 15000 });
        return workbook;
      } catch (error) {
        workbook.destroy();
        throw error;
      }
    } } }), new CsvDocumentAdapter()],
    assetBaseUrl: new URL("./zrimo/", location.href),
    fetch: localFetch,
    fontPolicy: { mode: "offline" },
    limits: {
      maxInputBytes: 64 * 1024 * 1024,
      maxExpandedOfficeBytes: 256 * 1024 * 1024,
      maxZipEntryBytes: 64 * 1024 * 1024,
      maxDocumentUnits: 10000,
      maxCsvCells: 2000000,
      maxConcurrentRenders: 1,
      maxOperationMs: 60000,
    },
  });
  viewer = client.createViewer({
    container: document.querySelector("#viewer"),
    ui: true,
    fit: "width",
    // Android System WebView can report a ready document while leaving an
    // OffscreenCanvas worker surface blank. Keep rendering inside this
    // isolated WebView, but use its main JS context for deterministic output.
    useWorker: false,
    layout: "continuous",
    locale: "en",
  });
  viewer.on("ready", () => notify("ready"));
  viewer.on("progress", progress => notify("progress", `${progress.phase}:${progress.ratio ?? ""}`));
  viewer.on("warning", warning => notify("warning", warning.code));
  viewer.on("error", error => notify("failed", errorSummary(error)));
  await viewer.load(bytes, { fileName, contentType, signal: previewAbort.signal });
  previewAbort.signal.throwIfAborted();
  removeTouchGestures = installTouchGestures(viewer);
}

globalThis.YiZheOffice = {
  destroy,
  requestDestroy() {
    void destroy().catch(() => {});
    return destroyed ? "complete" : "pending";
  },
};
addEventListener("unload", () => { void destroy().catch(() => {}); }, { once: true });
// The native close fence can arrive while the module's dependencies are still loading.
opening = closeRequested ? Promise.resolve() : open();
opening.catch(error => { if (!closeRequested) notify("failed", errorSummary(error)); });
