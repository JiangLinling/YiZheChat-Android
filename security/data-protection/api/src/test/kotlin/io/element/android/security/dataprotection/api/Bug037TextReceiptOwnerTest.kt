/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.dataprotection.api

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import org.junit.Test

class Bug037TextReceiptOwnerTest {
    @Test fun `delegated TEXT completion submits frontend fact and leaves service to durable lineage`() = runTest {
        val (coordinator, request, receipts) = setup(FullAppLifecycleId.LC_13)
        val value = coordinator.performMessageWithDeferredReceipts(request) { id ->
            val owner = requireNotNull(currentCoroutineContext()[TextVersionReceiptOwner])
            assertThat(owner.operationId).isEqualTo(id)
            assertThat(owner.roomIdHash).isEqualTo(request.roomIdHash)
            owner.delegated = true
            "applied"
        }
        assertThat(value).isEqualTo("applied")
        assertThat(receipts.map { it.component }).containsExactly(LifecycleComponent.ANDROID_FRONTEND)
    }

    @Test fun `nondelegated LC13 action retains both Android receipt facts`() = runTest {
        val (coordinator, request, receipts) = setup(FullAppLifecycleId.LC_13)
        coordinator.performMessageWithDeferredReceipts(request) { "applied" }
        assertThat(receipts.map { it.component }).containsExactly(LifecycleComponent.ANDROID_SERVICE, LifecycleComponent.ANDROID_FRONTEND)
    }

    @Test fun `failed TEXT protection cannot produce either completion fact`() = runTest {
        val (coordinator, request, receipts) = setup(FullAppLifecycleId.LC_13)
        val failure = runCatching {
            coordinator.performMessageWithDeferredReceipts(request) {
                requireNotNull(currentCoroutineContext()[TextVersionReceiptOwner]).delegated = true
                error("KMS outcome unknown")
            }
        }
        assertThat(failure.isFailure).isTrue()
        assertThat(receipts).isEmpty()
    }

    @Test fun `private LC25 action retains its existing independent receipt behavior`() = runTest {
        val (coordinator, request, receipts) = setup(FullAppLifecycleId.LC_25)
        coordinator.performPrivateWithDeferredReceipts(request) {
            assertThat(currentCoroutineContext()[TextVersionReceiptOwner]).isNull()
        }
        assertThat(receipts.map { it.component }).containsExactly(LifecycleComponent.ANDROID_SERVICE, LifecycleComponent.ANDROID_FRONTEND)
    }

    private fun setup(id: FullAppLifecycleId): Triple<AppOperationalStateCoordinator, LifecycleOperationRequest, MutableList<LifecycleClientReceipt>> {
        val request = lifecycleOperationRequest(id, if (id == FullAppLifecycleId.LC_25) "private_edit" else "send", accountIdHash = "a".repeat(64),
            matrixDeviceIdHash = "b".repeat(64), roomIdHash = "c".repeat(64))
        val coordinator = mockk<AppOperationalStateCoordinator>()
        val receipts = mutableListOf<LifecycleClientReceipt>()
        val now = System.currentTimeMillis()
        coEvery { coordinator.start(request) } returns LifecycleOperation(
            operationId = request.operationId, lifecycleId = id, idempotencyKey = request.operationId,
            subjects = LifecycleSubjectHashes(accountIdHash = request.accountIdHash, roomIdHash = request.roomIdHash),
            status = LifecycleOperationStatus.RUNNING, currentPhase = LifecyclePhase.INTENT_PERSISTED,
            deadlineEpochMillis = now + 600_000, createdAtEpochMillis = now, updatedAtEpochMillis = now)
        coEvery { coordinator.enqueueClientReceipts(any()) } answers { receipts.addAll(firstArg<List<LifecycleClientReceipt>>()); Unit }
        return Triple(coordinator, request, receipts)
    }
}
