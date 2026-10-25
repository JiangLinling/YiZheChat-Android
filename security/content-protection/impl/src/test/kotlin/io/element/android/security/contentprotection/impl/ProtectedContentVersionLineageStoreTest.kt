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
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class ProtectedContentVersionLineageStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `authority restore commits complete root child and branch once and preserves an independent root`() = runTest {
        val f = fixture()
        val independent = root().copy(contentId = ContentId("independent"), rootContentId = ContentId("independent"),
            operationId = "independent-operation")
        f.store.append(SCOPE, independent)
        val nodes = listOf(root(), child("child-one", "operation-child-one", ROOT, 8_000),
            child("child-two", "operation-child-two", ROOT, 7_000))
        val restored = f.store.restoreAuthorityGraph(SCOPE, nodes)
        assertThat(restored.generation).isEqualTo(2)
        assertThat(restored.versions).containsExactlyElementsIn(nodes)
        assertThat(f.store.graph(SCOPE, independent.contentId)!!.versions).containsExactly(independent)
        assertThat(f.store.restoreAuthorityGraph(SCOPE, nodes).generation).isEqualTo(2)
        val reopened = ProtectedContentVersionLineageStore(f.files, f.roots)
        assertThat(reopened.rootForContent(SCOPE, nodes.last().contentId)).isEqualTo(ContentId(ROOT))
    }

    @Test
    fun `failed authority pointer commit exposes no editable prefix and can retry complete graph`() = runTest {
        val files = FakeSensitiveFileStore()
        val roots = roots()
        val failed = FailingIndexWriteStore(files, roots, failAtWrite = 2)
        val nodes = listOf(root(), child("restored-child", "restored-child-operation", ROOT, 8_000))
        assertThat(runCatching { failed.restoreAuthorityGraph(SCOPE, nodes) }.isFailure).isTrue()
        val reopened = ProtectedContentVersionLineageStore(files, roots)
        assertThat(reopened.rootForContent(SCOPE, ContentId(ROOT))).isNull()
        assertThat(reopened.rootForContent(SCOPE, nodes.last().contentId)).isNull()
        assertThat(reopened.restoreAuthorityGraph(SCOPE, nodes).versions).containsExactlyElementsIn(nodes)
    }

    @Test
    fun `authority restore cannot overwrite an identity or discard an existing child`() = runTest {
        val f = fixture()
        f.store.append(SCOPE, root())
        val edit = child("existing-child", "existing-child-operation", ROOT, 8_000)
        f.store.append(SCOPE, edit)
        assertThat(runCatching { f.store.restoreAuthorityGraph(SCOPE, listOf(root())) }.exceptionOrNull())
            .isInstanceOf(ContentVersionLineageConflictException::class.java)
        assertThat(runCatching { f.store.restoreAuthorityGraph(SCOPE, listOf(root(), edit.copy(eventIdSha256 = "b".repeat(64)))) }.exceptionOrNull())
            .isInstanceOf(ContentVersionLineageConflictException::class.java)
        assertThat(f.store.graph(SCOPE, ContentId(ROOT))!!.versions).containsExactly(root(), edit)
    }

    @Test
    fun `incomplete or extending authority graph never publishes a root`() = runTest {
        val f = fixture()
        val bad = child("invalid-child", "invalid-child-operation", "missing-parent", 8_000)
        assertThat(runCatching { f.store.restoreAuthorityGraph(SCOPE, listOf(root(), bad)) }.isFailure).isTrue()
        assertThat(runCatching { f.store.restoreAuthorityGraph(SCOPE, listOf(root(), bad.copy(parentContentId = ContentId(ROOT),
            expiresAtEpochMillis = 10_001))) }.isFailure).isTrue()
        assertThat(f.store.graph(SCOPE, ContentId(ROOT))).isNull()
    }

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
        assertThat(files.writtenObjectIds.toSet().size).isEqualTo(files.writtenObjectIds.size)
        assertThat(files.writtenObjectIds.size).isAtMost(9)
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
        assertThat(result.graph.pendingRetirementCount).isGreaterThan(0)
        assertThat(result.graph.versions.map { it.contentId.value }).isEqualTo(listOf(ROOT, "journal-child"))
        val reopened = ProtectedContentVersionLineageStore(files, roots).graph(SCOPE, ContentId(ROOT))!!
        assertThat(reopened.versions.map { it.contentId.value }).isEqualTo(listOf(ROOT, "journal-child"))
        assertThat(reopened.pendingRetirementCount).isEqualTo(result.graph.pendingRetirementCount)
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
        val committedObjectCount = files.objectCount()

        assertThat(runCatching {
            store.append(SCOPE, child("orphan-staged", "operation-orphan-staged", ROOT, 8_000))
        }.isFailure).isTrue()
        assertThat(files.objectCount()).isEqualTo(committedObjectCount + 1)

        val reopened = ProtectedContentVersionLineageStore(files, roots)
        val graph = reopened.graph(SCOPE, ContentId(ROOT))!!
        assertThat(graph.versions.map { it.contentId.value }).isEqualTo(listOf(ROOT))
        assertThat(graph.pendingRetirementCount).isEqualTo(0)
        assertThat(files.objectCount()).isEqualTo(committedObjectCount)
    }

    @Test
    fun `thousand independent messages touch at most three encrypted pages per append and preserve exact lookup`() = runTest {
        val fixture = fixture()
        val nodes = (0 until 1_000).map { n ->
            root().copy(contentId = ContentId("independent-$n"), rootContentId = ContentId("independent-$n"), operationId = "independent-operation-$n")
        }
        nodes.forEach { node ->
            val readsBefore = fixture.files.readCount.get()
            val writesBefore = fixture.files.writtenObjectIds.size
            fixture.store.append(SCOPE, node)
            assertThat(fixture.files.readCount.get() - readsBefore).isAtMost(3)
            assertThat(fixture.files.writtenObjectIds.size - writesBefore).isAtMost(3)
        }
        val reopened = ProtectedContentVersionLineageStore(fixture.files, fixture.roots)
        for (node in listOf(nodes.first(), nodes[499], nodes.last())) {
            assertThat(reopened.rootForContent(SCOPE, node.contentId)).isEqualTo(node.rootContentId)
            assertThat(reopened.graph(SCOPE, node.rootContentId)!!.versions).containsExactly(node)
        }
        assertThat(fixture.files.objectCount()).isAtMost(256)
        assertThat(fixture.files.maximumPlaintextLength).isLessThan(32_768L)
        assertThat(runCatching {
            reopened.append(SCOPE, nodes.last().copy(operationId = nodes.first().operationId))
        }.exceptionOrNull()).isInstanceOf(ContentVersionLineageConflictException::class.java)
    }

    @Test
    fun `legacy migration is atomic and preserves cross-root operation uniqueness`() = runTest {
        val files = FakeSensitiveFileStore()
        val roots = roots()
        val original = root()
        writeLegacy(files, roots, listOf(original))
        val appended = child("legacy-child", "legacy-child-operation", ROOT, 8_000)
        val failed = FailingIndexWriteStore(files, roots, failAtWrite = 2)
        assertThat(runCatching { failed.append(SCOPE, appended) }.isFailure).isTrue()
        val reopened = ProtectedContentVersionLineageStore(files, roots)
        assertThat(reopened.graph(SCOPE, ContentId(ROOT))!!.versions).containsExactly(original)
        assertThat(files.objectCount()).isEqualTo(1)
        reopened.append(SCOPE, appended)
        assertThat(reopened.graph(SCOPE, ContentId(ROOT))!!.versions).containsExactly(original, appended)
        assertThat(reopened.rootForContent(SCOPE, appended.contentId)).isEqualTo(original.contentId)
        assertThat(runCatching {
            reopened.append(SCOPE, original.copy(contentId = ContentId("other-root"), rootContentId = ContentId("other-root")))
        }.exceptionOrNull()).isInstanceOf(ContentVersionLineageConflictException::class.java)
    }

    /** Exact v1 wire fixture, so compatibility is tested independently of the new encoder. */
    private suspend fun writeLegacy(files: FakeSensitiveFileStore, roots: TestRoots, nodes: List<ContentVersionNode>) {
        fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        fun DataOutputStream.string(value: String) { val bytes = value.encodeToByteArray(); writeInt(bytes.size); write(bytes) }
        val graph = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use { output ->
            output.write("YZLGP001".encodeToByteArray())
            output.string(SCOPE.vaultId.value); output.string(hash(SCOPE.roomId)); output.writeLong(1)
            output.writeInt(nodes.size)
            nodes.forEach { node ->
                output.string(node.contentId.value); output.string(node.rootContentId.value)
                output.writeBoolean(node.parentContentId != null); node.parentContentId?.let { output.string(it.value) }
                output.string(node.operationId); output.string(node.eventIdSha256)
                output.writeLong(node.createdAtEpochMillis); output.writeLong(node.expiresAtEpochMillis)
            }
        } }.toByteArray()
        val reference = files.write(SecureObjectWriteRequest(
            objectId = io.element.android.security.securestorage.api.SecureObjectId("content-lineage-v1:legacy-fixture"),
            vaultId = SCOPE.vaultId, purpose = io.element.android.security.securestorage.api.SecureObjectPurpose.MESSAGE_REFERENCE_INDEX,
            associatedData = "content-lineage-v1\u0000${SCOPE.vaultId.value}\u0000${hash(SCOPE.roomId)}\u00001".encodeToByteArray(),
        )) { it.write(graph) }
        val directory = File(roots.persistentRoot(), "content-version-lineage-v1").apply { mkdirs() }
        DataOutputStream(File(directory, hash("${SCOPE.vaultId.value}\u0000${SCOPE.roomId}") + ".yzli").outputStream()).use { output ->
            output.write("YZLIX001".encodeToByteArray()); output.string(SCOPE.vaultId.value); output.string(hash(SCOPE.roomId))
            output.writeLong(1); output.writeBoolean(true)
            output.string(reference.objectId.value); output.string(reference.vaultId.value); output.writeInt(reference.purpose.formatCode)
            output.string(reference.keyEnvelopeId.value); output.writeInt(reference.formatVersion)
            output.writeLong(reference.plaintextLength); output.writeLong(reference.ciphertextLength); output.string(reference.associatedDataSha256)
            output.writeInt(0); output.writeInt(0)
        }
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
        val readCount = AtomicInteger()
        var maximumPlaintextLength = 0L

        override suspend fun write(
            request: SecureObjectWriteRequest,
            writePlaintext: suspend (java.io.OutputStream) -> Unit,
        ): SecureObjectReference {
            val plaintext = ByteArrayOutputStream().also { writePlaintext(it) }.toByteArray()
            maximumPlaintextLength = maxOf(maximumPlaintextLength, plaintext.size.toLong())
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
            readCount.incrementAndGet()
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
