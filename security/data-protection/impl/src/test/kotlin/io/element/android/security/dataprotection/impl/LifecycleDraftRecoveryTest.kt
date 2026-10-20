/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.dataprotection.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.security.dataprotection.api.AppOperationalSnapshot
import io.element.android.security.dataprotection.api.AppOperationalStatus
import io.element.android.security.dataprotection.api.ComponentOperationalState
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleClientReceipt
import io.element.android.security.dataprotection.api.LifecycleComponent
import io.element.android.security.dataprotection.api.LifecycleOperation
import io.element.android.security.dataprotection.api.LifecycleOperationContext
import io.element.android.security.dataprotection.api.LifecycleOperationRequest
import io.element.android.security.dataprotection.api.LifecycleOperationStatus
import io.element.android.security.dataprotection.api.LifecyclePhase
import io.element.android.security.dataprotection.api.LifecycleSubjectHashes
import io.element.android.security.dataprotection.api.ManagementControlService
import io.element.android.security.dataprotection.api.REQUIRED_OPERATIONAL_COMPONENTS
import io.element.android.security.dataprotection.api.currentLifecycleOperationContext
import io.element.android.security.dataprotection.api.lifecycleOperationRequest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test

class LifecycleDraftRecoveryTest {
    @Test
    fun `local action commits original intent before storage and recovers both facts after recreation`() = runTest {
        val request = request()
        val store = MemoryStore()
        val service = service()
        val original = DefaultAppOperationalStateCoordinator(service, store, draftInstallationProvider = { INSTALLATION })
        var writes = 0
        withContext(LifecycleOperationContext("unrelated-parent-draft-0001", FullAppLifecycleId.LC_14)) {
            original.performLocalDraft(request) { id ->
                assertThat(id).isEqualTo(request.operationId)
                assertThat(currentLifecycleOperationContext()?.operationId).isEqualTo(request.operationId)
                assertThat(store.pendingDraftStarts().single().request).isEqualTo(request)
                assertThat(store.pendingClientReceipts()).isEmpty()
                writes++
            }
        }
        val facts = store.pendingClientReceipts()
        assertThat(facts).hasSize(2)
        original.performLocalDraft(request) { writes++ }
        assertThat(writes).isEqualTo(1)
        coVerify(exactly = 0) { service.startLifecycleOperation(any()) }
        coVerify(exactly = 0) { service.recordLifecycleClientReceipt(any()) }
        coEvery { service.startLifecycleOperation(request) } returns operation(request)
        coEvery { service.recordLifecycleClientReceipt(any()) } returns operation(request)
        DefaultAppOperationalStateCoordinator(service, store).refresh(ACCOUNT, DEVICE)
        coVerify(exactly = 1) { service.startLifecycleOperation(request) }
        facts.forEach { fact -> coVerify(exactly = 1) { service.recordLifecycleClientReceipt(fact) } }
        assertThat(store.pendingDraftStarts()).isEmpty()
        assertThat(store.pendingClientReceipts()).isEmpty()
        assertThat(writes).isEqualTo(1)
    }

    @Test
    fun `journal failure prevents the draft side effect and network admission`() = runTest {
        val service = service()
        val store = object : LifecycleOperationIdStore by MemoryStore() {
            override fun savePendingDraftState(starts: List<PendingLifecycleDraftStart>, receipts: List<LifecycleClientReceipt>) {
                throw java.io.IOException("TEST_COMMIT_FAILED")
            }
        }
        val coordinator = DefaultAppOperationalStateCoordinator(service, store, draftInstallationProvider = { INSTALLATION })
        var writes = 0
        assertThat(runCatching { coordinator.performLocalDraft(request()) { writes++ } }.isFailure).isTrue()
        assertThat(writes).isEqualTo(0)
        coVerify(exactly = 0) { service.startLifecycleOperation(any()) }
    }

    @Test
    fun `fact commit failure retains unresolved original and never repeats the product action`() = runTest {
        val base = MemoryStore()
        val store = object : LifecycleOperationIdStore by base {
            override fun savePendingClientReceipts(receipts: List<LifecycleClientReceipt>) {
                throw java.io.IOException("TEST_FACT_COMMIT_FAILED")
            }
        }
        val coordinator = DefaultAppOperationalStateCoordinator(service(), store, draftInstallationProvider = { INSTALLATION })
        val request = request()
        var writes = 0
        assertThat(runCatching { coordinator.performLocalDraft(request) { writes++ } }.isFailure).isTrue()
        assertThat(runCatching { coordinator.performLocalDraft(request) { writes++ } }.isFailure).isTrue()
        assertThat(writes).isEqualTo(1)
        assertThat(base.pendingDraftStarts().single().request).isEqualTo(request)
        assertThat(base.pendingClientReceipts()).isEmpty()
    }

    @Test
    fun `cancelled local action has no applied fact and is not replayed as a save`() = runTest {
        val store = MemoryStore()
        val service = service()
        val coordinator = DefaultAppOperationalStateCoordinator(service, store, draftInstallationProvider = { INSTALLATION })
        assertThat(runCatching { coordinator.performLocalDraft(request()) { throw CancellationException("TEST_SUPERSEDED") } }.isFailure).isTrue()
        coordinator.refresh(ACCOUNT, DEVICE)
        assertThat(store.pendingClientReceipts()).isEmpty()
        assertThat(store.pendingDraftStarts()).hasSize(1)
        coVerify(exactly = 0) { service.startLifecycleOperation(any()) }
    }

    @Test
    fun `cold admission transport failure retains exact request and original occurrence times`() = runTest {
        val request = request()
        val store = prepared(request)
        val facts = store.pendingClientReceipts()
        val service = service()
        coEvery { service.startLifecycleOperation(request) } throws java.io.IOException("TEST_OFFLINE")
        DefaultAppOperationalStateCoordinator(service, store).refresh(ACCOUNT, DEVICE)
        assertThat(store.pendingClientReceipts()).containsExactlyElementsIn(facts)
        assertThat(store.pendingDraftStarts().single().request).isEqualTo(request)
        coVerify(exactly = 0) { service.recordLifecycleClientReceipt(any()) }
    }

    @Test
    fun `another current installation or account cannot admit or transport the original draft`() = runTest {
        for (foreign in listOf(snapshot().copy(installationId = "other-installation-0001"), snapshot().copy(accountIdHash = "d".repeat(64)))) {
            val store = prepared(request())
            val service = service()
            coEvery { service.appOperationalSnapshot(any(), any()) } returns foreign
            DefaultAppOperationalStateCoordinator(service, store).refresh(foreign.accountIdHash, foreign.matrixDeviceIdHash)
            coVerify(exactly = 0) { service.startLifecycleOperation(any()) }
            coVerify(exactly = 0) { service.recordLifecycleClientReceipt(any()) }
            assertThat(store.pendingClientReceipts()).hasSize(2)
        }
    }

    @Test
    fun `authority response cannot change original account room installation identity or deadline`() = runTest {
        val request = request()
        val actual = operation(request)
        val foreign = listOf(
            actual.copy(operationId = "other-operation-original-0001"),
            actual.copy(deadlineEpochMillis = request.deadlineEpochMillis + 1),
            actual.copy(idempotencyKey = request.idempotencyKey),
            actual.copy(idempotencyKey = "d".repeat(64)),
            actual.copy(subjects = actual.subjects.copy(accountIdHash = "d".repeat(64))),
            actual.copy(subjects = actual.subjects.copy(roomIdHash = "d".repeat(64))),
            actual.copy(subjects = actual.subjects.copy(installationId = "other-installation-0001"))
        )
        for (response in foreign) {
            val store = prepared(request)
            val service = service()
            coEvery { service.startLifecycleOperation(request) } returns response
            DefaultAppOperationalStateCoordinator(service, store).refresh(ACCOUNT, DEVICE)
            coVerify(exactly = 0) { service.recordLifecycleClientReceipt(any()) }
            assertThat(store.pendingClientReceipts()).hasSize(2)
        }
    }

    @Test
    fun `installation change while waiting for original admission blocks both receipts`() = runTest {
        val request = request()
        val store = prepared(request)
        val service = service()
        var installation = INSTALLATION
        coEvery { service.startLifecycleOperation(request) } coAnswers {
            installation = "other-installation-0001"
            operation(request)
        }
        DefaultAppOperationalStateCoordinator(service, store, draftInstallationProvider = { installation }).refresh(ACCOUNT, DEVICE)
        coVerify(exactly = 1) { service.startLifecycleOperation(request) }
        coVerify(exactly = 0) { service.recordLifecycleClientReceipt(any()) }
        assertThat(store.pendingClientReceipts()).hasSize(2)
        assertThat(store.pendingDraftStarts()).hasSize(1)
    }

    @Test
    fun `expired or old local facts only inspect original and never create a late operation`() = runTest {
        for (expired in listOf(true, false)) {
            val now = System.currentTimeMillis()
            val request = request().copy(deadlineEpochMillis = if (expired) now - 1 else now + 60_000)
            val store = prepared(request, occurredAt = now - 6 * 60_000)
            val service = service()
            coEvery { service.lifecycleOperation(request.operationId) } throws java.io.IOException("TEST_ORIGINAL_UNKNOWN")
            DefaultAppOperationalStateCoordinator(service, store).refresh(ACCOUNT, DEVICE)
            coVerify(exactly = 1) { service.lifecycleOperation(request.operationId) }
            coVerify(exactly = 0) { service.startLifecycleOperation(any()) }
            coVerify(exactly = 0) { service.recordLifecycleClientReceipt(any()) }
            assertThat(store.pendingClientReceipts()).hasSize(2)
        }
    }

    @Test
    fun `one failed receipt retains start until the last original fact is acknowledged`() = runTest {
        val request = request()
        val store = prepared(request)
        val facts = store.pendingClientReceipts()
        val service = service()
        coEvery { service.startLifecycleOperation(request) } returns operation(request)
        coEvery { service.recordLifecycleClientReceipt(any()) } coAnswers {
            if (firstArg<LifecycleClientReceipt>().component == LifecycleComponent.ANDROID_SERVICE) throw java.io.IOException("TEST_RECEIPT_LOST")
            operation(request)
        }
        DefaultAppOperationalStateCoordinator(service, store).refresh(ACCOUNT, DEVICE)
        assertThat(store.pendingDraftStarts()).hasSize(1)
        assertThat(store.pendingClientReceipts()).containsExactly(facts.first { it.component == LifecycleComponent.ANDROID_SERVICE })
        coEvery { service.recordLifecycleClientReceipt(any()) } returns operation(request)
        DefaultAppOperationalStateCoordinator(service, store).refresh(ACCOUNT, DEVICE)
        assertThat(store.pendingDraftStarts()).isEmpty()
        assertThat(store.pendingClientReceipts()).isEmpty()
        coVerify(exactly = 2) { service.recordLifecycleClientReceipt(facts.first { it.component == LifecycleComponent.ANDROID_SERVICE }) }
    }

    private fun request() = lifecycleOperationRequest(
        FullAppLifecycleId.LC_14,
        "draft_change",
        ACCOUNT,
        DEVICE,
        ROOM,
        operationId = "local-draft-original-0001"
    )
    private fun operation(request: LifecycleOperationRequest) = LifecycleOperation(
        operationId = request.operationId,
        lifecycleId = request.lifecycleId,
        idempotencyKey = java.security.MessageDigest.getInstance("SHA-256").digest(request.idempotencyKey.encodeToByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) },
        subjects = LifecycleSubjectHashes(ACCOUNT, INSTALLATION, DEVICE, ROOM),
        status = LifecycleOperationStatus.RUNNING,
        currentPhase = LifecyclePhase.INTENT_PERSISTED,
        deadlineEpochMillis = request.deadlineEpochMillis,
        createdAtEpochMillis = System.currentTimeMillis(),
        updatedAtEpochMillis = System.currentTimeMillis(),
    )
    private fun snapshot(): AppOperationalSnapshot {
        val now = System.currentTimeMillis()
        return AppOperationalSnapshot(
            accountIdHash = ACCOUNT,
            installationId = INSTALLATION,
            matrixDeviceIdHash = DEVICE,
            issuedAtEpochMillis = now,
            expiresAtEpochMillis = now + 60_000,
            overallStatus = AppOperationalStatus.READY,
            components = REQUIRED_OPERATIONAL_COMPONENTS.associateWith {
                ComponentOperationalState(AppOperationalStatus.READY, it, "test", now, now + 60_000, receiptReference = "test:$it")
            },
            signingKeyId = "test-signing-key",
            signatureBase64 = "test-signature-value"
        )
    }
    private fun service() = mockk<ManagementControlService>().also { service ->
        coEvery { service.appOperationalSnapshot(any(), any()) } answers { snapshot() }
    }
    private fun prepared(request: LifecycleOperationRequest, occurredAt: Long = System.currentTimeMillis()) = MemoryStore().apply {
        savePendingDraftState(
            listOf(PendingLifecycleDraftStart(request, INSTALLATION)),
            listOf(LifecycleComponent.ANDROID_SERVICE, LifecycleComponent.ANDROID_FRONTEND).map { component ->
                LifecycleClientReceipt(
                    request.operationId,
                    component,
                    LifecyclePhase.CLIENT_APPLIED,
                    "${component.name}_APPLIED",
                    "test:${request.operationId}:${component.name}",
                    occurredAt
                )
            }
        )
    }
    private class MemoryStore : LifecycleOperationIdStore {
        private var starts = emptyList<PendingLifecycleDraftStart>()
        private var receipts = emptyList<LifecycleClientReceipt>()
        override fun latest(lifecycleId: FullAppLifecycleId): String? = null
        override fun remember(operation: LifecycleOperation) = Unit
        override fun pendingDraftStarts() = starts.toList()
        override fun pendingClientReceipts() = receipts.toList()
        override fun savePendingClientReceipts(receipts: List<LifecycleClientReceipt>) {
            this.receipts = receipts.toList()
        }
        override fun savePendingDraftState(starts: List<PendingLifecycleDraftStart>, receipts: List<LifecycleClientReceipt>) {
            this.starts = starts.toList()
            this.receipts = receipts.toList()
        }
    }
    private companion object {
        const val INSTALLATION = "installation-test-0001"
        val ACCOUNT = "a".repeat(64)
        val DEVICE = "b".repeat(64)
        val ROOM = "c".repeat(64)
    }
}
