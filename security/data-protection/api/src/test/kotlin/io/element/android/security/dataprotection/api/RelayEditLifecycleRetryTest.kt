/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.dataprotection.api

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RelayEditLifecycleRetryTest {
    private val request get() = lifecycleOperationRequest(
        FullAppLifecycleId.LC_15, "relay_operation", "a".repeat(64), "b".repeat(64),
        "c".repeat(64), "d".repeat(64), "relay-edit-original-0001",
    )
    private val android = setOf(LifecycleComponent.ANDROID_SERVICE, LifecycleComponent.ANDROID_FRONTEND)
    private val components = android + setOf(LifecycleComponent.MATRIX, LifecycleComponent.KMS,
        LifecycleComponent.DATABASE, LifecycleComponent.CONTROL_PLANE)

    private fun operation(request: LifecycleOperationRequest, status: LifecycleOperationStatus = LifecycleOperationStatus.RUNNING,
        externalComplete: Boolean = false,
    ): LifecycleOperation {
        val now = System.currentTimeMillis()
        return LifecycleOperation(operationId = request.operationId, lifecycleId = request.lifecycleId,
            idempotencyKey = request.idempotencyKey,
            subjects = LifecycleSubjectHashes(request.accountIdHash, "installation-original", request.matrixDeviceIdHash,
                request.roomIdHash, request.eventIdHash),
            status = status, currentPhase = LifecyclePhase.INTENT_PERSISTED,
            steps = components.map { component ->
                val complete = status == LifecycleOperationStatus.SUCCEEDED || externalComplete && component !in android
                LifecycleStep("original-step-${component.name}", component,
                    status = if (complete) LifecycleStepStatus.COMPLETE else LifecycleStepStatus.PENDING, attemptCount = 1,
                    authorityRevision = if (complete) "authority-${component.name}-1" else null,
                    receiptReference = if (complete) "original-receipt-${component.name}" else null, updatedAtEpochMillis = now)
            }, deadlineEpochMillis = request.deadlineEpochMillis,
            terminalReceiptId = "original-terminal-receipt".takeIf { status.isTerminal },
            createdAtEpochMillis = now, updatedAtEpochMillis = now)
    }

    @Test fun `fresh edit persists Android facts for original operation after one action`() = runTest {
        val coordinator = Coordinator { operation(it) }
        var sent = 0
        coordinator.performIdempotentRelayEdit(request, { error("No accepted carrier yet") }) { id ->
            assertThat(currentLifecycleOperationContext()?.operationId).isEqualTo(id)
            sent++
        }
        assertThat(sent).isEqualTo(1)
        assertThat(coordinator.persisted.map { it.operationId }.distinct()).containsExactly(request.operationId)
        assertThat(coordinator.receipts.map { it.component }.toSet()).isEqualTo(android)
        assertThat(coordinator.receipts.map { it.occurredAtEpochMillis }.distinct()).hasSize(1)
    }

    @Test fun `signed original terminal success verifies intent without new action or receipts`() = runTest {
        val coordinator = Coordinator { operation(it, LifecycleOperationStatus.SUCCEEDED) }
        var verified = 0
        repeat(2) {
            coordinator.performIdempotentRelayEdit(request, {
                assertThat(currentLifecycleOperationContext()?.operationId).isEqualTo(request.operationId)
                verified++
            }) { error("Accepted edit must not be sent again") }
        }
        assertThat(verified).isEqualTo(2)
        assertThat(coordinator.persisted).isEmpty()
        assertThat(coordinator.receipts).isEmpty()
    }

    @Test fun `accepted carrier after process loss observes intent and receipts without resending`() = runTest {
        val coordinator = Coordinator { operation(it, externalComplete = true) }
        var observed = 0
        coordinator.performIdempotentRelayEdit(request, { observed++ }) { error("Original carrier is already accepted") }
        assertThat(observed).isEqualTo(1)
        assertThat(coordinator.persisted).hasSize(2)
        assertThat(coordinator.receipts.map { it.operationId }.distinct()).containsExactly(request.operationId)
    }

    @Test fun `cached success cannot skip work when fresh admission has no accepted carrier`() = runTest {
        val coordinator = Coordinator { operation(it) }
        coordinator.operations.value = mapOf(request.operationId to operation(request, LifecycleOperationStatus.SUCCEEDED))
        var sent = 0
        coordinator.performIdempotentRelayEdit(request, { error("Cached success is not authority") }) { sent++ }
        assertThat(sent).isEqualTo(1)
    }

    @Test fun `wrong immutable intent cannot receipt or resend an accepted carrier`() = runTest {
        listOf(false, true).forEach { terminal ->
            val coordinator = Coordinator {
                operation(it, if (terminal) LifecycleOperationStatus.SUCCEEDED else LifecycleOperationStatus.RUNNING, true)
            }
            var sent = 0
            reject { coordinator.performIdempotentRelayEdit(request, { error("Original encrypted intent differs") }) { sent++ } }
            assertThat(sent).isEqualTo(0)
            assertThat(coordinator.persisted).isEmpty()
            assertThat(coordinator.receipts).isEmpty()
        }
    }

    @Test fun `other scope missing participants or unsigned step metadata cannot bypass action gate`() = runTest {
        val original = operation(request, LifecycleOperationStatus.SUCCEEDED)
        listOf(original.copy(operationId = "another-operation-0001"),
            original.copy(subjects = original.subjects.copy(matrixDeviceIdHash = "e".repeat(64))),
            original.copy(subjects = original.subjects.copy(eventIdHash = "e".repeat(64))),
            original.copy(idempotencyKey = "another-idempotency-key"), original.copy(steps = emptyList()),
            original.copy(steps = original.steps.map { it.copy(authorityRevision = null) }),
            original.copy(steps = original.steps + original.steps.first().copy(stepId = "duplicate-component-step")),
        ).forEach { response ->
            val coordinator = Coordinator { response }
            var callbacks = 0
            reject { coordinator.performIdempotentRelayEdit(request, { callbacks++ }) { callbacks++ } }
            assertThat(callbacks).isEqualTo(0)
            assertThat(coordinator.receipts).isEmpty()
        }
    }

    @Test fun `unsuccessful terminal operation never starts another edit`() = runTest {
        listOf(LifecycleOperationStatus.FAILED_FINAL, LifecycleOperationStatus.CANCELLED, LifecycleOperationStatus.TERMINATED).forEach { status ->
            val coordinator = Coordinator { operation(it, status) }
            var callbacks = 0
            reject { coordinator.performIdempotentRelayEdit(request, { callbacks++ }) { callbacks++ } }
            assertThat(callbacks).isEqualTo(0)
            assertThat(coordinator.persisted).isEmpty()
        }
    }

    @Test fun `expired original still reconciles actual key erasure without extending deadline dispatch or receipts`() = runTest {
        val coordinator = Coordinator { operation(it).copy(
            createdAtEpochMillis = System.currentTimeMillis() - 120_000,
            deadlineEpochMillis = System.currentTimeMillis() - 60_000,
        ) }
        var reconciled = 0
        reject {
            coordinator.performIdempotentRelayEdit(request, { error("No accepted carrier") }, reconcileExpired = {
                assertThat(currentLifecycleOperationContext()?.operationId).isEqualTo(request.operationId)
                reconciled++
            }) { error("Expired original dispatched another edit") }
        }
        assertThat(reconciled).isEqualTo(1)
        assertThat(coordinator.persisted).isEmpty()
        assertThat(coordinator.receipts).isEmpty()
    }


    @Test fun `reconciliation resolves only exact successful original without admission dispatch or receipts`() = runTest {
        val original = operation(request, LifecycleOperationStatus.SUCCEEDED)
        val coordinator = Coordinator { error("Reconciliation must not start") }.apply { started = original }
        var resolved = 0
        var verified = 0
        assertThat(coordinator.reconcileOriginalRelayEdit(request, {
            assertThat(currentLifecycleOperationContext()?.operationId).isEqualTo(original.operationId)
            verified++
        }, { resolved++ })).isTrue()
        assertThat(verified).isEqualTo(1)
        assertThat(resolved).isEqualTo(1)
        assertThat(coordinator.receipts).isEmpty()
        assertThat(coordinator.persisted).isEmpty()
    }

    @Test fun `reconciliation preserves unknown even when external receipt steps completed`() = runTest {
        val coordinator = Coordinator { error("No new admission") }.apply { started = operation(request, externalComplete = true) }
        assertThat(coordinator.reconcileOriginalRelayEdit(request, { error("Do not read intent while UNKNOWN") }, {
            error("Do not resolve UNKNOWN")
        })).isFalse()
        assertThat(coordinator.receipts).isEmpty()
    }

    @Test fun `client receipts v1 without authority revision resolve the exact accepted edit`() = runTest {
        val original = operation(request, LifecycleOperationStatus.SUCCEEDED).let { operation ->
            operation.copy(steps = operation.steps.map {
                if (it.component in android) it.copy(authorityRevision = null) else it
            })
        }
        val coordinator = Coordinator { original }.apply { started = original }
        var verified = 0
        var resolved = 0
        coordinator.performIdempotentRelayEdit(request, { verified++ }) { error("Do not resend an accepted edit") }
        assertThat(coordinator.reconcileOriginalRelayEdit(request, { verified++ }, { resolved++ })).isTrue()
        assertThat(verified).isEqualTo(2)
        assertThat(resolved).isEqualTo(1)
        assertThat(coordinator.persisted).isEmpty()
        assertThat(coordinator.receipts).isEmpty()
    }

    @Test fun `client revision compatibility never accepts missing receipts or external revisions`() = runTest {
        val original = operation(request, LifecycleOperationStatus.SUCCEEDED)
        components.forEach { component ->
            // Wire decoding cannot construct a completed required step without
            // its receipt, before reconciliation is even reached.
            assertThat(runCatching {
                original.steps.single { it.component == component }.copy(receiptReference = null)
            }.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        }
        (components - android).forEach { component ->
            val invalid = original.copy(steps = original.steps.map {
                if (it.component == component) it.copy(authorityRevision = null) else it
            })
            val coordinator = Coordinator { invalid }.apply { started = invalid }
            var callbacks = 0
            reject {
                coordinator.reconcileOriginalRelayEdit(request, { callbacks++ }, { callbacks++ })
            }
            assertThat(callbacks).isEqualTo(0)
        }
    }

    @Test fun `reconciliation rejects incomplete terminal conflict expiry and revoked authority without resolving`() = runTest {
        val success = operation(request, LifecycleOperationStatus.SUCCEEDED)
        assertThat(runCatching { success.copy(terminalReceiptId = null) }.isFailure).isTrue()
        assertThat(runCatching { success.copy(steps = success.steps.map { it.copy(status = LifecycleStepStatus.PENDING) }) }.isFailure).isTrue()
        val invalid = listOf(success.copy(steps = success.steps.map { it.copy(authorityRevision = null) }),
            success.copy(steps = success.steps.map { it.copy(status = LifecycleStepStatus.TERMINATED) }),
            success.copy(steps = success.steps.drop(1)), success.copy(operationId = "same-body-different-operation"),
            success.copy(subjects = success.subjects.copy(matrixDeviceIdHash = "e".repeat(64))),
            success.copy(status = LifecycleOperationStatus.FAILED_FINAL), success.copy(status = LifecycleOperationStatus.CANCELLED),
            success.copy(status = LifecycleOperationStatus.TERMINATED),
            operation(request).copy(createdAtEpochMillis = System.currentTimeMillis() - 100_000, deadlineEpochMillis = System.currentTimeMillis() - 1))
        invalid.forEach { original ->
            val coordinator = Coordinator { error("No admission") }.apply { started = original }
            var callbacks = 0
            reject { coordinator.reconcileOriginalRelayEdit(request, { callbacks++ }, { callbacks++ }) }
            assertThat(callbacks).isEqualTo(0)
            assertThat(coordinator.receipts).isEmpty()
        }
        val coordinator = Coordinator { error("No admission") }.apply { started = success }
        var verified = 0
        var resolved = 0
        reject { coordinator.reconcileOriginalRelayEdit(request, {
            verified++
            throw IllegalStateException("Intent authorization revoked")
        }, { resolved++ }) }
        assertThat(verified).isEqualTo(1)
        assertThat(resolved).isEqualTo(0)
    }

    private suspend fun reject(block: suspend () -> Unit) {
        var rejected = false
        try { block() } catch (_: IllegalArgumentException) { rejected = true } catch (_: IllegalStateException) { rejected = true }
        assertThat(rejected).isTrue()
    }

    private class Coordinator(val response: (LifecycleOperationRequest) -> LifecycleOperation) : AppOperationalStateCoordinator {
        override val state = MutableStateFlow<AppOperationalCoordinatorState>(AppOperationalCoordinatorState.Discovering)
        override val operations = MutableStateFlow<Map<String, LifecycleOperation>>(emptyMap())
        override val uiStates = MutableStateFlow<Map<FullAppLifecycleId, AppLifecycleUiState>>(emptyMap())
        val receipts = mutableListOf<LifecycleClientReceipt>()
        val persisted = mutableListOf<LifecycleClientReceipt>()
        lateinit var started: LifecycleOperation
        override suspend fun start(request: LifecycleOperationRequest) = response(request).also { started = it }
        override suspend fun operation(operationId: String) = started
        override suspend fun resume(operationId: String) = started
        override suspend fun recordClientReceipt(receipt: LifecycleClientReceipt): LifecycleOperation { receipts += receipt; return started }
        override suspend fun persistClientReceipts(receipts: List<LifecycleClientReceipt>) { persisted += receipts }
        override suspend fun refresh(accountIdHash: String, matrixDeviceIdHash: String): AppOperationalSnapshot = error("Unused")
        override suspend fun <T> performResuming(request: LifecycleOperationRequest, hasNewStartupIntent: Boolean,
            block: suspend (operationId: String) -> T,
        ): T = error("Unused")
    }
}
