/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.messages.impl.attachments

import io.element.android.features.messages.impl.attachments.preview.recoverOriginalMediaUploadCompletion
import io.element.android.features.messages.impl.draft.mergeMediaSubmissionFacts
import io.element.android.libraries.matrix.api.media.MediaSubmissionFact
import io.element.android.libraries.matrix.api.media.MediaSubmissionPhase
import io.element.android.security.dataprotection.api.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class OriginalMediaUploadCompletionTest {
    private val id = "original-upload-operation-0123456789"
    private val account = "a".repeat(64); private val device = "b".repeat(64); private val room = "c".repeat(64)
    private val fact = MediaSubmissionFact(MediaSubmissionPhase.ACCEPTED, ".yizhe-original-content.yzm", "\$original", "sdk-original",
        setOf("original-content"), localCleanupAtEpochMillis = 1100, clientAppliedAtEpochMillis = 1400, uploadOperationId = id)
    private fun operation() = LifecycleOperation(operationId = id, lifecycleId = FullAppLifecycleId.LC_16, idempotencyKey = id,
        subjects = LifecycleSubjectHashes(accountIdHash = account, matrixDeviceIdHash = device, roomIdHash = room),
        status = LifecycleOperationStatus.RUNNING, currentPhase = LifecyclePhase.PROJECTED,
        steps = listOf(LifecycleComponent.ANDROID_SERVICE, LifecycleComponent.ANDROID_FRONTEND).map {
            LifecycleStep("$id:${it.name}", it, status = LifecycleStepStatus.PENDING, attemptCount = 0, updatedAtEpochMillis = 1000)
        }, deadlineEpochMillis = 2000, createdAtEpochMillis = 1000, updatedAtEpochMillis = 1500)
    private fun coordinator() = mockk<AppOperationalStateCoordinator>(relaxed = true).also {
        coEvery { it.operation(id) } returns operation()
    }

    @Test fun `confirmed original checkpoints both actual component times before receipt transport`() = runTest {
        val c = coordinator(); val order = mutableListOf<String>(); var receipts = emptyList<LifecycleClientReceipt>()
        coEvery { c.persistClientReceipts(any()) } coAnswers { order += "persist"; receipts = firstArg() }
        coEvery { c.enqueueClientReceipts(any()) } coAnswers { order += "enqueue"; assertEquals(receipts, firstArg<List<LifecycleClientReceipt>>()) }
        assertTrue(recoverOriginalMediaUploadCompletion(c, id, account, device, room, fact,
            { order += "checkpoint"; fact }, now = { 1700 }))
        assertEquals(listOf("checkpoint", "persist", "enqueue"), order)
        assertEquals(listOf(1100L, 1400L), receipts.map { it.occurredAtEpochMillis })
        assertTrue(receipts.all { it.operationId == id })
        coVerify(exactly = 0) { c.start(any()) }; coVerify(exactly = 0) { c.resume(any()) }
    }

    @Test fun `missing original transaction cleanup or operation remains unknown without synthetic receipts`() = runTest {
        for (missing in listOf(fact.copy(transactionId = null), fact.copy(localCleanupAtEpochMillis = null),
            fact.copy(uploadOperationId = null), fact.copy(uploadOperationId = "foreign-original-operation"))) {
            val c = coordinator()
            assertFalse(recoverOriginalMediaUploadCompletion(c, id, account, device, room, missing, { fail("No checkpoint"); fact }))
            coVerify(exactly = 0) { c.persistClientReceipts(any()) }
        }
    }

    @Test fun `foreign scope cannot complete original even with a matching event`() = runTest {
        val c = coordinator(); coEvery { c.operation(id) } returns operation().copy(subjects = operation().subjects.copy(roomIdHash = "d".repeat(64)))
        try { recoverOriginalMediaUploadCompletion(c, id, account, device, room, fact, { fact }); fail("Foreign scope") }
        catch (_: IllegalArgumentException) { }
        coVerify(exactly = 0) { c.persistClientReceipts(any()) }
    }

    @Test fun `checkpoint cannot replace original operation or protected content`() = runTest {
        for (replacement in listOf(fact.copy(uploadOperationId = "foreign-upload-operation"), fact.copy(contentIds = setOf("foreign-content")))) {
            val c = coordinator()
            try { recoverOriginalMediaUploadCompletion(c, id, account, device, room, fact, { replacement }); fail("Rebound checkpoint") }
            catch (_: IllegalArgumentException) { }
            coVerify(exactly = 0) { c.persistClientReceipts(any()) }
        }
    }

    @Test fun `expired operation cannot fabricate a new frontend occurrence before its deadline`() = runTest {
        val c = coordinator()
        assertFalse(recoverOriginalMediaUploadCompletion(c, id, account, device, room,
            fact.copy(clientAppliedAtEpochMillis = null), { fail("Expired application"); fact }, now = { 3000 }))
        coVerify(exactly = 0) { c.enqueueClientReceipts(any()) }
    }

    @Test fun `terminal failed original and signed receipt are preserved`() = runTest {
        val c = coordinator(); coEvery { c.operation(id) } returns operation().copy(status = LifecycleOperationStatus.FAILED_FINAL,
            terminalReceiptId = "signed-original-failure")
        assertFalse(recoverOriginalMediaUploadCompletion(c, id, account, device, room, fact, { fail("Terminal checkpoint"); fact }))
        coVerify(exactly = 0) { c.persistClientReceipts(any()) }
    }

    @Test fun `receipt transport failure retries identical retained facts without refreshing occurrence time`() = runTest {
        val c = coordinator(); val persisted = mutableListOf<List<LifecycleClientReceipt>>()
        coEvery { c.persistClientReceipts(any()) } coAnswers { persisted += firstArg<List<LifecycleClientReceipt>>() }
        coEvery { c.enqueueClientReceipts(any()) } throws IllegalStateException("network offline")
        try { recoverOriginalMediaUploadCompletion(c, id, account, device, room, fact, { fact }); fail("Transport failure") }
        catch (_: IllegalStateException) { }
        coEvery { c.enqueueClientReceipts(any()) } returns Unit
        assertTrue(recoverOriginalMediaUploadCompletion(c, id, account, device, room, fact, { fact }, now = { 9000 }))
        assertEquals(persisted[0], persisted[1])
        coVerify(exactly = 0) { c.start(any()) }
    }

    @Test fun `encrypted draft merging preserves original identities and first actual times`() {
        val next = fact.copy(localCleanupAtEpochMillis = 1800, clientAppliedAtEpochMillis = 1900)
        assertEquals(fact, mergeMediaSubmissionFacts(mapOf(0 to fact), mapOf(0 to next)).getValue(0))
        try { mergeMediaSubmissionFacts(mapOf(0 to fact), mapOf(0 to fact.copy(uploadOperationId = "another-original-upload"))); fail("Operation rebound") }
        catch (_: IllegalArgumentException) { }
    }
}
