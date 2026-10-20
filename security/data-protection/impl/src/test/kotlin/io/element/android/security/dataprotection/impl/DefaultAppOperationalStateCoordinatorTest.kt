/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.dataprotection.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.security.dataprotection.api.AppOperationalCoordinatorState
import io.element.android.security.dataprotection.api.AppOperationalSnapshot
import io.element.android.security.dataprotection.api.AppOperationalStatus
import io.element.android.security.dataprotection.api.ComponentOperationalState
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleClientReceipt
import io.element.android.security.dataprotection.api.LifecycleComponent
import io.element.android.security.dataprotection.api.LifecycleOperation
import io.element.android.security.dataprotection.api.LifecycleOperationRequest
import io.element.android.security.dataprotection.api.LifecycleOperationStatus
import io.element.android.security.dataprotection.api.LifecyclePhase
import io.element.android.security.dataprotection.api.LifecycleSubjectHashes
import io.element.android.security.dataprotection.api.ManagementControlService
import io.element.android.security.dataprotection.api.REQUIRED_OPERATIONAL_COMPONENTS
import io.element.android.security.dataprotection.api.lifecycleOperationRequest
import io.element.android.security.dataprotection.api.perform
import io.element.android.security.dataprotection.api.performMessageWithDeferredReceipts
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class DefaultAppOperationalStateCoordinatorTest {
    @Test
    fun `terminal coordination without the original Android fact cannot retire persisted receipt after restart`() = runTest {
        val request = lifecycleOperationRequest(
            FullAppLifecycleId.LC_16,
            "download",
            operationId = "media-cleanup-original-0001",
            accountIdHash = "a".repeat(64),
            matrixDeviceIdHash = "b".repeat(64)
        )
        val receipt = LifecycleClientReceipt(
            request.operationId,
            LifecycleComponent.ANDROID_SERVICE,
            LifecyclePhase.CLIENT_APPLIED,
            "ANDROID_SERVICE_APPLIED",
            "android-service:${request.operationId}",
            123L
        )
        val missing = io.element.android.security.dataprotection.api.LifecycleStep(
            stepId = "media-original-android-step",
            component = receipt.component,
            status = io.element.android.security.dataprotection.api.LifecycleStepStatus.PENDING,
            attemptCount = 0,
            updatedAtEpochMillis = 123L,
        )
        val ended = operation(request).copy(
            subjects = operation(request).subjects.copy(installationId = "installation-test-0001"),
            status = LifecycleOperationStatus.FAILED_FINAL,
            currentPhase = LifecyclePhase.RECEIPTED,
            terminalReceiptId = "original-coordination-timeout-receipt",
            steps = listOf(missing),
        )
        val store = FakeLifecycleOperationIdStore().apply { savePendingClientReceipts(listOf(receipt)) }
        val service = mockk<ManagementControlService>()
        coEvery { service.appOperationalSnapshot(any(), any()) } returns snapshot(100L)
        coEvery { service.lifecycleOperation(request.operationId) } returns ended
        repeat(2) {
            DefaultAppOperationalStateCoordinator(service, store).refresh("a".repeat(64), "b".repeat(64))
            assertThat(store.pendingClientReceipts()).containsExactly(receipt)
        }
        coVerify(exactly = 0) { service.startLifecycleOperation(any()) }
        coVerify(exactly = 0) { service.recordLifecycleClientReceipt(any()) }
    }

    @Test
    fun `receipt endpoint terminal response without matching step keeps the original pending fact`() = runTest {
        val request = lifecycleOperationRequest(
            FullAppLifecycleId.LC_16,
            "download",
            operationId = "media-cleanup-response-0001",
            accountIdHash = "a".repeat(64),
            matrixDeviceIdHash = "b".repeat(64)
        )
        val receipt = LifecycleClientReceipt(
            request.operationId,
            LifecycleComponent.ANDROID_SERVICE,
            LifecyclePhase.CLIENT_APPLIED,
            "ANDROID_SERVICE_APPLIED",
            "android-service:${request.operationId}",
            123L
        )
        val missing = io.element.android.security.dataprotection.api.LifecycleStep(
            stepId = "media-original-android-step",
            component = receipt.component,
            status = io.element.android.security.dataprotection.api.LifecycleStepStatus.PENDING,
            attemptCount = 0,
            updatedAtEpochMillis = 123L,
        )
        val admitted = operation(request).copy(steps = listOf(missing))
        val ended = admitted.copy(
            status = LifecycleOperationStatus.FAILED_FINAL,
            currentPhase = LifecyclePhase.RECEIPTED,
            terminalReceiptId = "original-response-timeout-receipt"
        )
        val service = mockk<ManagementControlService>()
        coEvery { service.startLifecycleOperation(request) } returns admitted
        coEvery { service.recordLifecycleClientReceipt(receipt) } returns ended
        val store = FakeLifecycleOperationIdStore()
        val coordinator = DefaultAppOperationalStateCoordinator(service, store)
        coordinator.start(request)
        assertThat(runCatching { coordinator.recordClientReceipt(receipt) }.isFailure).isTrue()
        assertThat(store.pendingClientReceipts()).containsExactly(receipt)
    }

    @Test
    fun `terminal failure elsewhere can acknowledge an independently completed exact Android fact`() = runTest {
        val request = lifecycleOperationRequest(
            FullAppLifecycleId.LC_16,
            "download",
            operationId = "media-cleanup-observed-0001",
            accountIdHash = "a".repeat(64),
            matrixDeviceIdHash = "b".repeat(64)
        )
        val receipt = LifecycleClientReceipt(
            request.operationId,
            LifecycleComponent.ANDROID_SERVICE,
            LifecyclePhase.CLIENT_APPLIED,
            "ANDROID_SERVICE_APPLIED",
            "android-service:${request.operationId}",
            123L
        )
        val step = io.element.android.security.dataprotection.api.LifecycleStep(
            stepId = "media-original-android-step",
            component = receipt.component,
            status = io.element.android.security.dataprotection.api.LifecycleStepStatus.COMPLETE,
            attemptCount = 1,
            resultCode = receipt.resultCode,
            receiptReference = receipt.receiptReference,
            updatedAtEpochMillis = 123L,
        )
        val ended = operation(request).copy(
            subjects = operation(request).subjects.copy(installationId = "installation-test-0001"),
            status = LifecycleOperationStatus.FAILED_FINAL,
            currentPhase = LifecyclePhase.RECEIPTED,
            terminalReceiptId = "original-independent-failure-receipt",
            steps = listOf(step),
        )
        val store = FakeLifecycleOperationIdStore().apply { savePendingClientReceipts(listOf(receipt)) }
        val service = mockk<ManagementControlService>()
        coEvery { service.appOperationalSnapshot(any(), any()) } returns snapshot(100L)
        coEvery { service.lifecycleOperation(request.operationId) } returns ended
        DefaultAppOperationalStateCoordinator(service, store).refresh("a".repeat(64), "b".repeat(64))
        assertThat(store.pendingClientReceipts()).isEmpty()
        coVerify(exactly = 0) { service.recordLifecycleClientReceipt(any()) }
        coVerify(exactly = 0) { service.startLifecycleOperation(any()) }
    }

    @Test
    fun `verified same installation terminal receipt retires observer intent without account transport`() = runTest {
        val request = lifecycleOperationRequest(FullAppLifecycleId.LC_07, "heartbeat", operationId = "terminal-observer-original-0001")
        val receipt = LifecycleClientReceipt(
            request.operationId,
            LifecycleComponent.ANDROID_FRONTEND,
            LifecyclePhase.CLIENT_APPLIED,
            "ANDROID_FRONTEND_APPLIED",
            "android-frontend:${request.operationId}",
            123L
        )
        for (installation in listOf("installation-test-0001", "other-installation-0001")) {
            val service = mockk<ManagementControlService>()
            val completed = operation(request).copy(
                subjects = LifecycleSubjectHashes(installationId = installation),
                status = LifecycleOperationStatus.SUCCEEDED,
                currentPhase = LifecyclePhase.RECEIPTED,
                terminalReceiptId = "verified-terminal-observer-receipt-0001",
            )
            val store = FakeLifecycleOperationIdStore().apply { savePendingClientReceipts(listOf(receipt)) }
            coEvery { service.appOperationalSnapshot(any(), any()) } returns snapshot(100L)
            coEvery { service.lifecycleOperation(request.operationId) } returns completed

            DefaultAppOperationalStateCoordinator(service, store).refresh("a".repeat(64), "b".repeat(64))

            coVerify(exactly = 0) { service.recordLifecycleClientReceipt(any()) }
            coVerify(exactly = 0) { service.startLifecycleOperation(any()) }
            assertThat(store.pendingClientReceipts().isEmpty()).isEqualTo(installation == "installation-test-0001")
        }
    }

    @Test
    fun `ordinary product completion commits both facts before any failing transport and replays after process death`() = runTest {
        val service = mockk<ManagementControlService>()
        val request = lifecycleOperationRequest(
            FullAppLifecycleId.LC_14,
            "draft_change",
            operationId = "durable-draft-original-0001",
            accountIdHash = "a".repeat(64),
            matrixDeviceIdHash = "b".repeat(64)
        )
        val accepted = operation(request)
        val store = FakeLifecycleOperationIdStore()
        coEvery { service.startLifecycleOperation(request) } returns accepted
        coEvery { service.recordLifecycleClientReceipt(any()) } coAnswers {
            assertThat(store.pendingClientReceipts()).hasSize(2)
            throw java.io.IOException("TEST_TRANSPORT_LOST")
        }
        val original = DefaultAppOperationalStateCoordinator(service, store)
        var actionCount = 0
        assertThat(runCatching {
            original.perform(request) {
            actionCount++
            Unit
        }
        }.isFailure).isTrue()
        assertThat(actionCount).isEqualTo(1)
        val committed = store.pendingClientReceipts()
        assertThat(committed).hasSize(2)
        assertThat(committed.map { it.operationId }.distinct()).containsExactly(request.operationId)
        coEvery { service.appOperationalSnapshot(any(), any()) } returns snapshot(100L)
        coEvery { service.lifecycleOperation(request.operationId) } returns accepted
        coEvery { service.recordLifecycleClientReceipt(any()) } returns accepted

        DefaultAppOperationalStateCoordinator(service, store).refresh("a".repeat(64), "b".repeat(64))

        assertThat(store.pendingClientReceipts()).isEmpty()
        committed.forEach { receipt -> coVerify(exactly = 2) { service.recordLifecycleClientReceipt(receipt) } }
        coVerify(exactly = 1) { service.startLifecycleOperation(request) }
        assertThat(actionCount).isEqualTo(1)
    }

    @Test
    fun `direct receipt failure retains original occurrence time across retries`() = runTest {
        val service = mockk<ManagementControlService>()
        val store = FakeLifecycleOperationIdStore()
        val request = lifecycleOperationRequest(
            FullAppLifecycleId.LC_02,
            "foreground",
            accountIdHash = "a".repeat(64),
            matrixDeviceIdHash = "b".repeat(64)
        )
        val receipt = LifecycleClientReceipt(
            request.operationId,
            LifecycleComponent.ANDROID_SERVICE,
            LifecyclePhase.CLIENT_APPLIED,
            "ANDROID_SERVICE_APPLIED",
            "android-service:${request.operationId}",
            123L
        )
        coEvery { service.recordLifecycleClientReceipt(any()) } throws java.io.IOException("TEST_TRANSPORT_LOST")
        val coordinator = DefaultAppOperationalStateCoordinator(service, store)
        assertThat(runCatching { coordinator.recordClientReceipt(receipt) }.isFailure).isTrue()
        assertThat(runCatching { coordinator.recordClientReceipt(receipt.copy(occurredAtEpochMillis = 999L)) }.isFailure).isTrue()
        assertThat(store.pendingClientReceipts()).containsExactly(receipt)
        coVerify(exactly = 2) { service.recordLifecycleClientReceipt(receipt) }
    }

    @Test
    fun `installation restoration receipt replay is limited to exact installation without resource subjects`() = runTest {
        val request = lifecycleOperationRequest(FullAppLifecycleId.LC_02, "process_create", operationId = "restore-installation-original-0001")
        val receipt = LifecycleClientReceipt(
            request.operationId,
            LifecycleComponent.ANDROID_SERVICE,
            LifecyclePhase.CLIENT_APPLIED,
            "ANDROID_SERVICE_APPLIED",
            "android-service:${request.operationId}",
            123L
        )
        val scope = LifecycleSubjectHashes(installationId = "installation-test-0001")
        val cases = listOf(
            scope to true,
            scope.copy(installationId = "other-installation-0001") to false,
            scope.copy(roomIdHash = "c".repeat(64)) to false,
            scope.copy(eventIdHash = "d".repeat(64)) to false
        )
        for ((subjects, allowed) in cases) {
            val service = mockk<ManagementControlService>()
            val accepted = operation(request).copy(subjects = subjects)
            val store = FakeLifecycleOperationIdStore().apply { savePendingClientReceipts(listOf(receipt)) }
            coEvery { service.appOperationalSnapshot(any(), any()) } returns snapshot(100L)
            coEvery { service.lifecycleOperation(request.operationId) } returns accepted
            coEvery { service.recordLifecycleClientReceipt(receipt) } returns accepted
            DefaultAppOperationalStateCoordinator(service, store).refresh("a".repeat(64), "b".repeat(64))
            coVerify(exactly = if (allowed) 1 else 0) { service.recordLifecycleClientReceipt(receipt) }
            assertThat(store.pendingClientReceipts().isEmpty()).isEqualTo(allowed)
        }
    }

    @Test
    fun `first activation reconciliation reads signed scope without admitted snapshot`() = runTest {
        val service = mockk<ManagementControlService>()
        val request = lifecycleOperationRequest(
            FullAppLifecycleId.LC_05,
            "first_activation",
            accountIdHash = "a".repeat(64),
            matrixDeviceIdHash = "b".repeat(64)
        )
        val recovered = operation(request)
        coEvery { service.startupLifecycleOperations(any(), any(), "first_activation", any()) } returns listOf(recovered)
        val coordinator = DefaultAppOperationalStateCoordinator(service)
        coordinator.reconcileFirstActivation(request.accountIdHash!!, request.matrixDeviceIdHash!!)
        assertThat(coordinator.operations.value[request.operationId]).isEqualTo(recovered)
        coVerify(exactly = 0) { service.appOperationalSnapshot(any(), any()) }
        coVerify(exactly = 0) { service.startLifecycleOperation(any()) }
    }

    @Test
    fun `first activation rejects cross-account recovery without publishing any receipt`() = runTest {
        val service = mockk<ManagementControlService>()
        val wrong = lifecycleOperationRequest(
            FullAppLifecycleId.LC_05,
            "first_activation",
            accountIdHash = "c".repeat(64),
            matrixDeviceIdHash = "b".repeat(64)
        )
        coEvery { service.startupLifecycleOperations(any(), any(), "first_activation", any()) } returns listOf(operation(wrong))
        val coordinator = DefaultAppOperationalStateCoordinator(service)
        assertThat(runCatching { coordinator.reconcileFirstActivation("a".repeat(64), "b".repeat(64)) }.isFailure).isTrue()
        assertThat(coordinator.operations.value).isEmpty()
        coVerify(exactly = 0) { service.startLifecycleOperation(any()) }
    }

    @Test
    fun `first activation reconciles a rejected provisional ID through signed absence`() = runTest {
        val service = mockk<ManagementControlService>()
        val original = lifecycleOperationRequest(FullAppLifecycleId.LC_05, "first_activation")
        coEvery { service.startupLifecycleOperations(any(), any(), "first_activation", original.operationId) } returns emptyList()
        val coordinator = DefaultAppOperationalStateCoordinator(service, FakeLifecycleOperationIdStore(original.operationId))
        coordinator.reconcileFirstActivation("a".repeat(64), "b".repeat(64))
        assertThat(coordinator.operations.value).isEmpty()
        coVerify(exactly = 0) { service.lifecycleOperation(any()) }
        coVerify(exactly = 0) { service.startLifecycleOperation(any()) }
        coVerify(exactly = 1) { service.startupLifecycleOperations(any(), any(), "first_activation", original.operationId) }
    }

    @Test
    fun `cold login reconciles server owner before creating any new operation`() = runTest {
        val service = mockk<ManagementControlService>()
        val old = lifecycleOperationRequest(
            FullAppLifecycleId.LC_06,
            "post_login",
            operationId = "cold-login-original-0001",
            accountIdHash = "a".repeat(64),
            matrixDeviceIdHash = "b".repeat(64)
        )
        val next = old.copy(operationId = "cold-login-next-intent-0001", idempotencyKey = "cold-login-next-intent-0001")
        val recovered = operation(old)
        coEvery { service.startupLifecycleOperations(any(), any(), "post_login") } returns listOf(recovered)
        coEvery { service.lifecycleOperation(old.operationId) } returns recovered
        coEvery { service.resumeLifecycleOperation(old.operationId) } returns recovered
        coEvery { service.recordLifecycleClientReceipt(any()) } returns recovered
        val coordinator = DefaultAppOperationalStateCoordinator(service)
        assertThat(coordinator.performResuming(next) { it }).isEqualTo(old.operationId)
        coVerify(exactly = 0) { service.startLifecycleOperation(any()) }
        assertThat(coordinator.operations.value.getValue(old.operationId).deadlineEpochMillis).isEqualTo(old.deadlineEpochMillis)
    }

    @Test
    fun `cold login unavailable authority cannot create a competing startup`() = runTest {
        val service = mockk<ManagementControlService>()
        val request = lifecycleOperationRequest(
            FullAppLifecycleId.LC_06,
            "post_login",
            operationId = "cold-login-unavailable-0001",
            accountIdHash = "a".repeat(64),
            matrixDeviceIdHash = "b".repeat(64)
        )
        coEvery { service.startupLifecycleOperations(any(), any(), "post_login") } throws java.io.IOException("TEST_AUTHORITY_UNAVAILABLE")
        val coordinator = DefaultAppOperationalStateCoordinator(service)
        assertThat(runCatching { coordinator.performResuming(request) { error("Cannot execute without provenance") } }.isFailure).isTrue()
        coVerify(exactly = 0) { service.startLifecycleOperation(any()) }
        coVerify(exactly = 0) { service.recordLifecycleClientReceipt(any()) }
    }

    @Test
    fun `fresh candidate starts only after an empty verified startup index`() = runTest {
        val service = mockk<ManagementControlService>()
        val request = lifecycleOperationRequest(
            FullAppLifecycleId.LC_06,
            "post_login",
            operationId = "fresh-candidate-login-0001",
            accountIdHash = "a".repeat(64),
            matrixDeviceIdHash = "b".repeat(64)
        )
        val admitted = operation(request)
        coEvery { service.startupLifecycleOperations(any(), any(), "post_login") } returns emptyList()
        coEvery { service.startLifecycleOperation(request) } returns admitted
        coEvery { service.recordLifecycleClientReceipt(any()) } returns admitted
        val coordinator = DefaultAppOperationalStateCoordinator(service)
        assertThat(coordinator.performResuming(request) { it }).isEqualTo(request.operationId)
        coVerify(exactly = 0) { service.appOperationalSnapshot(any(), any()) }
        coVerify(exactly = 1) { service.startLifecycleOperation(request) }
    }

    @Test
    fun `startup reconciliation rejects another account before publishing it`() = runTest {
        val service = mockk<ManagementControlService>()
        val request = lifecycleOperationRequest(
            FullAppLifecycleId.LC_06,
            "post_login",
            operationId = "candidate-scope-login-0001",
            accountIdHash = "a".repeat(64),
            matrixDeviceIdHash = "b".repeat(64)
        )
        coEvery { service.startupLifecycleOperations(any(), any(), "post_login") } returns listOf(
            operation(request.copy(accountIdHash = "c".repeat(64)))
        )
        val coordinator = DefaultAppOperationalStateCoordinator(service)
        assertThat(runCatching { coordinator.performResuming(request) { error("Must not execute") } }.isFailure).isTrue()
        coVerify(exactly = 0) { service.startLifecycleOperation(any()) }
        assertThat(coordinator.operations.value).isEmpty()
    }

    @Test
    fun `known response lost login is retried with original identity without empty snapshot replacement`() = runTest {
        val service = mockk<ManagementControlService>()
        val original = lifecycleOperationRequest(
            FullAppLifecycleId.LC_06,
            "post_login",
            operationId = "cold-login-response-lost-0001",
            accountIdHash = "a".repeat(64),
            matrixDeviceIdHash = "b".repeat(64)
        )
        val next = original.copy(operationId = "cold-login-response-next-0001", idempotencyKey = "cold-login-response-next-0001")
        val accepted = operation(original)
        coEvery { service.startLifecycleOperation(original) } throws java.io.IOException("TEST_RESPONSE_LOST") andThen accepted
        coEvery { service.recordLifecycleClientReceipt(any()) } returns accepted
        val coordinator = DefaultAppOperationalStateCoordinator(service)
        assertThat(runCatching { coordinator.start(original) }.isFailure).isTrue()
        assertThat(coordinator.performResuming(next) { it }).isEqualTo(original.operationId)
        coVerify(exactly = 0) { service.appOperationalSnapshot(any(), any()) }
        coVerify(exactly = 0) { service.startLifecycleOperation(next) }
    }

    @Test
    fun `cancelled submitted start retains original retry identity without client completion`() = runTest {
        val service = mockk<ManagementControlService>()
        val request = lifecycleOperationRequest(FullAppLifecycleId.LC_16, "download", operationId = "submitted-media-original-0001")
        val entered = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        coEvery { service.startLifecycleOperation(request) } coAnswers {
            entered.complete(Unit)
            response.await()
            operation(request)
        }
        val store = FakeLifecycleOperationIdStore()
        val coordinator = DefaultAppOperationalStateCoordinator(service, store)
        val caller = async { coordinator.perform(request) { error("Product must not execute after this caller cancellation") } }
        entered.await()
        caller.cancel()
        caller.join()
        assertThat(coordinator.operations.value.getValue(request.operationId).status).isEqualTo(LifecycleOperationStatus.RETRYABLE_FAILURE)
        // This store persists LC-05 only; the LC-16 retry below is explicitly process-local.
        assertThat(store.latest(FullAppLifecycleId.LC_16)).isNull()
        assertThat(coordinator.operations.value.getValue(request.operationId).operationId).isEqualTo(request.operationId)
        coVerify(exactly = 0) { service.recordLifecycleClientReceipt(any()) }
        // Recovery uses the exact original request; this is a unit fixture, not a resend of an observed QA operation.
        response.complete(Unit)
        assertThat(coordinator.resume(request.operationId).operationId).isEqualTo(request.operationId)
        coVerify(exactly = 2) { service.startLifecycleOperation(request) }
    }

    @Test
    fun `message returns while receipt network is suspended with exact durable intents and pending status`() = runTest {
        val service = mockk<ManagementControlService>()
        val request = lifecycleOperationRequest(FullAppLifecycleId.LC_13, "send", operationId = "deferred-message-operation-0001")
        val accepted = operation(request)
        val allowReceipt = CompletableDeferred<Unit>()
        coEvery { service.startLifecycleOperation(request) } returns accepted
        coEvery { service.recordLifecycleClientReceipt(any()) } coAnswers {
            allowReceipt.await()
            accepted
        }
        val store = FakeLifecycleOperationIdStore()
        val coordinator = DefaultAppOperationalStateCoordinator(service, store, backgroundScope, StandardTestDispatcher(testScheduler))

        val result = coordinator.performMessageWithDeferredReceipts(request) { "authorized-result" }

        assertThat(result).isEqualTo("authorized-result")
        assertThat(store.pendingClientReceipts()).hasSize(2)
        assertThat(coordinator.operations.value.getValue(request.operationId).status).isEqualTo(LifecycleOperationStatus.RUNNING)
        runCurrent()
        assertThat(store.pendingClientReceipts()).hasSize(2)
        allowReceipt.complete(Unit)
        runCurrent()
        assertThat(store.pendingClientReceipts()).isEmpty()
        // Queue drainage is not a manufactured terminal receipt.
        assertThat(coordinator.operations.value.getValue(request.operationId).status).isEqualTo(LifecycleOperationStatus.RUNNING)
    }

    @Test
    fun `persisted message receipts recover after process death with original identity and time`() = runTest {
        val service = mockk<ManagementControlService>()
        val request = lifecycleOperationRequest(FullAppLifecycleId.LC_13, "receive", operationId = "deferred-message-operation-0002")
        val accepted = operation(request).copy(subjects = LifecycleSubjectHashes(accountIdHash = "a".repeat(64), matrixDeviceIdHash = "b".repeat(64)))
        coEvery { service.startLifecycleOperation(request) } returns accepted
        val store = FakeLifecycleOperationIdStore()
        val original = DefaultAppOperationalStateCoordinator(service, store)
        original.performMessageWithDeferredReceipts(request) { Unit }
        val receipts = store.pendingClientReceipts()
        coVerify(exactly = 0) { service.recordLifecycleClientReceipt(any()) }
        coEvery { service.appOperationalSnapshot(any(), any()) } returns snapshot(100L)
        coEvery { service.lifecycleOperation(request.operationId) } returns accepted
        coEvery { service.recordLifecycleClientReceipt(any()) } returns accepted

        DefaultAppOperationalStateCoordinator(service, store).refresh("a".repeat(64), "b".repeat(64))

        receipts.forEach { receipt -> coVerify(exactly = 1) { service.recordLifecycleClientReceipt(receipt) } }
        assertThat(store.pendingClientReceipts()).isEmpty()
        coVerify(exactly = 1) { service.startLifecycleOperation(request) }
    }

    @Test
    fun `receipt retry failure retains intent without changing successful message result`() = runTest {
        val service = mockk<ManagementControlService>()
        val request = lifecycleOperationRequest(FullAppLifecycleId.LC_13, "send", operationId = "deferred-message-operation-0003")
        val accepted = operation(request).copy(subjects = LifecycleSubjectHashes(accountIdHash = "a".repeat(64), matrixDeviceIdHash = "b".repeat(64)))
        coEvery { service.startLifecycleOperation(request) } returns accepted
        coEvery { service.appOperationalSnapshot(any(), any()) } returns snapshot(100L)
        coEvery { service.recordLifecycleClientReceipt(any()) } throws java.io.IOException("HTTP 429")
        val store = FakeLifecycleOperationIdStore()
        val coordinator = DefaultAppOperationalStateCoordinator(service, store)
        assertThat(coordinator.performMessageWithDeferredReceipts(request) { 7 }).isEqualTo(7)
        val before = store.pendingClientReceipts()
        coordinator.refresh("a".repeat(64), "b".repeat(64))
        assertThat(store.pendingClientReceipts()).containsExactlyElementsIn(before)
    }

    @Test
    fun `old account receipt is not replayed with current account snapshot`() = runTest {
        val service = mockk<ManagementControlService>()
        val request = lifecycleOperationRequest(FullAppLifecycleId.LC_13, "receive", operationId = "deferred-message-operation-0004")
        val accepted = operation(request).copy(subjects = LifecycleSubjectHashes(accountIdHash = "c".repeat(64), matrixDeviceIdHash = "d".repeat(64)))
        coEvery { service.startLifecycleOperation(request) } returns accepted
        val store = FakeLifecycleOperationIdStore()
        DefaultAppOperationalStateCoordinator(service, store).performMessageWithDeferredReceipts(request) { Unit }
        coEvery { service.appOperationalSnapshot(any(), any()) } returns snapshot(100L)
        coEvery { service.lifecycleOperation(request.operationId) } returns accepted

        DefaultAppOperationalStateCoordinator(service, store).refresh("a".repeat(64), "b".repeat(64))

        coVerify(exactly = 0) { service.recordLifecycleClientReceipt(any()) }
        assertThat(store.pendingClientReceipts()).hasSize(2)
    }

    @Test
    fun `public login rejection closes its original installation operation with another account snapshot`() = runTest {
        val service = mockk<ManagementControlService>()
        val request = lifecycleOperationRequest(FullAppLifecycleId.LC_05, "matrix_login", operationId = "public-login-rejected-original-0001")
        val accepted = operation(request).copy(subjects = LifecycleSubjectHashes(installationId = "installation-test-0001"))
        val receipt = LifecycleClientReceipt(
            request.operationId,
            LifecycleComponent.ANDROID_SERVICE,
            LifecyclePhase.CLIENT_APPLIED,
            "MATRIX_AUTHENTICATION_REJECTED",
            "android-login-rejected:${request.operationId}",
            123L
        )
        val store = FakeLifecycleOperationIdStore().apply { savePendingClientReceipts(listOf(receipt)) }
        coEvery { service.appOperationalSnapshot(any(), any()) } returns snapshot(100L)
        coEvery { service.lifecycleOperation(request.operationId) } returns accepted
        coEvery { service.recordLifecycleClientReceipt(receipt) } returns accepted.copy(
            status = LifecycleOperationStatus.FAILED_FINAL,
            terminalReceiptId = "public-login-rejected-terminal-0001",
        )

        val coordinator = DefaultAppOperationalStateCoordinator(service, store)
        coordinator.refresh("a".repeat(64), "b".repeat(64))

        coVerify(exactly = 1) { service.recordLifecycleClientReceipt(receipt) }
        coVerify(exactly = 0) { service.startLifecycleOperation(any()) }
        assertThat(store.pendingClientReceipts()).isEmpty()
        assertThat(coordinator.operations.value.getValue(request.operationId).status).isEqualTo(LifecycleOperationStatus.FAILED_FINAL)
    }

    @Test
    fun `public rejection exception cannot transport success other installation or account bound receipts`() = runTest {
        val request = lifecycleOperationRequest(FullAppLifecycleId.LC_05, "matrix_login", operationId = "public-login-rejected-boundary-0001")
        val subjects = LifecycleSubjectHashes(installationId = "installation-test-0001")
        val failure = LifecycleClientReceipt(
            request.operationId,
            LifecycleComponent.ANDROID_SERVICE,
            LifecyclePhase.CLIENT_APPLIED,
            "MATRIX_AUTHENTICATION_REJECTED",
            "android-login-rejected:${request.operationId}",
            123L
        )
        val cases = listOf(
            operation(request).copy(subjects = subjects.copy(installationId = "another-installation-0001")) to failure,
            operation(request).copy(subjects = subjects) to failure.copy(resultCode = "ANDROID_SERVICE_APPLIED"),
            operation(request).copy(subjects = subjects.copy(accountIdHash = "c".repeat(64), matrixDeviceIdHash = "d".repeat(64))) to failure,
            operation(request).copy(subjects = subjects, lifecycleId = FullAppLifecycleId.LC_08) to failure,
        )
        for ((accepted, receipt) in cases) {
            val service = mockk<ManagementControlService>()
            val store = FakeLifecycleOperationIdStore().apply { savePendingClientReceipts(listOf(receipt)) }
            coEvery { service.appOperationalSnapshot(any(), any()) } returns snapshot(100L)
            coEvery { service.lifecycleOperation(request.operationId) } returns accepted
            DefaultAppOperationalStateCoordinator(service, store).refresh("a".repeat(64), "b".repeat(64))
            coVerify(exactly = 0) { service.recordLifecycleClientReceipt(any()) }
            assertThat(store.pendingClientReceipts()).containsExactly(receipt)
        }
    }

    @Test
    fun `denied product action never enqueues success receipts`() = runTest {
        val service = mockk<ManagementControlService>()
        val request = lifecycleOperationRequest(FullAppLifecycleId.LC_13, "receive", operationId = "deferred-message-operation-0005")
        coEvery { service.startLifecycleOperation(request) } returns operation(request)
        val store = FakeLifecycleOperationIdStore()
        val coordinator = DefaultAppOperationalStateCoordinator(service, store)
        val result = runCatching { coordinator.performMessageWithDeferredReceipts(request) { throw SecurityException("denied") } }
        assertThat(result.exceptionOrNull()).isInstanceOf(SecurityException::class.java)
        assertThat(store.pendingClientReceipts()).isEmpty()
        coVerify(exactly = 0) { service.recordLifecycleClientReceipt(any()) }
    }

    @Test
    fun `refresh restores a persisted terminal first activation after process death`() = runTest {
        val service = mockk<ManagementControlService>()
        val accountIdHash = "a".repeat(64)
        val matrixDeviceIdHash = "b".repeat(64)
        val request = lifecycleOperationRequest(
            lifecycleId = FullAppLifecycleId.LC_05,
            trigger = "first_activation",
            accountIdHash = accountIdHash,
            matrixDeviceIdHash = matrixDeviceIdHash,
            operationId = "persisted-first-activation-0001",
        )
        val completed = operation(request).copy(
            subjects = LifecycleSubjectHashes(
                accountIdHash = accountIdHash,
                matrixDeviceIdHash = matrixDeviceIdHash,
            ),
            status = LifecycleOperationStatus.SUCCEEDED,
            currentPhase = LifecyclePhase.RECEIPTED,
            terminalReceiptId = "persisted-terminal-receipt-0001",
        )
        val operationIdStore = FakeLifecycleOperationIdStore(request.operationId)
        coEvery { service.appOperationalSnapshot(accountIdHash, matrixDeviceIdHash) } returns snapshot(
            issuedAtEpochMillis = 100L,
        )
        coEvery { service.lifecycleOperation(request.operationId) } returns completed
        val coordinator = DefaultAppOperationalStateCoordinator(service, operationIdStore)

        coordinator.refresh(accountIdHash, matrixDeviceIdHash)

        assertThat(coordinator.operations.value[request.operationId]).isEqualTo(completed)
        coVerify(exactly = 1) { service.lifecycleOperation(request.operationId) }
    }

    @Test
    fun `starting first activation persists its non-secret operation id`() = runTest {
        val service = mockk<ManagementControlService>()
        val request = lifecycleOperationRequest(
            lifecycleId = FullAppLifecycleId.LC_05,
            trigger = "first_activation",
            operationId = "persisted-first-activation-0002",
        )
        val operationIdStore = FakeLifecycleOperationIdStore()
        coEvery { service.startLifecycleOperation(request) } returns operation(request)
        val coordinator = DefaultAppOperationalStateCoordinator(service, operationIdStore)

        coordinator.start(request)

        assertThat(operationIdStore.latest(FullAppLifecycleId.LC_05)).isEqualTo(request.operationId)
    }

    @Test
    fun `older failed refresh cannot replace a newer verified snapshot`() = runTest {
        val service = mockk<ManagementControlService>()
        val firstRefreshEntered = CompletableDeferred<Unit>()
        val allowFirstRefreshToFail = CompletableDeferred<Unit>()
        val refreshCount = AtomicInteger()
        val newerSnapshot = snapshot(issuedAtEpochMillis = 200L)
        coEvery { service.appOperationalSnapshot(any(), any()) } coAnswers {
            if (refreshCount.incrementAndGet() == 1) {
                firstRefreshEntered.complete(Unit)
                allowFirstRefreshToFail.await()
                throw IllegalStateException("older refresh failed")
            }
            newerSnapshot
        }
        val coordinator = DefaultAppOperationalStateCoordinator(service)

        val olderRefresh = async { runCatching { coordinator.refresh("a".repeat(64), "b".repeat(64)) } }
        firstRefreshEntered.await()
        coordinator.refresh("a".repeat(64), "b".repeat(64))
        allowFirstRefreshToFail.complete(Unit)
        assertThat(olderRefresh.await().isFailure).isTrue()

        assertThat(coordinator.state.value)
            .isEqualTo(AppOperationalCoordinatorState.Available(newerSnapshot))
    }

    @Test
    fun `slow lifecycle start does not starve an authoritative client receipt`() = runTest {
        val service = mockk<ManagementControlService>()
        val slowRequest = lifecycleOperationRequest(
            lifecycleId = FullAppLifecycleId.LC_13,
            trigger = "receive",
            operationId = "slow-receive-operation-0001",
        )
        val receiptRequest = lifecycleOperationRequest(
            lifecycleId = FullAppLifecycleId.LC_10,
            trigger = "home_enter",
            operationId = "home-operation-0001",
        )
        val startEntered = CompletableDeferred<Unit>()
        val allowStartToFinish = CompletableDeferred<Unit>()
        coEvery { service.startLifecycleOperation(slowRequest) } coAnswers {
            startEntered.complete(Unit)
            allowStartToFinish.await()
            operation(slowRequest)
        }
        coEvery { service.recordLifecycleClientReceipt(any()) } returns operation(receiptRequest)
        val coordinator = DefaultAppOperationalStateCoordinator(service)
        val receipt = LifecycleClientReceipt(
            operationId = receiptRequest.operationId,
            component = LifecycleComponent.ANDROID_SERVICE,
            phase = LifecyclePhase.CLIENT_APPLIED,
            resultCode = "ANDROID_SERVICE_APPLIED",
            receiptReference = "android-service:${receiptRequest.operationId}",
            occurredAtEpochMillis = System.currentTimeMillis(),
        )

        val slowStart = launch { coordinator.start(slowRequest) }
        startEntered.await()
        val receiptResult = async { coordinator.recordClientReceipt(receipt) }
        yield()

        assertThat(receiptResult.isCompleted).isTrue()
        allowStartToFinish.complete(Unit)
        slowStart.join()
    }

    @Test
    fun `lost start response keeps the same operation id for resume`() = runTest {
        val service = mockk<ManagementControlService>()
        val request = LifecycleOperationRequest(
            operationId = "client-operation-0001",
            lifecycleId = FullAppLifecycleId.LC_01,
            trigger = "cold_start",
            idempotencyKey = "cold-start-request-0001",
            deadlineEpochMillis = System.currentTimeMillis() + 20_000,
        )
        val accepted = operation(request)
        coEvery { service.startLifecycleOperation(request) } throws IllegalStateException("response lost") andThen accepted
        val coordinator = DefaultAppOperationalStateCoordinator(service)

        assertThat(runCatching { coordinator.start(request) }.isFailure).isTrue()
        assertThat(coordinator.operations.value.getValue(request.operationId).status)
            .isEqualTo(LifecycleOperationStatus.RETRYABLE_FAILURE)

        assertThat(coordinator.resume(request.operationId)).isEqualTo(accepted)
        assertThat(coordinator.operations.value.keys).containsExactly(request.operationId)
        assertThat(coordinator.uiStates.value.getValue(FullAppLifecycleId.LC_01).operationId)
            .isEqualTo(request.operationId)
    }

    @Test
    fun `startup response loss retry preserves the original operation id`() = runTest {
        val service = mockk<ManagementControlService>()
        val original = lifecycleOperationRequest(
            lifecycleId = FullAppLifecycleId.LC_01,
            trigger = "cold_start",
            operationId = "startup-operation-0001",
        )
        val retry = original.copy(operationId = "startup-operation-0002", idempotencyKey = "startup-request-0002")
        val accepted = operation(original)
        coEvery { service.startLifecycleOperation(original) } throws IllegalStateException("response lost") andThen accepted
        coEvery { service.recordLifecycleClientReceipt(any()) } returns accepted
        val coordinator = DefaultAppOperationalStateCoordinator(service)

        assertThat(runCatching {
            coordinator.performResuming(original) { error("must not run after lost start response") }
        }.isFailure).isTrue()
        var resumedOperationId: String? = null
        coordinator.performResuming(retry) { resumedOperationId = it }

        assertThat(resumedOperationId).isEqualTo(original.operationId)
        coVerify(exactly = 2) { service.startLifecycleOperation(original) }
        coVerify(exactly = 0) { service.startLifecycleOperation(retry) }
    }

    @Test
    fun `expired unconfirmed process startup retry keeps identity and uses current submission deadline`() = runTest {
        val service = mockk<ManagementControlService>()
        val original = lifecycleOperationRequest(
            FullAppLifecycleId.LC_02,
            "process_create",
            operationId = "startup-expired-operation-0001",
            deadlineEpochMillis = System.currentTimeMillis() + 20_000
        )
        val retry = original.copy(
            operationId = "startup-current-operation-0002",
            idempotencyKey = "startup-current-intent-0002",
            deadlineEpochMillis = original.deadlineEpochMillis + 20_000
        )
        val expected = original.copy(deadlineEpochMillis = retry.deadlineEpochMillis)
        val accepted = operation(expected)
        coEvery { service.startLifecycleOperation(original) } throws java.io.IOException("TEST_OFFLINE")
        coEvery { service.startLifecycleOperation(expected) } returns accepted
        coEvery { service.recordLifecycleClientReceipt(any()) } returns accepted
        val coordinator = DefaultAppOperationalStateCoordinator(
            service,
            FakeLifecycleOperationIdStore(),
            pendingStartRetryClock = { original.deadlineEpochMillis + 1 }
        )
        assertThat(runCatching { coordinator.start(original) }.isFailure).isTrue()
        assertThat(coordinator.performResuming(retry) { it }).isEqualTo(original.operationId)
        coVerify(exactly = 1) { service.startLifecycleOperation(expected) }
        coVerify(exactly = 0) { service.startLifecycleOperation(retry) }
        assertThat(coordinator.operations.value.keys).containsExactly(original.operationId)
    }

    @Test
    fun `new submission deadline never replaces an already admitted server deadline`() = runTest {
        val service = mockk<ManagementControlService>()
        val original = lifecycleOperationRequest(
            FullAppLifecycleId.LC_02,
            "process_create",
            operationId = "startup-expired-operation-0003",
            deadlineEpochMillis = System.currentTimeMillis() + 20_000
        )
        val retry = original.copy(
            operationId = "startup-current-operation-0004",
            idempotencyKey = "startup-current-intent-0004",
            deadlineEpochMillis = original.deadlineEpochMillis + 20_000
        )
        val authoritative = operation(original)
        coEvery { service.startLifecycleOperation(original) } throws java.io.IOException("TEST_RESPONSE_LOST")
        coEvery { service.startLifecycleOperation(original.copy(deadlineEpochMillis = retry.deadlineEpochMillis)) } returns authoritative
        coEvery { service.recordLifecycleClientReceipt(any()) } returns authoritative
        val coordinator = DefaultAppOperationalStateCoordinator(
            service,
            FakeLifecycleOperationIdStore(),
            pendingStartRetryClock = { original.deadlineEpochMillis + 1 }
        )
        assertThat(runCatching { coordinator.start(original) }.isFailure).isTrue()
        assertThat(runCatching { coordinator.performResuming(retry) { error("expired admission must not execute") } }.isFailure).isTrue()
        coVerify(exactly = 0) { service.recordLifecycleClientReceipt(any()) }
        assertThat(coordinator.operations.value.getValue(original.operationId).deadlineEpochMillis).isEqualTo(original.deadlineEpochMillis)
        assertThat(coordinator.operations.value.getValue(original.operationId).status).isNotEqualTo(LifecycleOperationStatus.SUCCEEDED)
    }

    @Test
    fun `process retry reconciles terminal timeout and preserves original pending facts`() = runTest {
        val service = mockk<ManagementControlService>()
        val original = lifecycleOperationRequest(
            FullAppLifecycleId.LC_02,
            "process_create",
            deadlineEpochMillis = System.currentTimeMillis() + 20_000
        )
        val retry = original.copy(
            operationId = "fresh-process-operation-0002",
            idempotencyKey = "fresh-process-intent-0002",
            deadlineEpochMillis = original.deadlineEpochMillis + 60_000
        )
        val old = operation(original)
        val ended = old.copy(
            status = LifecycleOperationStatus.FAILED_FINAL,
            currentPhase = LifecyclePhase.RECEIPTED,
            terminalReceiptId = "signed-process-timeout-receipt",
            updatedAtEpochMillis = old.updatedAtEpochMillis + 1
        )
        val fresh = operation(retry)
        coEvery { service.startLifecycleOperation(original) } returns old
        coEvery { service.resumeLifecycleOperation(original.operationId) } returns ended
        coEvery { service.verifyStartupCoordinationExpiry(ended, "process_create") } returns true
        coEvery { service.startLifecycleOperation(retry) } returns fresh
        coEvery { service.recordLifecycleClientReceipt(any()) } returns fresh
        val store = FakeLifecycleOperationIdStore()
        val coordinator = DefaultAppOperationalStateCoordinator(
            service,
            store,
            pendingStartRetryClock = { original.deadlineEpochMillis + 1 }
        )
        coordinator.start(original)
        val fact = LifecycleClientReceipt(
            original.operationId,
            LifecycleComponent.ANDROID_SERVICE,
            LifecyclePhase.CLIENT_APPLIED,
            "ANDROID_SERVICE_APPLIED",
            "original-local-fact-0001",
            System.currentTimeMillis()
        )
        coordinator.persistClientReceipts(listOf(fact))
        assertThat(coordinator.performResuming(retry) { it }).isEqualTo(retry.operationId)
        assertThat(coordinator.operations.value[original.operationId]).isEqualTo(ended)
        assertThat(store.pendingClientReceipts()).containsExactly(fact)
        coVerify(exactly = 1) { service.startLifecycleOperation(retry) }
        coVerify(exactly = 0) { service.recordLifecycleClientReceipt(match { it.operationId == original.operationId }) }
    }

    @Test
    fun `process rejection or unverifiable expiry never executes startup or creates replacement`() = runTest {
        for (status in listOf(LifecycleOperationStatus.CANCELLED, LifecycleOperationStatus.FAILED_FINAL)) {
            val service = mockk<ManagementControlService>()
            val original = lifecycleOperationRequest(
                FullAppLifecycleId.LC_02,
                "process_create",
                deadlineEpochMillis = System.currentTimeMillis() + 20_000
            )
            val retry = original.copy(
                operationId = "new-rejected-process-0002",
                idempotencyKey = "new-rejected-intent-0002",
                deadlineEpochMillis = original.deadlineEpochMillis + 60_000
            )
            val old = operation(original)
            val ended = old.copy(status = status, terminalReceiptId = "rejected-original-receipt-0001")
            coEvery { service.startLifecycleOperation(original) } returns old
            coEvery { service.resumeLifecycleOperation(original.operationId) } returns ended
            coEvery { service.verifyStartupCoordinationExpiry(any(), any()) } returns false
            val coordinator = DefaultAppOperationalStateCoordinator(
                service,
                FakeLifecycleOperationIdStore(),
                pendingStartRetryClock = { original.deadlineEpochMillis + 1 }
            )
            coordinator.start(original)
            assertThat(runCatching { coordinator.performResuming(retry) { error("must remain closed") } }.isFailure).isTrue()
            coVerify(exactly = 0) { service.startLifecycleOperation(retry) }
            coVerify(exactly = 0) { service.recordLifecycleClientReceipt(any()) }
        }
    }

    @Test
    fun `startup without a new intent cannot renew an unconfirmed deadline`() = runTest {
        val service = mockk<ManagementControlService>()
        val original = lifecycleOperationRequest(
            FullAppLifecycleId.LC_02,
            "process_create",
            operationId = "startup-expired-operation-0005",
            deadlineEpochMillis = System.currentTimeMillis() + 20_000
        )
        val retry = original.copy(deadlineEpochMillis = original.deadlineEpochMillis + 20_000)
        coEvery { service.startLifecycleOperation(original) } throws java.io.IOException("TEST_OFFLINE")
        val coordinator = DefaultAppOperationalStateCoordinator(
            service,
            FakeLifecycleOperationIdStore(),
            pendingStartRetryClock = { original.deadlineEpochMillis + 1 }
        )
        assertThat(runCatching { coordinator.start(original) }.isFailure).isTrue()
        assertThat(runCatching { coordinator.performResuming(retry, hasNewStartupIntent = false) { error("must stay closed") } }.isFailure).isTrue()
        coVerify(exactly = 2) { service.startLifecycleOperation(original) }
        coVerify(exactly = 0) { service.startLifecycleOperation(retry) }
    }

    @Test
    fun `different startup subjects cannot renew the old submission deadline`() = runTest {
        val service = mockk<ManagementControlService>()
        val original = lifecycleOperationRequest(
            FullAppLifecycleId.LC_02,
            "process_create",
            operationId = "startup-expired-operation-0006",
            deadlineEpochMillis = System.currentTimeMillis() + 20_000
        )
        val retry = original.copy(
            accountIdHash = "a".repeat(64),
            matrixDeviceIdHash = "b".repeat(64),
            deadlineEpochMillis = original.deadlineEpochMillis + 20_000
        )
        coEvery { service.startLifecycleOperation(original) } throws java.io.IOException("TEST_OFFLINE")
        val coordinator = DefaultAppOperationalStateCoordinator(
            service,
            FakeLifecycleOperationIdStore(),
            pendingStartRetryClock = { original.deadlineEpochMillis + 1 }
        )
        assertThat(runCatching { coordinator.start(original) }.isFailure).isTrue()
        assertThat(runCatching { coordinator.performResuming(retry) { error("must stay closed") } }.isFailure).isTrue()
        coVerify(exactly = 2) { service.startLifecycleOperation(original) }
        coVerify(exactly = 0) { service.startLifecycleOperation(retry) }
    }

    @Test
    fun `expired unconfirmed non startup operation keeps its original request`() = runTest {
        val service = mockk<ManagementControlService>()
        val original = lifecycleOperationRequest(
            FullAppLifecycleId.LC_01,
            "cold_start",
            operationId = "cold-start-expired-operation-0007",
            deadlineEpochMillis = System.currentTimeMillis() + 20_000
        )
        val retry = original.copy(deadlineEpochMillis = original.deadlineEpochMillis + 20_000)
        coEvery { service.startLifecycleOperation(original) } throws java.io.IOException("TEST_OFFLINE")
        val coordinator = DefaultAppOperationalStateCoordinator(
            service,
            FakeLifecycleOperationIdStore(),
            pendingStartRetryClock = { original.deadlineEpochMillis + 1 }
        )
        assertThat(runCatching { coordinator.start(original) }.isFailure).isTrue()
        assertThat(runCatching { coordinator.performResuming(retry) { error("must stay closed") } }.isFailure).isTrue()
        coVerify(exactly = 2) { service.startLifecycleOperation(original) }
        coVerify(exactly = 0) { service.startLifecycleOperation(retry) }
    }

    @Test
    fun `concurrent startup callers share one newly created operation`() = runTest {
        val service = mockk<ManagementControlService>()
        val first = lifecycleOperationRequest(
            lifecycleId = FullAppLifecycleId.LC_02,
            trigger = "process_create",
            operationId = "startup-operation-0011",
        )
        val second = first.copy(operationId = "startup-operation-0012", idempotencyKey = "startup-request-0012")
        val startEntered = CompletableDeferred<Unit>()
        val allowStart = CompletableDeferred<Unit>()
        val accepted = operation(first)
        coEvery { service.startLifecycleOperation(any()) } coAnswers {
            startEntered.complete(Unit)
            allowStart.await()
            accepted
        }
        coEvery { service.recordLifecycleClientReceipt(any()) } returns accepted
        val coordinator = DefaultAppOperationalStateCoordinator(service)

        val firstCall = async { coordinator.performResuming(first) { it } }
        startEntered.await()
        val secondCall = async { coordinator.performResuming(second) { it } }
        allowStart.complete(Unit)

        val operationIds = awaitAll(firstCall, secondCall)
        assertThat(operationIds.toSet()).containsExactly(first.operationId)
        coVerify(atLeast = 1) { service.startLifecycleOperation(first) }
        coVerify(exactly = 0) { service.startLifecycleOperation(second) }
    }

    @Test
    fun `durable non terminal startup recovery preserves its operation id`() = runTest {
        val service = mockk<ManagementControlService>()
        val durable = lifecycleOperationRequest(
            lifecycleId = FullAppLifecycleId.LC_02,
            trigger = "process_create",
            operationId = "startup-operation-0021",
        )
        val newCaller = durable.copy(operationId = "startup-operation-0022", idempotencyKey = "startup-request-0022")
        val recovered = operation(durable)
        coEvery { service.appOperationalSnapshot(any(), any()) } returns snapshot(
            issuedAtEpochMillis = 100L,
            activeOperationIds = listOf(durable.operationId),
        )
        coEvery { service.lifecycleOperation(durable.operationId) } returns recovered
        coEvery { service.resumeLifecycleOperation(durable.operationId) } returns recovered
        coEvery { service.recordLifecycleClientReceipt(any()) } returns recovered
        val coordinator = DefaultAppOperationalStateCoordinator(service)
        coordinator.refresh("a".repeat(64), "b".repeat(64))

        var resumedOperationId: String? = null
        coordinator.performResuming(newCaller) { resumedOperationId = it }

        assertThat(resumedOperationId).isEqualTo(durable.operationId)
        coVerify(exactly = 1) { service.resumeLifecycleOperation(durable.operationId) }
        coVerify(exactly = 0) { service.startLifecycleOperation(any()) }
    }

    @Test
    fun `new startup operation requires terminal predecessor and explicit new intent`() = runTest {
        val service = mockk<ManagementControlService>()
        val terminalRequest = lifecycleOperationRequest(
            lifecycleId = FullAppLifecycleId.LC_02,
            trigger = "process_create",
            operationId = "startup-operation-0031",
        )
        val terminal = operation(terminalRequest).copy(
            status = LifecycleOperationStatus.SUCCEEDED,
            currentPhase = LifecyclePhase.RECEIPTED,
            terminalReceiptId = "terminal-receipt-0031",
        )
        val newRequest = terminalRequest.copy(
            operationId = "startup-operation-0032",
            idempotencyKey = "startup-request-0032",
        )
        val newOperation = operation(newRequest)
        coEvery { service.startLifecycleOperation(terminalRequest) } returns terminal
        coEvery { service.startLifecycleOperation(newRequest) } returns newOperation
        coEvery { service.recordLifecycleClientReceipt(any()) } returns newOperation
        val coordinator = DefaultAppOperationalStateCoordinator(service)
        coordinator.start(terminalRequest)

        assertThat(runCatching {
            coordinator.performResuming(newRequest, hasNewStartupIntent = false) { error("must not create an operation") }
        }.isFailure).isTrue()
        var newOperationId: String? = null
        coordinator.performResuming(newRequest, hasNewStartupIntent = true) { newOperationId = it }

        assertThat(newOperationId).isEqualTo(newRequest.operationId)
        coVerify(exactly = 1) { service.startLifecycleOperation(terminalRequest) }
        coVerify(exactly = 1) { service.startLifecycleOperation(newRequest) }
    }

    @Test
    fun `authoritative idempotent operation replaces a provisional client id`() = runTest {
        val service = mockk<ManagementControlService>()
        val request = LifecycleOperationRequest(
            operationId = "client-operation-0002",
            lifecycleId = FullAppLifecycleId.LC_23,
            trigger = "diagnostics_refresh",
            idempotencyKey = "diagnostics-request-0002",
            deadlineEpochMillis = System.currentTimeMillis() + 20_000,
        )
        val accepted = operation(request).copy(operationId = "server-operation-0002")
        coEvery { service.startLifecycleOperation(request) } returns accepted
        val coordinator = DefaultAppOperationalStateCoordinator(service)

        coordinator.start(request)

        assertThat(coordinator.operations.value.keys).containsExactly(accepted.operationId)
        assertThat(coordinator.uiStates.value.getValue(FullAppLifecycleId.LC_23).operationId)
            .isEqualTo(accepted.operationId)
    }

    @Test
    fun `perform retains a failed client receipt and refresh retries it after connectivity returns`() = runTest {
        val service = mockk<ManagementControlService>()
        val request = lifecycleOperationRequest(
            lifecycleId = FullAppLifecycleId.LC_18,
            trigger = "network_lost",
            accountIdHash = "a".repeat(64),
            matrixDeviceIdHash = "b".repeat(64),
            operationId = "network-operation-0001",
        )
        val accepted = operation(request)
        val current = System.currentTimeMillis()
        val snapshot = AppOperationalSnapshot(
            accountIdHash = "a".repeat(64),
            installationId = "installation-test-0001",
            matrixDeviceIdHash = "b".repeat(64),
            issuedAtEpochMillis = current,
            expiresAtEpochMillis = current + 60_000,
            overallStatus = AppOperationalStatus.READY,
            components = REQUIRED_OPERATIONAL_COMPONENTS.associateWith { component ->
                ComponentOperationalState(
                    status = AppOperationalStatus.READY,
                    authority = component,
                    authorityRevision = "test-revision",
                    observedAtEpochMillis = current,
                    expiresAtEpochMillis = current + 60_000,
                    receiptReference = "test-receipt:$component",
                )
            },
            activeOperationIds = listOf(request.operationId),
            signingKeyId = "test-signing-key",
            signatureBase64 = "test-signature-value",
        )
        coEvery { service.startLifecycleOperation(request) } returns accepted
        coEvery {
            service.recordLifecycleClientReceipt(match { it.component == LifecycleComponent.ANDROID_SERVICE })
        } throws IllegalStateException("offline") andThen accepted
        coEvery {
            service.recordLifecycleClientReceipt(match { it.component == LifecycleComponent.ANDROID_FRONTEND })
        } returns accepted
        coEvery { service.appOperationalSnapshot(any(), any()) } returns snapshot
        coEvery { service.lifecycleOperation(request.operationId) } returns accepted
        val coordinator = DefaultAppOperationalStateCoordinator(service)

        assertThat(runCatching { coordinator.perform(request) { Unit } }.isFailure).isTrue()
        coVerify(exactly = 1) {
            service.recordLifecycleClientReceipt(match { it.component == LifecycleComponent.ANDROID_FRONTEND })
        }

        coordinator.refresh("a".repeat(64), "b".repeat(64))

        coVerify(exactly = 2) {
            service.recordLifecycleClientReceipt(match { it.component == LifecycleComponent.ANDROID_SERVICE })
        }
    }

    @Test
    fun `perform completes both Android receipts after caller cancellation once product action completed`() = runTest {
        val service = mockk<ManagementControlService>()
        val request = lifecycleOperationRequest(
            lifecycleId = FullAppLifecycleId.LC_24,
            trigger = "font_scale_change",
            operationId = "configuration-operation-0001",
        )
        val accepted = operation(request)
        val serviceReceiptStarted = CompletableDeferred<Unit>()
        val allowServiceReceiptToFinish = CompletableDeferred<Unit>()
        coEvery { service.startLifecycleOperation(request) } returns accepted
        coEvery {
            service.recordLifecycleClientReceipt(match { it.component == LifecycleComponent.ANDROID_SERVICE })
        } coAnswers {
            serviceReceiptStarted.complete(Unit)
            allowServiceReceiptToFinish.await()
            accepted
        }
        coEvery {
            service.recordLifecycleClientReceipt(match { it.component == LifecycleComponent.ANDROID_FRONTEND })
        } returns accepted
        val coordinator = DefaultAppOperationalStateCoordinator(service)

        val job = launch { coordinator.perform(request) { Unit } }
        serviceReceiptStarted.await()
        job.cancel()
        allowServiceReceiptToFinish.complete(Unit)
        job.join()

        coVerify(exactly = 1) {
            service.recordLifecycleClientReceipt(match { it.component == LifecycleComponent.ANDROID_SERVICE })
        }
        coVerify(exactly = 1) {
            service.recordLifecycleClientReceipt(match { it.component == LifecycleComponent.ANDROID_FRONTEND })
        }
    }

    @Test
    fun `perform does not publish observer receipt after required receipt terminalizes operation`() = runTest {
        val service = mockk<ManagementControlService>()
        val request = lifecycleOperationRequest(
            lifecycleId = FullAppLifecycleId.LC_07,
            trigger = "heartbeat",
            operationId = "heartbeat-operation-0001",
        )
        val accepted = operation(request)
        val completed = accepted.copy(
            status = LifecycleOperationStatus.SUCCEEDED,
            currentPhase = LifecyclePhase.RECEIPTED,
            terminalReceiptId = "heartbeat-terminal-receipt",
        )
        coEvery { service.startLifecycleOperation(request) } returns accepted
        coEvery {
            service.recordLifecycleClientReceipt(match { it.component == LifecycleComponent.ANDROID_SERVICE })
        } returns completed
        val coordinator = DefaultAppOperationalStateCoordinator(service)

        coordinator.perform(request) { Unit }

        coVerify(exactly = 1) {
            service.recordLifecycleClientReceipt(match { it.component == LifecycleComponent.ANDROID_SERVICE })
        }
        coVerify(exactly = 0) {
            service.recordLifecycleClientReceipt(match { it.component == LifecycleComponent.ANDROID_FRONTEND })
        }
    }

    @Test
    fun `startup parallel receipts are durable together and product waits for slower reply`() = runTest {
        val request = lifecycleOperationRequest(FullAppLifecycleId.LC_06, "reactivation")
        val accepted = startupOperation(request)
        val service = mockk<ManagementControlService>()
        val store = FakeLifecycleOperationIdStore()
        coEvery { service.startLifecycleOperation(request) } returns accepted
        coEvery { service.recordLifecycleClientReceipt(any()) } coAnswers {
            assertThat(store.pendingClientReceipts()).hasSize(2)
            val receipt = firstArg<LifecycleClientReceipt>()
            kotlinx.coroutines.delay(if (receipt.component == LifecycleComponent.ANDROID_SERVICE) 200 else 300)
            accepted
        }
        val coordinator = DefaultAppOperationalStateCoordinator(service, store)
        val before = testScheduler.currentTime
        assertThat(coordinator.perform(request) { "fully-initialized" }).isEqualTo("fully-initialized")
        assertThat(testScheduler.currentTime - before).isEqualTo(300L)
        assertThat(store.pendingClientReceipts()).isEmpty()
        coVerify(exactly = 2) { service.recordLifecycleClientReceipt(any()) }
    }

    @Test
    fun `startup receipt failure still waits for other real receipt and retains failed original fact`() = runTest {
        val request = lifecycleOperationRequest(FullAppLifecycleId.LC_06, "reactivation")
        val accepted = startupOperation(request)
        val service = mockk<ManagementControlService>()
        val store = FakeLifecycleOperationIdStore()
        val release = CompletableDeferred<Unit>()
        coEvery { service.startLifecycleOperation(request) } returns accepted
        coEvery { service.recordLifecycleClientReceipt(any()) } coAnswers {
            if (firstArg<LifecycleClientReceipt>().component == LifecycleComponent.ANDROID_SERVICE) {
                throw java.io.IOException("receipt response lost")
            }
            release.await()
            accepted
        }
        val coordinator = DefaultAppOperationalStateCoordinator(service, store)
        val result = async { runCatching { coordinator.perform(request) { Unit } } }
        runCurrent()
        assertThat(result.isCompleted).isFalse()
        assertThat(store.pendingClientReceipts()).hasSize(2)
        release.complete(Unit)
        assertThat(result.await().isFailure).isTrue()
        assertThat(store.pendingClientReceipts().single().component).isEqualTo(LifecycleComponent.ANDROID_SERVICE)
        coVerify(exactly = 2) { service.recordLifecycleClientReceipt(any()) }
    }

    @Test
    fun `startup caller cancellation after product completion still waits for both receipts`() = runTest {
        val request = lifecycleOperationRequest(FullAppLifecycleId.LC_02, "process_create")
        val accepted = startupOperation(request)
        val service = mockk<ManagementControlService>()
        val store = FakeLifecycleOperationIdStore()
        val release = CompletableDeferred<Unit>()
        coEvery { service.startLifecycleOperation(request) } returns accepted
        coEvery { service.recordLifecycleClientReceipt(any()) } coAnswers {
            release.await()
            accepted
        }
        val coordinator = DefaultAppOperationalStateCoordinator(service, store)
        val job = launch { coordinator.perform(request) { Unit } }
        runCurrent()
        job.cancel()
        assertThat(job.isCompleted).isFalse()
        coVerify(exactly = 2) { service.recordLifecycleClientReceipt(any()) }
        release.complete(Unit)
        job.join()
        assertThat(store.pendingClientReceipts()).isEmpty()
    }

    @Test
    fun `identical overlapping receipt callers share one transport and acknowledgement`() = runTest {
        val request = lifecycleOperationRequest(FullAppLifecycleId.LC_02, "foreground")
        val accepted = operation(request)
        val service = mockk<ManagementControlService>()
        val store = FakeLifecycleOperationIdStore()
        val release = CompletableDeferred<Unit>()
        val receipt = LifecycleClientReceipt(
            request.operationId,
            LifecycleComponent.ANDROID_SERVICE,
            LifecyclePhase.CLIENT_APPLIED,
            "ANDROID_SERVICE_APPLIED",
            "android-service:${request.operationId}",
            123L
        )
        coEvery { service.recordLifecycleClientReceipt(receipt) } coAnswers {
            release.await()
            accepted
        }
        val coordinator = DefaultAppOperationalStateCoordinator(service, store)
        val first = async { coordinator.recordClientReceipt(receipt) }
        val second = async { coordinator.recordClientReceipt(receipt) }
        runCurrent()
        coVerify(exactly = 1) { service.recordLifecycleClientReceipt(receipt) }
        release.complete(Unit)
        assertThat(first.await()).isEqualTo(second.await())
        assertThat(store.pendingClientReceipts()).isEmpty()
    }

    @Test
    fun `reverse startup receipt replies cannot replace signed terminal result with old running state`() = runTest {
        val request = lifecycleOperationRequest(FullAppLifecycleId.LC_06, "reactivation")
        val accepted = startupOperation(request)
        val service = mockk<ManagementControlService>()
        val store = FakeLifecycleOperationIdStore()
        val releaseOld = CompletableDeferred<Unit>()
        val completeSteps = accepted.steps.map { step ->
            step.copy(
            status = io.element.android.security.dataprotection.api.LifecycleStepStatus.COMPLETE,
            resultCode = "${step.component.name}_APPLIED",
            receiptReference = "${step.component.name.lowercase().replace('_', '-')}:${request.operationId}",
        )
        }
        val completed = accepted.copy(
            status = LifecycleOperationStatus.SUCCEEDED,
            currentPhase = LifecyclePhase.RECEIPTED,
            terminalReceiptId = "signed-terminal-receipt",
            steps = completeSteps,
            updatedAtEpochMillis = accepted.updatedAtEpochMillis + 10
        )
        coEvery { service.startLifecycleOperation(request) } returns accepted
        coEvery { service.recordLifecycleClientReceipt(any()) } coAnswers {
            if (firstArg<LifecycleClientReceipt>().component == LifecycleComponent.ANDROID_SERVICE) {
                releaseOld.await()
                accepted.copy(steps = completeSteps.take(1) + accepted.steps.drop(1))
            } else {
                completed
            }
        }
        val coordinator = DefaultAppOperationalStateCoordinator(service, store)
        val result = async { coordinator.perform(request) { Unit } }
        runCurrent()
        assertThat(result.isCompleted).isFalse()
        try {
            assertThat(coordinator.operations.value[request.operationId]).isEqualTo(completed)
        } finally {
            releaseOld.complete(Unit)
        }
        result.await()
        assertThat(coordinator.operations.value[request.operationId]).isEqualTo(completed)
        assertThat(store.pendingClientReceipts()).isEmpty()
    }

    @Test
    fun `duplicate transport failure does not erase durable receipt and retry can submit original fact`() = runTest {
        val request = lifecycleOperationRequest(FullAppLifecycleId.LC_02, "foreground")
        val accepted = operation(request)
        val service = mockk<ManagementControlService>()
        val store = FakeLifecycleOperationIdStore()
        val release = CompletableDeferred<Unit>()
        val receipt = LifecycleClientReceipt(
            request.operationId,
            LifecycleComponent.ANDROID_SERVICE,
            LifecyclePhase.CLIENT_APPLIED,
            "ANDROID_SERVICE_APPLIED",
            "android-service:${request.operationId}",
            123L
        )
        coEvery { service.recordLifecycleClientReceipt(receipt) } coAnswers {
            release.await()
            throw java.io.IOException("transport lost")
        }
        val coordinator = DefaultAppOperationalStateCoordinator(service, store)
        val first = async { runCatching { coordinator.recordClientReceipt(receipt) } }
        val second = async { runCatching { coordinator.recordClientReceipt(receipt) } }
        runCurrent()
        coVerify(exactly = 1) { service.recordLifecycleClientReceipt(receipt) }
        release.complete(Unit)
        assertThat(first.await().isFailure).isTrue()
        assertThat(second.await().isFailure).isTrue()
        assertThat(store.pendingClientReceipts()).containsExactly(receipt)
        coEvery { service.recordLifecycleClientReceipt(receipt) } returns accepted
        coordinator.recordClientReceipt(receipt)
        assertThat(store.pendingClientReceipts()).isEmpty()
        coVerify(exactly = 2) { service.recordLifecycleClientReceipt(receipt) }
    }

    private fun startupOperation(request: LifecycleOperationRequest): LifecycleOperation = operation(request).copy(
        steps = listOf(LifecycleComponent.ANDROID_SERVICE, LifecycleComponent.ANDROID_FRONTEND).map { component ->
            io.element.android.security.dataprotection.api.LifecycleStep(
                stepId = "${component.name}:${request.operationId}",
                component = component,
                status = io.element.android.security.dataprotection.api.LifecycleStepStatus.PENDING,
                attemptCount = 0,
                updatedAtEpochMillis = System.currentTimeMillis(),
            )
        }
    )

    private fun operation(request: LifecycleOperationRequest): LifecycleOperation {
        val current = System.currentTimeMillis()
        return LifecycleOperation(
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
            createdAtEpochMillis = current,
            updatedAtEpochMillis = current,
        )
    }

    private fun snapshot(issuedAtEpochMillis: Long, activeOperationIds: List<String> = emptyList()): AppOperationalSnapshot {
        return AppOperationalSnapshot(
            accountIdHash = "a".repeat(64),
            installationId = "installation-test-0001",
            matrixDeviceIdHash = "b".repeat(64),
            issuedAtEpochMillis = issuedAtEpochMillis,
            expiresAtEpochMillis = issuedAtEpochMillis + 60_000,
            overallStatus = AppOperationalStatus.READY,
            components = REQUIRED_OPERATIONAL_COMPONENTS.associateWith { component ->
                ComponentOperationalState(
                    status = AppOperationalStatus.READY,
                    authority = component,
                    authorityRevision = "test-revision",
                    observedAtEpochMillis = issuedAtEpochMillis,
                    expiresAtEpochMillis = issuedAtEpochMillis + 60_000,
                    receiptReference = "test-receipt:$component",
                )
            },
            activeOperationIds = activeOperationIds,
            signingKeyId = "test-signing-key",
            signatureBase64 = "test-signature-value",
        )
    }

    private class FakeLifecycleOperationIdStore(
        initialFirstActivationId: String? = null,
    ) : LifecycleOperationIdStore {
        private val operationIds = mutableMapOf<FullAppLifecycleId, String>()
        private var receipts = emptyList<LifecycleClientReceipt>()

        init {
            initialFirstActivationId?.let { operationIds[FullAppLifecycleId.LC_05] = it }
        }

        override fun latest(lifecycleId: FullAppLifecycleId): String? = operationIds[lifecycleId]

        override fun pendingClientReceipts(): List<LifecycleClientReceipt> = receipts.toList()

        override fun savePendingClientReceipts(receipts: List<LifecycleClientReceipt>) {
            this.receipts = receipts.toList()
        }

        override fun remember(operation: LifecycleOperation) {
            if (operation.lifecycleId == FullAppLifecycleId.LC_05) {
                operationIds[operation.lifecycleId] = operation.operationId
            }
        }
    }
}
