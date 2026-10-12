/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.dataprotection.test

import io.element.android.security.dataprotection.api.AppLifecycleUiProjector
import io.element.android.security.dataprotection.api.AppLifecycleUiState
import io.element.android.security.dataprotection.api.AppOperationalCoordinatorState
import io.element.android.security.dataprotection.api.AppOperationalSnapshot
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.AppOperationalStatus
import io.element.android.security.dataprotection.api.ComponentOperationalState
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleClientReceipt
import io.element.android.security.dataprotection.api.LifecycleComponent
import io.element.android.security.dataprotection.api.LifecycleOperation
import io.element.android.security.dataprotection.api.LifecycleOperationRequest
import io.element.android.security.dataprotection.api.LifecycleOperationContext
import io.element.android.security.dataprotection.api.LifecycleOperationStatus
import io.element.android.security.dataprotection.api.LifecyclePhase
import io.element.android.security.dataprotection.api.LifecycleSubjectHashes
import io.element.android.security.dataprotection.api.REQUIRED_OPERATIONAL_COMPONENTS
import io.element.android.security.dataprotection.api.perform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

class FakeAppOperationalStateCoordinator(
    private val startFailure: ((LifecycleOperationRequest) -> Throwable?)? = null,
    private val refreshFailure: (() -> Throwable?)? = null,
) : AppOperationalStateCoordinator {
    private val mutableOperations = MutableStateFlow<Map<String, LifecycleOperation>>(emptyMap())
    private val mutableState = MutableStateFlow<AppOperationalCoordinatorState>(AppOperationalCoordinatorState.Discovering)
    private val mutableUiStates = MutableStateFlow(AppLifecycleUiProjector.projectAll(null, emptyMap()))

    override val state: StateFlow<AppOperationalCoordinatorState> = mutableState.asStateFlow()
    override val operations: StateFlow<Map<String, LifecycleOperation>> = mutableOperations.asStateFlow()
    override val uiStates: StateFlow<Map<FullAppLifecycleId, AppLifecycleUiState>> = mutableUiStates.asStateFlow()

    val startedRequests = mutableListOf<LifecycleOperationRequest>()
    val resumingRequests = mutableListOf<LifecycleOperationRequest>()
    val clientReceipts = mutableListOf<LifecycleClientReceipt>()
    val pendingLocalDraftReceipts = mutableListOf<LifecycleClientReceipt>()
    val refreshRequests = mutableListOf<Pair<String, String>>()

    override suspend fun refresh(accountIdHash: String, matrixDeviceIdHash: String): AppOperationalSnapshot {
        refreshRequests += accountIdHash to matrixDeviceIdHash
        refreshFailure?.invoke()?.let { throw it }
        val now = System.currentTimeMillis()
        val components = REQUIRED_OPERATIONAL_COMPONENTS.associateWith { component ->
            ComponentOperationalState(
                status = AppOperationalStatus.READY,
                authority = "fake-$component",
                authorityRevision = "fake-revision",
                observedAtEpochMillis = now,
                expiresAtEpochMillis = now + 60_000,
                receiptReference = "fake-receipt:$component",
            )
        }
        return AppOperationalSnapshot(
            accountIdHash = accountIdHash,
            installationId = "fake-installation-id",
            matrixDeviceIdHash = matrixDeviceIdHash,
            issuedAtEpochMillis = now,
            expiresAtEpochMillis = now + 60_000,
            overallStatus = AppOperationalStatus.READY,
            components = components,
            signingKeyId = "fake-signing-key",
            signatureBase64 = "fake-signature-value",
        ).also { snapshot ->
            mutableState.value = AppOperationalCoordinatorState.Available(snapshot)
            mutableUiStates.value = AppLifecycleUiProjector.projectAll(snapshot, mutableOperations.value)
        }
    }

    override suspend fun start(request: LifecycleOperationRequest): LifecycleOperation {
        startedRequests += request
        startFailure?.invoke(request)?.let { throw it }
        return mutableOperations.value[request.operationId] ?: LifecycleOperation(
            operationId = request.operationId,
            lifecycleId = request.lifecycleId,
            idempotencyKey = request.idempotencyKey,
            subjects = LifecycleSubjectHashes(
                accountIdHash = request.accountIdHash,
                matrixDeviceIdHash = request.matrixDeviceIdHash,
                roomIdHash = request.roomIdHash,
                eventIdHash = request.eventIdHash,
            ),
            status = LifecycleOperationStatus.RUNNING,
            currentPhase = LifecyclePhase.INTENT_PERSISTED,
            deadlineEpochMillis = request.deadlineEpochMillis,
            createdAtEpochMillis = System.currentTimeMillis(),
            updatedAtEpochMillis = System.currentTimeMillis(),
        ).also(::store)
    }

    override suspend fun operation(operationId: String): LifecycleOperation =
        mutableOperations.value.getValue(operationId)

    override suspend fun performLocalDraft(request: LifecycleOperationRequest, block: suspend (operationId: String) -> Unit) {
        try {
            // Online fake retains the ordinary synchronous test semantics.
            perform(request, block)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (request.operationId in mutableOperations.value) throw failure
            // Fake-only offline path: collect actual local facts, never a server acknowledgement.
            withContext(LifecycleOperationContext(request.operationId, request.lifecycleId)) { block(request.operationId) }
            val occurredAt = System.currentTimeMillis()
            pendingLocalDraftReceipts += listOf(LifecycleComponent.ANDROID_SERVICE, LifecycleComponent.ANDROID_FRONTEND).map { component ->
                LifecycleClientReceipt(request.operationId, component, LifecyclePhase.CLIENT_APPLIED, "${component.name}_APPLIED",
                    "${component.name.lowercase().replace('_', '-')}:${request.operationId}", occurredAt)
            }
        }
    }

    override suspend fun resume(operationId: String): LifecycleOperation = operation(operationId)

    override suspend fun recordClientReceipt(receipt: LifecycleClientReceipt): LifecycleOperation {
        clientReceipts += receipt
        return operation(receipt.operationId).copy(
            currentPhase = receipt.phase,
            updatedAtEpochMillis = receipt.occurredAtEpochMillis,
        ).also(::store)
    }

    override suspend fun <T> performResuming(
        request: LifecycleOperationRequest,
        hasNewStartupIntent: Boolean,
        block: suspend (operationId: String) -> T,
    ): T {
        resumingRequests += request
        return this.perform(request, block)
    }

    private fun store(operation: LifecycleOperation) {
        mutableOperations.value = mutableOperations.value + (operation.operationId to operation)
        val snapshot = (mutableState.value as? AppOperationalCoordinatorState.Available)?.snapshot
        mutableUiStates.value = AppLifecycleUiProjector.projectAll(snapshot, mutableOperations.value)
    }
}
