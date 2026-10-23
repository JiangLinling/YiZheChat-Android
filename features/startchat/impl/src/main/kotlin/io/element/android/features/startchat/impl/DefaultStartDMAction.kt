/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.startchat.impl

import androidx.compose.runtime.MutableState
import dev.zacsweers.metro.ContributesBinding
import im.vector.app.features.analytics.plan.CreatedRoom
import io.element.android.features.startchat.api.ConfirmingStartDmWithMatrixUser
import io.element.android.features.startchat.api.StartDMAction
import io.element.android.libraries.architecture.AsyncAction
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.room.DirectRoomOperationEvidence
import io.element.android.libraries.matrix.api.room.StartDMResult
import io.element.android.libraries.matrix.api.room.startDM
import io.element.android.libraries.matrix.api.user.MatrixUser
import io.element.android.security.dataprotection.api.LifecycleComponent
import io.element.android.security.dataprotection.api.ManagedDirectRoomResult
import io.element.android.security.dataprotection.api.admitManagedDirectRoomOperations
import io.element.android.security.dataprotection.api.managedDirectRoomApplicationReceipts
import io.element.android.security.securestorage.api.DeviceTrustService
import io.element.android.security.securestorage.api.accountIdHash
import io.element.android.services.analytics.api.AnalyticsService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicInteger

@ContributesBinding(SessionScope::class)
class DefaultStartDMAction(
    private val matrixClient: MatrixClient,
    private val analyticsService: AnalyticsService,
    private val managementControlService: io.element.android.security.dataprotection.api.ManagementControlService,
    private val appOperationalStateCoordinator: io.element.android.security.dataprotection.api.AppOperationalStateCoordinator,
    private val deviceTrustService: DeviceTrustService,
) : StartDMAction {
    // Only admission to this UI action is deduplicated. No room, authorization or operation is cached here.
    private val activeActions = IdentityHashMap<MutableState<AsyncAction<RoomId>>, Any>()

    override suspend fun execute(
        matrixUser: MatrixUser,
        createIfDmDoesNotExist: Boolean,
        actionState: MutableState<AsyncAction<RoomId>>,
    ) {
        val invocation = Any()
        synchronized(activeActions) {
            if (activeActions.containsKey(actionState) || actionState.value is AsyncAction.Loading) return
            activeActions[actionState] = invocation
        }
        try {
            val sample = timingSamples.updateAndGet { (it + 1).coerceAtMost(17) }
            measureStage(sample, "action") { executeOnce(matrixUser, createIfDmDoesNotExist, actionState, sample) }
        } catch (failure: CancellationException) {
            synchronized(activeActions) {
                if (activeActions[actionState] === invocation && actionState.value is AsyncAction.Loading) {
                    actionState.value = AsyncAction.Uninitialized
                }
            }
            throw failure
        } catch (failure: Exception) {
            actionState.value = AsyncAction.Failure(failure)
        } finally {
            synchronized(activeActions) {
                if (activeActions[actionState] === invocation) activeActions.remove(actionState)
            }
        }
    }

    private suspend fun executeOnce(
        matrixUser: MatrixUser,
        createIfDmDoesNotExist: Boolean,
        actionState: MutableState<AsyncAction<RoomId>>,
        sample: Int,
    ) {
        actionState.value = AsyncAction.Loading
        when (val result = matrixClient.startDM(matrixUser.userId, createIfDmDoesNotExist)) {
            is StartDMResult.Success -> {
                if (result.isNew) {
                    analyticsService.capture(CreatedRoom(isDM = true))
                }
                // Complete all signed reads before publishing the UI transition. Navigation can
                // cancel this action immediately after Success, so persist the actual fact atomically.
                val operations = if (managementControlService.supportsManagedRoomCreation) {
                    val evidence = checkNotNull(result.managedEvidence) { "Managed direct room evidence is missing" }
                    measureStage(sample, "frontend_evidence") { requireCurrentEvidence(evidence, result.roomId, matrixUser.userId) }
                    val applied = ManagedDirectRoomResult(
                        evidence.roomId.value,
                        evidence.status,
                        evidence.operationIds,
                        evidence.installationId,
                        evidence.issuedAtEpochMillis,
                        evidence.expiresAtEpochMillis
                    )
                    measureStage(sample, "frontend_admission") {
                        appOperationalStateCoordinator.admitManagedDirectRoomOperations(
                            applied,
                            accountIdHash(matrixClient.sessionId.value),
                            accountIdHash(matrixClient.deviceId.value)
                        )
                    }.also {
                        // Admission may suspend. Never revive a response that expired while waiting for local sync or signed operations.
                        measureStage(sample, "frontend_evidence_recheck") { requireCurrentEvidence(evidence, result.roomId, matrixUser.userId) }
                    }
                } else {
                    emptyList()
                }
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    actionState.value = AsyncAction.Success(result.roomId)
                    val receipts = managedDirectRoomApplicationReceipts(operations, LifecycleComponent.ANDROID_FRONTEND)
                    if (receipts.isNotEmpty()) measureStage(sample, "frontend_receipts") { appOperationalStateCoordinator.enqueueClientReceipts(receipts) }
                }
            }
            is StartDMResult.Failure -> {
                if (result.throwable is CancellationException) throw result.throwable
                actionState.value = AsyncAction.Failure(result.throwable)
            }
            StartDMResult.DmDoesNotExist -> {
                val identityState = matrixClient.encryptionService.getUserIdentity(matrixUser.userId, fallbackToServer = false).getOrNull()
                actionState.value = ConfirmingStartDmWithMatrixUser(
                    matrixUser = matrixUser,
                    isUserIdentityUnknown = identityState == null
                )
            }
        }
    }

    private suspend fun requireCurrentEvidence(evidence: DirectRoomOperationEvidence, roomId: RoomId, peer: UserId) {
        check(
            evidence.roomId == roomId && evidence.peerUserId == peer &&
            evidence.accountIdHash == accountIdHash(matrixClient.sessionId.value) &&
            evidence.matrixDeviceIdHash == accountIdHash(matrixClient.deviceId.value)
        ) { "Managed direct room scope changed" }
        check(evidence.status in setOf("INVITED", "ACCEPTED")) { "Managed direct room relationship is unavailable" }
        check(evidence.operationIds.size <= 2 && evidence.operationIds.distinct().size == evidence.operationIds.size)
        val trust = deviceTrustService.current()
        check(evidence.installationId.isNotBlank() && evidence.installationId == trust.installationId) {
            "Managed direct room installation changed"
        }
        val now = System.currentTimeMillis()
        check(
            evidence.issuedAtEpochMillis > 0 && evidence.issuedAtEpochMillis <= now + 5000 &&
            evidence.expiresAtEpochMillis > now && evidence.expiresAtEpochMillis > evidence.issuedAtEpochMillis &&
            evidence.expiresAtEpochMillis - evidence.issuedAtEpochMillis <= 15000
        ) { "LC11_DIRECT_RESPONSE_EXPIRED_RETRY" }
    }

    // Bounded diagnostics on the existing timing tag; completed means the stage returned, not authority success.
    private suspend fun <T> measureStage(sample: Int, stage: String, block: suspend () -> T): T {
        if (sample > 16) return block()
        val startedAt = System.nanoTime()
        var outcome = "failed"
        return try {
            block().also { outcome = "completed" }
        } catch (cancelled: CancellationException) {
            outcome = "cancelled"
            throw cancelled
        } finally {
            Timber.tag("YiZheTimelinePerf").d(
                "stage=direct_%s sample=%d outcome=%s duration_ms=%d",
                stage,
                sample,
                outcome,
                (System.nanoTime() - startedAt) / 1_000_000L,
            )
        }
    }

    private companion object {
        val timingSamples = AtomicInteger()
    }
}
