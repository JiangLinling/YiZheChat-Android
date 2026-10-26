/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.rageshake.impl.reporter

import com.google.common.truth.Truth.assertThat
import io.element.android.security.securestorage.api.DiagnosticCollectionState
import io.element.android.security.securestorage.api.SecureStorageRoots
import io.element.android.security.securestorage.impl.DefaultSecureDiagnosticLogStore
import io.element.android.security.securestorage.test.FakeDeviceRootKeyService
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.zip.GZIPInputStream

@RunWith(RobolectricTestRunner::class)
class DiagnosticSnapshotProviderTest {
    @Test fun `real encrypted store snapshot exports exact app gzip for backend round trip`() = runTest {
        val directory = java.nio.file.Files.createTempDirectory("diagnostic-roundtrip").toFile()
        val roots = object : SecureStorageRoots {
            override fun persistentRoot() = File(directory, "persistent")
            override fun runtimeRoot() = File(directory, "runtime")
        }
        val store = DefaultSecureDiagnosticLogStore(roots, FakeDeviceRootKeyService())
        store.append(
            6,
            "RegistrationClient",
            "HTTP 503 CERT_DATE_INVALID password=DO_NOT_UPLOAD",
            java.net.SocketTimeoutException("access_token=DO_NOT_UPLOAD")
        )
        assertThat(store.flushBlocking(5000)).isTrue()
        val bundle = DiagnosticSnapshotProvider(store).get()
        assertThat(bundle.canUpload).isTrue()
        assertThat(bundle.text).doesNotContain("DO_NOT_UPLOAD")
        assertThat(bundle.text).contains("SocketTimeoutException")
        System.getProperty("diagnostic.fixture.dir")?.let { path ->
            File(path).apply { mkdirs() }.also {
                File(it, "android-diagnostics.jsonl.gz").writeBytes(bundle.compressed)
                File(it, "android-manifest.json").writeText(bundle.manifest)
            }
        }
    }
    @Test fun `view and upload share immutable bytes until explicit refresh`() = runTest {
        val store = FakeDiagnosticLogStore()
        val provider = DiagnosticSnapshotProvider(store)
        val viewed = provider.get()
        store.result = store.result.copy(id = "abcdefab-1234-1234-1234-123456789abc")
        val uploaded = provider.get()
        assertThat(uploaded).isSameInstanceAs(viewed)
        assertThat(store.reads).isEqualTo(1)
        val raw = GZIPInputStream(viewed.compressed.inputStream()).use { it.readBytes() }
        val manifest = JSONObject(viewed.manifest)
        assertThat(raw.size.toLong()).isEqualTo(manifest.getLong("uncompressed_bytes"))
        assertThat(raw.decodeToString()).isEqualTo(viewed.text)
        assertThat(JSONObject(raw.decodeToString().trim()).getString("stack")).contains("Client.kt:42")
        assertThat(provider.get(refresh = true).snapshot.id).isEqualTo(store.result.id)
        provider.clear()
        provider.get()
        assertThat(store.reads).isEqualTo(3)
    }

    @Test fun `empty unavailable and damaged collection states are explicit and never upload empty logs`() = runTest {
        val store = FakeDiagnosticLogStore()
        val provider = DiagnosticSnapshotProvider(store)
        listOf(DiagnosticCollectionState.EMPTY, DiagnosticCollectionState.KEY_UNAVAILABLE, DiagnosticCollectionState.STORAGE_ERROR).forEach { state ->
            store.result = store.result.copy(state = state, events = emptyList())
            val bundle = provider.get(refresh = true)
            assertThat(bundle.canUpload).isFalse()
            assertThat(bundle.summary).doesNotContain("已采集")
        }
        store.result = FakeDiagnosticLogStore().result.copy(state = DiagnosticCollectionState.PARTIAL, droppedEvents = 3, unreadableSegments = 1)
        val partial = provider.get(refresh = true)
        assertThat(partial.canUpload).isTrue()
        assertThat(partial.summary).contains("采集不完整")
        assertThat(JSONObject(partial.manifest).getLong("dropped_events")).isEqualTo(3)
    }
}
