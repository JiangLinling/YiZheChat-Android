// Isolated candidate. The original ZIP bytes are never rewritten. Only the public,
// cached worksheet image model is supplemented before OfficeDocumentAdapter opens it.
const XDR = "http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing";
const A = "http://schemas.openxmlformats.org/drawingml/2006/main";
const R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
const PKG = "http://schemas.openxmlformats.org/package/2006/relationships";
const SS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
const EMU = 9525;
const fail = message => { throw Object.assign(new Error(message), { code: "xlsx-image-metadata" }); };
const children = (node, ns, local) => [...node.children].filter(e => e.namespaceURI === ns && e.localName === local);
const one = (node, ns, local) => {
  const matches = children(node, ns, local);
  if (matches.length !== 1) fail(`Expected one ${local}`);
  return matches[0];
};
const number = (value, maximum = Number.MAX_SAFE_INTEGER) => {
  if (!/^\d+$/.test(value ?? "")) fail("Invalid drawing coordinate");
  const n = Number(value);
  if (!Number.isSafeInteger(n) || n > maximum) fail("Drawing coordinate exceeds limit");
  return n;
};
const coordinateOffset = value => {
  if (!/^-?\d+$/.test(value ?? "")) fail("Invalid drawing offset");
  const n = Number(value);
  if (!Number.isSafeInteger(n) || Math.abs(n) > 1000000000000) fail("Drawing offset exceeds limit");
  return n;
};
function resolvePart(source, target) {
  if (!target || /[\\?#\u0000-\u0020]/.test(target) || /^[a-z][a-z0-9+.-]*:/i.test(target) || target.startsWith("//")) fail("Unsafe relationship target");
  const out = target.startsWith("/") ? [] : source.split("/").slice(0, -1);
  for (const part of target.split("/")) {
    if (!part || part === ".") continue;
    if (part === "..") { if (!out.length) fail("Relationship escapes ZIP root"); out.pop(); }
    else { if (part.includes("%")) fail("Encoded relationship target is unsupported"); out.push(part); }
  }
  return out.join("/");
}
const relPart = part => { const p = part.lastIndexOf("/"); return `${part.slice(0, p + 1)}_rels/${part.slice(p + 1)}.rels`; };
// Read only central-directory names; no second inflater or full worksheet XML tree.
async function partNames(buffer, budget) {
  const view = new DataView(buffer);
  const bytes = new Uint8Array(buffer);
  let end = -1;
  for (let i = bytes.length - 22; i >= Math.max(0, bytes.length - 65557); i--) {
    if (view.getUint32(i, true) === 0x06054b50 && i + 22 + view.getUint16(i + 20, true) === bytes.length) { end = i; break; }
  }
  if (end < 0) fail("Missing ZIP directory");
  const count = view.getUint16(end + 10, true), length = view.getUint32(end + 12, true), start = view.getUint32(end + 16, true);
  if (view.getUint16(end + 4, true) || view.getUint16(end + 6, true) || view.getUint16(end + 8, true) !== count || count === 65535 || count > 16384 || start + length > end) fail("Unsupported ZIP directory");
  const names = new Set(), decoder = new TextDecoder("utf-8", { fatal: true });
  let cursor = start;
  for (let n = 0; n < count; n++) {
    if (n % 256 === 0) await budget.checkpoint();
    if (cursor + 46 > start + length || view.getUint32(cursor, true) !== 0x02014b50) fail("Invalid ZIP entry");
    const nameLength = view.getUint16(cursor + 28, true), extra = view.getUint16(cursor + 30, true), comment = view.getUint16(cursor + 32, true);
    const next = cursor + 46 + nameLength + extra + comment;
    if (nameLength > 1024 || next > start + length) fail("ZIP name exceeds limit");
    const name = decoder.decode(bytes.subarray(cursor + 46, cursor + 46 + nameLength));
    if (names.has(name) || name.startsWith("/") || name.split("/").some(p => p === "..") || /[\\\u0000]/.test(name)) fail("Ambiguous ZIP part");
    names.add(name); cursor = next;
  }
  if (cursor !== start + length) fail("ZIP directory length mismatch");
  return names;
}
function rels(document, source) {
  const result = new Map();
  if (document.documentElement.namespaceURI !== PKG || document.documentElement.localName !== "Relationships") fail("Invalid relationship document");
  for (const e of children(document.documentElement, PKG, "Relationship")) {
    const id = e.getAttribute("Id"), type = e.getAttribute("Type");
    if (!id || result.has(id)) fail("Ambiguous relationship ID");
    result.set(id, { type, external: e.getAttribute("TargetMode") === "External", target: e.getAttribute("Target"), source });
  }
  return result;
}
function imageMime(path) {
  const extension = path.split(".").pop().toLowerCase();
  const mime = { png: "image/png", jpg: "image/jpeg", jpeg: "image/jpeg", gif: "image/gif", bmp: "image/bmp", webp: "image/webp" }[extension];
  if (!mime) fail("Unsupported embedded image format");
  return mime;
}
function createBudget({ signal, maxOperationMs = 15000 } = {}) {
  const deadline = performance.now() + Math.min(15000, Math.max(1, maxOperationMs));
  let lastYield = performance.now();
  const check = () => {
    if (signal?.aborted) throw Object.assign(new Error("Image parsing cancelled"), { code: "xlsx-image-cancelled" });
    if (performance.now() >= deadline) throw Object.assign(new Error("Image parsing timed out"), { code: "xlsx-image-timeout" });
  };
  return {
    check,
    async checkpoint() {
      check();
      if (performance.now() - lastYield >= 8) {
        await new Promise(resolve => setTimeout(resolve, 0));
        lastYield = performance.now(); check();
      }
    },
    async wait(promise) {
      check();
      let timer, listener;
      try {
        const stopped = new Promise((_, reject) => {
          listener = () => reject(Object.assign(new Error("Image parsing cancelled"), { code: "xlsx-image-cancelled" }));
          signal?.addEventListener("abort", listener, { once: true });
          timer = setTimeout(() => reject(Object.assign(new Error("Image parsing timed out"), { code: "xlsx-image-timeout" })), Math.max(0, deadline - performance.now()));
        });
        const value = await Promise.race([promise, stopped]); check(); return value;
      } finally { clearTimeout(timer); signal?.removeEventListener("abort", listener); }
    },
  };
}
async function axisIndex(defaultSize, overrides, count, budget) {
  if (!Number.isFinite(defaultSize) || defaultSize < 0) fail("Invalid default worksheet dimension");
  await budget.checkpoint();
  const entries = [...overrides].sort((a, b) => a[0] - b[0]);
  await budget.checkpoint();
  const indices = [], deltas = [0];
  for (let n = 0; n < entries.length; n++) {
    if (n % 256 === 0) await budget.checkpoint();
    const [index, size] = entries[n];
    if (!Number.isInteger(index) || index < 1 || index > count || !Number.isFinite(size) || size < 0) fail("Invalid worksheet dimension");
    indices.push(index - 1); deltas.push(deltas[deltas.length - 1] + size - defaultSize);
  }
  const position = index => {
    let low = 0, high = indices.length;
    while (low < high) { const mid = (low + high) >>> 1; if (indices[mid] < index) low = mid + 1; else high = mid; }
    return index * defaultSize + deltas[low];
  };
  return (start, offset, extent) => {
    budget.check();
    const target = position(start) + (offset + extent) / EMU;
    if (target > position(count)) fail("Image exceeds worksheet bounds");
    let low = 0, high = count;
    while (low < high) { const mid = (low + high) >>> 1; if (position(mid) <= target) low = mid + 1; else high = mid; }
    const index = Math.max(0, Math.min(count - 1, low - 1));
    return { index, offset: Math.round((target - position(index)) * EMU) };
  };
}
async function worksheetAxes(worksheet, budget) {
  const widthPx = width => Math.max(0, Math.trunc(((256 * width + 16) / 256) * 8));
  const heightPx = height => Math.max(0, Math.round(height * 4 / 3));
  const columns = new Map(), rows = new Map();
  let visited = 0;
  for (const [index, width] of Object.entries(worksheet.colWidths ?? {})) { if (visited++ % 256 === 0) await budget.checkpoint(); columns.set(Number(index), widthPx(width)); }
  for (const [index, hidden] of Object.entries(worksheet.colHidden ?? {})) { if (visited++ % 256 === 0) await budget.checkpoint(); if (hidden) columns.set(Number(index), 0); }
  for (const [index, height] of Object.entries(worksheet.rowHeights ?? {})) { if (visited++ % 256 === 0) await budget.checkpoint(); rows.set(Number(index), heightPx(height)); }
  for (const row of worksheet.rows ?? []) { if (visited++ % 256 === 0) await budget.checkpoint(); if (row.hidden) rows.set(row.index, 0); else if (row.height != null) rows.set(row.index, heightPx(row.height)); }
  return { col: await axisIndex(widthPx(worksheet.defaultColWidth ?? 8.43), columns, 16384, budget), row: await axisIndex(heightPx(worksheet.defaultRowHeight ?? 15), rows, 1048576, budget) };
}
function makeAnchor(anchor, worksheet, relationships, drawingPart, names, axes) {
  const from = one(anchor, XDR, "from"), ext = one(anchor, XDR, "ext"), pic = one(anchor, XDR, "pic");
  const fromCol = number(one(from, XDR, "col").textContent, 16383), fromRow = number(one(from, XDR, "row").textContent, 1048575);
  const fromColOff = coordinateOffset(one(from, XDR, "colOff").textContent), fromRowOff = coordinateOffset(one(from, XDR, "rowOff").textContent);
  const nativeExtCx = number(ext.getAttribute("cx")), nativeExtCy = number(ext.getAttribute("cy"));
  if (!nativeExtCx || !nativeExtCy) fail("Empty image extent");
  const fill = one(pic, XDR, "blipFill"), blip = one(fill, A, "blip"), image = relationships.get(blip.getAttributeNS(R, "embed"));
  if (!image || image.external || !image.type.endsWith("/image") || blip.hasAttributeNS(R, "link")) fail("External or missing image relationship");
  const imagePath = resolvePart(drawingPart, image.target);
  if (!names.has(imagePath)) fail("Missing image part");
  // Never silently misrepresent rotations, effects, or grouped/vector pictures.
  const shape = one(pic, XDR, "spPr"), transforms = children(shape, A, "xfrm");
  const existingShape = (worksheet.shapeGroups ?? []).some(g => g.fromCol === fromCol && g.fromRow === fromRow && g.fromColOff === fromColOff && g.fromRowOff === fromRowOff && g.nativeExtCx === nativeExtCx && g.nativeExtCy === nativeExtCy && (g.shapes ?? []).some(s => s.geom?.type === "image" && s.geom.imagePath === imagePath));
  if (transforms.some(e => ["rot", "flipH", "flipV"].some(k => e.hasAttribute(k) && !["0", "false"].includes(e.getAttribute(k))))) {
    // The upstream generic shape renderer already handles these transforms.
    // Preserve that existing public model instead of replacing or rejecting it.
    if (existingShape) return null;
    fail("Unsupported image transform");
  }
  if ([...shape.children].some(e => e.namespaceURI === A && !["xfrm", "prstGeom", "noFill", "ln"].includes(e.localName))) { if (existingShape) return null; fail("Unsupported image shape effect"); }
  if ([...blip.children].some(e => e.namespaceURI === A && e.localName !== "alphaModFix")) { if (existingShape) return null; fail("Unsupported image color effect"); }
  // End markers expand the adapter's scrolling range. Rendering uses the exact
  // original oneCell extent, never these computed end markers as the picture size.
  const colEnd = axes.col(fromCol, fromColOff, nativeExtCx);
  const rowEnd = axes.row(fromRow, fromRowOff, nativeExtCy);
  const result = { fromCol, fromColOff, fromRow, fromRowOff, toCol: colEnd.index, toColOff: colEnd.offset, toRow: rowEnd.index, toRowOff: rowEnd.offset, nativeExtCx, nativeExtCy, editAs: "oneCell", imagePath, mimeType: imageMime(imagePath) };
  const crops = children(fill, A, "srcRect");
  if (crops.length > 1) fail("Ambiguous image crop");
  if (crops.length) { result.srcRect = Object.fromEntries(["l", "t", "r", "b"].map(k => [k, number(crops[0].getAttribute(k) || "0", 100000) / 100000])); if (result.srcRect.l + result.srcRect.r >= 1 || result.srcRect.t + result.srcRect.b >= 1) fail("Empty image crop"); }
  const alphas = children(blip, A, "alphaModFix");
  if (alphas.length > 1) fail("Ambiguous image alpha");
  if (alphas.length) result.alpha = number(alphas[0].getAttribute("amt"), 100000) / 100000;
  return result;
}
export async function supplementOneCellImages(book, buffer, options = {}) {
  const budget = createBudget(options);
  const names = await partNames(buffer, budget);
  let totalMetadataBytes = 0;
  const read = async part => {
    if (!names.has(part)) fail("Missing drawing metadata part");
    await budget.checkpoint();
    const blob = await budget.wait(book.getImage(part, "application/xml"));
    totalMetadataBytes += blob.size;
    if (blob.size > 4 * 1024 * 1024 || totalMetadataBytes > 16 * 1024 * 1024) fail("Drawing metadata exceeds limit");
    const source = await budget.wait(blob.text());
    if (/<!DOCTYPE|<!ENTITY/i.test(source)) fail("XML declarations are forbidden");
    const xml = new DOMParser().parseFromString(source, "application/xml");
    await budget.checkpoint();
    if (xml.getElementsByTagName("parsererror").length) fail("Invalid drawing XML");
    return xml;
  };
  const root = rels(await read("_rels/.rels"), "");
  const offices = [...root.values()].filter(r => r.type.endsWith("/officeDocument"));
  if (offices.length !== 1 || offices[0].external) fail("Invalid workbook relationship");
  const workbookPart = resolvePart("", offices[0].target);
  const workbook = await read(workbookPart), workbookRelationships = rels(await read(relPart(workbookPart)), workbookPart);
  const sheets = [...workbook.getElementsByTagNameNS(SS, "sheet")];
  if (sheets.length !== book.sheetCount) fail("Workbook sheet mapping mismatch");
  const additions = [];
  let anchorCount = 0;
  for (let index = 0; index < sheets.length; index++) {
    await budget.checkpoint();
    const sheet = sheets[index], relationship = workbookRelationships.get(sheet.getAttributeNS(R, "id"));
    if (!relationship || relationship.external || !relationship.type.endsWith("/worksheet") || sheet.getAttribute("name") !== book.sheetNames[index]) fail("Unsupported workbook sheet relationship");
    const sheetPart = resolvePart(workbookPart, relationship.target), sheetRels = relPart(sheetPart);
    if (!names.has(sheetRels)) continue;
    const relationships = rels(await read(sheetRels), sheetPart), worksheet = await budget.wait(book.getWorksheet(index));
    let axes;
    const pending = [], redundantGroups = new Set();
    for (const drawing of [...relationships.values()].filter(r => r.type.endsWith("/drawing"))) {
      if (drawing.external) fail("External drawing relationship");
      const drawingPart = resolvePart(sheetPart, drawing.target), drawingXML = await read(drawingPart);
      if (drawingXML.documentElement.namespaceURI !== XDR || drawingXML.documentElement.localName !== "wsDr") fail("Unsupported drawing namespace");
      const anchors = children(drawingXML.documentElement, XDR, "oneCellAnchor");
      if (!anchors.length) continue;
      let pictureRelationships;
      for (const anchor of anchors) {
        await budget.checkpoint();
        if (++anchorCount > 10000) fail("Too many drawing anchors");
        // Non-picture shapes/charts remain owned by the original generic renderer.
        if (children(anchor, XDR, "pic").length === 0) continue;
        if (children(anchor, XDR, "pic").length !== 1) fail("Ambiguous one-cell picture");
        pictureRelationships ??= rels(await read(relPart(drawingPart)), drawingPart);
        axes ??= await worksheetAxes(worksheet, budget);
        const image = makeAnchor(anchor, worksheet, pictureRelationships, drawingPart, names, axes);
        if (!image) continue;
        const same = other => other.imagePath === image.imagePath && other.fromCol === image.fromCol && other.fromRow === image.fromRow && other.fromColOff === image.fromColOff && other.fromRowOff === image.fromRowOff;
        for (const group of worksheet.shapeGroups ?? []) {
          if (group.fromCol !== image.fromCol || group.fromRow !== image.fromRow || group.fromColOff !== image.fromColOff || group.fromRowOff !== image.fromRowOff || group.nativeExtCx !== image.nativeExtCx || group.nativeExtCy !== image.nativeExtCy || group.editAs !== "oneCell") continue;
          const shapes = group.shapes ?? [], shape = shapes[0];
          if (shapes.length === 1 && shape.geom?.type === "image" && shape.geom.imagePath === image.imagePath && shape.x === 0 && shape.y === 0 && shape.w === 1 && shape.h === 1 && !shape.rot && !shape.flipH && !shape.flipV && !shape.strokeWidth && JSON.stringify(shape.geom.srcRect ?? null) === JSON.stringify(image.srcRect ?? null) && (shape.geom.alpha ?? 1) === (image.alpha ?? 1) && !shape.geom.duotone) redundantGroups.add(group);
          else fail("Ambiguous duplicate image shape");
        }
        if (!(worksheet.images ?? []).some(same) && !pending.some(same)) pending.push(image);
        if (pending.length > 10000) fail("Too many drawing anchors");
      }
    }
    additions.push({ worksheet, pending, redundantGroups });
  }
  // Commit atomically after all parts pass. Any failure leaves the backend unchanged.
  await budget.checkpoint();
  budget.check();
  for (const { worksheet, pending, redundantGroups } of additions) {
    if (pending.length) worksheet.images = [...(worksheet.images ?? []), ...pending];
    if (redundantGroups.size) worksheet.shapeGroups = (worksheet.shapeGroups ?? []).filter(g => !redundantGroups.has(g));
  }
  return { added: additions.reduce((n, a) => n + a.pending.length, 0), metadataBytes: totalMetadataBytes };
}
