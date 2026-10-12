import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import { dirname } from "node:path";

// Zrimo 0.1.2's scroll axes use fractional bands while Silurus rounds each band to
// a CSS pixel. Transform only this pinned source at build time; keep node_modules
// unchanged, and reject an upstream drift instead of guessing another patch.
export const SPREADSHEET_VIEWPORT_SOURCE_SHA256 = "e4d989914c9e8fed95a4523a5b45056c990d0bc23f2d59472a0516823a3f2572";

export function transformSpreadsheetPixelGeometry(bytes) {
  if (createHash("sha256").update(bytes).digest("hex") !== SPREADSHEET_VIEWPORT_SOURCE_SHA256) {
    throw new Error("Unreviewed spreadsheet viewport source; update the pinned compatibility patch first");
  }
  let source = bytes.toString("utf8");
  const replace = (before, after) => {
    if (source.split(before).length !== 2) throw new Error("Spreadsheet compatibility patch context differs");
    source = source.replace(before, after);
  };
  replace("export class AxisGeometry {", `// Return logical lengths whose scaled size equals Silurus's per-band pixel rounding.
const pixelBandSize = (size, zoom) => zoom === undefined ? size : Math.round(size * zoom) / zoom;
export class AxisGeometry {`);
  replace("constructor(count, defaultSize, overrides = {}) {", "constructor(count, defaultSize, overrides = {}, pixelZoom) {");
  replace("this.#defaultSize = nonNegative(defaultSize, 1);", "this.#defaultSize = pixelBandSize(nonNegative(defaultSize, 1), pixelZoom);");
  replace(".sort(([left], [right]) => left - right);", ".map(([index, size]) => [index, pixelBandSize(size, pixelZoom)])\n            .sort(([left], [right]) => left - right);");
  replace("    #appliedZoom;", "    #appliedZoom;\n    #geometryPixelZoom;");
  replace("        const defaultRowHeight = sheet?.defaultRowHeight ?? DEFAULT_ROW_HEIGHT;", `        const defaultRowHeight = sheet?.defaultRowHeight ?? DEFAULT_ROW_HEIGHT;
        const pixelZoom = this.#pixelGeometryZoom();
        const geometryChanged = pixelZoom !== this.#geometryPixelZoom;`);
  replace("Math.max(1, defaultColumnWidth));", "Math.max(1, pixelBandSize(defaultColumnWidth, pixelZoom)));");
  replace("Math.max(1, defaultRowHeight));", "Math.max(1, pixelBandSize(defaultRowHeight, pixelZoom)));");
  replace("if (this.#columns.count !== columnCount)", "if (geometryChanged || this.#columns.count !== columnCount)");
  replace("new AxisGeometry(columnCount, defaultColumnWidth, this.#effectiveColumnWidths());", "new AxisGeometry(columnCount, defaultColumnWidth, this.#effectiveColumnWidths(), pixelZoom);");
  replace("if (this.#rows.count !== rowCount)", "if (geometryChanged || this.#rows.count !== rowCount)");
  replace("new AxisGeometry(rowCount, defaultRowHeight, sheet?.rowHeights);", "new AxisGeometry(rowCount, defaultRowHeight, sheet?.rowHeights, pixelZoom);\n        this.#geometryPixelZoom = pixelZoom;");
  replace("new AxisGeometry(this.#columns.count, this.#sheet?.defaultColumnWidth ?? DEFAULT_COLUMN_WIDTH, this.#effectiveColumnWidths());", "new AxisGeometry(this.#columns.count, this.#sheet?.defaultColumnWidth ?? DEFAULT_COLUMN_WIDTH, this.#effectiveColumnWidths(), this.#pixelGeometryZoom());");
  replace(`    #rowHeaderWidth() {
        return this.#sheet?.rowHeaderWidth ?? DEFAULT_ROW_HEADER_WIDTH;
    }
    #columnHeaderHeight() {
        return this.#sheet?.columnHeaderHeight ?? DEFAULT_COLUMN_HEADER_HEIGHT;
    }`, `    #pixelGeometryZoom() {
        return this.#info?.format === "xlsx" || this.#info?.format === "xls" ? this.#host.state.zoom : undefined;
    }
    #rowHeaderWidth() {
        return pixelBandSize(this.#sheet?.rowHeaderWidth ?? DEFAULT_ROW_HEADER_WIDTH, this.#pixelGeometryZoom());
    }
    #columnHeaderHeight() {
        return pixelBandSize(this.#sheet?.columnHeaderHeight ?? DEFAULT_COLUMN_HEADER_HEIGHT, this.#pixelGeometryZoom());
    }`);
  return source;
}

export function spreadsheetPixelGeometryPlugin() {
  return {
    name: "yizhe-spreadsheet-pixel-geometry",
    setup(build) {
      build.onLoad({ filter: /[\\/]@zrimo[\\/]viewer[\\/]dist[\\/]spreadsheet-viewport\.js$/ }, async args => ({
        contents: transformSpreadsheetPixelGeometry(await readFile(args.path)),
        loader: "js",
        resolveDir: dirname(args.path),
      }));
    },
  };
}
