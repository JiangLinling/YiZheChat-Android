/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.securestorage.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.security.securestorage.api.DeviceRootKeyService
import io.element.android.security.securestorage.api.DeviceWrappedKey
import io.element.android.security.securestorage.api.DiagnosticCollectionState
import io.element.android.security.securestorage.api.SecureStorageRoots
import io.element.android.security.securestorage.test.FakeDeviceRootKeyService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.SocketTimeoutException

class SecureDiagnosticLogStoreTest {
    @Test fun `direct management reason media stage auth stage and startup timing survive redaction`() {
        for (reason in listOf("ENVELOPE_DECODE", "SIGNED_STATE_ACCEPT", "RELEASE_IDENTITY")) {
            val event = DiagnosticLogRedactor.redact(
                1,
                5,
                "YiZheManagement",
                "YZMGMT_RELEASE_FAILURE=$reason failure_chain=IllegalStateException@h2:178 password=PRIVATE",
                null
            )
            assertThat(event.details).contains("YZMGMT_RELEASE_FAILURE=$reason")
            assertThat(event.details).contains("failure_site=IllegalStateException@h2:178")
            assertThat(event.details).doesNotContain("PRIVATE")
        }
        val media = DiagnosticLogRedactor.redact(1, 5, "YiZheSecureMedia", "stage=download_authorization result=failure body=CHAT", null)
        assertThat(media.details).contains("stage=download_authorization")
        assertThat(media.details).contains("result=failure")
        assertThat(media.details).doesNotContain("CHAT")
        val auth = DiagnosticLogRedactor.redact(1, 6, "YiZheAuth", "Login finalization failed at session-persistence: java.io.IOException", null)
        assertThat(auth.details).contains("auth_stage=session-persistence")
        assertThat(auth.details).contains("IOException")
        val startup = DiagnosticLogRedactor.redact(1, 4, "YiZheStartup", "[Startup] +123ms Application.onCreate thread=PRIVATE", null)
        assertThat(startup.details).contains("startup_stage=Application.onCreate")
        assertThat(startup.details).contains("elapsed_ms=123")
        assertThat(startup.details).doesNotContain("PRIVATE")
        val panic = DiagnosticLogRedactor.redact(1, 6, "org.matrix.rust.sdk", "thread 'PRIVATE' panicked at src/transport.rs:42", null)
        assertThat(panic.details).contains("RUST_PANIC")
        assertThat(panic.details).doesNotContain("PRIVATE")
        val frame = DiagnosticLogRedactor.redact(1, 6, "org.matrix.rust.sdk", "0: new_sdk_crate::transport::run", null)
        assertThat(frame.stack).contains("new_sdk_crate::transport::run")
    }
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val roots by lazy {
        object : SecureStorageRoots {
            override fun persistentRoot() = File(temporaryFolder.root, "persistent")
            override fun runtimeRoot() = File(temporaryFolder.root, "runtime")
        }
    }
    private val key = FakeDeviceRootKeyService()
    private fun files() = File(roots.persistentRoot(), "diagnostics/v1").listFiles().orEmpty().filter { it.extension == "elog" }

    @Test fun `unlisted components keep actionable HTTP certificate and SDK failures but never secrets`() {
        val http = DiagnosticLogRedactor.redact(1, 6, "RegistrationHttp", "HTTP 503 endpoint=https://security.yizhesy.cn/v1/register?token=SECRET", null)
        assertThat(http.source).isEqualTo("RegistrationHttp")
        assertThat(http.details).contains("http_status=503")
        assertThat(http.details).contains("/v1/register")
        assertThat(http.details).doesNotContain("SECRET")
        val cert = DiagnosticLogRedactor.redact(2, 6, "AdmissionClient", "CERT_DATE_INVALID body=CHAT_SECRET", null)
        assertThat(cert.details).contains("CERT_DATE_INVALID")
        assertThat(cert.details).doesNotContain("CHAT_SECRET")
        val timeout = DiagnosticLogRedactor.redact(3, 6, "org.matrix.rust.sdk", "SocketTimeoutException access_token=TOKEN", null)
        assertThat(timeout.details).contains("NETWORK_TIMEOUT")
        assertThat(timeout.details).contains("SocketTimeoutException")
        assertThat(timeout.details).doesNotContain("TOKEN")
        val update = DiagnosticLogRedactor.redact(
            4,
            5,
            "UpdateDownload",
            "SDK_UPDATE_TRANSFER authority=CDN http_status=206 cache=hit range_start=100 range_total=200 bytes_per_second=2000 DOWNLOAD_FAILED",
            null
        )
        listOf("authority=CDN", "http_status=206", "cache=hit", "range_start=100", "range_total=200", "bytes_per_second=2000", "DOWNLOAD_FAILED")
            .forEach { assertThat(update.details).contains(it) }
    }

    @Test fun `full code stack causes and suppressed survive without throwable messages`() {
        val error = IllegalStateException("password=SECRET", SocketTimeoutException("CHAT_BODY"))
        error.addSuppressed(IOException("RECOVERY_KEY"))
        val event = DiagnosticLogRedactor.redact(1, 6, "CallSession", "payload=PRIVATE", error, "CallSession.kt:42")
        assertThat(event.stack).contains("Caused by: java.net.SocketTimeoutException")
        assertThat(event.stack).contains("Suppressed: java.io.IOException")
        assertThat(event.stack).contains("SecureDiagnosticLogStoreTest")
        listOf("SECRET", "CHAT_BODY", "RECOVERY_KEY", "PRIVATE").forEach { assertThat(event.toString()).doesNotContain(it) }
    }

    @Test fun `all priorities survive process reopen and disk is encrypted`() = runBlocking {
        val store = DefaultSecureDiagnosticLogStore(roots, key)
        (2..7).forEach { store.append(it, "NetworkTransport", "HTTP 503 password=NOT_ON_DISK") }
        assertThat(store.flushBlocking(5000)).isTrue()
        store.closeForTests()
        files().forEach {
            val raw = it.readBytes().decodeToString()
            assertThat(raw).doesNotContain("NetworkTransport")
            assertThat(raw).doesNotContain("NOT_ON_DISK")
        }
        val reopened = DefaultSecureDiagnosticLogStore(roots, key)
        try {
            val snapshot = reopened.snapshot()
            assertThat(snapshot.state).isEqualTo(DiagnosticCollectionState.AVAILABLE)
            assertThat(snapshot.events.map { it.priority }).containsExactlyElementsIn(2..7).inOrder()
            assertThat(snapshot.events.all { "http_status=503" in it.details }).isTrue()
        } finally {
            reopened.closeForTests()
        }
    }

    @Test fun `destroyed installation key never regenerates a readable replacement`() = runBlocking {
        val store = DefaultSecureDiagnosticLogStore(roots, key)
        store.append(4, "Startup", "HTTP 503")
        assertThat(store.flushBlocking(5000)).isTrue()
        key.destroy()
        store.append(6, "Startup", "KMS_UNAVAILABLE")
        assertThat(store.flushBlocking(5000)).isFalse()
        assertThat(store.snapshot().state).isEqualTo(DiagnosticCollectionState.KEY_UNAVAILABLE)
        store.closeForTests()
    }

    @Test fun `half written frame seals segment and later committed records remain readable`() = runBlocking {
        val store = DefaultSecureDiagnosticLogStore(roots, key)
        store.append(4, "Network", "HTTP 502")
        assertThat(store.flushBlocking(5000)).isTrue()
        val normal = store.appendEncryptedFrame
        store.appendEncryptedFrame = { file, frame ->
            FileOutputStream(file, true).use { it.write(frame, 0, frame.size / 2) }
            throw IOException("injected partial disk write")
        }
        store.append(6, "Network", "HTTP 503")
        assertThat(store.flushBlocking(5000)).isFalse()
        store.appendEncryptedFrame = normal
        store.append(4, "Network", "HTTP 504")
        val snapshot = store.snapshot()
        assertThat(files()).hasSize(2)
        assertThat(snapshot.state).isEqualTo(DiagnosticCollectionState.PARTIAL)
        assertThat(snapshot.recordCount).isEqualTo(2)
        assertThat(snapshot.events.last().details).contains("504")
        assertThat(snapshot.unreadableSegments).isEqualTo(1)
        assertThat(snapshot.droppedEvents).isEqualTo(1)
        store.closeForTests()
    }

    @Test fun `failed retention delete refuses new data and stays visible after later small writes`() = runBlocking {
        val store = DefaultSecureDiagnosticLogStore(roots, key)
        store.maximumSegmentBytes = 600
        store.maximumTotalBytes = 600
        store.maximumSegments = 1
        store.deleteSegment = { false }
        repeat(12) { store.append(4, "Network", "HTTP 503") }
        store.flushBlocking(5000)
        assertThat(files().sumOf { it.length() }).isAtMost(600)
        assertThat(files()).hasSize(1)
        val snapshot = store.snapshot()
        assertThat(snapshot.state).isEqualTo(DiagnosticCollectionState.PARTIAL)
        assertThat(snapshot.droppedEvents).isGreaterThan(0)
        assertThat(snapshot.failureCode).contains("RETENTION")
        store.closeForTests()
    }

    @Test fun `migration counts only committed records and reports truncated source incomplete`() = runBlocking {
        val store = DefaultSecureDiagnosticLogStore(roots, key)
        val good = temporaryFolder.newFile("old.log").apply { writeText("HTTP 503\nCERT_DATE_INVALID\n") }
        val large = temporaryFolder.newFile("truncated.log").apply { writeBytes(ByteArray(1024 * 1024 + 1) { 65 }) }
        val result = store.migrateLegacy(listOf(good, large))
        assertThat(result.filesMigrated).isEqualTo(1)
        assertThat(result.filesFailed).isEqualTo(1)
        assertThat(result.recordsMigrated).isEqualTo(2)
        assertThat(store.snapshot().recordCount).isEqualTo(2)
        assertThat(good.exists()).isTrue() // Cleanup belongs to the caller, not a false migration success.
        store.closeForTests()
    }

    @Test fun `failed migration write cannot count enqueued records as migrated`() = runBlocking {
        val store = DefaultSecureDiagnosticLogStore(roots, key)
        store.appendEncryptedFrame = { _, _ -> throw IOException("write rejected") }
        val file = temporaryFolder.newFile("old.log").apply { writeText("HTTP 503\n") }
        val result = store.migrateLegacy(listOf(file))
        assertThat(result.filesMigrated).isEqualTo(0)
        assertThat(result.recordsMigrated).isEqualTo(0)
        assertThat(result.filesFailed).isEqualTo(1)
        store.closeForTests()
    }

    @Test fun `DRK diagnostics across dispatcher switch do not recurse into collector`() = runBlocking {
        lateinit var store: DefaultSecureDiagnosticLogStore
        val noisyKey = object : DeviceRootKeyService by key {
            override suspend fun wrapKey(keyMaterial: ByteArray, binding: ByteArray): DeviceWrappedKey = withContext(Dispatchers.IO) {
                store.append(4, "DeviceRootKey", "KMS_WRAP_START")
                key.wrapKey(keyMaterial, binding)
            }
            override suspend fun unwrapKey(wrappedKey: DeviceWrappedKey, binding: ByteArray): ByteArray = withContext(Dispatchers.IO) {
                store.append(4, "DeviceRootKey", "KMS_UNWRAP_START")
                key.unwrapKey(wrappedKey, binding)
            }
        }
        store = DefaultSecureDiagnosticLogStore(roots, noisyKey)
        try {
            store.append(4, "Startup", "HTTP 503")
            assertThat(store.flushBlocking(5000)).isTrue()
            assertThat(store.snapshot().recordCount).isEqualTo(1)
        } finally {
            store.closeForTests()
        }
    }
}
