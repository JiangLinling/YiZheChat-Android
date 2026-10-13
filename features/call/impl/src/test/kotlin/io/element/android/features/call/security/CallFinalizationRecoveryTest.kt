/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.call.security

import com.google.common.truth.Truth.assertThat
import io.element.android.features.call.api.CallData
import io.element.android.features.call.impl.security.*
import io.element.android.features.call.utils.FakeCallProtectionService
import io.element.android.libraries.matrix.test.A_ROOM_ID
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.libraries.matrix.test.FakeMatrixClientProvider
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.libraries.matrix.test.room.aRoomInfo
import io.element.android.libraries.matrix.test.timeline.FakeTimeline
import io.element.android.security.dataprotection.api.*
import io.element.android.security.dataprotection.test.FakeAppOperationalStateCoordinator
import io.element.android.security.securestorage.api.*
import io.element.android.security.securestorage.test.InMemorySensitiveFileStore
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class CallFinalizationRecoveryTest {
    @org.junit.Before fun captureFailures() {
        timber.log.Timber.plant(object : timber.log.Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                println(message)
                t?.printStackTrace()
            }
        })
    }
    @org.junit.After fun clearLogger() { timber.log.Timber.uprootAll() }
    @get:Rule val temporary = TemporaryFolder()
    private val files = InMemorySensitiveFileStore()
    private val roots = object : SecureStorageRoots {
        override fun persistentRoot(): File = temporary.root
        override fun runtimeRoot(): File = temporary.root
    }
    private val timeline = FakeTimeline()
    private val room = FakeJoinedRoom(liveTimeline = timeline).apply { givenRoomInfo(aRoomInfo(isDm = true)) }
    private var roomCloses = 0
    private val trackedRoom = object : io.element.android.libraries.matrix.api.room.JoinedRoom by room {
        override fun close() { roomCloses++; room.close() }
    }
    private val client = FakeMatrixClient().apply {
        givenGetRoomResult(A_ROOM_ID, trackedRoom)
        getCurrentRoomInfoLambda = { Result.success(room.roomInfoFlow.value) }
    }
    private val clients = FakeMatrixClientProvider { Result.success(client) }
    private val protection = FakeCallProtectionService()
    private val operations = FakeAppOperationalStateCoordinator()
    private val clock = io.element.android.services.toolbox.test.systemclock.FakeSystemClock()
        .apply { epochMillisResult = System.currentTimeMillis() }
    private val call get() = CallData(client.sessionId, A_ROOM_ID, true, "\$exact-invitation", "attempt-1")
    private val operationId = "join-operation-123456789"
    private var sent = 0

    private fun TestScope.recovery(
        coordinator: AppOperationalStateCoordinator = operations,
        store: CallFinalizationStore = EncryptedCallFinalizationStore(files, roots),
        authorization: CallProtectionService = protection,
    ) = DefaultCallFinalizationRecovery(store, clients, authorization, coordinator, backgroundScope, clock)

    @Test fun `real call authorization blocks restored worker reads when SDK Vault is unlocked but PIN is locked`() = runTest {
        org.junit.Assume.assumeTrue(io.element.android.appconfig.NextDeploymentConfig.isNextDeployment)
        val state = kotlinx.coroutines.flow.MutableStateFlow(ProtectedPreviewAccessState(false, false, true))
        val gate = object : ProtectedPreviewAuthorizationGate {
            override val accessState = state
            override val authorizationRevision = kotlinx.coroutines.flow.MutableStateFlow(0L)
            override fun canAttemptPreview() = accessState.value.allowsPreviewAttempt
            override fun canDisplayAuthorizedContent(contentProtectionAuthorized: Boolean) = contentProtectionAuthorized && canAttemptPreview()
        }
        val vault = io.element.android.security.securestorage.test.FakeVaultKeyService()
        val vaultId = deriveAccountVaultId(client.sessionId.value, client.deviceId.value)
        vault.provision(VaultProvisioningRequest(vaultId, accountIdHash(client.sessionId.value), false, false))
        check(vault.unlock(vaultId))
        val authorization = DefaultCallProtectionService(
            io.element.android.security.dataprotection.test.FakeDataProtectionPolicyProvider(allowRtc = true),
            object : DataProtectionPolicyRevisionProvider { override val policyRevision = kotlinx.coroutines.flow.MutableStateFlow(0L) },
            io.element.android.security.securestorage.test.FakeDeviceTrustService(), vault, gate,
        )
        val backing = EncryptedCallFinalizationStore(files, roots)
        var reads = 0
        val store = object : CallFinalizationStore by backing {
            override suspend fun read(pending: PendingCallFinalization): CallFinalizationRecord { reads++; return backing.read(pending) }
        }
        val original = recovery(store = store)
        original.checkpoint(call, operationId)
        original.released(call, true)
        val restarted = recovery(store = store, authorization = authorization)
        restarted.recoverOnce { false }
        assertThat(reads).isEqualTo(0)
        assertThat(operations.clientReceipts).isEmpty()
        state.value = ProtectedPreviewAccessState(true, false, true)
        restarted.recoverOnce { false }
        assertThat(reads).isEqualTo(0)
        state.value = ProtectedPreviewAccessState(true, true, true)
        timeline.sendCallLifecycleLambda = { _, _, _ -> sent++; Result.success(Unit) }
        restarted.recoverOnce { false }
        assertThat(reads).isGreaterThan(0)
        assertThat(sent).isEqualTo(1)
        assertThat(operations.clientReceipts).hasSize(2)
    }

    @Test fun `locked cleanup survives store and worker recreation and retains exact operation`() = runTest {
        timeline.sendCallLifecycleLambda = { event, state, _ ->
            assertThat(event.value).isEqualTo("\$exact-invitation")
            assertThat(state.name).isEqualTo("ENDED")
            assertThat(currentLifecycleOperationContext()?.operationId).isEqualTo("$operationId-hangup")
            sent++
            Result.success(Unit)
        }
        val first = recovery()
        first.checkpoint(call, operationId)
        first.update(call, connected = true)
        protection.deviceAccess.value = false
        assertThat(first.released(call, true)).isTrue()
        first.recoverOnce { error("Already released") }
        assertThat(operations.startedRequests.map { it.operationId }).containsExactly("$operationId-hangup")
        assertThat(operations.clientReceipts).isEmpty()
        assertThat(sent).isEqualTo(0)
        testScheduler.advanceTimeBy(11 * 60_000L)
        // No timeout or retained coroutine is involved: a different worker and index instance recover.
        val restarted = recovery()
        restarted.recoverOnce { error("Already released") }
        assertThat(sent).isEqualTo(0)
        protection.deviceAccess.value = true
        restarted.recoverOnce { error("Already released") }
        assertThat(sent).isEqualTo(1)
        assertThat(operations.clientReceipts).hasSize(2)
        assertThat(roomCloses).isEqualTo(0) // The original authority operation is still running.
        assertThat(operations.startedRequests.map { it.operationId }.toSet()).containsExactly("$operationId-hangup")
        val store = EncryptedCallFinalizationStore(files, roots)
        assertThat(store.read(store.pending().single()).recordSent).isTrue()
        val pointers = File(temporary.root, "rtc-finalization-v1").listFiles()!!.joinToString { it.readText() }
        assertThat(pointers).doesNotContain("exact-invitation")
        assertThat(pointers).doesNotContain(client.sessionId.value)
        assertThat(pointers).doesNotContain(A_ROOM_ID.value)
    }

    @Test fun `receipt transport failure followed by restart does not resend an already recorded call`() = runTest {
        timeline.sendCallLifecycleLambda = { _, _, _ -> sent++; Result.success(Unit) }
        val failing = object : AppOperationalStateCoordinator by operations {
            override suspend fun recordClientReceipt(receipt: LifecycleClientReceipt): LifecycleOperation = throw IOException("HTTP 429")
        }
        val first = recovery(failing)
        first.checkpoint(call, operationId)
        first.released(call, true)
        first.recoverOnce { false }
        assertThat(sent).isEqualTo(1)
        assertThat(operations.clientReceipts).isEmpty()
        recovery().recoverOnce { false }
        assertThat(sent).isEqualTo(1)
        assertThat(operations.clientReceipts).hasSize(2)
    }

    @Test fun `callee widget notification cannot replace the incoming invitation`() = runTest {
        val store = EncryptedCallFinalizationStore(files, roots)
        val current = recovery(store = store)
        current.checkpoint(call, operationId)
        current.update(call, invitationId = "\$callee-member-notification", connected = true)
        val saved = store.read(store.pending().single())
        assertThat(saved.invitationId).isEqualTo(call.notificationEventId)
        assertThat(saved.connected).isTrue()
        timeline.sendCallLifecycleLambda = { event, state, _ ->
            assertThat(event.value).isEqualTo(call.notificationEventId)
            assertThat(state).isEqualTo(io.element.android.libraries.matrix.api.timeline.item.event.CallLifecycleState.ENDED)
            sent++
            Result.success(Unit)
        }
        current.released(call, true)
        current.recoverOnce { false }
        assertThat(sent).isEqualTo(1)
    }

    @Test fun `outgoing invitation cannot be rebound to a later call`() = runTest {
        val store = EncryptedCallFinalizationStore(files, roots)
        val current = recovery(store = store)
        val outgoing = call.copy(notificationEventId = null)
        current.checkpoint(outgoing, operationId)
        current.update(outgoing, invitationId = "\$original-outgoing")
        val failure = runCatching { current.update(outgoing, invitationId = "\$later-outgoing") }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(store.read(store.pending().single()).invitationId).isEqualTo("\$original-outgoing")
    }

    @Test fun `failed native cleanup cannot publish receipts in the same process`() = runTest {
        val current = recovery()
        current.checkpoint(call, operationId)
        current.released(call, false)
        current.recoverOnce { error("Must not substitute process death for failed live cleanup") }
        assertThat(operations.clientReceipts).isEmpty()
        assertThat(operations.startedRequests).isEmpty()
    }

    @Test fun `abandoned active checkpoint requires real native cleanup before recovery`() = runTest {
        timeline.sendCallLifecycleLambda = { _, _, _ -> sent++; Result.success(Unit) }
        recovery().checkpoint(call, operationId)
        val restarted = recovery()
        restarted.recoverOnce { false }
        assertThat(operations.clientReceipts).isEmpty()
        restarted.recoverOnce { true }
        assertThat(sent).isEqualTo(1)
        assertThat(operations.clientReceipts).hasSize(2)
    }

    @Test fun `destroyed Vault cannot release old context or fabricate completion`() = runTest {
        val current = recovery()
        current.checkpoint(call, operationId)
        current.released(call, true)
        files.deleteVault(deriveAccountVaultId(client.sessionId.value, client.deviceId.value))
        recovery().recoverOnce { true }
        assertThat(operations.clientReceipts).isEmpty()
        assertThat(sent).isEqualTo(0)
    }

    @Test fun `repeated unknown invitation recovery never opens a timeline and preserves the original checkpoint`() = runTest {
        val store = EncryptedCallFinalizationStore(files, roots)
        val first = recovery(store = store)
        val outgoing = call.copy(notificationEventId = null)
        first.checkpoint(outgoing, operationId)
        first.released(outgoing, true)
        repeat(4) {
            clock.epochMillisResult += 31_000
            first.recoverOnce { error("Already released") }
            assertThat(client.getJoinedRoomCalledCount).isEqualTo(0)
            val pending = store.pending().single()
            assertThat(pending.operationId).isEqualTo("$operationId-hangup")
            assertThat(store.read(pending).recordSent).isFalse()
        }
        assertThat(operations.clientReceipts).isEmpty()
    }

    @Test fun `group finalization does not load history when no direct call event is required`() = runTest {
        room.givenRoomInfo(aRoomInfo(isDm = false))
        val store = EncryptedCallFinalizationStore(files, roots)
        val current = recovery(store = store)
        current.checkpoint(call, operationId)
        current.released(call, true)
        current.recoverOnce { error("Already released") }
        assertThat(client.getJoinedRoomCalledCount).isEqualTo(0)
        assertThat(sent).isEqualTo(0)
        assertThat(store.read(store.pending().single()).recordSent).isTrue()
        assertThat(operations.clientReceipts).hasSize(2)
    }

    @Test fun `unavailable current room metadata preserves checkpoint without loading history or acknowledging`() = runTest {
        client.getCurrentRoomInfoLambda = { Result.failure(IOException("Metadata unavailable")) }
        val store = EncryptedCallFinalizationStore(files, roots)
        val current = recovery(store = store)
        current.checkpoint(call, operationId)
        current.released(call, true)
        current.recoverOnce { error("Already released") }
        assertThat(client.getJoinedRoomCalledCount).isEqualTo(0)
        assertThat(store.read(store.pending().single()).recordSent).isFalse()
        assertThat(operations.clientReceipts).isEmpty()
    }

    @Test fun `unknown write outcome reuses one room across eligible retries without acknowledging the call`() = runTest {
        val store = EncryptedCallFinalizationStore(files, roots)
        val current = recovery(store = store)
        timeline.sendCallLifecycleLambda = { _, _, _ -> sent++; Result.failure(IOException("Transport unavailable")) }
        current.checkpoint(call, operationId)
        current.released(call, true)
        repeat(4) {
            clock.epochMillisResult += 31_000
            current.recoverOnce { error("Already released") }
        }
        assertThat(sent).isEqualTo(4)
        assertThat(client.getJoinedRoomCalledCount).isEqualTo(1)
        assertThat(roomCloses).isEqualTo(0)
        assertThat(store.read(store.pending().single()).recordSent).isFalse()
        assertThat(operations.clientReceipts).isEmpty()
    }

    @Test fun `queued lifecycle send retains its room until the original authority operation completes`() = runTest {
        var terminal = false
        val coordinator = object : AppOperationalStateCoordinator by operations {
            override suspend fun operation(operationId: String): LifecycleOperation = this@CallFinalizationRecoveryTest.operations.operation(operationId).let {
                if (terminal) it.copy(status = LifecycleOperationStatus.SUCCEEDED, terminalReceiptId = "original-terminal") else it
            }
        }
        val store = EncryptedCallFinalizationStore(files, roots)
        val current = recovery(coordinator, store)
        timeline.sendCallLifecycleLambda = { _, _, _ -> sent++; Result.success(Unit) }
        current.checkpoint(call, operationId)
        current.released(call, true)
        current.recoverOnce { error("Already released") }
        assertThat(roomCloses).isEqualTo(0)
        clock.epochMillisResult += 31_000
        current.recoverOnce { error("Already released") }
        assertThat(sent).isEqualTo(1)
        assertThat(client.getJoinedRoomCalledCount).isEqualTo(1)
        assertThat(roomCloses).isEqualTo(0)
        terminal = true
        clock.epochMillisResult += 31_000
        current.recoverOnce { error("Already released") }
        assertThat(roomCloses).isEqualTo(1)
        assertThat(store.pending()).isEmpty()
    }

    @Test fun `retiring the durable checkpoint releases its retained room without fabricating completion`() = runTest {
        val store = EncryptedCallFinalizationStore(files, roots)
        val current = recovery(store = store)
        timeline.sendCallLifecycleLambda = { _, _, _ -> Result.failure(IOException("Outcome unknown")) }
        current.checkpoint(call, operationId)
        current.released(call, true)
        current.recoverOnce { error("Already released") }
        assertThat(roomCloses).isEqualTo(0)
        store.remove(store.pending().single().key)
        current.recoverOnce { error("No pending checkpoint") }
        assertThat(roomCloses).isEqualTo(1)
        assertThat(operations.clientReceipts).isEmpty()
    }

    @Test fun `recovery room capacity preserves queued owners and leaves excess work pending`() = runTest {
        val store = EncryptedCallFinalizationStore(files, roots)
        val current = recovery(store = store)
        timeline.sendCallLifecycleLambda = { _, _, _ -> sent++; Result.success(Unit) }
        repeat(17) { index ->
            val attempt = call.copy(attemptId = "bounded-attempt-$index")
            current.checkpoint(attempt, "$operationId-$index")
            current.released(attempt, true)
        }
        current.recoverOnce { error("Already released") }
        assertThat(client.getJoinedRoomCalledCount).isEqualTo(16)
        assertThat(sent).isEqualTo(16)
        assertThat(roomCloses).isEqualTo(0)
        assertThat(store.pending()).hasSize(17)
        val records = store.pending().map { store.read(it) }
        assertThat(records.count { it.recordSent }).isEqualTo(16)
        assertThat(records.count { !it.recordSent }).isEqualTo(1)
    }

    @Test fun `cancelled lifecycle write releases its room and propagates cancellation`() = runTest {
        val store = EncryptedCallFinalizationStore(files, roots)
        val current = recovery(store = store)
        val cancelled = kotlinx.coroutines.CancellationException("Worker stopped")
        timeline.sendCallLifecycleLambda = { _, _, _ -> throw cancelled }
        current.checkpoint(call, operationId)
        current.released(call, true)
        val failure = runCatching { current.recoverOnce { error("Already released") } }.exceptionOrNull()
        assertThat(failure).isSameInstanceAs(cancelled)
        room.baseRoom.assertDestroyed()
        assertThat(store.read(store.pending().single()).recordSent).isFalse()
        assertThat(operations.clientReceipts).isEmpty()
    }

    @Test fun `early cancellation needs proof that there was no pending invitation`() = runTest {
        val outgoing = call.copy(notificationEventId = null, attemptId = "outgoing-early")
        val current = recovery()
        current.checkpoint(outgoing, operationId)
        current.released(outgoing, true, noInvitationConfirmed = true)
        current.recoverOnce { false }
        assertThat(sent).isEqualTo(0)
        assertThat(operations.clientReceipts).hasSize(2)
        assertThat(client.getJoinedRoomCalledCount).isEqualTo(0)
    }

    @Test fun `unknown invitation cannot bind to a later call or fabricate completion`() = runTest {
        val outgoing = call.copy(notificationEventId = null, attemptId = "outgoing-unknown")
        val current = recovery()
        current.checkpoint(outgoing, operationId)
        current.released(outgoing, true)
        current.recoverOnce { false }
        assertThat(operations.clientReceipts).isEmpty()
        assertThat(sent).isEqualTo(0)
        assertThat(client.getJoinedRoomCalledCount).isEqualTo(0)
    }

    @Test fun `authoritative pre-admission cancellation retires the completed recovery journal`() = runTest {
        val coordinator = object : AppOperationalStateCoordinator by operations {
            override suspend fun resume(operationId: String): LifecycleOperation = this@CallFinalizationRecoveryTest.operations.operation(operationId).copy(
                status = LifecycleOperationStatus.CANCELLED,
                terminalReceiptId = "test-terminal-receipt",
                authorityRevisions = mapOf("RTC_PRE_ADMISSION_CANCEL" to "test-authority-evidence"),
            )
        }
        val store = EncryptedCallFinalizationStore(files, roots)
        val current = recovery(coordinator, store)
        val outgoing = call.copy(notificationEventId = null)
        current.checkpoint(outgoing, operationId)
        current.released(outgoing, true, noInvitationConfirmed = true)
        current.recoverOnce { false }
        assertThat(operations.clientReceipts).hasSize(2)
        assertThat(store.pending()).isEmpty()
    }
}
