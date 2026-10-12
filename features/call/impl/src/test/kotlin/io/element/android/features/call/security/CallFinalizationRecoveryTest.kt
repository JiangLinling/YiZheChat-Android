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
    private val client = FakeMatrixClient().apply { givenGetRoomResult(A_ROOM_ID, room) }
    private val clients = FakeMatrixClientProvider { Result.success(client) }
    private val protection = FakeCallProtectionService()
    private val operations = FakeAppOperationalStateCoordinator()
    private val call get() = CallData(client.sessionId, A_ROOM_ID, true, "\$exact-invitation", "attempt-1")
    private val operationId = "join-operation-123456789"
    private var sent = 0

    private fun TestScope.recovery(
        coordinator: AppOperationalStateCoordinator = operations,
        store: CallFinalizationStore = EncryptedCallFinalizationStore(files, roots),
        authorization: CallProtectionService = protection,
    ) = DefaultCallFinalizationRecovery(store, clients, authorization, coordinator, backgroundScope)

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

    @Test fun `early cancellation needs proof that there was no pending invitation`() = runTest {
        val outgoing = call.copy(notificationEventId = null, attemptId = "outgoing-early")
        val current = recovery()
        current.checkpoint(outgoing, operationId)
        current.released(outgoing, true, noInvitationConfirmed = true)
        current.recoverOnce { false }
        assertThat(sent).isEqualTo(0)
        assertThat(operations.clientReceipts).hasSize(2)
    }

    @Test fun `unknown invitation cannot bind to a later call or fabricate completion`() = runTest {
        val outgoing = call.copy(notificationEventId = null, attemptId = "outgoing-unknown")
        val current = recovery()
        current.checkpoint(outgoing, operationId)
        current.released(outgoing, true)
        current.recoverOnce { false }
        assertThat(operations.clientReceipts).isEmpty()
        assertThat(sent).isEqualTo(0)
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
