/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.security.contentprotection.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.security.contentprotection.api.ContentId
import io.element.android.security.contentprotection.api.ContentVersionLineageConflictException
import io.element.android.security.contentprotection.api.ContentVersionLineageCorruptException
import io.element.android.security.contentprotection.api.ContentVersionLineageScope
import io.element.android.security.contentprotection.api.ContentVersionNode
import io.element.android.security.contentprotection.api.ContentVersionRetirementState
import io.element.android.security.securestorage.api.AccountVaultId
import io.element.android.security.securestorage.api.KeyEnvelopeId
import io.element.android.security.securestorage.api.SecureObjectNotFoundException
import io.element.android.security.securestorage.api.SecureObjectIdentity
import io.element.android.security.securestorage.api.SecureObjectReference
import io.element.android.security.securestorage.api.SecureObjectWriteRequest
import io.element.android.security.securestorage.api.SecureStoreMaintenanceReport
import io.element.android.security.securestorage.api.SecureStorageRoots
import io.element.android.security.securestorage.api.SecureVaultDeletionReport
import io.element.android.security.securestorage.api.SensitiveFileStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class ProtectedContentVersionLineageStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `enumerates a real root child and branch graph with independent non-extending expiries`() = runTest {
        val fixture = fixture()
        fixture.store.append(SCOPE, root())
        fixture.store.append(SCOPE, child("content-caption-v2", "operation-caption-v2", ROOT, expiresAt = 8_000))
        fixture.store.append(SCOPE, child("content-caption-v3", "operation-caption-v3", ROOT, expiresAt = 7_000))

        val graph = fixture.store.graph(SCOPE, ContentId(ROOT))!!
        assertThat(graph.generation).isEqualTo(3)
        assertThat(graph.versions.map { it.contentId.value }).isEqualTo(
            listOf(ROOT, "content-caption-v2", "content-caption-v3")
        )
        assertThat(graph.versions.map { it.parentContentId?.value }).isEqualTo(listOf(null, ROOT, ROOT))
        assertThat(graph.versions.map { it.expiresAtEpochMillis }).isEqualTo(listOf(10_000L, 8_000L, 7_000L))
    }

    @Test
    fun `exact membership lookup survives reopen and cannot include an independent reply or another room`() = runTest {
        val fixture = fixture()
        fixture.store.append(SCOPE, root())
        val edit = ContentId("text-edit-member")
        fixture.store.append(SCOPE, child(edit.value, "text-edit-operation-0001", ROOT, 8_000))
        val reply = ContentId("text-independent-reply")
        fixture.store.append(SCOPE, root().copy(contentId = reply, rootContentId = reply, operationId = "reply-operation-0001"))
        val reopened = ProtectedContentVersionLineageStore(fixture.files, fixture.roots)
        assertThat(reopened.rootForContent(SCOPE, edit)).isEqualTo(ContentId(ROOT))
        assertThat(reopened.rootForContent(SCOPE, reply)).isEqualTo(reply)
        assertThat(reopened.rootForContent(SCOPE, ContentId("unknown-history"))).isNull()
        assertThat(reopened.rootForContent(SCOPE.copy(roomId = "!foreign:example.org"), edit)).isNull()
        fixture.files.removeAllObjects()
        assertThat(runCatching { reopened.rootForContent(SCOPE, edit) }.exceptionOrNull())
            .isInstanceOf(ContentVersionLineageCorruptException::class.java)
    }

    @Test
    fun `same operation and exact node is idempotent while changed operation data fails closed`() = runTest {
        val fixture = fixture()
        val root = root()
        val first = fixture.store.append(SCOPE, root)
        val repeated = fixture.store.append(SCOPE, root)
        assertThat(repeated.graph.generation).isEqualTo(first.graph.generation)
        assertThat(runCatching {
            fixture.store.append(SCOPE, root.copy(eventIdSha256 = "b".repeat(64)))
        }.exceptionOrNull()).isInstanceOf(ContentVersionLineageConflictException::class.java)
    }

    @Test
    fun `missing parent cross-root branch and parent ttl extension fail closed`() = runTest {
        val fixture = fixture()
        fixture.store.append(SCOPE, root())
        assertThat(runCatching {
            fixture.store.append(SCOPE, child("missing-parent", "operation-missing-parent", "absent", 8_000))
        }.exceptionOrNull()).isInstanceOf(ContentVersionLineageConflictException::class.java)
        assertThat(runCatching {
            fixture.store.append(SCOPE, child("ttl-extension", "operation-ttl-extension", ROOT, 10_001))
        }.exceptionOrNull()).isInstanceOf(ContentVersionLineageConflictException::class.java)
        assertThat(runCatching {
            fixture.store.append(
                SCOPE,
                child("cross-root", "operation-cross-root", ROOT, 8_000).copy(rootContentId = ContentId("other-root")),
            )
        }.exceptionOrNull()).isInstanceOf(ContentVersionLineageConflictException::class.java)
    }

    @Test
    fun `two store instances serialize append and cannot overwrite the same generation snapshot`() = runTest {
        val files = FakeSensitiveFileStore()
        val roots = roots()
        val first = ProtectedContentVersionLineageStore(files, roots)
        val second = ProtectedContentVersionLineageStore(files, roots)
        first.append(SCOPE, root())

        listOf(
            async { first.append(SCOPE, child("concurrent-a", "operation-concurrent-a", ROOT, 8_000)) },
            async { second.append(SCOPE, child("concurrent-b", "operation-concurrent-b", ROOT, 7_000)) },
        ).awaitAll()

        val graph = first.graph(SCOPE, ContentId(ROOT))!!
        assertThat(graph.generation).isEqualTo(3)
        assertThat(graph.versions.map { it.contentId.value }.toSet()).isEqualTo(setOf(ROOT, "concurrent-a", "concurrent-b"))
        assertThat(files.writtenObjectIds.toSet().size).isEqualTo(3)
    }

    @Test
    fun `delete failure remains durable cleanup debt across store instances and retry clears it`() = runTest {
        val files = FakeSensitiveFileStore().apply { failedDeletes.set(1) }
        val roots = roots()
        val first = ProtectedContentVersionLineageStore(files, roots)
        first.append(SCOPE, root())
        val appended = first.append(SCOPE, child("debt-child", "operation-debt-child", ROOT, 8_000))
        assertThat(appended.graph.pendingRetirementCount).isEqualTo(1)
        assertThat(appended.retirementState).isEqualTo(ContentVersionRetirementState.PENDING)

        val reopened = ProtectedContentVersionLineageStore(files, roots)
        assertThat(reopened.graph(SCOPE, ContentId(ROOT))!!.pendingRetirementCount).isEqualTo(1)
        assertThat(reopened.retryPendingRetirements(SCOPE)).isEqualTo(0)
        assertThat(reopened.graph(SCOPE, ContentId(ROOT))!!.pendingRetirementCount).isEqualTo(0)
    }

    @Test
    fun `missing encrypted current snapshot and corrupt index fail closed`() = runTest {
        val fixture = fixture()
        fixture.store.append(SCOPE, root())
        fixture.files.removeAllObjects()
        assertThat(runCatching { fixture.store.graph(SCOPE, ContentId(ROOT)) }.exceptionOrNull())
            .isInstanceOf(ContentVersionLineageCorruptException::class.java)

        val other = fixture()
        other.store.append(SCOPE, root())
        other.roots.persistentRoot().walkTopDown().single { it.extension == "yzli" }.writeBytes(byteArrayOf(1, 2, 3))
        assertThat(runCatching { other.store.graph(SCOPE, ContentId(ROOT)) }.exceptionOrNull())
            .isInstanceOf(ContentVersionLineageCorruptException::class.java)
    }

    @Test
    fun `vault and room scopes cannot read each other's lineage`() = runTest {
        val fixture = fixture()
        fixture.store.append(SCOPE, root())
        assertThat(fixture.store.graph(SCOPE.copy(roomId = "!other:example.org"), ContentId(ROOT))).isNull()
        assertThat(
            fixture.store.graph(
                SCOPE.copy(vaultId = AccountVaultId("matrix-device-v1:other")),
                ContentId(ROOT),
            )
        ).isNull()
        val ids = fixture.roots.persistentRoot().walkTopDown().filter { it.extension == "yzli" }.map { it.name }.toList()
        assertThat(ids.size).isEqualTo(1)
    }

    @Test
    fun `random secure object identity is not derived from content or event identity`() = runTest {
        val fixture = fixture()
        fixture.store.append(SCOPE, root())
        fixture.store.append(SCOPE, child("identity-child", "operation-identity-child", ROOT, 8_000))
        val ids = fixture.files.writtenObjectIds
        assertThat(ids[0]).isNotEqualTo(ids[1])
        assertThat(ids.all { it.startsWith("content-lineage-v1:") }).isTrue()
        assertThat(ids.none { it.contains(ROOT) || it.contains("identity-child") }).isTrue()
    }

    @Test
    fun `committed append returns pending debt when retirement journal rewrite fails`() = runTest {
        val files = FakeSensitiveFileStore()
        val roots = roots()
        val store = FailingIndexWriteStore(files, roots, failAtWrite = 5)
        store.append(SCOPE, root())

        val result = store.append(SCOPE, child("journal-child", "operation-journal-child", ROOT, 8_000))

        assertThat(result.retirementState).isEqualTo(ContentVersionRetirementState.PENDING)
        assertThat(result.graph.pendingRetirementCount).isEqualTo(1)
        assertThat(result.graph.versions.map { it.contentId.value }).isEqualTo(listOf(ROOT, "journal-child"))
        val reopened = ProtectedContentVersionLineageStore(files, roots).graph(SCOPE, ContentId(ROOT))!!
        assertThat(reopened.versions.map { it.contentId.value }).isEqualTo(listOf(ROOT, "journal-child"))
        assertThat(reopened.pendingRetirementCount).isEqualTo(1)
    }

    @Test
    fun `authoritative pointer failure throws and does not commit append`() = runTest {
        val files = FakeSensitiveFileStore()
        val roots = roots()
        val store = FailingIndexWriteStore(files, roots, failAtWrite = 4)
        store.append(SCOPE, root())

        assertThat(runCatching {
            store.append(SCOPE, child("not-committed", "operation-not-committed", ROOT, 8_000))
        }.isFailure).isTrue()
        val graph = ProtectedContentVersionLineageStore(files, roots).graph(SCOPE, ContentId(ROOT))!!
        assertThat(graph.versions.map { it.contentId.value }).isEqualTo(listOf(ROOT))
    }

    @Test
    fun `pointer failure and cleanup failure leave durable staging debt that reopen retires`() = runTest {
        val files = FakeSensitiveFileStore().apply { failedObjectDeletes.set(1) }
        val roots = roots()
        val store = FailingIndexWriteStore(files, roots, failAtWrite = 4)
        store.append(SCOPE, root())

        assertThat(runCatching {
            store.append(SCOPE, child("orphan-staged", "operation-orphan-staged", ROOT, 8_000))
        }.isFailure).isTrue()
        assertThat(files.objectCount()).isEqualTo(2)

        val reopened = ProtectedContentVersionLineageStore(files, roots)
        val graph = reopened.graph(SCOPE, ContentId(ROOT))!!
        assertThat(graph.versions.map { it.contentId.value }).isEqualTo(listOf(ROOT))
        assertThat(graph.pendingRetirementCount).isEqualTo(0)
        assertThat(files.objectCount()).isEqualTo(1)
    }

    private fun fixture(): Fixture {
        val files = FakeSensitiveFileStore()
        val roots = roots()
        return Fixture(files, roots, ProtectedContentVersionLineageStore(files, roots))
    }

    private fun roots(): TestRoots = TestRoots(temporaryFolder.newFolder())

    private fun root(): ContentVersionNode = ContentVersionNode(
        contentId = ContentId(ROOT),
        rootContentId = ContentId(ROOT),
        parentContentId = null,
        operationId = "operation-root-0001",
        eventIdSha256 = "a".repeat(64),
        createdAtEpochMillis = 1_000,
        expiresAtEpochMillis = 10_000,
    )

    private fun child(
        contentId: String,
        operationId: String,
        parentId: String,
        expiresAt: Long,
    ): ContentVersionNode = ContentVersionNode(
        contentId = ContentId(contentId),
        rootContentId = ContentId(ROOT),
        parentContentId = ContentId(parentId),
        operationId = operationId.padEnd(16, '0'),
        eventIdSha256 = MessageDigest.getInstance("SHA-256")
            .digest(contentId.encodeToByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) },
        createdAtEpochMillis = 2_000,
        expiresAtEpochMillis = expiresAt,
    )

    private data class Fixture(
        val files: FakeSensitiveFileStore,
        val roots: TestRoots,
        val store: ProtectedContentVersionLineageStore,
    )

    private class TestRoots(private val root: File) : SecureStorageRoots {
        override fun persistentRoot(): File = root
        override fun runtimeRoot(): File = root
    }

    private class FakeSensitiveFileStore : SensitiveFileStore {
        private data class Stored(val reference: SecureObjectReference, val plaintext: ByteArray)

        private val counter = AtomicInteger()
        private val objects = ConcurrentHashMap<KeyEnvelopeId, Stored>()
        val failedDeletes = AtomicInteger()
        val failedObjectDeletes = AtomicInteger()
        val writtenObjectIds = mutableListOf<String>()

        override suspend fun write(
            request: SecureObjectWriteRequest,
            writePlaintext: suspend (java.io.OutputStream) -> Unit,
        ): SecureObjectReference {
            val plaintext = ByteArrayOutputStream().also { writePlaintext(it) }.toByteArray()
            val envelope = KeyEnvelopeId("envelope-${counter.incrementAndGet()}")
            val reference = SecureObjectReference(
                objectId = request.objectId,
                vaultId = request.vaultId,
                purpose = request.purpose,
                keyEnvelopeId = envelope,
                formatVersion = 1,
                plaintextLength = plaintext.size.toLong(),
                ciphertextLength = plaintext.size.toLong(),
                associatedDataSha256 = MessageDigest.getInstance("SHA-256")
                    .digest(request.associatedData)
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) },
            )
            objects[envelope] = Stored(reference, plaintext)
            synchronized(writtenObjectIds) { writtenObjectIds += request.objectId.value }
            return reference
        }

        override suspend fun <T> read(
            reference: SecureObjectReference,
            readPlaintext: suspend (java.io.InputStream) -> T,
        ): T {
            val stored = objects[reference.keyEnvelopeId]
                ?.takeIf { it.reference == reference }
                ?: throw SecureObjectNotFoundException()
            return readPlaintext(ByteArrayInputStream(stored.plaintext))
        }

        override suspend fun readRange(reference: SecureObjectReference, offset: Long, byteCount: Int): ByteArray =
            error("Not used")

        override suspend fun delete(reference: SecureObjectReference): Boolean {
            if (failedDeletes.getAndUpdate { value -> maxOf(0, value - 1) } > 0) return false
            return objects.remove(reference.keyEnvelopeId)?.reference == reference
        }

        override suspend fun deleteObject(identity: SecureObjectIdentity): Boolean {
            if (failedObjectDeletes.getAndUpdate { value -> maxOf(0, value - 1) } > 0) return false
            objects.entries.removeIf { (_, stored) ->
                stored.reference.objectId == identity.objectId &&
                    stored.reference.vaultId == identity.vaultId &&
                    stored.reference.purpose == identity.purpose
            }
            return true
        }

        override suspend fun deleteVault(vaultId: AccountVaultId): SecureVaultDeletionReport =
            SecureVaultDeletionReport(vaultId, true, true, true)

        override suspend fun runMaintenance(): SecureStoreMaintenanceReport = SecureStoreMaintenanceReport(0, 0, 0)

        fun removeAllObjects() = objects.clear()
        fun objectCount(): Int = objects.size
    }

    private class FailingIndexWriteStore(
        files: SensitiveFileStore,
        roots: SecureStorageRoots,
        private val failAtWrite: Int,
    ) : ProtectedContentVersionLineageStore(files, roots) {
        private var writes = 0

        override fun persistIndex(file: File, encoded: ByteArray) {
            writes += 1
            if (writes == failAtWrite) error("injected index write failure")
            super.persistIndex(file, encoded)
        }
    }

    private companion object {
        const val ROOT = "content-caption-root"
        val SCOPE = ContentVersionLineageScope(
            vaultId = AccountVaultId("matrix-device-v1:account-device"),
            roomId = "!caption:example.org",
        )
    }
}
