/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.timeline

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.media.MediaCaptionOperationContext
import io.element.android.security.dataprotection.api.LifecycleClientReceipt
import io.element.android.security.dataprotection.api.LifecycleComponent
import io.element.android.security.dataprotection.api.LifecycleOperationStatus
import io.element.android.security.dataprotection.api.LifecyclePhase
import io.element.android.security.dataprotection.api.LifecycleStep
import io.element.android.security.dataprotection.api.LifecycleStepStatus
import io.element.android.security.dataprotection.api.LifecycleSubjectHashes
import io.element.android.security.contentprotection.api.ContentId
import io.element.android.security.contentprotection.api.ContentVersionAppendResult
import io.element.android.security.contentprotection.api.ContentVersionGraph
import io.element.android.security.contentprotection.api.ContentVersionLineageScope
import io.element.android.security.contentprotection.api.ContentVersionLineageStore
import io.element.android.security.contentprotection.api.ContentVersionNode
import io.element.android.security.contentprotection.api.ContentVersionRetirementState
import io.element.android.security.contentprotection.api.KmsShareClient
import io.element.android.security.contentprotection.api.RegisterContentVersionRequest
import io.element.android.security.contentprotection.api.RegisterContentVersionResult
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleOperation
import io.element.android.security.dataprotection.api.TextVersionReceiptOwner
import io.element.android.security.dataprotection.api.LifecycleOperationContext
import io.element.android.security.securestorage.api.AccountVaultId
import io.element.android.security.securestorage.api.SecureStorageRoots
import io.element.android.security.securestorage.test.InMemorySensitiveFileStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class ProtectedCaptionLineageCoordinatorTest {
    @get:Rule val temporary = TemporaryFolder()

    private val scope = ContentVersionLineageScope(AccountVaultId("vault-one"), "!room:example")
    private val operationId = "caption-operation-0001"
    private val targetEvent = "\$root-event"
    private val root = ContentId("caption-root")
    private val now = System.currentTimeMillis()
    private val expiry = now + 120_000
    private val rootNode = ContentVersionNode(root, root, null, "root-operation-0001", sha(targetEvent), now - 1000, expiry)
    private val graph = ContentVersionGraph(root, 1, listOf(rootNode), 0)

    @Test fun `missing local TEXT root and child recover exact complete authority before edit intent`() = runTest {
        val editNode = rootNode.copy(contentId = ContentId("original-child"), parentContentId = root, operationId = "original-child-operation")
        for (versions in listOf(listOf(rootNode), listOf(rootNode, editNode))) {
            val target = versions.last()
            val restored = graph.copy(versions = versions)
            val lineage = mockk<ContentVersionLineageStore>()
            coEvery { lineage.rootForContent(scope, target.contentId) } returnsMany listOf(null, root)
            coEvery { lineage.graph(scope, root) } returns restored
            coEvery { lineage.restoreAuthorityGraph(scope, versions) } returns restored
            val kms = mockk<KmsShareClient>()
            coEvery { kms.recoverTextVersions(target.contentId, any(), sha(scope.roomId), sha(targetEvent), expiry) } returns versions
            val pending = MemoryPending()
            val owner = textOwner()
            val coordinator = ProtectedCaptionLineageCoordinator(scope, pending, lineage, mockk(), kms)
            withContext(LifecycleOperationContext(operationId, FullAppLifecycleId.LC_13) + owner) {
                coordinator.prepareText(targetEvent, target.contentId, expiry)
            }
            coVerify(exactly = 1) { lineage.restoreAuthorityGraph(scope, versions) }
            assertThat(pending.all(scope).single().parentContentId).isEqualTo(target.contentId.value)
            assertThat(pending.all(scope).single().parentExpiresAt).isEqualTo(expiry)
            assertThat(owner.delegated).isTrue()
        }
    }

    @Test fun `destroyed TEXT recovery and wrong target event never import history or persist edit`() = runTest {
        for (destroyed in listOf(true, false)) {
            val lineage = mockk<ContentVersionLineageStore>()
            coEvery { lineage.rootForContent(scope, root) } returns null
            val pending = MemoryPending()
            if (destroyed) pending.fenceDestruction(scope, root.value)
            val versions = listOf(if (destroyed) rootNode else rootNode.copy(eventIdSha256 = sha("other-event")))
            val kms = mockk<KmsShareClient>()
            coEvery { kms.recoverTextVersions(root, any(), any(), any(), expiry) } returns versions
            val coordinator = ProtectedCaptionLineageCoordinator(scope, pending, lineage, mockk(), kms)
            withContext(LifecycleOperationContext(operationId, FullAppLifecycleId.LC_13) + textOwner()) {
                assertThat(runCatching { coordinator.prepareText(targetEvent, root, expiry) }.isFailure).isTrue()
            }
            coVerify(exactly = 0) { lineage.restoreAuthorityGraph(any(), any()) }
            assertThat(pending.all(scope)).isEmpty()
        }
    }

    @Test fun `old root registration does not block a new root in the same room`() = runTest {
        val store = MemoryPending()
        val lineage = FakeLineage(graph)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val kms = mockk<KmsShareClient>()
        coEvery { kms.registerContentVersion(any()) } coAnswers {
            entered.complete(Unit)
            release.await()
            RegisterContentVersionResult(root, root, null, operationId, expiry, sha(targetEvent), now)
        }
        val first = ProtectedCaptionLineageCoordinator(scope, store, lineage, lifecycle(), kms)
        val second = ProtectedCaptionLineageCoordinator(scope, store, lineage, lifecycle(), kms)
        withContext(LifecycleOperationContext(operationId, FullAppLifecycleId.LC_15)) { first.prepareRoot(root) }
        val binding = launch { first.observeBoundEvent(root, targetEvent, expiry) }
        entered.await()
        val newRoot = ContentId("independent-new-root")
        val next = async(LifecycleOperationContext("independent-operation", FullAppLifecycleId.LC_15)) { second.prepareRoot(newRoot) }
        runCurrent()
        assertThat(next.isCompleted).isTrue()
        assertThat(next.await()).isEqualTo(newRoot)
        assertThat(binding.isCompleted).isFalse()
        release.complete(Unit)
        binding.join()
        assertThat(store.all(scope).single().contentId).isEqualTo(newRoot.value)
    }

    @Test fun `same root binding and destruction stay serialized across coordinator instances`() = runTest {
        val store = MemoryPending()
        val lineage = FakeLineage(graph)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val kms = mockk<KmsShareClient>()
        coEvery { kms.registerContentVersion(any()) } coAnswers {
            entered.complete(Unit)
            release.await()
            RegisterContentVersionResult(root, root, null, operationId, expiry, sha(targetEvent), now)
        }
        val first = ProtectedCaptionLineageCoordinator(scope, store, lineage, lifecycle(), kms)
        val second = ProtectedCaptionLineageCoordinator(scope, store, lineage, lifecycle(), kms)
        withContext(LifecycleOperationContext(operationId, FullAppLifecycleId.LC_15)) { first.prepareRoot(root) }
        val binding = launch { first.observeBoundEvent(root, targetEvent, expiry) }
        entered.await()
        val duplicate = launch { second.observeBoundEvent(root, targetEvent, expiry) }
        val destruction = launch { second.markDestructionPending(root) }
        runCurrent()
        assertThat(duplicate.isCompleted).isFalse()
        assertThat(destruction.isCompleted).isFalse()
        release.complete(Unit)
        binding.join()
        duplicate.join()
        destruction.join()
        assertThat(lineage.appended).hasSize(1)
        assertThat(store.isDestructionFenced(scope, root.value)).isTrue()
        coVerify(exactly = 1) { kms.registerContentVersion(any()) }
    }

    @Test fun `destruction before initial pending publication forbids preparing that root`() = runTest {
        val store = MemoryPending()
        val coordinator = ProtectedCaptionLineageCoordinator(scope, store, FakeLineage(graph), mockk(), mockk())
        coordinator.markDestructionPending(root)
        withContext(LifecycleOperationContext(operationId, FullAppLifecycleId.LC_15)) {
            expectSuspendFailure<IllegalStateException> { coordinator.prepareRoot(root) }
        }
        assertThat(store.all(scope)).isEmpty()
    }

    @Test fun `concurrent root preparation cannot reuse an operation for another content`() = runTest {
        val delegate = MemoryPending()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = object : CaptionPendingStore by delegate {
            override suspend fun put(scope: ContentVersionLineageScope, pending: CaptionPending) {
                entered.complete(Unit)
                release.await()
                delegate.put(scope, pending)
            }
        }
        val first = ProtectedCaptionLineageCoordinator(scope, store, FakeLineage(graph), mockk(), mockk())
        val second = ProtectedCaptionLineageCoordinator(scope, store, FakeLineage(graph), mockk(), mockk())
        val context = LifecycleOperationContext(operationId, FullAppLifecycleId.LC_15)
        val original = async(context) { first.prepareRoot(root) }
        entered.await()
        val conflicting = async(context) { runCatching { second.prepareRoot(ContentId("different-root")) } }
        runCurrent()
        assertThat(conflicting.isCompleted).isFalse()
        release.complete(Unit)
        assertThat(original.await()).isEqualTo(root)
        assertThat(conflicting.await().exceptionOrNull()).isInstanceOf(SecurityException::class.java)
        assertThat(store.all(scope).single().contentId).isEqualTo(root.value)
    }

    @Test fun `concurrent operations cannot establish the same root twice`() = runTest {
        val store = MemoryPending()
        val first = ProtectedCaptionLineageCoordinator(scope, store, FakeLineage(graph), mockk(), mockk())
        val second = ProtectedCaptionLineageCoordinator(scope, store, FakeLineage(graph), mockk(), mockk())
        val one = async(LifecycleOperationContext(operationId, FullAppLifecycleId.LC_15)) { runCatching { first.prepareRoot(root) } }
        val two = async(LifecycleOperationContext("other-operation", FullAppLifecycleId.LC_15)) { runCatching { second.prepareRoot(root) } }
        assertThat(listOf(one.await(), two.await()).count { it.isSuccess }).isEqualTo(1)
        assertThat(store.all(scope)).hasSize(1)
    }

    @Test fun `keyed locks retain waiters and retire after cancellation and completion`() = runTest {
        val locks = CaptionLineageMutexes()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = launch { locks.withKey("root") { entered.complete(Unit); release.await() } }
        entered.await()
        val second = launch { locks.withKey("root") { error("Cancelled waiter must never execute") } }
        runCurrent()
        assertThat(locks.activeKeys).isEqualTo(1)
        second.cancelAndJoin()
        assertThat(locks.activeKeys).isEqualTo(1)
        release.complete(Unit)
        first.join()
        assertThat(locks.activeKeys).isEqualTo(0)
    }

    @Test fun `keyed locks clean up failed owners and many historical roots`() = runTest {
        val locks = CaptionLineageMutexes()
        expectSuspendFailure<IllegalStateException> { locks.withKey("failed") { error("injected") } }
        assertThat(locks.activeKeys).isEqualTo(0)
        repeat(2000) { index -> locks.withKey("root-$index") { Unit } }
        assertThat(locks.activeKeys).isEqualTo(0)
    }

    @Test fun `destruction enumerates committed descendants and encrypted pending child after reopen`() = runTest {
        val files = InMemorySensitiveFileStore()
        val roots = object : SecureStorageRoots {
            override fun persistentRoot() = temporary.root
            override fun runtimeRoot() = temporary.root
        }
        val store = EncryptedCaptionPendingStore(files, roots)
        val committed = ContentId("caption-committed-child")
        val pendingChild = ContentId("caption-pending-child")
        val lineage = FakeLineage(graph.copy(
            generation = 2,
            versions = listOf(
                rootNode,
                ContentVersionNode(committed, root, root, "committed-operation", sha("\$committed-event"), now, expiry),
            ),
        ))
        store.put(scope, CaptionPending(
            operationId = "pending-operation",
            targetEventId = targetEvent,
            contentId = pendingChild.value,
            rootContentId = root.value,
            parentContentId = committed.value,
            originOperationId = rootNode.operationId,
            parentExpiresAt = expiry,
        ))
        val reopenedStore = EncryptedCaptionPendingStore(files, roots)
        val reopened = ProtectedCaptionLineageCoordinator(scope, reopenedStore, lineage, mockk(), mockk())
        val contentIds = reopened.contentIdsForDestruction(root)
        assertThat(contentIds).containsExactly(root, committed, pendingChild).inOrder()

        val erased = mutableListOf<ContentId>()
        assertThat(destroyContentIdsRetainingLineageDebt(
            contentIds = contentIds,
            markDestructionPending = reopened::markDestructionPending,
            destroyContentAccess = { erased += it; true },
        )).isTrue()
        assertThat(erased).containsExactly(root, committed, pendingChild).inOrder()
        assertThat(reopenedStore.all(scope).single().phase).isEqualTo(CaptionPendingPhase.DESTRUCTION_PENDING.name)
    }

    @Test fun `destruction keeps pending child when local graph is absent`() = runTest {
        val store = MemoryPending()
        val pendingChild = ContentId("caption-unregistered-child")
        store.put(scope, CaptionPending(
            operationId = "unregistered-operation",
            targetEventId = targetEvent,
            contentId = pendingChild.value,
            rootContentId = root.value,
            parentContentId = root.value,
            originOperationId = rootNode.operationId,
            parentExpiresAt = expiry,
        ))
        val missing = mockk<ContentVersionLineageStore>()
        coEvery { missing.graph(scope, root) } returns null
        val coordinator = ProtectedCaptionLineageCoordinator(scope, store, missing, mockk(), mockk())
        assertThat(coordinator.contentIdsForDestruction(root)).containsExactly(root, pendingChild).inOrder()
    }

    @Test fun `destruction fails closed when encrypted lineage cannot be read`() = runTest {
        val failure = IllegalStateException("encrypted lineage unavailable")
        val missing = mockk<ContentVersionLineageStore>()
        coEvery { missing.graph(scope, root) } throws failure
        val coordinator = ProtectedCaptionLineageCoordinator(scope, MemoryPending(), missing, mockk(), mockk())
        val thrown = expectSuspendFailure<IllegalStateException> {
            coordinator.contentIdsForDestruction(root)
        }
        assertThat(thrown).isSameInstanceAs(failure)
    }

    @Test fun `child edit is blocked until the root is registered locally`() = runTest {
        val missing = mockk<ContentVersionLineageStore>()
        coEvery { missing.graph(scope, root) } returns null
        val coordinator = ProtectedCaptionLineageCoordinator(
            scope, MemoryPending(), missing, mockk(), mockk(),
        )
        withContext(LifecycleOperationContext(operationId, FullAppLifecycleId.LC_15)) {
            expectSuspendFailure<SecurityException> { coordinator.prepare(targetEvent, operationId, root, expiry) }
        }
    }

    @Test fun `encrypted pending reopens with original operation and ambiguous send is never resent`() = runTest {
        val files = InMemorySensitiveFileStore()
        val roots = object : SecureStorageRoots {
            override fun persistentRoot() = temporary.root
            override fun runtimeRoot() = temporary.root
        }
        val lineage = FakeLineage(graph)
        val lifecycle = mockk<AppOperationalStateCoordinator>()
        val kms = mockk<KmsShareClient>()
        val first = ProtectedCaptionLineageCoordinator(scope, EncryptedCaptionPendingStore(files, roots), lineage, lifecycle, kms)
        val plan = withContext(LifecycleOperationContext(operationId, FullAppLifecycleId.LC_15)) {
            first.prepare(targetEvent, operationId, root, expiry)
        }
        first.matrixSendOutcomeUnknown(plan, IllegalStateException("response lost"))

        val reopenedStore = EncryptedCaptionPendingStore(files, roots)
        val reopened = ProtectedCaptionLineageCoordinator(scope, reopenedStore, lineage, lifecycle, kms)
        assertThat(reopenedStore.all(scope).single().operationId).isEqualTo(operationId)
        assertThat(reopenedStore.all(scope).single().contentId).isEqualTo(plan.childContentId.value)
        withContext(LifecycleOperationContext(operationId, FullAppLifecycleId.LC_15)) {
            expectSuspendFailure<SecurityException> { reopened.prepare(targetEvent, operationId, root, expiry) }
        }
        coVerify(exactly = 0) { kms.registerContentVersion(any()) }
    }

    @Test fun `unfenced server registration remains pending after append failure and reopen`() = runTest {
        val store = MemoryPending()
        val lineage = FakeLineage(graph).apply { failAppendOnce = true }
        val lifecycle = lifecycle()
        val kms = mockk<KmsShareClient>()
        val first = ProtectedCaptionLineageCoordinator(scope, store, lineage, lifecycle, kms)
        val plan = withContext(LifecycleOperationContext(operationId, FullAppLifecycleId.LC_15)) {
            first.prepare(targetEvent, operationId, root, expiry)
        }
        val boundEvent = "\$caption-version-event"
        val hash = sha(boundEvent)
        val serverCreated = now + 1000
        coEvery { kms.registerContentVersion(any()) } answers {
            val request = firstArg<RegisterContentVersionRequest>()
            RegisterContentVersionResult(
                request.contentId, root, root, operationId, expiry,
                hash, serverCreated,
            )
        }
        expectSuspendFailure<IllegalStateException> { first.observeBoundEvent(plan.childContentId, boundEvent, expiry) }
        assertThat(store.all(scope).single().phase).isEqualTo(CaptionPendingPhase.SERVER_REGISTERED.name)
        assertThat(store.all(scope).single().createdAt).isEqualTo(serverCreated)

        val reopened = ProtectedCaptionLineageCoordinator(scope, store, lineage, lifecycle, kms)
        expectSuspendFailure<SecurityException> { reopened.reconcileLocalCommits() }
        assertThat(store.all(scope).single().phase).isEqualTo(CaptionPendingPhase.SERVER_REGISTERED.name)
        assertThat(lineage.appended).isEmpty()
        expectSuspendFailure<SecurityException> { reopened.observeBoundEvent(plan.childContentId, boundEvent, expiry) }
        coVerify(exactly = 1) { kms.registerContentVersion(any()) }
    }

    @Test fun `initial caption root waits for real sync KMS binding before v4 registration`() = runTest {
        val store = MemoryPending()
        val lineage = FakeLineage(graph)
        val lifecycle = lifecycle()
        val kms = mockk<KmsShareClient>()
        val coordinator = ProtectedCaptionLineageCoordinator(scope, store, lineage, lifecycle, kms)
        withContext(LifecycleOperationContext(operationId, FullAppLifecycleId.LC_15)) {
            coordinator.prepareRoot(root)
        }
        val journal = store.all(scope).single()
        assertThat(journal.parentContentId).isNull()
        assertThat(journal.originOperationId).isEqualTo(operationId)
        coVerify(exactly = 0) { kms.registerContentVersion(any()) }

        val boundEvent = "\$root-bound-event"
        coEvery { kms.registerContentVersion(any()) } answers {
            val request = firstArg<RegisterContentVersionRequest>()
            assertThat(request.parentContentId).isNull()
            assertThat(request.operationId).isEqualTo(operationId)
            assertThat(request.originOperationId).isEqualTo(operationId)
            RegisterContentVersionResult(root, root, null, operationId, expiry, sha(boundEvent), now)
        }
        coordinator.observeBoundEvent(root, boundEvent, expiry)
        assertThat(store.all(scope)).isEmpty()
        assertThat(lineage.appended.single().parentContentId).isNull()
        coVerify(exactly = 1) { kms.registerContentVersion(any()) }
        withContext(LifecycleOperationContext(operationId, FullAppLifecycleId.LC_15)) {
            expectSuspendFailure<SecurityException> { coordinator.prepareRoot(ContentId("second-caption-root")) }
        }
        assertThat(store.all(scope)).isEmpty()
    }

    @Test fun committedIndexThenPendingRemovalFailureRemainsPendingAfterReopen() = runTest {
        val files = InMemorySensitiveFileStore()
        val roots = object : SecureStorageRoots {
            override fun persistentRoot() = temporary.root
            override fun runtimeRoot() = temporary.root
        }
        val store = EncryptedCaptionPendingStore(files, roots)
        var failRemove = true
        val failing = object : CaptionPendingStore by store {
            override suspend fun remove(scope: ContentVersionLineageScope, operationId: String) {
                if (failRemove) {
                    failRemove = false
                    throw IllegalStateException("injected pending removal failure")
                }
                store.remove(scope, operationId)
            }
        }
        val lineage = FakeLineage(graph)
        val kms = mockk<KmsShareClient>()
        val boundEvent = "committed-root-event"
        coEvery { kms.registerContentVersion(any()) } returns RegisterContentVersionResult(
            root, root, null, operationId, expiry, sha(boundEvent), now,
        )
        val first = ProtectedCaptionLineageCoordinator(scope, failing, lineage, lifecycle(), kms)
        withContext(LifecycleOperationContext(operationId, FullAppLifecycleId.LC_15)) {
            first.prepareRoot(root)
        }
        expectSuspendFailure<IllegalStateException> { first.observeBoundEvent(root, boundEvent, expiry) }
        assertThat(store.all(scope).single().phase).isEqualTo(CaptionPendingPhase.SERVER_REGISTERED.name)
        assertThat(store.reserveRootOperation(scope, operationId, root.value).phase)
            .isEqualTo(CaptionRootReservationPhase.COMMITTED)
        val reopened = ProtectedCaptionLineageCoordinator(
            scope, EncryptedCaptionPendingStore(files, roots), lineage, lifecycle(), kms,
        )
        expectSuspendFailure<SecurityException> { reopened.reconcileLocalCommits() }
        assertThat(store.all(scope).single().phase).isEqualTo(CaptionPendingPhase.SERVER_REGISTERED.name)
        coVerify(exactly = 1) { kms.registerContentVersion(any()) }
    }

    @Test fun reservationCommitThenPendingFailureRecoversExactBindingAfterReopen() = runTest {
        val files = InMemorySensitiveFileStore()
        val roots = object : SecureStorageRoots {
            override fun persistentRoot() = temporary.root
            override fun runtimeRoot() = temporary.root
        }
        val firstStore = EncryptedCaptionPendingStore(files, roots)
        val failing = object : CaptionPendingStore by firstStore {
            override suspend fun put(scope: ContentVersionLineageScope, pending: CaptionPending) {
                throw IllegalStateException("injected pending pointer failure")
            }
        }
        val rootContent = ContentId("recoverable-root")
        val first = ProtectedCaptionLineageCoordinator(scope, failing, FakeLineage(graph), mockk(), mockk())
        withContext(LifecycleOperationContext(operationId, FullAppLifecycleId.LC_15)) {
            expectSuspendFailure<IllegalStateException> { first.prepareRoot(rootContent) }
        }
        assertThat(firstStore.all(scope)).isEmpty()
        val reservation = firstStore.reserveRootOperation(scope, operationId, rootContent.value)
        assertThat(reservation.contentId).isEqualTo(rootContent.value)
        assertThat(reservation.phase).isEqualTo(CaptionRootReservationPhase.RESERVED)
        assertThat(reservation.existing).isTrue()
        val reopenedStore = EncryptedCaptionPendingStore(files, roots)
        val kms = mockk<KmsShareClient>()
        val reopened = ProtectedCaptionLineageCoordinator(scope, reopenedStore, FakeLineage(graph), mockk(), kms)
        withContext(LifecycleOperationContext(operationId, FullAppLifecycleId.LC_15)) {
            expectSuspendFailure<SecurityException> { reopened.prepareRoot(ContentId("different-root")) }
            assertThat(reopened.prepareRoot()).isEqualTo(rootContent)
        }
        assertThat(reopenedStore.all(scope).single().contentId).isEqualTo(rootContent.value)
        coVerify(exactly = 0) { kms.registerContentVersion(any()) }

        val otherScope = ContentVersionLineageScope(scope.vaultId, "!other-room:example")
        val other = ProtectedCaptionLineageCoordinator(
            otherScope, EncryptedCaptionPendingStore(files, roots), FakeLineage(graph), mockk(), kms,
        )
        withContext(LifecycleOperationContext(operationId, FullAppLifecycleId.LC_15)) {
            expectSuspendFailure<SecurityException> { other.prepareRoot(rootContent) }
        }
    }

    @Test fun crossRoomReuseFailsBeforeAnyKmsOrMatrixEffect() = runTest {
        val files = InMemorySensitiveFileStore()
        val roots = object : SecureStorageRoots {
            override fun persistentRoot() = temporary.root
            override fun runtimeRoot() = temporary.root
        }
        val firstStore = EncryptedCaptionPendingStore(files, roots)
        val first = ProtectedCaptionLineageCoordinator(scope, firstStore, FakeLineage(graph), mockk(), mockk())
        withContext(LifecycleOperationContext(operationId, FullAppLifecycleId.LC_15)) {
            first.prepareRoot(root)
        }
        val otherScope = ContentVersionLineageScope(scope.vaultId, "!other-room:example")
        val reopenedStore = EncryptedCaptionPendingStore(files, roots)
        val kms = mockk<KmsShareClient>()
        val second = ProtectedCaptionLineageCoordinator(otherScope, reopenedStore, FakeLineage(graph), mockk(), kms)
        var secondProtectionOrSendCount = 0
        withContext(LifecycleOperationContext(operationId, FullAppLifecycleId.LC_15)) {
            expectSuspendFailure<SecurityException> {
                second.prepareRoot(ContentId("other-room-root"))
                secondProtectionOrSendCount++
            }
        }
        assertThat(secondProtectionOrSendCount).isEqualTo(0)
        assertThat(reopenedStore.all(otherScope)).isEmpty()
        coVerify(exactly = 0) { kms.registerContentVersion(any()) }
    }

    @Test fun moreThan256CommittedRootsLeavePendingCapacityAvailable() = runTest {
        val files = InMemorySensitiveFileStore()
        val roots = object : SecureStorageRoots {
            override fun persistentRoot() = temporary.root
            override fun runtimeRoot() = temporary.root
        }
        val store = EncryptedCaptionPendingStore(files, roots)
        val lifecycle = mockk<AppOperationalStateCoordinator>()
        coEvery { lifecycle.resume(any()) } answers {
            val id = firstArg<String>()
            val resumed = mockk<LifecycleOperation>()
            every { resumed.operationId } returns id
            every { resumed.lifecycleId } returns FullAppLifecycleId.LC_15
            resumed
        }
        val kms = mockk<KmsShareClient>()
        coEvery { kms.registerContentVersion(any()) } answers {
            val request = firstArg<RegisterContentVersionRequest>()
            val ordinal = request.operationId.substringAfterLast('-').toInt()
            RegisterContentVersionResult(
                request.contentId, request.contentId, null, request.operationId,
                expiry, sha("event-$ordinal"), now,
            )
        }
        val lineage = FakeLineage(graph)
        val coordinator = ProtectedCaptionLineageCoordinator(scope, store, lineage, lifecycle, kms)
        repeat(258) { ordinal ->
            val id = "caption-operation-$ordinal"
            val contentId = ContentId("caption-root-$ordinal")
            withContext(LifecycleOperationContext(id, FullAppLifecycleId.LC_15)) {
                coordinator.prepareRoot(contentId)
            }
            coordinator.observeBoundEvent(contentId, "event-$ordinal", expiry)
            assertThat(store.all(scope)).isEmpty()
        }
        assertThat(lineage.appended).hasSize(258)
        val reopenedStore = EncryptedCaptionPendingStore(files, roots)
        val reopened = ProtectedCaptionLineageCoordinator(scope, reopenedStore, lineage, lifecycle, kms)
        withContext(LifecycleOperationContext("caption-operation-0", FullAppLifecycleId.LC_15)) {
            expectSuspendFailure<SecurityException> { reopened.prepareRoot(ContentId("duplicate-after-reopen")) }
            expectSuspendFailure<SecurityException> { reopened.prepareRoot(ContentId("caption-root-0")) }
        }
        assertThat(reopenedStore.all(scope)).isEmpty()
    }

    @Test fun serverRegisteredDestructionRetainsDebtAfterAppendFailureAndRestart() = runTest {
        val files = InMemorySensitiveFileStore()
        val roots = object : SecureStorageRoots {
            override fun persistentRoot() = temporary.root
            override fun runtimeRoot() = temporary.root
        }
        val store = EncryptedCaptionPendingStore(files, roots)
        val lineage = FakeLineage(graph).apply { failAppendOnce = true }
        val kms = mockk<KmsShareClient>()
        val first = ProtectedCaptionLineageCoordinator(scope, store, lineage, lifecycle(), kms)
        val plan = withContext(LifecycleOperationContext(operationId, FullAppLifecycleId.LC_15)) {
            first.prepare(targetEvent, operationId, root, expiry)
        }
        val boundEvent = "registered-before-destruction"
        val eventHash = sha(boundEvent)
        coEvery { kms.registerContentVersion(any()) } answers {
            val request = firstArg<RegisterContentVersionRequest>()
            RegisterContentVersionResult(request.contentId, root, root, operationId, expiry, eventHash, now)
        }
        expectSuspendFailure<IllegalStateException> { first.observeBoundEvent(plan.childContentId, boundEvent, expiry) }
        val registered = store.all(scope).single()
        assertThat(registered.phase).isEqualTo(CaptionPendingPhase.SERVER_REGISTERED.name)
        assertThat(registered.eventIdSha256).isEqualTo(eventHash)
        assertThat(registered.expiresAt).isEqualTo(expiry)
        assertThat(lineage.appended).isEmpty()

        val erased = mutableListOf<ContentId>()
        assertThat(destroyContentIdsRetainingLineageDebt(
            contentIds = linkedSetOf(root, plan.childContentId),
            markDestructionPending = first::markDestructionPending,
            destroyContentAccess = { erased += it; true },
        )).isTrue()
        assertThat(erased).containsExactly(root, plan.childContentId).inOrder()
        val debt = store.all(scope).single()
        assertThat(debt.phase).isEqualTo(CaptionPendingPhase.DESTRUCTION_PENDING.name)
        assertThat(debt.eventIdSha256).isEqualTo(eventHash)
        assertThat(debt.expiresAt).isEqualTo(expiry)
        assertThat(debt.createdAt).isEqualTo(now)

        val reopenedStore = EncryptedCaptionPendingStore(files, roots)
        val reopened = ProtectedCaptionLineageCoordinator(scope, reopenedStore, lineage, lifecycle(), kms)
        reopened.reconcileLocalCommits()
        assertThat(lineage.appended).isEmpty()
        assertThat(reopenedStore.all(scope).single().phase).isEqualTo(CaptionPendingPhase.DESTRUCTION_PENDING.name)
        expectSuspendFailure<SecurityException> {
            reopened.observeBoundEvent(plan.childContentId, boundEvent, expiry)
        }
        coVerify(exactly = 1) { kms.registerContentVersion(any()) }

        val cancelledErasure = mutableListOf<ContentId>()
        val cancellation = CancellationException("cleanup cancelled")
        val thrown = expectSuspendFailure<CancellationException> {
            destroyContentIdsRetainingLineageDebt(
                contentIds = linkedSetOf(root, plan.childContentId),
                markDestructionPending = {
                    reopened.markDestructionPending(it)
                    if (it == root) throw cancellation
                },
                destroyContentAccess = { cancelledErasure += it; true },
            )
        }
        assertThat(thrown).isSameInstanceAs(cancellation)
        assertThat(cancelledErasure).containsExactly(root, plan.childContentId).inOrder()
        assertThat(reopenedStore.all(scope).single().phase).isEqualTo(CaptionPendingPhase.DESTRUCTION_PENDING.name)
        assertThat(lineage.appended).isEmpty()
    }

    @Test fun independentFenceSurvivesFailedDestructionPhaseWriteAndRestart() = runTest {
        val files = InMemorySensitiveFileStore()
        val roots = object : SecureStorageRoots {
            override fun persistentRoot() = temporary.root
            override fun runtimeRoot() = temporary.root
        }
        val durable = EncryptedCaptionPendingStore(files, roots)
        val journalFailure = IllegalStateException("destruction phase write failed")
        val failing = object : CaptionPendingStore by durable {
            override suspend fun put(scope: ContentVersionLineageScope, pending: CaptionPending) {
                if (pending.phase == CaptionPendingPhase.DESTRUCTION_PENDING.name) throw journalFailure
                durable.put(scope, pending)
            }
        }
        val lineage = FakeLineage(graph).apply { failAppendOnce = true }
        val kms = mockk<KmsShareClient>()
        val coordinator = ProtectedCaptionLineageCoordinator(scope, failing, lineage, lifecycle(), kms)
        val plan = withContext(LifecycleOperationContext(operationId, FullAppLifecycleId.LC_15)) {
            coordinator.prepare(targetEvent, operationId, root, expiry)
        }
        val event = "bound-before-failed-destruction-write"
        coEvery { kms.registerContentVersion(any()) } answers {
            val request = firstArg<RegisterContentVersionRequest>()
            RegisterContentVersionResult(request.contentId, root, root, operationId, expiry, sha(event), now)
        }
        expectSuspendFailure<IllegalStateException> { coordinator.observeBoundEvent(plan.childContentId, event, expiry) }
        val erased = mutableListOf<ContentId>()
        val thrown = expectSuspendFailure<IllegalStateException> {
            destroyContentIdsRetainingLineageDebt(
                linkedSetOf(root, plan.childContentId),
                coordinator::markDestructionPending,
                { erased += it; true },
            )
        }
        assertThat(thrown).isSameInstanceAs(journalFailure)
        assertThat(erased).containsExactly(root, plan.childContentId).inOrder()
        assertThat(durable.all(scope).single().phase).isEqualTo(CaptionPendingPhase.SERVER_REGISTERED.name)

        val reopenedStore = EncryptedCaptionPendingStore(files, roots)
        assertThat(reopenedStore.isDestructionFenced(scope, plan.childContentId.value)).isTrue()
        val reopened = ProtectedCaptionLineageCoordinator(scope, reopenedStore, lineage, lifecycle(), kms)
        reopened.reconcileLocalCommits()
        val debt = reopenedStore.all(scope).single()
        assertThat(debt.phase).isEqualTo(CaptionPendingPhase.DESTRUCTION_PENDING.name)
        assertThat(debt.eventIdSha256).isEqualTo(sha(event))
        assertThat(debt.expiresAt).isEqualTo(expiry)
        assertThat(lineage.appended).isEmpty()
        coVerify(exactly = 1) { kms.registerContentVersion(any()) }
    }

    @Test fun dualPersistenceFailureStillErasesAllIdsAndBlocksRestartCommit() = runTest {
        val files = InMemorySensitiveFileStore()
        val roots = object : SecureStorageRoots {
            override fun persistentRoot() = temporary.root
            override fun runtimeRoot() = temporary.root
        }
        val durable = EncryptedCaptionPendingStore(files, roots)
        val cancellation = CancellationException("destruction fence cancelled")
        val failing = object : CaptionPendingStore by durable {
            override suspend fun fenceDestruction(scope: ContentVersionLineageScope, contentId: String) {
                throw cancellation
            }
            override suspend fun put(scope: ContentVersionLineageScope, pending: CaptionPending) {
                if (pending.phase == CaptionPendingPhase.DESTRUCTION_PENDING.name) {
                    throw IllegalStateException("destruction journal failed")
                }
                durable.put(scope, pending)
            }
        }
        val lineage = FakeLineage(graph).apply { failAppendOnce = true }
        val kms = mockk<KmsShareClient>()
        val coordinator = ProtectedCaptionLineageCoordinator(scope, failing, lineage, lifecycle(), kms)
        val plan = withContext(LifecycleOperationContext(operationId, FullAppLifecycleId.LC_15)) {
            coordinator.prepare(targetEvent, operationId, root, expiry)
        }
        val event = "bound-before-dual-failure"
        coEvery { kms.registerContentVersion(any()) } answers {
            val request = firstArg<RegisterContentVersionRequest>()
            RegisterContentVersionResult(request.contentId, root, root, operationId, expiry, sha(event), now)
        }
        expectSuspendFailure<IllegalStateException> { coordinator.observeBoundEvent(plan.childContentId, event, expiry) }
        val erased = mutableListOf<ContentId>()
        val thrown = expectSuspendFailure<CancellationException> {
            destroyContentIdsRetainingLineageDebt(
                linkedSetOf(root, plan.childContentId),
                coordinator::markDestructionPending,
                { erased += it; true },
            )
        }
        assertThat(thrown).isSameInstanceAs(cancellation)
        assertThat(erased).containsExactly(root, plan.childContentId).inOrder()

        val reopenedStore = EncryptedCaptionPendingStore(files, roots)
        assertThat(reopenedStore.isDestructionFenced(scope, plan.childContentId.value)).isFalse()
        val reopened = ProtectedCaptionLineageCoordinator(scope, reopenedStore, lineage, lifecycle(), kms)
        expectSuspendFailure<SecurityException> { reopened.reconcileLocalCommits() }
        expectSuspendFailure<SecurityException> { reopened.observeBoundEvent(plan.childContentId, event, expiry) }
        assertThat(reopenedStore.all(scope).single().phase).isEqualTo(CaptionPendingPhase.SERVER_REGISTERED.name)
        assertThat(lineage.appended).isEmpty()
        coVerify(exactly = 1) { kms.registerContentVersion(any()) }
    }

    @Test fun unsupportedLifecycleRootsAreRejectedBeforeEffects() = runTest {
        for (lifecycleId in listOf(FullAppLifecycleId.LC_13, FullAppLifecycleId.LC_16, FullAppLifecycleId.LC_21)) {
            val store = MemoryPending()
            val kms = mockk<KmsShareClient>()
            val coordinator = ProtectedCaptionLineageCoordinator(scope, store, FakeLineage(graph), mockk(), kms)
            withContext(LifecycleOperationContext("operation-${lifecycleId.name}", lifecycleId)) {
                expectSuspendFailure<IllegalArgumentException> { coordinator.prepareRoot(root) }
            }
            assertThat(store.all(scope)).isEmpty()
            coVerify(exactly = 0) { kms.registerContentVersion(any()) }
        }
    }

    @Test fun protectionFailureKeepsEncryptedUnknownOutcome() = runTest {
        val store = MemoryPending()
        val coordinator = ProtectedCaptionLineageCoordinator(scope, store, FakeLineage(graph), mockk(), mockk())
        withContext(LifecycleOperationContext(operationId, FullAppLifecycleId.LC_15)) {
            coordinator.prepareRoot(root)
        }
        coordinator.protectionOutcomeUnknown(root)
        assertThat(store.all(scope).single().phase).isEqualTo(CaptionPendingPhase.PROTECTION_OUTCOME_UNKNOWN.name)
        withContext(LifecycleOperationContext(operationId, FullAppLifecycleId.LC_15)) {
            expectSuspendFailure<SecurityException> { coordinator.prepareRoot(ContentId("replacement-root")) }
        }
        coordinator.markDestructionPending(root)
        assertThat(store.all(scope).single().phase).isEqualTo(CaptionPendingPhase.DESTRUCTION_PENDING.name)
    }

    @Test fun `LC21 destruction retains pending root debt and forbids late registration`() = runTest {
        val store = MemoryPending()
        val coordinator = ProtectedCaptionLineageCoordinator(
            scope, store, FakeLineage(graph), mockk(), mockk(),
        )
        withContext(LifecycleOperationContext(operationId, FullAppLifecycleId.LC_15)) {
            coordinator.prepareRoot(root)
        }
        coordinator.markDestructionPending(root)
        assertThat(store.all(scope).single().phase).isEqualTo(CaptionPendingPhase.DESTRUCTION_PENDING.name)
        expectSuspendFailure<SecurityException> {
            coordinator.observeBoundEvent(root, "\$late-sync", expiry)
        }
        assertThat(store.all(scope)).hasSize(1)
    }

    @Test fun `mismatched synced expiry fails before KMS registration and keeps pending`() = runTest {
        val store = MemoryPending()
        val lineage = FakeLineage(graph)
        val lifecycle = mockk<AppOperationalStateCoordinator>()
        val kms = mockk<KmsShareClient>()
        val coordinator = ProtectedCaptionLineageCoordinator(scope, store, lineage, lifecycle, kms)
        val plan = withContext(LifecycleOperationContext(operationId, FullAppLifecycleId.LC_15)) {
            coordinator.prepare(targetEvent, operationId, root, expiry)
        }
        expectSuspendFailure<IllegalArgumentException> {
            coordinator.observeBoundEvent(plan.childContentId, "\$bad-event", expiry + 1)
        }
        assertThat(store.all(scope).single().phase).isEqualTo(CaptionPendingPhase.AWAITING_BINDING.name)
        coVerify(exactly = 0) { kms.registerContentVersion(any()) }
    }


    @Test fun `initial media service receipt follows durable local lineage for either frontend order`() = runTest {
        for (frontendFirst in listOf(false, true)) {
            val h = OwnedRootHarness(frontendFirst = frontendFirst)
            h.prepare()
            assertThat(h.receipts).isEmpty()
            h.coordinator.observeBoundEvent(root, targetEvent, expiry)
            assertThat(h.lineage.appended).hasSize(1)
            assertThat(h.receipts.single().component).isEqualTo(LifecycleComponent.ANDROID_SERVICE)
            assertThat(h.store.all(scope)).isEmpty()
            assertThat(h.current.status).isEqualTo(if (frontendFirst) LifecycleOperationStatus.SUCCEEDED else LifecycleOperationStatus.RUNNING)
        }
    }

    @Test fun `lost service response reopens encrypted committed journal without repeating KMS or Matrix`() = runTest {
        val files = InMemorySensitiveFileStore()
        val roots = object : SecureStorageRoots {
            override fun persistentRoot() = temporary.root
            override fun runtimeRoot() = temporary.root
        }
        val h = OwnedRootHarness(store = EncryptedCaptionPendingStore(files, roots), frontendFirst = true)
        h.prepare()
        h.loseResponse = true
        expectSuspendFailure<IllegalStateException> { h.coordinator.observeBoundEvent(root, targetEvent, expiry) }
        val reopened = EncryptedCaptionPendingStore(files, roots)
        val saved = reopened.all(scope).single()
        assertThat(saved.phase).isEqualTo(CaptionPendingPhase.LINEAGE_COMMITTED.name)
        assertThat(saved.serviceAppliedAt).isEqualTo(h.receipts.single().occurredAtEpochMillis)
        ProtectedCaptionLineageCoordinator(scope, reopened, h.lineage, h.lifecycle, h.kms).reconcileLocalCommits()
        assertThat(reopened.all(scope)).isEmpty()
        assertThat(h.receipts).hasSize(1)
        assertThat(h.lineage.appended).hasSize(1)
        coVerify(exactly = 1) { h.kms.registerContentVersion(any()) }
    }

    @Test fun `service retry uses original intent and rejects mismatched acknowledgment or destruction`() = runTest {
        for (mismatch in listOf("operation", "account", "device", "room", "reference", "fence")) {
            val h = OwnedRootHarness()
            h.prepare()
            h.failDelivery = true
            expectSuspendFailure<IllegalStateException> { h.coordinator.observeBoundEvent(root, targetEvent, expiry) }
            val saved = h.store.all(scope).single()
            h.failDelivery = false
            when (mismatch) {
                "operation" -> h.current = h.current.copy(operationId = "foreign-operation-0001")
                "account" -> h.current = h.current.copy(subjects = h.current.subjects.copy(accountIdHash = "d".repeat(64)))
                "device" -> h.current = h.current.copy(subjects = h.current.subjects.copy(matrixDeviceIdHash = "d".repeat(64)))
                "room" -> h.current = h.current.copy(subjects = h.current.subjects.copy(roomIdHash = "d".repeat(64)))
                "reference" -> h.current = h.current.copy(steps = h.current.steps.map {
                    it.copy(status = LifecycleStepStatus.COMPLETE, receiptReference = "different-owner", resultCode = "ANDROID_SERVICE_APPLIED")
                })
                "fence" -> h.coordinator.markDestructionPending(root)
            }
            if (mismatch == "fence") {
                h.coordinator.reconcileLocalCommits()
            } else {
                expectSuspendFailure<IllegalArgumentException> { h.coordinator.reconcileLocalCommits() }
            }
            assertThat(h.store.all(scope)).hasSize(1)
            assertThat(h.receipts).hasSize(1)
            assertThat(h.store.all(scope).single().serviceAppliedAt).isEqualTo(saved.serviceAppliedAt)
        }
        val h = OwnedRootHarness()
        h.prepare()
        h.failDelivery = true
        expectSuspendFailure<IllegalStateException> { h.coordinator.observeBoundEvent(root, targetEvent, expiry) }
        h.failDelivery = false
        h.coordinator.reconcileLocalCommits()
        assertThat(h.receipts).hasSize(2)
        assertThat(h.receipts[1]).isEqualTo(h.receipts[0])
        assertThat(h.store.all(scope)).isEmpty()
    }

    @Test fun `failure persisting committed phase never sends service receipt or reappends on restart`() = runTest {
        val storage = MemoryPending()
        val store = object : CaptionPendingStore by storage {
            override suspend fun put(scope: ContentVersionLineageScope, pending: CaptionPending) {
                if (pending.phase == CaptionPendingPhase.LINEAGE_COMMITTED.name) error("disk unavailable")
                storage.put(scope, pending)
            }
        }
        val h = OwnedRootHarness(store)
        h.prepare()
        expectSuspendFailure<IllegalStateException> { h.coordinator.observeBoundEvent(root, targetEvent, expiry) }
        assertThat(h.lineage.appended).hasSize(1)
        assertThat(h.receipts).isEmpty()
        assertThat(storage.all(scope).single().phase).isEqualTo(CaptionPendingPhase.SERVER_REGISTERED.name)
        expectSuspendFailure<SecurityException> { h.coordinator.reconcileLocalCommits() }
        assertThat(h.lineage.appended).hasSize(1)
    }

    private fun textOwner(id: String = operationId) = TextVersionReceiptOwner(id, "a".repeat(64), "b".repeat(64), sha(scope.roomId))

    @Test fun `TEXT root persists owner before protection and blocks duplicate operation after reopen`() = runTest {
        val files = InMemorySensitiveFileStore()
        val roots = object : SecureStorageRoots {
            override fun persistentRoot() = temporary.root
            override fun runtimeRoot() = temporary.root
        }
        val store = EncryptedCaptionPendingStore(files, roots)
        val owner = textOwner()
        val coordinator = ProtectedCaptionLineageCoordinator(scope, store, FakeLineage(graph), mockk(), mockk())
        withContext(LifecycleOperationContext(operationId, FullAppLifecycleId.LC_13) + owner) {
            assertThat(coordinator.prepareRoot(root)).isEqualTo(root)
        }
        assertThat(owner.delegated).isTrue()
        coordinator.protectionOutcomeUnknown(root)
        val reopened = EncryptedCaptionPendingStore(files, roots)
        assertThat(reopened.all(scope).single().lifecycleId).isEqualTo(FullAppLifecycleId.LC_13.name)
        assertThat(reopened.all(scope).single().phase).isEqualTo(CaptionPendingPhase.PROTECTION_OUTCOME_UNKNOWN.name)
        val duplicateOwner = textOwner()
        withContext(LifecycleOperationContext(operationId, FullAppLifecycleId.LC_13) + duplicateOwner) {
            expectSuspendFailure<SecurityException> {
                ProtectedCaptionLineageCoordinator(scope, reopened, FakeLineage(graph), mockk(), mockk()).prepareRoot(root)
            }
        }
        assertThat(duplicateOwner.delegated).isFalse()
    }

    @Test fun `TEXT pending publication failure retries the reserved root and delegates only after publication`() = runTest {
        val durable = MemoryPending()
        var failOnce = true
        val pending = object : CaptionPendingStore by durable {
            override suspend fun advanceRootOperation(scope: ContentVersionLineageScope, operationId: String,
                contentId: String, phase: CaptionRootReservationPhase) {
                if (failOnce) { failOnce = false; error("phase publication failed") }
                durable.advanceRootOperation(scope, operationId, contentId, phase)
            }
        }
        val coordinator = ProtectedCaptionLineageCoordinator(scope, pending, FakeLineage(graph), mockk(), mockk())
        val owner = textOwner()
        withContext(LifecycleOperationContext(operationId, FullAppLifecycleId.LC_13) + owner) {
            expectSuspendFailure<IllegalStateException> { coordinator.prepareRoot(root) }
        }
        assertThat(owner.delegated).isFalse()
        assertThat(durable.all(scope).single().contentId).isEqualTo(root.value)
        val retriedOwner = textOwner()
        withContext(LifecycleOperationContext(operationId, FullAppLifecycleId.LC_13) + retriedOwner) {
            assertThat(coordinator.prepareRoot(root)).isEqualTo(root)
        }
        assertThat(retriedOwner.delegated).isTrue()
        assertThat(durable.all(scope)).hasSize(1)
    }

    @Test fun `TEXT edit persists exact parent and inherited expiry and rejects unknown send duplication`() = runTest {
        val store = MemoryPending()
        val coordinator = ProtectedCaptionLineageCoordinator(scope, store, FakeLineage(graph), mockk(), mockk())
        val owner = textOwner()
        val plan = withContext(LifecycleOperationContext(operationId, FullAppLifecycleId.LC_13) + owner) {
            coordinator.prepareText(targetEvent, root, expiry)
        }
        val intent = store.all(scope).single()
        assertThat(intent.parentContentId).isEqualTo(root.value)
        assertThat(intent.rootContentId).isEqualTo(root.value)
        assertThat(intent.originOperationId).isEqualTo(rootNode.operationId)
        assertThat(coordinator.parentExpiry(plan.childContentId)).isEqualTo(expiry)
        assertThat(owner.delegated).isTrue()
        coordinator.protectionOutcomeUnknown(plan.childContentId)
        withContext(LifecycleOperationContext("other-operation-0001", FullAppLifecycleId.LC_13) + textOwner("other-operation-0001")) {
            expectSuspendFailure<IllegalArgumentException> { coordinator.prepareText(targetEvent, root, expiry) }
        }
        assertThat(store.all(scope).single().contentId).isEqualTo(plan.childContentId.value)
        assertThat(coordinator.textContentIdsForDestruction(plan.childContentId)).containsExactly(root, plan.childContentId)
    }

    @Test fun `TEXT destruction excludes independent reply and fails closed on missing historical membership`() = runTest {
        val child = ContentId("text-child")
        val lineage = FakeLineage(graph.copy(versions = listOf(rootNode,
            ContentVersionNode(child, root, root, operationId, sha(targetEvent), now, expiry))))
        val coordinator = ProtectedCaptionLineageCoordinator(scope, MemoryPending(), lineage, mockk(), mockk())
        assertThat(coordinator.textContentIdsForDestruction(child)).containsExactly(root, child)
        expectSuspendFailure<SecurityException> { coordinator.textContentIdsForDestruction(ContentId("independent-reply")) }
        withContext(LifecycleOperationContext(operationId, FullAppLifecycleId.LC_13) + textOwner()) {
            expectSuspendFailure<IllegalArgumentException> { coordinator.prepareText(targetEvent, root, expiry) }
        }
    }

    @Test fun `TEXT owner from another room cannot persist intent or delegate service receipt`() = runTest {
        val store = MemoryPending()
        val owner = TextVersionReceiptOwner(operationId, "a".repeat(64), "b".repeat(64), sha("!other:example"))
        val coordinator = ProtectedCaptionLineageCoordinator(scope, store, FakeLineage(graph), mockk(), mockk())
        withContext(LifecycleOperationContext(operationId, FullAppLifecycleId.LC_13) + owner) {
            expectSuspendFailure<IllegalArgumentException> { coordinator.prepareRoot(root) }
        }
        assertThat(store.all(scope)).isEmpty()
        assertThat(owner.delegated).isFalse()
    }

    @Test fun `TEXT service receipt waits for server registration and durable local commit`() = runTest {
        val h = OwnedRootHarness()
        h.current = h.current.copy(lifecycleId = FullAppLifecycleId.LC_13)
        withContext(LifecycleOperationContext(operationId, FullAppLifecycleId.LC_13) + textOwner()) { h.coordinator.prepareRoot(root) }
        assertThat(h.receipts).isEmpty()
        h.coordinator.observeBoundEvent(root, targetEvent, expiry)
        assertThat(h.receipts.single().receiptReference).isEqualTo("android-service:" + operationId)
        assertThat(h.store.all(scope)).isEmpty()
    }

    @Test fun `live TEXT binding avoids recovery reads but still verifies and persists every result`() = runTest {
        val h = OwnedRootHarness()
        h.current = h.current.copy(lifecycleId = FullAppLifecycleId.LC_13)
        val operation = LifecycleOperationContext(operationId, FullAppLifecycleId.LC_13)
        withContext(operation + textOwner()) { h.coordinator.prepareRoot(root) }
        h.coordinator.observeBoundEvent(root, targetEvent, expiry, liveSendOperation = operation)
        coVerify(exactly = 0) { h.lifecycle.resume(any()) }
        coVerify(exactly = 0) { h.lifecycle.operation(any()) }
        coVerify(exactly = 1) { h.kms.registerContentVersion(any()) }
        assertThat(h.receipts.single().receiptReference).isEqualTo("android-service:$operationId")
        assertThat(h.lineage.appended).hasSize(1)
        assertThat(h.store.all(scope)).isEmpty()
    }

    @Test fun `live TEXT binding rejects another operation before any remote or local commit`() = runTest {
        val h = OwnedRootHarness()
        h.current = h.current.copy(lifecycleId = FullAppLifecycleId.LC_13)
        withContext(LifecycleOperationContext(operationId, FullAppLifecycleId.LC_13) + textOwner()) {
            h.coordinator.prepareRoot(root)
        }
        expectSuspendFailure<IllegalArgumentException> {
            h.coordinator.observeBoundEvent(root, targetEvent, expiry,
                liveSendOperation = LifecycleOperationContext("foreign-operation-0001", FullAppLifecycleId.LC_13))
        }
        coVerify(exactly = 0) { h.kms.registerContentVersion(any()) }
        assertThat(h.lineage.appended).isEmpty()
        assertThat(h.receipts).isEmpty()
        assertThat(h.store.all(scope)).hasSize(1)
    }

    @Test fun `live TEXT authority rejection preserves original pending intent without a service receipt`() = runTest {
        val h = OwnedRootHarness()
        h.current = h.current.copy(lifecycleId = FullAppLifecycleId.LC_13)
        val operation = LifecycleOperationContext(operationId, FullAppLifecycleId.LC_13)
        withContext(operation + textOwner()) { h.coordinator.prepareRoot(root) }
        coEvery { h.kms.registerContentVersion(any()) } throws SecurityException("Operation revoked")
        expectSuspendFailure<SecurityException> {
            h.coordinator.observeBoundEvent(root, targetEvent, expiry, liveSendOperation = operation)
        }
        assertThat(h.store.all(scope).single().operationId).isEqualTo(operationId)
        assertThat(h.lineage.appended).isEmpty()
        assertThat(h.receipts).isEmpty()
    }

    @Test fun `live TEXT lost receipt response retains original fact and recovery verifies it without replay`() = runTest {
        val h = OwnedRootHarness()
        h.current = h.current.copy(lifecycleId = FullAppLifecycleId.LC_13)
        val operation = LifecycleOperationContext(operationId, FullAppLifecycleId.LC_13)
        withContext(operation + textOwner()) { h.coordinator.prepareRoot(root) }
        h.loseResponse = true
        expectSuspendFailure<IllegalStateException> {
            h.coordinator.observeBoundEvent(root, targetEvent, expiry, liveSendOperation = operation)
        }
        assertThat(h.store.all(scope).single().phase).isEqualTo(CaptionPendingPhase.LINEAGE_COMMITTED.name)
        assertThat(h.receipts).hasSize(1)
        h.loseResponse = false
        h.coordinator.reconcileLocalCommits()
        coVerify(exactly = 1) { h.lifecycle.operation(operationId) }
        assertThat(h.receipts).hasSize(1)
        assertThat(h.lineage.appended).hasSize(1)
        assertThat(h.store.all(scope)).isEmpty()
    }

    private inner class OwnedRootHarness(
        val store: CaptionPendingStore = MemoryPending(),
        private val frontendFirst: Boolean = false,
    ) {
        val lineage = FakeLineage(graph)
        val lifecycle = mockk<AppOperationalStateCoordinator>()
        val kms = mockk<KmsShareClient>()
        val receipts = mutableListOf<LifecycleClientReceipt>()
        var failDelivery = false
        var loseResponse = false
        var current = LifecycleOperation(
            operationId = operationId, lifecycleId = FullAppLifecycleId.LC_15, idempotencyKey = operationId,
            subjects = LifecycleSubjectHashes(accountIdHash = "a".repeat(64), matrixDeviceIdHash = "b".repeat(64), roomIdHash = sha(scope.roomId)),
            status = LifecycleOperationStatus.RUNNING, currentPhase = LifecyclePhase.INTENT_PERSISTED,
            deadlineEpochMillis = expiry, createdAtEpochMillis = now, updatedAtEpochMillis = now,
            steps = listOf(LifecycleStep("service-step-0001", LifecycleComponent.ANDROID_SERVICE,
                status = LifecycleStepStatus.PENDING, attemptCount = 0, updatedAtEpochMillis = now)),
        )
        val coordinator = ProtectedCaptionLineageCoordinator(scope, store, lineage, lifecycle, kms)
        init {
            coEvery { lifecycle.resume(operationId) } answers { current }
            coEvery { lifecycle.operation(operationId) } answers { current }
            coEvery { kms.registerContentVersion(any()) } answers {
                val req = firstArg<RegisterContentVersionRequest>()
                RegisterContentVersionResult(req.contentId, req.rootContentId, null, operationId, expiry, sha(targetEvent), now)
            }
            coEvery { lifecycle.recordClientReceipt(any()) } coAnswers {
                val receipt = firstArg<LifecycleClientReceipt>()
                assertThat(store.all(scope).single().phase).isEqualTo(CaptionPendingPhase.LINEAGE_COMMITTED.name)
                assertThat(store.reserveRootOperation(scope, operationId, root.value).phase).isEqualTo(CaptionRootReservationPhase.COMMITTED)
                assertThat(lineage.appended).hasSize(1)
                receipts += receipt
                if (failDelivery) error("transport unavailable")
                current = current.copy(
                    status = if (frontendFirst) LifecycleOperationStatus.SUCCEEDED else LifecycleOperationStatus.RUNNING,
                    terminalReceiptId = if (frontendFirst) "terminal-receipt-0001" else null,
                    steps = current.steps.map { it.copy(status = LifecycleStepStatus.COMPLETE,
                        receiptReference = receipt.receiptReference, resultCode = receipt.resultCode) },
                )
                if (loseResponse) error("response lost")
                current
            }
        }
        suspend fun prepare() {
            withContext(LifecycleOperationContext(operationId, FullAppLifecycleId.LC_15) + MediaCaptionOperationContext(
                "upload-operation-0001", operationId, "a".repeat(64), "b".repeat(64), sha(scope.roomId),
            )) { coordinator.prepareRoot(root) }
        }
    }

    private fun lifecycle(): AppOperationalStateCoordinator = mockk<AppOperationalStateCoordinator>().also { lifecycle ->
        val operation = mockk<LifecycleOperation>()
        every { operation.operationId } returns operationId
        every { operation.lifecycleId } returns FullAppLifecycleId.LC_15
        coEvery { lifecycle.resume(operationId) } returns operation
    }

    private class MemoryPending : CaptionPendingStore {
        private val items = linkedMapOf<Pair<String, String>, MutableMap<String, CaptionPending>>()
        private data class RootOperation(val roomId: String, val contentId: String, val phase: CaptionRootReservationPhase)
        private val rootOperations = linkedMapOf<Pair<String, String>, RootOperation>()
        private fun key(scope: ContentVersionLineageScope) = scope.vaultId.value to scope.roomId
        override suspend fun reserveRootOperation(
            scope: ContentVersionLineageScope, operationId: String, contentId: String?,
        ): CaptionRootReservation {
            val operationKey = scope.vaultId.value to operationId
            val old = rootOperations[operationKey]
            if (old != null) {
                if (old.roomId != scope.roomId || contentId != null && old.contentId != contentId) {
                    throw SecurityException("LC operation is reserved for another room or root.")
                }
                return CaptionRootReservation(old.contentId, old.phase, existing = true)
            }
            val chosen = contentId ?: java.util.UUID.randomUUID().toString()
            rootOperations[operationKey] = RootOperation(scope.roomId, chosen, CaptionRootReservationPhase.RESERVED)
            return CaptionRootReservation(chosen, CaptionRootReservationPhase.RESERVED, existing = false)
        }
        override suspend fun advanceRootOperation(
            scope: ContentVersionLineageScope, operationId: String, contentId: String, phase: CaptionRootReservationPhase,
        ) {
            val operationKey = scope.vaultId.value to operationId
            val old = requireNotNull(rootOperations[operationKey])
            require(old.roomId == scope.roomId && old.contentId == contentId)
            if (old.phase == phase) return
            require(
                old.phase == CaptionRootReservationPhase.RESERVED && phase == CaptionRootReservationPhase.PENDING_ESTABLISHED ||
                    old.phase == CaptionRootReservationPhase.PENDING_ESTABLISHED && phase == CaptionRootReservationPhase.COMMITTED
            )
            rootOperations[operationKey] = old.copy(phase = phase)
        }
        private val destructionFences = linkedSetOf<Pair<Pair<String, String>, String>>()
        override suspend fun fenceDestruction(scope: ContentVersionLineageScope, contentId: String) {
            destructionFences += key(scope) to contentId
        }
        override suspend fun isDestructionFenced(scope: ContentVersionLineageScope, contentId: String) =
            (key(scope) to contentId) in destructionFences
        override suspend fun all(scope: ContentVersionLineageScope) = items[key(scope)]?.values?.toList().orEmpty()
        override suspend fun put(scope: ContentVersionLineageScope, pending: CaptionPending) {
            items.getOrPut(key(scope)) { linkedMapOf() }[pending.operationId] = pending
        }
        override suspend fun remove(scope: ContentVersionLineageScope, operationId: String) {
            items[key(scope)]?.remove(operationId)
        }
    }

    private class FakeLineage(private val initial: ContentVersionGraph) : ContentVersionLineageStore {
        var failAppendOnce = false
        val appended = mutableListOf<ContentVersionNode>()
        override suspend fun graph(scope: ContentVersionLineageScope, rootContentId: ContentId): ContentVersionGraph? =
            initial.takeIf { it.rootContentId == rootContentId }
        override suspend fun rootForContent(scope: ContentVersionLineageScope, contentId: ContentId): ContentId? =
            (initial.versions + appended).singleOrNull { it.contentId == contentId }?.rootContentId
        override suspend fun append(scope: ContentVersionLineageScope, node: ContentVersionNode): ContentVersionAppendResult {
            if (failAppendOnce) {
                failAppendOnce = false
                throw IllegalStateException("local encrypted append failed")
            }
            appended += node
            return ContentVersionAppendResult(
                initial.copy(versions = initial.versions + node), ContentVersionRetirementState.CLEAN,
            )
        }
        override suspend fun retryPendingRetirements(scope: ContentVersionLineageScope) = 0
    }

    private fun sha(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

private suspend inline fun <reified T : Throwable> expectSuspendFailure(block: suspend () -> Unit): T {
    try {
        block()
    } catch (failure: Throwable) {
        if (failure is T) return failure
        throw AssertionError("Expected " + T::class.java.name + " but got " + failure::class.java.name, failure)
    }
    throw AssertionError("Expected " + T::class.java.name + " to be thrown")
}
