/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.appnav

import androidx.lifecycle.LifecycleOwner
import com.google.common.truth.Truth.assertThat
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleClientReceipt
import io.element.android.security.dataprotection.api.LifecycleComponent
import io.element.android.security.dataprotection.api.LifecycleOperation
import io.element.android.security.dataprotection.api.LifecycleOperationRequest
import io.element.android.security.dataprotection.api.LifecycleOperationStatus
import io.element.android.security.dataprotection.api.LifecyclePhase
import io.element.android.security.dataprotection.api.LifecycleSubjectHashes
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LoggedInBackgroundLifecycleObserverTest {
    private val owner = mockk<LifecycleOwner>()
    private val requests = mutableListOf<LifecycleOperationRequest>()
    private val receipts = mutableListOf<LifecycleClientReceipt>()
    private val coordinator = mockk<AppOperationalStateCoordinator>().apply {
        every { operations } returns MutableStateFlow(emptyMap())
        coEvery { start(any()) } answers {
            val request = firstArg<LifecycleOperationRequest>()
            requests += request
            operation(request)
        }
        coEvery { persistClientReceipts(any()) } returns Unit
        coEvery { recordClientReceipt(any()) } answers {
            val receipt = firstArg<LifecycleClientReceipt>()
            receipts += receipt
            operation(requests.single { it.operationId == receipt.operationId })
        }
    }

    @Test
    fun `three real departures have separate account bound identities and both Android receipts`() = runTest {
        val observer = observer(backgroundScope)
        repeat(3) {
            observer.onStart(owner)
            observer.onStop(owner)
            runCurrent()
        }
        assertThat(requests).hasSize(3)
        assertThat(requests.map { it.operationId }.toSet()).hasSize(3)
        requests.forEach { request ->
            assertThat(request.lifecycleId).isEqualTo(FullAppLifecycleId.LC_02)
            assertThat(request.trigger).isEqualTo("background")
            assertThat(request.accountIdHash).isEqualTo("a".repeat(64))
            assertThat(request.matrixDeviceIdHash).isEqualTo("b".repeat(64))
            assertThat(request.idempotencyKey).isEqualTo(request.operationId)
            assertThat(receipts.filter { it.operationId == request.operationId }.map { it.component })
                .containsExactly(LifecycleComponent.ANDROID_SERVICE, LifecycleComponent.ANDROID_FRONTEND)
        }
        coVerify(exactly = 0) { coordinator.refresh(any(), any()) }
        coVerify(exactly = 0) { coordinator.performResuming<Unit>(any(), any(), any()) }
    }

    @Test
    fun `initial stopped state and duplicate stops do not manufacture operations`() = runTest {
        val observer = observer(backgroundScope)
        observer.onStop(owner)
        runCurrent()
        assertThat(requests).isEmpty()
        observer.onStart(owner)
        observer.onStart(owner)
        observer.onStop(owner)
        observer.onStop(owner)
        runCurrent()
        assertThat(requests).hasSize(1)
    }

    @Test
    fun `resume during delayed admission does not cancel the original background operation`() = runTest {
        val admitted = CompletableDeferred<Unit>()
        coEvery { coordinator.start(any()) } coAnswers {
            val request = firstArg<LifecycleOperationRequest>()
            requests += request
            admitted.await()
            operation(request)
        }
        val observer = observer(backgroundScope)
        observer.onStart(owner)
        observer.onStop(owner)
        runCurrent()
        val original = requests.single().operationId
        observer.onStart(owner)
        assertThat(receipts).isEmpty()
        admitted.complete(Unit)
        runCurrent()
        assertThat(receipts.map { it.operationId }).containsExactly(original, original)
        assertThat(requests).hasSize(1)
    }

    @Test
    fun `failed admission emits no success receipts and a later real departure remains independent`() = runTest {
        var failed = true
        coEvery { coordinator.start(any()) } answers {
            val request = firstArg<LifecycleOperationRequest>()
            requests += request
            if (failed) error("CONTROL_CONNECTION_FAILED")
            operation(request)
        }
        val observer = observer(backgroundScope)
        observer.onStart(owner)
        observer.onStop(owner)
        runCurrent()
        assertThat(receipts).isEmpty()
        failed = false
        observer.onStart(owner)
        observer.onStop(owner)
        runCurrent()
        assertThat(requests).hasSize(2)
        assertThat(receipts.map { it.operationId }.toSet()).containsExactly(requests[1].operationId)
    }

    private fun observer(scope: kotlinx.coroutines.CoroutineScope) = LoggedInBackgroundLifecycleObserver(
        scope, coordinator, "a".repeat(64), "b".repeat(64),
    )

    private fun operation(request: LifecycleOperationRequest) = LifecycleOperation(
        operationId = request.operationId,
        lifecycleId = request.lifecycleId,
        idempotencyKey = request.idempotencyKey,
        subjects = LifecycleSubjectHashes(accountIdHash = request.accountIdHash, matrixDeviceIdHash = request.matrixDeviceIdHash),
        status = LifecycleOperationStatus.RUNNING,
        currentPhase = LifecyclePhase.INTENT_PERSISTED,
        deadlineEpochMillis = request.deadlineEpochMillis,
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
    )
}
