# Local Office Viewer Supply Chain

- Package: `@zrimo/viewer`
- Pinned version: `0.1.2`
- Declared license: `MIT OR Apache-2.0`
- Registry integrity: `sha512-dSU48kB7BuK3LJgZ+XYwW9IpTvC6ar0MBeRME6srou567msVMtPjyNW7EHk4tQaPAZ3WrSGtTnq11vkJpSC/og==`
- Parser dependency: `@silurus/ooxml` `0.72.2` (MIT), pinned transitively by the lockfile with integrity
  `sha512-5uKuHwsIrWwpWITuArzoHjkflwPV0oiDEPxD20yq8RTnS7a5CEm8ll35/BKc6Ug5YITHkLDSWN5DT6ZI6AqIwg==`.
- Runtime parser assets: the DOCX/XLSX/PPTX WASM files are copied beside the generated chunks because
  the parser resolves their URLs at runtime; they remain APK-local and cannot be loaded from the network.
- Install policy: `npm install --ignore-scripts`; no lifecycle script may run.
- Runtime policy: assets are bundled into the APK and served only from the viewer's synthetic local HTTPS origin.

The lockfile, actual package contents, transitive dependency licenses, WASM/JS asset list and packaged APK size must be checked before any GO decision.

## Spreadsheet pixel geometry compatibility

The build applies `spreadsheet-pixel-geometry.mjs` to the pinned Zrimo spreadsheet
viewport source (SHA-256 `e4d989914c9e8fed95a4523a5b45056c990d0bc23f2d59472a0516823a3f2572`).
XLS/XLSX scroll bands use the same per-band pixel rounding as Silurus rendering,
so the final row remains reachable at fractional zoom. Natural fit geometry and
CSV geometry retain the original behavior. Source hash and replacement contexts
are checked before bundling; upstream drift fails the build. Installed package
files, parser binaries, input documents, media bytes and the lockfile are unchanged.

Run `node test-spreadsheet-pixel-geometry.mjs` after installing the pinned dependencies.
The regression checks include unchanged CSV axes and sparse million-row offsets.
Browser viewport/zoom tests and real-device acceptance remain separate gates.

## Runtime cleanup compatibility

The build applies `runtime-cleanup.mjs` to six exact Zrimo 0.1.2 source hashes.
It waits for admitted parsing/rendering/text operations, retains failed-close handles,
retains Office parser backends created before an open failure, clears owned input and
parser-copy buffers after backend cleanup, and permits original client/registry/font
cleanup to retry. Source drift fails before bundling; installed package files and the
dependency lockfile are not modified. This uses the existing viewer and adapter owners.

The page keeps its original load promise and source buffer until cleanup finishes.
`requestDestroy()` reports `complete` only after that work, viewer/client teardown and
buffer clearing. The native caller waits for that response before destroying the WebView;
an uninitialized-page response is accepted only if that document never acquired content.
The preinitialization close fence prevents a later module open. A renderer exit is accepted
only through Android's `onRenderProcessGone` callback for the same WebView, not by comparing
random process identifiers. This does not implement recovery after application-process death
before a cleanup fact was persisted.

Run `node test-runtime-cleanup.mjs`, `node test-viewer-entry-cleanup.mjs` and the geometry
regression before `node build-assets.mjs`. Dependency-level tests run the transformed real
viewer/adapter implementations with controlled blocking/failing backends; they do not prove
real WASM render output, physical heap erasure, Android renderer callbacks or device acceptance.
