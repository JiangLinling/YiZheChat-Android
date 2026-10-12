/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.commoninfo

import com.google.common.truth.Truth.assertThat
import io.element.android.features.messages.impl.local.FavouriteReferenceSynchronizer
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.DeviceId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.timeline.item.event.MessageClipboardSource
import kotlinx.collections.immutable.persistentSetOf
import io.element.android.security.contentprotection.api.*
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineExceptionHandler
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class CommonInfoRepositoryTest {
    private val matrix = mockk<MatrixClient> {
        every { sessionId } returns UserId("@common-test:yizhe.test")
        every { deviceId } returns DeviceId("COMMON_TEST_DEVICE")
    }

    private val protectionRuntime = mockk<MatrixContentProtectionRuntime> { every { authorizationRevision() } returns 0L }

    @Test fun `valid display cache avoids repeated cloud reads and mutation updates reuse the committed index`() = runTest {
        val cloud = MemoryResources().apply { cacheLeases = true }
        val repo = CommonInfoRepository(matrix, cloud, protectionRuntime, backgroundScope)
        val item = repo.save(CommonInfoItem(title = "cached", content = "value")).single()
        val before = cloud.fetches
        assertThat(repo.load().single().title).isEqualTo("cached")
        assertThat(repo.load().single().title).isEqualTo("cached")
        assertThat(cloud.fetches).isEqualTo(before)
        repo.pin(item)
        assertThat(repo.load().single().pinnedAt).isGreaterThan(0)
        assertThat(cloud.fetches).isEqualTo(before)
    }

    @Test fun `simultaneous prewarm and panel opens coalesce the signed index read`() = runTest {
        val cloud = MemoryResources().apply { cacheLeases = true; fetchDelayMillis = 50 }
        val repo = CommonInfoRepository(matrix, cloud, protectionRuntime, backgroundScope)
        val item = repo.save(CommonInfoItem(title = "coalesced", content = "value")).single()
        repo.clearViewCache()
        val before = cloud.fetches
        val loaded = List(8) { async { repo.loadForDisplay() } }.awaitAll()
        assertThat(loaded.map { it.items.single().id }).containsExactlyElementsIn(List(8) { item.id })
        assertThat(cloud.fetches - before).isEqualTo(1)
    }

    @Test fun `valid body grant avoids a new online read operation for repeated edit`() = runTest {
        val cloud = MemoryResources().apply { cacheLeases = true }
        val repo = CommonInfoRepository(matrix, cloud, protectionRuntime, backgroundScope)
        val saved = repo.save(CommonInfoItem(title = "local", content = "complete private value")).single()
        val before = cloud.readOperations
        assertThat(repo.edit(saved).content).isEqualTo("complete private value")
        assertThat(repo.edit(saved).content).isEqualTo("complete private value")
        assertThat(cloud.readOperations).isEqualTo(before)
    }

    @Test fun `late index plaintext cannot publish after hard authorization change`() = runTest {
        val cloud = MemoryResources().apply { cacheLeases = true }
        val repo = CommonInfoRepository(matrix, cloud, protectionRuntime, backgroundScope)
        repo.save(CommonInfoItem(title = "private", content = "complete value"))
        repo.clearViewCache()
        cloud.invalidateAfterRead = true
        val result = runCatching { repo.loadForDisplay() }
        assertThat(result.exceptionOrNull()?.message).isEqualTo("PRIVATE_RESOURCE_AUTHORIZATION_CHANGED")
        assertThat(repo.display.value).isNull()
    }

    @Test fun `encrypted outbox survives index failure and resumes without creating a duplicate body`() = runBlocking {
        val cloud = MemoryResources().apply { failIndexWrite = true }
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val first = CommonInfoRepository(matrix, cloud, protectionRuntime, firstScope)
            val item = CommonInfoItem(title = "queued", content = "private saved value")
            first.enqueue("save", item)
            withTimeout(5_000) { while (first.pending.value.none { it.failed }) delay(10) }
            assertThat(cloud.metadata["common-outbox"]).isNotNull()
            assertThat(first.pending.value.single().item.content).isEmpty()
            firstScope.cancel()
            cloud.failIndexWrite = false
            val resumed = CommonInfoRepository(matrix, cloud, protectionRuntime, secondScope)
            resumed.startSync()
            withTimeout(5_000) { while (cloud.metadata["common-outbox"] != null) delay(10) }
            assertThat(resumed.load().map { it.id }).containsExactly(item.id)
            assertThat(cloud.heads.values.count { it.kind == "COMMON_BODY" }).isEqualTo(1)
            assertThat(cloud.writeTriggers.map { it.first }.toSet()).containsExactly("private_create")
            Unit
        } finally { firstScope.cancel();secondScope.cancel() }
    }

    @Test fun `message import requires current session authorization generation and unexpired source`() {
        val repo = CommonInfoRepository(matrix, MemoryResources(), protectionRuntime)
        val source = MessageClipboardSource.Protected(matrix.sessionId.value, "!room:yizhe.test", "event-test",
            "version-1", persistentSetOf("content-1"), 0L, Long.MAX_VALUE)
        assertThat(repo.canImportSource(source)).isTrue()
        assertThat(repo.canImportSource(source.copy(sessionId = "@other:yizhe.test"))).isFalse()
        assertThat(repo.canImportSource(source.copy(expiresAtEpochMillis = 1L))).isFalse()
        every { protectionRuntime.authorizationRevision() } returns 1L
        assertThat(repo.canImportSource(source)).isFalse()
    }

    @Test fun `successful event is counted once after retry and repository recreation`() = runTest {
        val cloud = MemoryResources()
        val original = CommonInfoRepository(matrix, cloud, protectionRuntime)
        val item = original.save(CommonInfoItem(title = "常用电话", content = "13800000000", kind = CommonInfoKind.PHONE)).single()
        val room = "!common-room:yizhe.test"
        original.stageSend(item.id, room, "stable-send-transaction")
        val content = UUID.nameUUIDFromBytes("$room\u0000stable-send-transaction".encodeToByteArray()).toString()
        val recovered = CommonInfoRepository(matrix, cloud, protectionRuntime)
        recovered.confirmSend(content, room, "event-one", System.currentTimeMillis())
        recovered.confirmSend(content, room, "event-one", System.currentTimeMillis())
        recovered.stageSend(item.id, room, "retry-transaction")
        val retry = UUID.nameUUIDFromBytes("$room\u0000retry-transaction".encodeToByteArray()).toString()
        recovered.confirmSend(retry, room, "event-one", System.currentTimeMillis())
        assertThat(recovered.load().single().uses.size).isEqualTo(1)
        assertThat(cloud.metadata["usage-pending"]).isNull()
    }

    @Test fun `stale deletion cannot erase an edit made from another view`() = runTest {
        val cloud = MemoryResources()
        val repo = CommonInfoRepository(matrix, cloud, protectionRuntime)
        val old = repo.save(CommonInfoItem(title = "地址", content = "旧地址")).single()
        val full = repo.edit(old)
        repo.save(full.copy(content = "新地址"))
        assertThat(runCatching { repo.delete(old) }.exceptionOrNull()?.message).isEqualTo("PRIVATE_RESOURCE_CONFLICT")
        assertThat(cloud.heads.getValue(old.id).deleted).isFalse()
        assertThat(repo.edit(repo.load().single()).content).isEqualTo("新地址")
    }

    @Test fun `crash between body destruction and index update converges on the next open`() = runTest {
        val cloud = MemoryResources()
        val repo = CommonInfoRepository(matrix, cloud, protectionRuntime)
        val item = repo.save(CommonInfoItem(title = "证件", content = "123456789012345678", kind = CommonInfoKind.IDENTITY)).single()
        cloud.failIndexWrite = true
        assertThat(runCatching { repo.delete(item) }.isFailure).isTrue()
        assertThat(cloud.heads.getValue(item.id).deleted).isTrue()
        assertThat(cloud.metadata["common-index-pending"]).isNotNull()
        cloud.failIndexWrite = false
        assertThat(CommonInfoRepository(matrix, cloud, protectionRuntime).load()).isEmpty()
        assertThat(cloud.metadata["common-index-pending"]).isNull()
    }

    @Test fun `favourites synchronize only references and an explicit removal converges`() = runTest {
        val cloud = MemoryResources()
        val first = FavouriteReferenceSynchronizer(matrix, cloud)
        val key = "!common-room:yizhe.test\u0000\$common-event"
        first.update(setOf(key), emptySet())
        assertThat(FavouriteReferenceSynchronizer(matrix, cloud).refresh()).containsExactly(key)
        val body = JSONObject(cloud.bodies.values.single().decodeToString())
        assertThat(body.keys().asSequence().toSet()).containsExactly("format", "references")
        first.update(emptySet(), setOf(key))
        assertThat(FavouriteReferenceSynchronizer(matrix, cloud).refresh()).isEmpty()
        assertThat(cloud.writeTriggers.map { it.first }).containsExactly("favourite_add", "favourite_remove").inOrder()
        assertThat(cloud.writeTriggers.map { UUID.fromString(it.second).toString() }.distinct()).hasSize(2)
    }

    @Test fun `favourite refresh and repeated no-op mutations do not create lifecycle writes`() = runTest {
        val cloud = MemoryResources()
        val sync = FavouriteReferenceSynchronizer(matrix, cloud)
        val key = "!common-room:yizhe.test\u0000\$common-event"
        assertThat(sync.refresh()).isEmpty()
        assertThat(sync.update(emptySet(), setOf(key))).isEmpty()
        assertThat(cloud.writeTriggers).isEmpty()
        sync.update(setOf(key), emptySet())
        val revision = cloud.heads.values.single().revision
        sync.update(setOf(key), emptySet())
        sync.refresh()
        assertThat(cloud.heads.values.single().revision).isEqualTo(revision)
        assertThat(cloud.writeTriggers.map { it.first }).containsExactly("favourite_add")
    }

    @Test fun `mixed effective favourite additions and removals cannot be mislabeled as one action`() = runTest {
        val cloud = MemoryResources()
        val sync = FavouriteReferenceSynchronizer(matrix, cloud)
        val old = "!common-room:yizhe.test\u0000\$old-event"
        val next = "!common-room:yizhe.test\u0000\$new-event"
        sync.update(setOf(old), emptySet())
        val before = cloud.heads.values.single()
        assertThat(runCatching { sync.update(setOf(next), setOf(old)) }.exceptionOrNull()?.message)
            .isEqualTo("FAVOURITE_MIXED_MUTATION")
        assertThat(sync.refresh()).containsExactly(old)
        assertThat(cloud.heads.values.single()).isEqualTo(before)
        assertThat(cloud.writeTriggers).hasSize(1)
    }

    @Test fun `favourite CAS retry preserves concurrent references and semantic action`() = runTest {
        val cloud = MemoryResources()
        val key = "!common-room:yizhe.test\u0000\$own-event"
        val concurrent = "!common-room:yizhe.test\u0000\$other-event"
        cloud.concurrentFavourite = concurrent
        assertThat(FavouriteReferenceSynchronizer(matrix, cloud).update(setOf(key), emptySet())).containsExactly(key, concurrent)
        assertThat(FavouriteReferenceSynchronizer(matrix, cloud).refresh()).containsExactly(key, concurrent)
        assertThat(cloud.writeTriggers.map { it.first }).containsExactly("favourite_add", "favourite_add").inOrder()
        assertThat(cloud.heads.values.single().revision).isEqualTo(2)
    }

    @Test fun `a full index rejects a new body before mutation and remains manageable`() = runTest {
        val cloud = MemoryResources()
        val repo = CommonInfoRepository(matrix, cloud, protectionRuntime)
        repeat(100) { repo.save(CommonInfoItem(title = "entry-$it", content = "value-$it")) }
        assertThat(runCatching { repo.save(CommonInfoItem(title = "extra", content = "extra")) }.exceptionOrNull()?.message)
            .isEqualTo("PRIVATE_RESOURCE_LIMIT")
        assertThat(cloud.heads.size).isEqualTo(101)
        assertThat(cloud.metadata["common-index-pending"]).isNull()
        assertThat(repo.load().size).isEqualTo(100)
    }

    @Test fun `reopening favourites resumes interrupted ciphertext before reading its head`() = runTest {
        val cloud = MemoryResources()
        val key = "!common-room:yizhe.test\u0000\$common-event"
        cloud.pendingFavourite = JSONObject().put("format", 1)
            .put("references", org.json.JSONArray(listOf(key))).toString().encodeToByteArray()
        assertThat(FavouriteReferenceSynchronizer(matrix, cloud).refresh()).containsExactly(key)
        assertThat(cloud.pendingFavourite).isNull()
    }

    @Test fun `several interrupted deletions retain independent recovery intents`() = runTest {
        val cloud = MemoryResources()
        val repo = CommonInfoRepository(matrix, cloud, protectionRuntime)
        val first = repo.save(CommonInfoItem(title = "one", content = "one")).single()
        val second = repo.save(CommonInfoItem(title = "two", content = "two")).first { it.id != first.id }
        cloud.failIndexWrite = true
        assertThat(runCatching { repo.delete(first) }.isFailure).isTrue()
        assertThat(runCatching { repo.delete(second) }.isFailure).isTrue()
        assertThat(JSONObject(cloud.metadata.getValue("common-index-pending").decodeToString()).length()).isEqualTo(2)
        cloud.failIndexWrite = false
        assertThat(CommonInfoRepository(matrix, cloud, protectionRuntime).load()).isEmpty()
    }

    @Test fun `insertion usage retries with stable identity and never rewrites the private body`() = runBlocking {
        val cloud = MemoryResources().apply { cacheLeases = true }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repo = CommonInfoRepository(matrix, cloud, protectionRuntime, scope)
            val saved = repo.save(CommonInfoItem(title = "fill", content = "private content")).single()
            val bodyRevision = cloud.heads.getValue(saved.id).revision
            cloud.failIndexWrite = true
            repo.enqueue("use", saved)
            withTimeout(5_000) { while (repo.pending.value.none { it.failed }) delay(10) }
            val queued = repo.pending.value.single()
            assertThat(queued.item.content).isEmpty()
            assertThat(queued.item.uses.size).isEqualTo(1)
            val stableKey = queued.item.uses.keys.single()
            cloud.failIndexWrite = false
            repo.startSync()
            withTimeout(5_000) { while (repo.pending.value.isNotEmpty()) delay(10) }
            assertThat(repo.load().single().uses.keys).containsExactly(stableKey)
            assertThat(cloud.heads.getValue(saved.id).revision).isEqualTo(bodyRevision)
        } finally { scope.cancel() }
    }

    @Test fun `repeated insertions keep separate intents while statistics do not block pinning`() = runBlocking {
        val cloud = MemoryResources().apply { cacheLeases = true }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repo = CommonInfoRepository(matrix, cloud, protectionRuntime, scope)
            val saved = repo.save(CommonInfoItem(title = "fill", content = "value")).single()
            cloud.failIndexWrite = true
            repo.enqueue("use", saved)
            withTimeout(5_000) { while (repo.pending.value.none { it.failed }) delay(10) }
            repo.enqueue("use", saved)
            repo.enqueue("pin", saved)
            assertThat(repo.projected(listOf(saved)).single().uses.size).isEqualTo(2)
            assertThat(repo.projected(listOf(saved)).single().pinnedAt).isGreaterThan(0)
            cloud.failIndexWrite = false
            repo.startSync()
            withTimeout(5_000) { while (repo.pending.value.isNotEmpty()) delay(10) }
            assertThat(repo.load().single().uses.size).isEqualTo(2)
            assertThat(repo.load().single().pinnedAt).isGreaterThan(0)
        } finally { scope.cancel() }
    }

    @Test fun `locked queue selection pauses without crashing and resumes the encrypted intent`() = runBlocking {
        val cloud = MemoryResources().apply { cacheLeases = true }
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val errors = ConcurrentLinkedQueue<Throwable>()
        val resumedScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error -> errors.add(error) })
        try {
            val first = CommonInfoRepository(matrix, cloud, protectionRuntime, firstScope)
            val saved = first.save(CommonInfoItem(title = "locked", content = "private value")).single()
            cloud.failIndexWrite = true
            first.enqueue("use", saved)
            withTimeout(5_000) { while (first.pending.value.none { it.failed }) delay(10) }
            firstScope.cancel()
            val encryptedIntent = cloud.metadata.getValue("common-outbox").copyOf()
            cloud.metadataLocked = true
            val resumed = CommonInfoRepository(matrix, cloud, protectionRuntime, resumedScope)
            resumed.startSync()
            withTimeout(5_000) { while (cloud.lockedMetadataReads == 0) delay(10) }
            delay(100)
            assertThat(errors).isEmpty()
            assertThat(resumed.pending.value).isEmpty()
            assertThat(cloud.metadata.getValue("common-outbox")).isEqualTo(encryptedIntent)
            val reads = cloud.lockedMetadataReads
            delay(100)
            assertThat(cloud.lockedMetadataReads).isEqualTo(reads)
            cloud.metadataLocked = false
            cloud.failIndexWrite = false
            resumed.startSync()
            withTimeout(5_000) { while (cloud.metadata["common-outbox"] != null) delay(10) }
            assertThat(resumed.load().single().uses.size).isEqualTo(1)
            assertThat(errors).isEmpty()
        } finally { firstScope.cancel(); resumedScope.cancel() }
    }

    @Test fun `vault locking during cloud failure cannot escape through the failure projection reread`() = runBlocking {
        val cloud = MemoryResources().apply { cacheLeases = true }
        val errors = ConcurrentLinkedQueue<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error -> errors.add(error) })
        try {
            val repo = CommonInfoRepository(matrix, cloud, protectionRuntime, scope)
            val saved = repo.save(CommonInfoItem(title = "locked", content = "private value")).single()
            cloud.failIndexWrite = true
            cloud.lockMetadataOnIndexFailure = true
            repo.enqueue("use", saved)
            withTimeout(5_000) { while (cloud.lockedMetadataReads == 0) delay(10) }
            delay(100)
            assertThat(errors).isEmpty()
            assertThat(repo.pending.value).isEmpty()
            assertThat(repo.display.value).isNull()
            assertThat(cloud.metadata["common-outbox"]).isNotNull()
            cloud.metadataLocked = false
            cloud.lockMetadataOnIndexFailure = false
            cloud.failIndexWrite = false
            repo.startSync()
            withTimeout(5_000) { while (cloud.metadata["common-outbox"] != null) delay(10) }
            assertThat(repo.load().single().uses.size).isEqualTo(1)
            assertThat(errors).isEmpty()
        } finally { scope.cancel() }
    }

    @Test fun `common mutations retain their semantic lifecycle triggers`() = runTest {
        val cloud = MemoryResources().apply { cacheLeases = true }
        val repo = CommonInfoRepository(matrix, cloud, protectionRuntime, backgroundScope)
        val item = repo.save(CommonInfoItem(title = "semantic", content = "owned synthetic value")).single()
        assertThat(cloud.writeTriggers.map { it.first }).containsExactly("private_create", "private_create")
        cloud.writeTriggers.clear()
        repo.save(repo.edit(item).copy(content = "edited synthetic value"))
        assertThat(cloud.writeTriggers.map { it.first }).containsExactly("private_edit", "private_edit")
        cloud.writeTriggers.clear()
        repo.pin(item)
        repo.resetOrder()
        repo.stageSend(item.id, "!room:yizhe.test", "transaction-semantic")
        val content = UUID.nameUUIDFromBytes("!room:yizhe.test\u0000transaction-semantic".encodeToByteArray()).toString()
        repo.confirmSend(content, "!room:yizhe.test", "event-semantic", System.currentTimeMillis())
        assertThat(cloud.writeTriggers.map { it.first }).containsExactly("private_pin", "private_reset_order", "private_usage_commit").inOrder()
    }

    /** Models the authority's CAS/state transitions; cryptography is verified by separate client/wire tests. */
    private class MemoryResources : AccountPrivateResourceClient {
        override val revision = MutableStateFlow(0L)
        val heads = mutableMapOf<String, PrivateResourceRecord>()
        val bodies = mutableMapOf<String, ByteArray>()
        val metadata = mutableMapOf<String, ByteArray>()
        val writeTriggers = mutableListOf<Pair<String, String>>()
        var failIndexWrite = false
        @Volatile var metadataLocked = false
        @Volatile var lockedMetadataReads = 0
        var lockMetadataOnIndexFailure = false
        var cacheLeases = false
        var fetches = 0
        var readOperations = 0
        override suspend fun <T> withReadOperation(scope: PrivateResourceScope, block: suspend () -> T): T {
            readOperations++
            return block()
        }
        var fetchDelayMillis = 0L
        var invalidateAfterRead = false
        var pendingFavourite: ByteArray? = null
        var concurrentFavourite: String? = null
        override suspend fun fetch(scope: PrivateResourceScope, resourceId: String): PrivateResourceRecord {
            delay(fetchDelayMillis)
            fetches++
            return heads[resourceId] ?: PrivateResourceRecord(resourceId, "COMMON_BODY", 0, null, false)
        }
        override fun readAuthorizationRemainingMillis(scope: PrivateResourceScope, record: PrivateResourceRecord) = if (cacheLeases) 60_000L else 0L
        override suspend fun sync(scope: PrivateResourceScope, cursor: Long) = PrivateResourceChanges("c".repeat(64), cursor, false, emptyList())
        override suspend fun read(scope: PrivateResourceScope, record: PrivateResourceRecord): ByteArray {
            val bytes = checkNotNull(bodies[record.resourceId]).copyOf()
            if (invalidateAfterRead) { invalidateAfterRead = false; invalidate() }
            return bytes
        }
        override suspend fun write(scope: PrivateResourceScope, resourceId: String, kind: String, expectedRevision: Long, plaintext: ByteArray): PrivateResourceRecord {
            if (kind == "COMMON_INDEX" && failIndexWrite) {
                if (lockMetadataOnIndexFailure) metadataLocked = true
                error("PRIVATE_RESOURCE_STORAGE_UNAVAILABLE")
            }
            if (kind == "FAVOURITE_REFERENCE" && concurrentFavourite != null) {
                val concurrent = checkNotNull(concurrentFavourite)
                concurrentFavourite = null
                val record = PrivateResourceRecord(resourceId, kind, expectedRevision + 1, UUID.randomUUID().toString(), false)
                heads[resourceId] = record
                bodies[resourceId] = JSONObject().put("format", 1).put("references", org.json.JSONArray(listOf(concurrent))).toString().encodeToByteArray()
                error("PRIVATE_RESOURCE_CONFLICT")
            }
            check((heads[resourceId]?.revision ?: 0) == expectedRevision) { "PRIVATE_RESOURCE_CONFLICT" }
            val record = PrivateResourceRecord(resourceId, kind, expectedRevision + 1, UUID.randomUUID().toString(), false)
            heads[resourceId] = record
            bodies[resourceId] = plaintext.copyOf()
            return record
        }
        override suspend fun writeWithLifecycleOperation(scope: PrivateResourceScope, resourceId: String, kind: String,
            expectedRevision: Long, plaintext: ByteArray, operationId: String, trigger: String): PrivateResourceRecord {
            writeTriggers += trigger to operationId
            return write(scope, resourceId, kind, expectedRevision, plaintext)
        }
        override suspend fun delete(scope: PrivateResourceScope, record: PrivateResourceRecord): PrivateResourceRecord {
            check(heads[record.resourceId]?.revision == record.revision) { "PRIVATE_RESOURCE_CONFLICT" }
            val deleted = record.copy(revision = record.revision + 1, deleted = true)
            heads[record.resourceId] = deleted
            bodies.remove(record.resourceId)?.fill(0)
            return deleted
        }
        override suspend fun resumePending(scope: PrivateResourceScope, resourceId: String): PrivateResourceRecord? {
            val bytes = pendingFavourite ?: return null
            val record = write(scope, resourceId, "FAVOURITE_REFERENCE", 0, bytes)
            pendingFavourite = null
            return record
        }
        override suspend fun readLocalMetadata(scope: PrivateResourceScope, name: String): ByteArray? {
            if (metadataLocked) { lockedMetadataReads++; error("PRIVATE_RESOURCE_LOCKED") }
            return metadata[name]?.copyOf()
        }
        override suspend fun writeLocalMetadata(scope: PrivateResourceScope, name: String, bytes: ByteArray?) {
            if (bytes == null) metadata.remove(name) else metadata[name] = bytes.copyOf()
        }
        override fun invalidate() { revision.value++ }
    }
}
