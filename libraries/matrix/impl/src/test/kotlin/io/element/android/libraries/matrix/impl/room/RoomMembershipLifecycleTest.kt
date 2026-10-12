/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.room

import com.google.common.truth.Truth.assertThat
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleComponent
import io.element.android.security.dataprotection.api.LifecycleOperation
import io.element.android.security.dataprotection.api.LifecycleOperationStatus
import io.element.android.security.dataprotection.api.LifecyclePhase
import io.element.android.security.dataprotection.api.LifecycleStep
import io.element.android.security.dataprotection.api.LifecycleStepStatus
import io.element.android.security.dataprotection.api.LifecycleSubjectHashes
import io.element.android.security.dataprotection.api.ManagementControlService
import io.element.android.security.dataprotection.api.RoomMembershipIntent
import io.element.android.security.securestorage.api.accountIdHash
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RoomMembershipLifecycleTest {
    private val actor = "@actor:matrix.test"
    private val device = "DEVICE"
    private val room = "!room:matrix.test"
    private val target = "@target:matrix.test"
    private val management = mockk<ManagementControlService>()
    private val coordinator = mockk<AppOperationalStateCoordinator>(relaxed = true)
    private val operation = LifecycleOperation(
        operationId = "member-original-operation", lifecycleId = FullAppLifecycleId.LC_12,
        idempotencyKey = "original-idempotency-key",
        subjects = LifecycleSubjectHashes(accountIdHash(actor), matrixDeviceIdHash = accountIdHash(device), roomIdHash = accountIdHash(room)),
        status = LifecycleOperationStatus.WAITING_EXTERNAL, currentPhase = LifecyclePhase.PROJECTED,
        authorityRevisions = mapOf("membership_intent_v1" to """{"scope":{"actor":"$actor","matrix_device":"$device","room":"$room","target":"$target","action":"kick"}}"""),
        steps = listOf(LifecycleComponent.MATRIX, LifecycleComponent.KMS, LifecycleComponent.CONTROL_PLANE, LifecycleComponent.DATABASE).map {
            LifecycleStep("step-${it.name}", it, status = LifecycleStepStatus.COMPLETE, attemptCount = 1, receiptReference = "verified-${it.name}", updatedAtEpochMillis = 1)
        },
        deadlineEpochMillis = Long.MAX_VALUE, createdAtEpochMillis = 1, updatedAtEpochMillis = 1,
    )

    init {
        coEvery { management.prepareRoomMembership(any(), any(), any(), any(), any(), any()) } returns RoomMembershipIntent(operation.operationId, false)
        coEvery { coordinator.operation(operation.operationId) } returns operation
        coEvery { coordinator.recordClientReceipt(any()) } returns operation
    }

    private fun helper(projected: suspend (String, String, String) -> Boolean) =
        RoomMembershipLifecycle(actor, device, management, coordinator, projected, enabled = true)

    @Test
    fun `authority receipt alone cannot acknowledge an unsynchronized client`() = runTest {
        var projected = false
        var nativeCalls = 0
        val job = launch { helper { _, _, _ -> projected }.perform(room, target, "kick", { nativeCalls++ }) }
        runCurrent()
        advanceTimeBy(1_000)
        coVerify(exactly = 0) { coordinator.persistClientReceipts(any()) }
        projected = true
        advanceTimeBy(250)
        runCurrent()
        job.join()
        assertThat(nativeCalls).isEqualTo(0)
        coVerify(exactly = 1) { coordinator.persistClientReceipts(match { receipts -> receipts.size == 2 && receipts.all { it.operationId == operation.operationId } }) }
        coVerify(exactly = 0) { coordinator.start(any()) }
    }

    @Test
    fun `recreated helper resumes original operation without issuing another kick`() = runTest {
        val failure = runCatching { helper { _, _, _ -> false }.perform(room, target, "kick", { error("duplicate mutation") }) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(TimeoutCancellationException::class.java)
        coVerify(exactly = 0) { coordinator.persistClientReceipts(any()) }
        helper { _, _, _ -> true }.recover(operation)
        coVerify(exactly = 1) { management.prepareRoomMembership(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { coordinator.persistClientReceipts(any()) }
        coVerify(exactly = 0) { coordinator.start(any()) }
    }

    @Test
    fun `local projection cannot substitute for missing KMS authority`() = runTest {
        coEvery { coordinator.operation(operation.operationId) } returns operation.copy(steps = operation.steps.filter { it.component != LifecycleComponent.KMS })
        val failure = runCatching { helper { _, _, _ -> true }.recover(operation) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(TimeoutCancellationException::class.java)
        coVerify(exactly = 0) { coordinator.persistClientReceipts(any()) }
    }

    @Test
    fun `recovery rejects another devices operation`() = runTest {
        val foreign = operation.copy(subjects = operation.subjects.copy(matrixDeviceIdHash = accountIdHash("OTHER")))
        assertThat(runCatching { helper { _, _, _ -> true }.recover(foreign) }.isFailure).isTrue()
        coVerify(exactly = 0) { coordinator.persistClientReceipts(any()) }
    }
}
