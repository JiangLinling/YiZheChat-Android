/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.dataprotection.impl

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleClientReceipt
import io.element.android.security.dataprotection.api.LifecycleComponent
import io.element.android.security.dataprotection.api.LifecyclePhase
import io.element.android.security.dataprotection.api.lifecycleOperationRequest
import io.element.android.security.dataprotection.api.AppOperationalSnapshot
import io.element.android.security.dataprotection.api.AppOperationalStatus
import io.element.android.security.dataprotection.api.ComponentOperationalState
import io.element.android.security.dataprotection.api.LifecycleOperation
import io.element.android.security.dataprotection.api.LifecycleOperationStatus
import io.element.android.security.dataprotection.api.LifecycleSubjectHashes
import io.element.android.security.dataprotection.api.ManagementControlService
import io.element.android.security.dataprotection.api.REQUIRED_OPERATIONAL_COMPONENTS
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class LifecycleDraftJournalTest {
    @Test
    fun `encrypted store result owner suppresses generic receipts then cold handoff restores exact version`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val request = entry().request.copy(deadlineEpochMillis = System.currentTimeMillis() + 60_000)
        val service = mockk<ManagementControlService>()
        val first = DefaultAppOperationalStateCoordinator(service, SharedPreferencesLifecycleOperationIdStore(context),
            draftInstallationProvider = { INSTALLATION })
        first.performLocalDraft(request) {
            checkNotNull(kotlinx.coroutines.currentCoroutineContext()[io.element.android.security.dataprotection.api.LifecycleDraftWriteContext])
                .claimResultOwnership(it)
        }
        val durable = SharedPreferencesLifecycleOperationIdStore(context)
        assertThat(durable.pendingClientReceipts()).isEmpty()
        assertThat(durable.pendingDraftStarts().single().request).isEqualTo(request)
        val fact = io.element.android.security.dataprotection.api.LifecycleDraftApplied(request.operationId,
            request.accountIdHash!!, request.matrixDeviceIdHash!!, request.roomIdHash!!, "d".repeat(64), 50_000L)
        val recovered = DefaultAppOperationalStateCoordinator(service, durable, draftInstallationProvider = { INSTALLATION })
        assertThat(recovered.recoverLocalDraftApplied(fact)).isTrue()
        assertThat(recovered.recoverLocalDraftApplied(fact)).isTrue()
        assertThat(SharedPreferencesLifecycleOperationIdStore(context).pendingClientReceipts()).containsExactlyElementsIn(fact.receipts())
        assertThat(runCatching { recovered.recoverLocalDraftApplied(fact.copy(storageVersionHash = "e".repeat(64))) }.isFailure).isTrue()
        assertThat(SharedPreferencesLifecycleOperationIdStore(context).pendingClientReceipts()).containsExactlyElementsIn(fact.receipts())
        coVerify(exactly = 0) { service.startLifecycleOperation(any()) }
    }

    @Test
    fun `draft recovery rejects another room device or installation before queuing any facts`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val store = SharedPreferencesLifecycleOperationIdStore(context)
        store.savePendingDraftState(listOf(entry()), emptyList())
        val service = mockk<ManagementControlService>()
        val coordinator = DefaultAppOperationalStateCoordinator(service, store, draftInstallationProvider = { INSTALLATION })
        val fact = io.element.android.security.dataprotection.api.LifecycleDraftApplied(entry().request.operationId,
            "a".repeat(64), "b".repeat(64), "c".repeat(64), "d".repeat(64), 50_000L)
        for (wrong in listOf(fact.copy(roomIdHash = "e".repeat(64)), fact.copy(matrixDeviceIdHash = "e".repeat(64)),
            fact.copy(accountIdHash = "e".repeat(64)))) {
            assertThat(runCatching { coordinator.recoverLocalDraftApplied(wrong) }.isFailure).isTrue()
        }
        val changed = DefaultAppOperationalStateCoordinator(service, store, draftInstallationProvider = { "different-installation" })
        assertThat(runCatching { changed.recoverLocalDraftApplied(fact) }.isFailure).isTrue()
        assertThat(store.pendingClientReceipts()).isEmpty()
        assertThat(store.pendingDraftStarts()).containsExactly(entry())
    }

    @Test
    fun `old applied version queues facts without changing original deadline or inventing creation time`() = runTest {
        val store = SharedPreferencesLifecycleOperationIdStore(RuntimeEnvironment.getApplication())
        store.savePendingDraftState(listOf(entry()), emptyList())
        val coordinator = DefaultAppOperationalStateCoordinator(mockk(), store, draftInstallationProvider = { INSTALLATION })
        val fact = io.element.android.security.dataprotection.api.LifecycleDraftApplied(entry().request.operationId,
            "a".repeat(64), "b".repeat(64), "c".repeat(64), "d".repeat(64), 50_000L)
        assertThat(coordinator.recoverLocalDraftApplied(fact)).isTrue()
        assertThat(store.pendingDraftStarts()).containsExactly(entry())
        assertThat(store.pendingClientReceipts()).containsExactlyElementsIn(fact.receipts())
        assertThat(coordinator.operations.value).isEmpty()
    }

    @Test
    fun `real preferences and coordinator jointly recover original facts without calling the draft writer again`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val request = entry().request.copy(deadlineEpochMillis = System.currentTimeMillis() + 60_000)
        val firstStore = SharedPreferencesLifecycleOperationIdStore(context)
        val service = mockk<ManagementControlService>()
        var writes = 0
        DefaultAppOperationalStateCoordinator(service, firstStore, draftInstallationProvider = { INSTALLATION }).performLocalDraft(request) {
            assertThat(SharedPreferencesLifecycleOperationIdStore(context).pendingDraftStarts().single().request).isEqualTo(request)
            writes++
        }
        val retained = SharedPreferencesLifecycleOperationIdStore(context).pendingClientReceipts()
        assertThat(retained).hasSize(2)
        val now = System.currentTimeMillis()
        val snapshot = AppOperationalSnapshot(accountIdHash = request.accountIdHash!!, installationId = INSTALLATION,
            matrixDeviceIdHash = request.matrixDeviceIdHash!!, issuedAtEpochMillis = now, expiresAtEpochMillis = now + 60_000,
            overallStatus = AppOperationalStatus.READY, components = REQUIRED_OPERATIONAL_COMPONENTS.associateWith {
                ComponentOperationalState(AppOperationalStatus.READY, it, "test-revision", now, now + 60_000, receiptReference = "test:$it")
            }, signingKeyId = "test-signing-key", signatureBase64 = "test-signature-value")
        val accepted = LifecycleOperation(operationId = request.operationId, lifecycleId = request.lifecycleId,
            idempotencyKey = java.security.MessageDigest.getInstance("SHA-256").digest(request.idempotencyKey.encodeToByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 0xff) },
            subjects = LifecycleSubjectHashes(request.accountIdHash, INSTALLATION, request.matrixDeviceIdHash, request.roomIdHash),
            status = LifecycleOperationStatus.RUNNING, currentPhase = LifecyclePhase.INTENT_PERSISTED,
            deadlineEpochMillis = request.deadlineEpochMillis, createdAtEpochMillis = now, updatedAtEpochMillis = now)
        coEvery { service.appOperationalSnapshot(any(), any()) } returns snapshot
        coEvery { service.startLifecycleOperation(request) } returns accepted
        coEvery { service.recordLifecycleClientReceipt(any()) } returns accepted
        DefaultAppOperationalStateCoordinator(service, SharedPreferencesLifecycleOperationIdStore(context))
            .refresh(request.accountIdHash!!, request.matrixDeviceIdHash!!)
        coVerify(exactly = 1) { service.startLifecycleOperation(request) }
        retained.forEach { fact -> coVerify(exactly = 1) { service.recordLifecycleClientReceipt(fact) } }
        assertThat(SharedPreferencesLifecycleOperationIdStore(context).pendingDraftStarts()).isEmpty()
        assertThat(SharedPreferencesLifecycleOperationIdStore(context).pendingClientReceipts()).isEmpty()
        assertThat(writes).isEqualTo(1)
    }

    @Test
    fun `real preferences preserve the complete original request and facts across store recreation`() {
        val context = RuntimeEnvironment.getApplication()
        val first = SharedPreferencesLifecycleOperationIdStore(context)
        first.savePendingDraftState(listOf(entry()), facts())
        val recreated = SharedPreferencesLifecycleOperationIdStore(context)
        assertThat(recreated.pendingDraftStarts()).containsExactly(entry())
        assertThat(recreated.pendingClientReceipts()).containsExactlyElementsIn(facts())
        val raw = context.getSharedPreferences("app_lifecycle_operation_ids_v1", Context.MODE_PRIVATE).all.toString()
        assertThat(raw).doesNotContain("plainText")
        assertThat(raw).doesNotContain("htmlText")
        recreated.savePendingDraftState(emptyList(), emptyList())
        assertThat(SharedPreferencesLifecycleOperationIdStore(context).pendingDraftStarts()).isEmpty()
        assertThat(SharedPreferencesLifecycleOperationIdStore(context).pendingClientReceipts()).isEmpty()
    }

    @Test
    fun `ordinary receipt update cannot discard the retained draft request`() {
        val context = RuntimeEnvironment.getApplication()
        val store = SharedPreferencesLifecycleOperationIdStore(context)
        store.savePendingDraftState(listOf(entry()), facts())
        store.savePendingClientReceipts(facts().take(1))
        assertThat(SharedPreferencesLifecycleOperationIdStore(context).pendingDraftStarts()).containsExactly(entry())
    }

    @Test
    fun `failed joint commit never reports success or replaces original persisted data`() {
        val context = RuntimeEnvironment.getApplication()
        val original = SharedPreferencesLifecycleOperationIdStore(context)
        original.savePendingDraftState(listOf(entry()), facts())
        val failingContext = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val actual = context.getSharedPreferences(name, mode)
                return object : SharedPreferences by actual {
                    override fun edit(): SharedPreferences.Editor {
                        val editor = actual.edit()
                        return object : SharedPreferences.Editor by editor {
                            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
                                editor.putStringSet(key, values)
                                return this
                            }
                            override fun commit(): Boolean = false
                        }
                    }
                }
            }
        }
        assertThat(runCatching {
            SharedPreferencesLifecycleOperationIdStore(failingContext).savePendingDraftState(emptyList(), emptyList())
        }.isFailure).isTrue()
        val recreated = SharedPreferencesLifecycleOperationIdStore(context)
        assertThat(recreated.pendingDraftStarts()).containsExactly(entry())
        assertThat(recreated.pendingClientReceipts()).containsExactlyElementsIn(facts())
    }

    @Test
    fun `invalid receipt batch is rejected before either preferences key is changed`() {
        val context = RuntimeEnvironment.getApplication()
        val store = SharedPreferencesLifecycleOperationIdStore(context)
        store.savePendingDraftState(listOf(entry()), facts())
        assertThat(runCatching { store.savePendingDraftState(emptyList(), List(8193) { facts().first() }) }.isFailure).isTrue()
        assertThat(SharedPreferencesLifecycleOperationIdStore(context).pendingDraftStarts()).containsExactly(entry())
        assertThat(SharedPreferencesLifecycleOperationIdStore(context).pendingClientReceipts()).containsExactlyElementsIn(facts())
    }

    @Test
    fun `draft retention rejects missing subjects and different lifecycle trigger`() {
        for (request in listOf(entry().request.copy(roomIdHash = null), entry().request.copy(trigger = "send_press"),
            lifecycleOperationRequest(FullAppLifecycleId.LC_13, "send", operationId = "other-domain-original-0001"))) {
            assertThat(runCatching { PendingLifecycleDraftStart(request, INSTALLATION) }.isFailure).isTrue()
        }
    }

    private fun entry() = PendingLifecycleDraftStart(lifecycleOperationRequest(FullAppLifecycleId.LC_14, "draft_change",
        accountIdHash = "a".repeat(64), matrixDeviceIdHash = "b".repeat(64), roomIdHash = "c".repeat(64),
        operationId = "preferences-draft-original-0001", deadlineEpochMillis = 100_000L), INSTALLATION)
    private fun facts() = listOf(LifecycleComponent.ANDROID_SERVICE, LifecycleComponent.ANDROID_FRONTEND).map { component ->
        LifecycleClientReceipt(entry().request.operationId, component, LifecyclePhase.CLIENT_APPLIED, "${component.name}_APPLIED",
            "test:${entry().request.operationId}:${component.name}", 50_000L)
    }
    private companion object { const val INSTALLATION = "installation-test-0001" }
}
