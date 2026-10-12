/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.call.impl.security

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.appconfig.NextDeploymentConfig
import io.element.android.features.call.api.CallData
import io.element.android.libraries.di.annotations.AppCoroutineScope
import io.element.android.libraries.matrix.api.MatrixClientProvider
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.api.timeline.item.event.CallLifecycleState
import io.element.android.security.dataprotection.api.*
import io.element.android.security.securestorage.api.deriveAccountVaultId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.UUID

/** Replays only call finalization; it never recreates a driver, admits RTC media, or unlocks a Vault. */
interface CallFinalizationRecovery {
    fun start(cleanAbandonedLocalCall: suspend () -> Boolean)
    suspend fun checkpoint(call: CallData, joinOperationId: String)
    suspend fun update(call: CallData, invitationId: String? = null, connected: Boolean = false)
    suspend fun released(call: CallData, cleanupSucceeded: Boolean, noInvitationConfirmed: Boolean = false): Boolean
}

@SingleIn(AppScope::class)
@dev.zacsweers.metro.ContributesBinding(AppScope::class)
class DefaultCallFinalizationRecovery(
    private val store: CallFinalizationStore,
    private val clients: MatrixClientProvider,
    private val protection: CallProtectionService,
    private val operations: AppOperationalStateCoordinator,
    @AppCoroutineScope private val scope: CoroutineScope,
) : CallFinalizationRecovery {
    private val owner = UUID.randomUUID().toString()
    private val mutex = Mutex()
    private val active = mutableMapOf<CallData, PendingCallFinalization>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var worker: Job? = null
    private val retryAt = mutableMapOf<String, Long>()
    private val started = mutableSetOf<String>()
    private data class CleanupResult(val phase: CallCleanupPhase, val noInvitation: Boolean, val at: Long)
    private val cleanupResults = mutableMapOf<String, CleanupResult>()

    override fun start(cleanAbandonedLocalCall: suspend () -> Boolean) {
        if (!NextDeploymentConfig.isNextDeployment || worker != null) return
        worker = scope.launch(Dispatchers.IO) {
            while (true) {
                try {
                    recoverOnce(cleanAbandonedLocalCall)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { Timber.w(failure, "RTC recovery scan remains pending") }
                withTimeoutOrNull(5_000) { wake.receive() }
            }
        }
    }

    override suspend fun checkpoint(call: CallData, joinOperationId: String) = mutex.withLock {
        store.pending().singleOrNull { it.operationId == "$joinOperationId-hangup" }?.let { existing ->
            check(existing.owner == owner && existing.phase == CallCleanupPhase.ACTIVE) { "A retired call checkpoint cannot be reused." }
            val old = store.read(existing)
            check(old.sessionId == call.sessionId.value && old.roomId == call.roomId.value && old.attemptId == call.attemptId)
            active[call] = existing
            return@withLock
        }
        val client = clients.getOrRestore(call.sessionId).getOrThrow()
        val vault = deriveAccountVaultId(call.sessionId.value, client.deviceId.value)
        val record = CallFinalizationRecord(
            operationId = "$joinOperationId-hangup", sessionId = call.sessionId.value, deviceId = client.deviceId.value,
            roomId = call.roomId.value, audioOnly = call.isAudioCall, attemptId = call.attemptId,
            invitationId = call.notificationEventId, incoming = call.notificationEventId != null, createdAt = System.currentTimeMillis(),
        )
        // Once a durable checkpoint commits, cancellation must not lose the in-process handle
        // that its already-running teardown uses to publish the RELEASED marker.
        withContext(NonCancellable) { active[call] = store.put(vault, owner, record) }
    }

    override suspend fun update(call: CallData, invitationId: String?, connected: Boolean) = mutex.withLock {
        val pending = active[call] ?: return@withLock
        if (!protection.observeDeviceAccess(CallProtectionSession(pending.vaultId, EMBEDDED_CALL_WIDGET_ORIGIN, true)).first()) {
            return@withLock
        }
        val old = store.read(pending)
        val outgoingInvitation = invitationId.takeUnless { old.incoming }
        if (outgoingInvitation != null && old.invitationId != null) check(outgoingInvitation == old.invitationId)
        if ((outgoingInvitation == null || outgoingInvitation == old.invitationId) && (!connected || old.connected)) return@withLock
        store.put(pending.vaultId, owner, old.copy(invitationId = outgoingInvitation ?: old.invitationId, connected = connected || old.connected,
            connectedAt = old.connectedAt ?: System.currentTimeMillis().takeIf { connected }))
        Unit
    }

    /** Call only after both driver shutdown and native notification/wake-lock release have returned. */
    override suspend fun released(call: CallData, cleanupSucceeded: Boolean, noInvitationConfirmed: Boolean): Boolean = mutex.withLock {
        val pending = active[call] ?: return@withLock false
        val result = CleanupResult(if (cleanupSucceeded) CallCleanupPhase.RELEASED else CallCleanupPhase.CLEANUP_FAILED,
            cleanupSucceeded && noInvitationConfirmed, System.currentTimeMillis())
        cleanupResults[pending.key] = result
        active.remove(call)
        try {
            store.mark(pending.key, result.phase, result.noInvitation, result.at)
            cleanupResults.remove(pending.key)
        } finally { wake.trySend(Unit) }
        true
    }

    internal suspend fun recoverOnce(cleanAbandonedLocalCall: suspend () -> Boolean) {
        for (initial in store.pending()) {
            if (System.currentTimeMillis() < (retryAt[initial.key] ?: 0)) continue
            try {
                var pending = initial
                mutex.withLock {
                    cleanupResults[initial.key]?.let { result ->
                        store.mark(initial.key, result.phase, result.noInvitation, result.at)
                        cleanupResults.remove(initial.key)
                        pending = initial.copy(phase = result.phase, noInvitationConfirmed = result.noInvitation, releasedAt = result.at)
                    }
                }
                if (pending.phase != CallCleanupPhase.RELEASED && pending.owner == owner) continue
                if (pending.phase != CallCleanupPhase.RELEASED) {
                    // A different process owns the checkpoint: its WebView/media no longer exist.
                    // Clear the remaining native projection before attesting that actual cleanup.
                    if (!cleanAbandonedLocalCall()) continue
                    store.mark(pending.key, CallCleanupPhase.RELEASED)
                    pending = pending.copy(phase = CallCleanupPhase.RELEASED)
                }
                val request = lifecycleOperationRequest(
                    FullAppLifecycleId.LC_20, "hangup", operationId = pending.operationId,
                    accountIdHash = pending.accountHash, matrixDeviceIdHash = pending.deviceHash, roomIdHash = pending.roomHash,
                )
                // This carries only opaque lifecycle subjects, so the authority can start departure
                // while the encrypted call record remains inaccessible in the locked Vault.
                if (pending.key !in started) {
                    operations.start(request)
                    started += pending.key
                }
                val access = CallProtectionSession(pending.vaultId, EMBEDDED_CALL_WIDGET_ORIGIN, blockScreenCapture = true)
                if (!protection.observeDeviceAccess(access).first()) continue
                val record = store.read(pending)
                val existing = operations.operation(record.operationId)
                if (existing.status.isTerminal) {
                    check(record.recordSent && isCompleted(existing)) { "Call finalization has a conflicting terminal authority result." }
                    store.remove(pending.key)
                    retryAt.remove(pending.key)
                    started.remove(pending.key)
                    continue
                }
                operations.perform(request) {
                    check(protection.observeDeviceAccess(access).first()) { "Call finalization access was revoked." }
                    if (!record.recordSent) {
                        val client = clients.getOrRestore(SessionId(record.sessionId)).getOrThrow()
                        check(client.deviceId.value == record.deviceId)
                        val room = checkNotNull(client.getJoinedRoom(RoomId(record.roomId)))
                        val noInvitation = pending.noInvitationConfirmed && record.invitationId == null && !record.incoming && !record.connected
                        if (room.roomInfoFlow.value.isDm && !noInvitation) {
                            // No room-wide fallback: it could bind this old hangup to a later call.
                            val invitation = checkNotNull(record.invitationId) { "Exact call invitation binding is unavailable." }
                            val state = when {
                                record.connected -> CallLifecycleState.ENDED
                                record.incoming -> CallLifecycleState.DECLINED
                                else -> CallLifecycleState.CANCELLED
                            }
                            // CALL_STATE carries this existing operation ID. A crash after send but before
                            // checkpoint may replay it; the authenticated reducer deduplicates that ID.
                            val duration = record.connectedAt?.let { began -> pending.releasedAt?.let { (it - began).coerceAtLeast(0) } }
                            room.liveTimeline.sendCallLifecycle(EventId(invitation), state, duration).getOrThrow()
                        }
                        store.put(pending.vaultId, owner, record.copy(recordSent = true))
                    }
                }
                val result = operations.resume(record.operationId)
                if (isCompleted(result)) {
                    store.remove(pending.key)
                    retryAt.remove(pending.key)
                    started.remove(pending.key)
                    Timber.i("RTC durable finalization completed with authoritative LC-20 terminal receipt")
                } else retryAt[pending.key] = System.currentTimeMillis() + 30_000L
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                retryAt[initial.key] = System.currentTimeMillis() + 30_000L
                Timber.w(failure, "RTC durable finalization remains pending")
            }
        }
    }

    private fun isCompleted(operation: LifecycleOperation): Boolean = operation.status == LifecycleOperationStatus.SUCCEEDED ||
        operation.status == LifecycleOperationStatus.CANCELLED && "RTC_PRE_ADMISSION_CANCEL" in operation.authorityRevisions

}
