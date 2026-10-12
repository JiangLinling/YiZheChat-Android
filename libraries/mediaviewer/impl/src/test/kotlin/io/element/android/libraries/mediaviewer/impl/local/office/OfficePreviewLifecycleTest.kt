/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.mediaviewer.impl.local.office

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.mediaviewer.api.anImageMediaInfo
import io.element.android.libraries.mediaviewer.api.local.LocalMedia
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleClientReceipt
import io.element.android.security.dataprotection.api.LifecycleComponent
import io.element.android.security.dataprotection.api.LifecycleOperation
import io.element.android.security.dataprotection.api.LifecycleOperationContext
import io.element.android.security.dataprotection.api.LifecycleOperationRequest
import io.element.android.security.dataprotection.api.LifecycleOperationStatus
import io.element.android.security.dataprotection.api.LifecyclePhase
import io.element.android.security.dataprotection.api.LifecycleStep
import io.element.android.security.dataprotection.api.LifecycleStepStatus
import io.element.android.security.dataprotection.api.LifecycleSubjectHashes
import io.element.android.security.dataprotection.api.currentLifecycleOperationContext
import io.element.android.security.dataprotection.api.lifecycleOperationRequest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OfficePreviewLifecycleTest {
    private val request get() = lifecycleOperationRequest(
        FullAppLifecycleId.LC_16, "preview", "a".repeat(64), "b".repeat(64), "c".repeat(64),
    )
    private val androidComponents = setOf(LifecycleComponent.ANDROID_FRONTEND, LifecycleComponent.ANDROID_SERVICE)
    private val components = androidComponents + setOf(LifecycleComponent.MATRIX, LifecycleComponent.CONTROL_PLANE,
        LifecycleComponent.DATABASE, LifecycleComponent.KMS, LifecycleComponent.OFFICE_PREVIEW)

    private fun operation(request: LifecycleOperationRequest): LifecycleOperation {
        val now = System.currentTimeMillis()
        return LifecycleOperation(
            operationId = request.operationId, lifecycleId = request.lifecycleId, idempotencyKey = request.idempotencyKey,
            subjects = LifecycleSubjectHashes(request.accountIdHash, "preview-installation", request.matrixDeviceIdHash,
                request.roomIdHash, request.eventIdHash),
            status = LifecycleOperationStatus.RUNNING, currentPhase = LifecyclePhase.INTENT_PERSISTED,
            steps = components.map { LifecycleStep("preview-step-${it.name}", it,
                status = LifecycleStepStatus.PENDING, attemptCount = 0, updatedAtEpochMillis = now) },
            deadlineEpochMillis = request.deadlineEpochMillis, createdAtEpochMillis = now, updatedAtEpochMillis = now,
        )
    }

    @Test
    fun `actual preview entry requests fresh identities instead of reusing downloaded source`() = runTest {
        val coordinator = mockk<AppOperationalStateCoordinator>()
        val starts = mutableListOf<LifecycleOperationRequest>()
        val unavailable = IllegalStateException("preview authority unavailable")
        coEvery { coordinator.start(capture(starts)) } throws unavailable
        val service = OfficePreviewService(mockk(), mockk(), coordinator, mockk(), mockk())
        val source = LocalMedia(Uri.parse("content://fixture/downloaded.docx"),
            anImageMediaInfo().copy(filename = "fixture.docx"), lifecycleOperationId = "download-original-operation",
            lifecycleRoomIdHash = "c".repeat(64))
        repeat(2) { assertThat(runCatching { service.prepare(source, "fixture-account", "fixture-device") {} }
            .exceptionOrNull()).isSameInstanceAs(unavailable) }
        assertThat(starts.map { it.operationId }.distinct()).hasSize(2)
        assertThat(starts.map { it.operationId }).doesNotContain(source.lifecycleOperationId)
        assertThat(starts.map { it.trigger }.distinct()).containsExactly("preview")
        assertThat(starts.map { it.roomIdHash }.distinct()).containsExactly(source.lifecycleRoomIdHash)
        assertThat(source.lifecycleOperationId).isEqualTo("download-original-operation")
    }

    @Test
    fun `same LC download parent cannot replace preview admission context or receipts`() = runTest {
        val request = request
        val response = operation(request)
        val coordinator = coordinator(response)
        val parent = LifecycleOperationContext("download-original-operation", FullAppLifecycleId.LC_16)
        withContext(parent) {
            assertThat(coordinator.performOfficePreview(request) { id ->
                assertThat(currentLifecycleOperationContext()?.operationId).isEqualTo(request.operationId)
                assertThat(id).isEqualTo(request.operationId)
                "preview-result"
            }).isEqualTo("preview-result")
            assertThat(currentLifecycleOperationContext()).isSameInstanceAs(parent)
        }
        coVerify(exactly = 1) { coordinator.start(request) }
        coVerify(exactly = 1) { coordinator.persistClientReceipts(match { receipts ->
            receipts.size == 2 && receipts.all { it.operationId == request.operationId } &&
                receipts.map { it.component }.toSet() == androidComponents
        }) }
        coVerify(exactly = 0) { coordinator.recordClientReceipt(match { it.operationId == parent.operationId }) }
    }

    @Test
    fun `expired terminal incomplete and wrong scope admission cannot invoke document action`() = runTest {
        val request = request
        val original = operation(request)
        val bad = listOf(
            original.copy(createdAtEpochMillis = System.currentTimeMillis() - 60_000,
                deadlineEpochMillis = System.currentTimeMillis() - 1),
            original.copy(deadlineEpochMillis = request.deadlineEpochMillis + 1),
            original.copy(status = LifecycleOperationStatus.SUCCEEDED, terminalReceiptId = "terminal-preview",
                steps = original.steps.map { it.copy(status = LifecycleStepStatus.COMPLETE,
                    receiptReference = "original-preview-receipt-${it.component}") }),
            original.copy(status = LifecycleOperationStatus.FAILED_FINAL, terminalReceiptId = "terminal-preview-failed"),
            original.copy(status = LifecycleOperationStatus.CANCELLED, terminalReceiptId = "terminal-preview-cancelled"),
            original.copy(status = LifecycleOperationStatus.TERMINATED, terminalReceiptId = "terminal-preview-terminated"),
            original.copy(operationId = "download-original-operation"),
            original.copy(lifecycleId = FullAppLifecycleId.LC_13),
            original.copy(subjects = original.subjects.copy(accountIdHash = "e".repeat(64))),
            original.copy(subjects = original.subjects.copy(matrixDeviceIdHash = "e".repeat(64))),
            original.copy(subjects = original.subjects.copy(roomIdHash = "e".repeat(64))),
            original.copy(steps = original.steps.filter { it.component != LifecycleComponent.OFFICE_PREVIEW }),
            original.copy(steps = original.steps + original.steps.first().copy(stepId = "duplicate-required")),
        )
        for (response in bad) {
            val coordinator = coordinator(response)
            var actions = 0
            assertThat(runCatching { coordinator.performOfficePreview(request) { actions++ } }.isFailure).isTrue()
            assertThat(actions).isEqualTo(0)
            coVerify(exactly = 0) { coordinator.persistClientReceipts(any()) }
            coVerify(exactly = 0) { coordinator.recordClientReceipt(any()) }
        }
    }

    @Test
    fun `download trigger and anonymous request cannot create preview admission`() = runTest {
        for (request in listOf(request.copy(trigger = "download"),
            request.copy(accountIdHash = null, matrixDeviceIdHash = null))) {
            val coordinator = mockk<AppOperationalStateCoordinator>()
            assertThat(runCatching { coordinator.performOfficePreview(request) { error("Unexpected action") } }.isFailure).isTrue()
            coVerify(exactly = 0) { coordinator.start(any()) }
        }
    }

    @Test
    fun `failed product action restores parent and cannot persist applied facts`() = runTest {
        val request = request
        val coordinator = coordinator(operation(request))
        val failure = IllegalStateException("upload failed")
        val parent = LifecycleOperationContext("download-original-operation", FullAppLifecycleId.LC_16)
        withContext(parent) {
            val observed = runCatching { coordinator.performOfficePreview(request) { throw failure } }.exceptionOrNull()
            // Coroutine stack-trace recovery may copy the original exception while retaining its cause.
            assertThat(observed).isInstanceOf(IllegalStateException::class.java)
            assertThat(observed?.message).isEqualTo(failure.message)
            assertThat(currentLifecycleOperationContext()).isSameInstanceAs(parent)
        }
        coVerify(exactly = 0) { coordinator.persistClientReceipts(any()) }
        coVerify(exactly = 0) { coordinator.recordClientReceipt(any()) }
    }

    @Test
    fun `receipt transport failure retains only genuine preview facts after action`() = runTest {
        val request = request
        val coordinator = coordinator(operation(request))
        val persisted = mutableListOf<List<LifecycleClientReceipt>>()
        var actions = 0
        coEvery { coordinator.persistClientReceipts(capture(persisted)) } answers { assertThat(actions).isEqualTo(1) }
        coEvery { coordinator.recordClientReceipt(any()) } throws IllegalStateException("transport unavailable")
        assertThat(runCatching { coordinator.performOfficePreview(request) { actions++ } }.isFailure).isTrue()
        assertThat(actions).isEqualTo(1)
        assertThat(persisted.single().map { it.operationId }.distinct()).containsExactly(request.operationId)
        assertThat(persisted.single().map { it.component }.toSet()).isEqualTo(androidComponents)
        coVerify(exactly = 2) { coordinator.recordClientReceipt(any()) }
    }

    private fun coordinator(response: LifecycleOperation): AppOperationalStateCoordinator = mockk<AppOperationalStateCoordinator>().also {
        coEvery { it.start(any()) } returns response
        coEvery { it.operation(response.operationId) } returns response
        coEvery { it.persistClientReceipts(any()) } returns Unit
        coEvery { it.recordClientReceipt(any()) } returns response
    }
}
