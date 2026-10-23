# Authenticated, bounded sync recovery

The previous offline supervisor tested the public `/versions` endpoint and immediately restarted both sync loops. A server returning `M_USER_LOCKED` for authenticated sync could still pass that public probe, repeatedly alternating `Running` and `Offline` and moving the Android offline banner.

This delta uses authenticated `GET /devices`, retaining `Client::send` token refresh and authentication-error notifications. It waits 5, 10, 20, then at most 30 seconds between failed probes; each HTTP probe has no automatic retry and a 10-second timeout. The existing supervisor select cancels both a sleeping backoff and an in-flight probe on stop. Only authenticated success resumes sync. Control Plane, KMS, encryption and admission remain separate mandatory checks.

Reconstruct the source from upstream commit `1c44fb66214667c6d00acaf72ab592493653708b`, applying these existing cumulative patches in order:

1. `../rtc-openid-response-priority/base-sdk.patch`
2. `../rtc-openid-response-priority/openid-response-priority.patch`
3. `../formal-origin-20261020/formal-rust-origin.patch`
4. `../formal-origin-20261020/formal-rust-regression.patch`
5. `sync-account-recovery.patch`

Verify all 1,254 entries of `source-manifest.json` after normalizing CRLF to LF. This retains the prior encrypted RTC origin and all preceding security fixes. No FFI API or generated Kotlin binding changes are required.

Focused tests:

```text
cargo test --offline --locked -p matrix-sdk-ui --features matrix-sdk/bundled-sqlite --test integration sync_service::test_sync_service -- --test-threads=1
```

The six regressions cover normal sync, offline resume, stopping, manual restart, a locked account with a healthy public server, and authentication-error notification after session revocation. Android's presentation-only connection stabilization has separate tests; it does not change authorization.

Build the existing `matrix-sdk-ffi` `dist` profile with the `sentry` feature for `aarch64-linux-android`. Replace only `jni/arm64-v8a/libmatrix_sdk_ffi.so` in the reviewed parent AAR. Record the resulting AAR/native hashes and the complete input manifest before the Android candidate is built. Real-device acceptance is recorded separately against the installed APK, not inferred from these tests.
