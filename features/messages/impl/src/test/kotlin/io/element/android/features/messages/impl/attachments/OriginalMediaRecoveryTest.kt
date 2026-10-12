/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.messages.impl.attachments

import com.google.common.truth.Truth.assertThat
import io.element.android.features.messages.impl.attachments.preview.inspectOriginalMediaOperation
import io.element.android.security.dataprotection.api.*
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class OriginalMediaRecoveryTest {
    private val id = "media-original-operation-0123456789"
    private val account = "a".repeat(64)
    private val device = "b".repeat(64)
    private val room = "c".repeat(64)
    private fun operation() = LifecycleOperation(
        operationId = id, lifecycleId = FullAppLifecycleId.LC_16, idempotencyKey = id,
        subjects = LifecycleSubjectHashes(accountIdHash = account, matrixDeviceIdHash = device, roomIdHash = room),
        status = LifecycleOperationStatus.RUNNING, currentPhase = LifecyclePhase.PROJECTED,
        deadlineEpochMillis = 2000, createdAtEpochMillis = 1000, updatedAtEpochMillis = 1500,
    )

    @Test
    fun `expired running operation is only a projection and never authorizes resubmission`() = runTest {
        val coordinator = mockk<AppOperationalStateCoordinator>(relaxed = true)
        coEvery { coordinator.operation(id) } returns operation()
        val result = inspectOriginalMediaOperation(coordinator, id, account, device, room, false)!!
        assertThat(result.status).isEqualTo("RUNNING")
        assertThat(result.deadlinePassed).isTrue()
        assertThat(result.terminal).isFalse()
        assertThat(result.terminalReceiptPresent).isFalse()
        coVerify(exactly = 1) { coordinator.operation(id) }
        coVerify(exactly = 0) { coordinator.start(any()) }
        coVerify(exactly = 0) { coordinator.resume(any()) }
        coVerify(exactly = 0) { coordinator.recordClientReceipt(any()) }
    }

    @Test
    fun `foreign identity room device and lifecycle cannot be displayed as original task facts`() = runTest {
        val original = operation()
        val invalid = listOf(original.copy(operationId = "media-another-operation-0123456789"),
            original.copy(lifecycleId = FullAppLifecycleId.LC_15),
            original.copy(subjects = original.subjects.copy(accountIdHash = "d".repeat(64))),
            original.copy(subjects = original.subjects.copy(matrixDeviceIdHash = "d".repeat(64))),
            original.copy(subjects = original.subjects.copy(roomIdHash = "d".repeat(64))))
        invalid.forEach { wrong ->
            val coordinator = mockk<AppOperationalStateCoordinator>(relaxed = true)
            coEvery { coordinator.operation(id) } returns wrong
            assertThat(inspectOriginalMediaOperation(coordinator, id, account, device, room, false)).isNull()
        }
        val missingScope = mockk<AppOperationalStateCoordinator>(relaxed = true)
        coEvery { missingScope.operation(id) } returns original
        assertThat(inspectOriginalMediaOperation(missingScope, id, account, device, null, false)).isNull()
    }

    @Test
    fun `offline or rejected authority yields no usable fact`() = runTest {
        val coordinator = mockk<AppOperationalStateCoordinator>(relaxed = true)
        coEvery { coordinator.operation(id) } throws IllegalStateException("access refused")
        assertThat(inspectOriginalMediaOperation(coordinator, id, account, device, room, false)).isNull()
        coVerify(exactly = 0) { coordinator.resume(any()) }
    }

    @Test
    fun `cancellation propagates without claiming a result`() = runTest {
        val coordinator = mockk<AppOperationalStateCoordinator>(relaxed = true)
        coEvery { coordinator.operation(id) } throws CancellationException("exit")
        try {
            inspectOriginalMediaOperation(coordinator, id, account, device, room, false)
            error("Cancellation must propagate")
        } catch (expected: CancellationException) {
            assertThat(expected.message).isEqualTo("exit")
        }
    }
}
