/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.room

import io.element.android.security.dataprotection.api.MembershipStage
import io.element.android.security.dataprotection.api.membershipStage
import io.element.android.appconfig.NextDeploymentConfig
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleComponent
import io.element.android.security.dataprotection.api.LifecycleOperationContext
import io.element.android.security.dataprotection.api.LifecycleOperation
import io.element.android.security.dataprotection.api.LifecycleOperationRequest
import io.element.android.security.dataprotection.api.LifecycleOperationStatus
import io.element.android.security.dataprotection.api.LifecycleStepStatus
import io.element.android.security.dataprotection.api.ManagementControlService
import io.element.android.security.dataprotection.api.perform
import io.element.android.security.securestorage.api.accountIdHash
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The server persists the intent and binds the exact pre-commit Matrix delta. */
class RoomMembershipLifecycle(
    private val actor: String,
    private val matrixDevice: String,
    private val management: ManagementControlService,
    private val coordinator: AppOperationalStateCoordinator,
    private val isProjected: suspend (room: String, target: String, action: String) -> Boolean,
    private val enabled: Boolean = NextDeploymentConfig.isNextDeployment,
) {
    private val completionMutex = Mutex()

    /** Startup refresh restores server-owned operations; recovery never submits another mutation. */
    fun startRecovery(scope: CoroutineScope) {
        if (!enabled) return
        scope.launch {
            while (isActive) {
                for (operation in coordinator.operations.value.values) {
                    if (operation.lifecycleId != FullAppLifecycleId.LC_12 || operation.status.isTerminal ||
                        operation.subjects.accountIdHash != accountIdHash(actor) ||
                        operation.subjects.matrixDeviceIdHash != accountIdHash(matrixDevice) ||
                        "membership_intent_v1" !in operation.authorityRevisions) continue
                    try {
                        recover(operation)
                    } catch (_: TimeoutCancellationException) {
                        // A slow/offline sync leaves the original operation pending.
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // The signed operation remains the source of recovery and visible failure.
                    }
                }
                delay(2_000)
            }
        }
    }

    internal suspend fun recover(operation: LifecycleOperation) = completionMutex.withLock {
        val scope = Json.parseToJsonElement(operation.authorityRevisions.getValue("membership_intent_v1"))
            .jsonObject.getValue("scope").jsonObject
        finish(operation, scope.getValue("room").jsonPrimitive.content,
            scope.getValue("target").jsonPrimitive.content, scope.getValue("action").jsonPrimitive.content) {}
    }

    suspend fun perform(roomId: String, target: String, action: String, nativeAction: suspend () -> Unit, reason: String? = null) {
        if (!enabled) {
            nativeAction()
            return
        }
        val intent = checkNotNull(management.prepareRoomMembership(accountIdHash(actor), accountIdHash(matrixDevice), roomId, target, action, reason))
        val operation = membershipStage(MembershipStage.LC12_INTENT) { coordinator.operation(intent.operationId) }
        completionMutex.withLock {
            membershipStage(MembershipStage.LC12_CONVERGENCE) {
                finish(operation, roomId, target, action) {
                    if (intent.dispatch) membershipStage(MembershipStage.NATIVE_LEAVE) { nativeAction() }
                }
            }
        }
    }

    private suspend fun finish(operation: LifecycleOperation, roomId: String, target: String, action: String, dispatch: suspend () -> Unit) {
        check(operation.lifecycleId == FullAppLifecycleId.LC_12 &&
            operation.subjects.accountIdHash == accountIdHash(actor) &&
            operation.subjects.matrixDeviceIdHash == accountIdHash(matrixDevice) &&
            operation.subjects.roomIdHash == accountIdHash(roomId)) { "Membership operation subject mismatch" }
        val scope = Json.parseToJsonElement(operation.authorityRevisions.getValue("membership_intent_v1"))
            .jsonObject.getValue("scope").jsonObject
        check(scope.getValue("actor").jsonPrimitive.content == actor &&
            scope.getValue("matrix_device").jsonPrimitive.content == matrixDevice &&
            scope.getValue("room").jsonPrimitive.content == roomId &&
            scope.getValue("target").jsonPrimitive.content == target &&
            scope.getValue("action").jsonPrimitive.content == action) { "Membership intent mismatch" }
        if (operation.status == LifecycleOperationStatus.SUCCEEDED) return
        check(!operation.status.isTerminal) { "Membership operation is already terminal." }
        val trigger = when (action) {
            "join" -> "member_join"
            "ban", "unban" -> "member_ban"
            else -> "member_leave"
        }
        val request = LifecycleOperationRequest(
            operationId = operation.operationId, lifecycleId = FullAppLifecycleId.LC_12, trigger = trigger,
            idempotencyKey = operation.idempotencyKey, accountIdHash = accountIdHash(actor),
            matrixDeviceIdHash = accountIdHash(matrixDevice), roomIdHash = accountIdHash(roomId),
            deadlineEpochMillis = operation.deadlineEpochMillis,
        )
        withContext(LifecycleOperationContext(operation.operationId, FullAppLifecycleId.LC_12)) {
            coordinator.perform(request) {
                dispatch()
                // A timed-out/lost native response may already have committed. On retry we
                // observe this same intent; never kick a newly rejoined member a second time.
                withTimeout(30_000) {
                    val authorities = setOf(LifecycleComponent.MATRIX, LifecycleComponent.CONTROL_PLANE, LifecycleComponent.DATABASE, LifecycleComponent.KMS)
                    while (true) {
                        val current = coordinator.operation(operation.operationId)
                        val ready = authorities.all { authority ->
                            current.steps.any { it.component == authority && it.status == LifecycleStepStatus.COMPLETE }
                        }
                        if (ready && isProjected(roomId, target, action)) break
                        check(!current.status.isTerminal) { "Membership operation did not succeed" }
                        delay(250)
                    }
                }
            }
        }
    }
}
