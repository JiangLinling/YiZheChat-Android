/* Copyright (c) 2026 YiZheChat contributors.
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
import io.element.android.security.dataprotection.api.LifecycleOperationRequest
import io.element.android.security.dataprotection.api.LifecycleOperationStatus
import io.element.android.security.dataprotection.api.LifecyclePhase
import io.element.android.security.dataprotection.api.LifecycleStep
import io.element.android.security.dataprotection.api.LifecycleStepStatus
import io.element.android.security.dataprotection.api.LifecycleSubjectHashes
import io.element.android.security.dataprotection.api.ManagementControlService
import io.element.android.security.dataprotection.api.REQUIRED_OPERATIONAL_COMPONENTS
import io.element.android.security.dataprotection.api.lifecycleOperationRequest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class Bug036StartupAdmissionTest {
    private val account = "a".repeat(64)
    private val device = "b".repeat(64)
    private val installation = "installation-bug036-original"

    @Test
    fun `snapshot omission during actual startup cannot abandon admitted operation or receipts`() = runTest {
        val request = request()
        val admitted = operation(request)
        val service = service(request, admitted)
        val store = Store()
        val coordinator = DefaultAppOperationalStateCoordinator(service, store)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val action = async {
            coordinator.performResuming(request) {
                entered.complete(Unit)
                release.await()
                "session-and-core-applied"
            }
        }
        entered.await()
        coEvery { service.appOperationalSnapshot(account, device) } returns snapshot()
        coordinator.refresh(account, device)
        try {
            assertThat(coordinator.operations.value[request.operationId]).isEqualTo(admitted)
        } finally {
            release.complete(Unit)
        }
        assertThat(action.await()).isEqualTo("session-and-core-applied")
        assertThat(store.pendingClientReceipts()).isEmpty()
        coVerify(exactly = 1) { service.startLifecycleOperation(request) }
        coVerify(exactly = 2) { service.recordLifecycleClientReceipt(any()) }
    }

    @Test
    fun `one cancelled overlapping caller cannot abandon another executing startup owner`() = runTest {
        val request = request()
        val admitted = operation(request)
        val service = service(request, admitted)
        val coordinator = DefaultAppOperationalStateCoordinator(service, Store())
        val firstEntered = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { service.resumeLifecycleOperation(request.operationId) } returns admitted
        coEvery { service.appOperationalSnapshot(account, device) } returns snapshot()
        val first = async { coordinator.performResuming(request) { firstEntered.complete(Unit); CompletableDeferred<Unit>().await() } }
        firstEntered.await()
        val second = async { coordinator.performResuming(request) { secondEntered.complete(Unit); release.await(); "applied" } }
        secondEntered.await()
        first.cancel()
        first.join()
        coordinator.refresh(account, device)
        try {
            assertThat(coordinator.operations.value[request.operationId]).isEqualTo(admitted)
        } finally {
            release.complete(Unit)
        }
        assertThat(second.await()).isEqualTo("applied")
        coordinator.refresh(account, device)
        assertThat(coordinator.operations.value).doesNotContainKey(request.operationId)
        coVerify(exactly = 1) { service.startLifecycleOperation(any()) }
        coVerify(exactly = 2) { service.recordLifecycleClientReceipt(any()) }
    }

    @Test
    fun `pruned projection retains original verified admission and exact durable facts across restart`() = runTest {
        val request = request()
        val admitted = operation(request)
        val service = service(request, admitted)
        val store = Store()
        val coordinator = DefaultAppOperationalStateCoordinator(service, store)
        coordinator.start(request)
        coEvery { service.appOperationalSnapshot(account, device) } returns snapshot()
        coordinator.refresh(account, device)
        assertThat(coordinator.operations.value).doesNotContainKey(request.operationId)
        val facts = facts(request)
        coordinator.persistClientReceipts(facts)
        assertThat(store.pendingClientReceipts()).containsExactlyElementsIn(facts)
        coEvery { service.lifecycleOperation(request.operationId) } returns admitted
        DefaultAppOperationalStateCoordinator(service, store).refresh(account, device)
        assertThat(store.pendingClientReceipts()).isEmpty()
        facts.forEach { fact -> coVerify(exactly = 1) { service.recordLifecycleClientReceipt(fact) } }
        coVerify(exactly = 1) { service.startLifecycleOperation(any()) }
        coVerify(exactly = 0) { service.resumeLifecycleOperation(any()) }
    }

    @Test
    fun `older recovered operation cannot regress authority terminal fact even in newer snapshot`() = runTest {
        val request = request()
        val admitted = operation(request)
        val terminal = admitted.copy(status = LifecycleOperationStatus.CANCELLED, currentPhase = LifecyclePhase.RECEIPTED,
            terminalReceiptId = "original-cancellation-receipt", updatedAtEpochMillis = admitted.updatedAtEpochMillis + 10)
        val service = service(request, admitted)
        val coordinator = DefaultAppOperationalStateCoordinator(service, Store())
        coordinator.start(request)
        coEvery { service.lifecycleOperation(request.operationId) } returns terminal
        coordinator.operation(request.operationId)
        coEvery { service.appOperationalSnapshot(account, device) } returns snapshot(listOf(request.operationId))
        coEvery { service.lifecycleOperation(request.operationId) } returns admitted
        coordinator.refresh(account, device)
        assertThat(coordinator.operations.value[request.operationId]).isEqualTo(terminal)
        assertThat(terminal.steps.all { it.status == LifecycleStepStatus.PENDING }).isTrue()
    }

    @Test
    fun `older startup index restores newer known original after projection pruning without creating replacement`() = runTest {
        val original = request()
        val retry = original.copy(operationId = "bug036-new-retry-caller", idempotencyKey = "bug036-new-retry-intent")
        val admitted = operation(original)
        val newer = admitted.copy(updatedAtEpochMillis = admitted.updatedAtEpochMillis + 10, currentPhase = LifecyclePhase.CLIENT_APPLIED)
        val service = service(original, newer)
        val coordinator = DefaultAppOperationalStateCoordinator(service, Store())
        coordinator.start(original)
        coEvery { service.appOperationalSnapshot(account, device) } returns snapshot()
        coordinator.refresh(account, device)
        assertThat(coordinator.operations.value).doesNotContainKey(original.operationId)
        coEvery { service.startupLifecycleOperations(account, device, "post_login", null) } returns listOf(admitted)
        coEvery { service.resumeLifecycleOperation(original.operationId) } returns newer
        assertThat(coordinator.performResuming(retry) { it }).isEqualTo(original.operationId)
        assertThat(coordinator.operations.value[original.operationId]).isEqualTo(newer)
        coVerify(exactly = 1) { service.startLifecycleOperation(original) }
        coVerify(exactly = 0) { service.startLifecycleOperation(retry) }
    }

    @Test
    fun `response lost provisional start cannot authorize Android applied receipts`() = runTest {
        val request = request()
        val service = mockk<ManagementControlService>()
        val store = Store()
        coEvery { service.startLifecycleOperation(request) } throws java.io.IOException("response lost")
        val coordinator = DefaultAppOperationalStateCoordinator(service, store)
        assertThat(runCatching { coordinator.start(request) }.isFailure).isTrue()
        assertThat(runCatching { coordinator.persistClientReceipts(facts(request)) }.isFailure).isTrue()
        assertThat(store.pendingClientReceipts()).isEmpty()
        coVerify(exactly = 0) { service.recordLifecycleClientReceipt(any()) }
    }

    @Test
    fun `unknown operation cannot become admission by persisting an applied fact`() = runTest {
        val service = mockk<ManagementControlService>()
        val store = Store()
        val coordinator = DefaultAppOperationalStateCoordinator(service, store)
        assertThat(runCatching { coordinator.persistClientReceipts(facts(request())) }.isFailure).isTrue()
        assertThat(store.pendingClientReceipts()).isEmpty()
        coVerify(exactly = 0) { service.lifecycleOperation(any()) }
        coVerify(exactly = 0) { service.startLifecycleOperation(any()) }
    }

    @Test
    fun `expired original LC06 cannot execute with a newer caller deadline`() = runTest {
        val original = request().copy(deadlineEpochMillis = System.currentTimeMillis() - 1)
        val retry = original.copy(operationId = "bug036-new-caller-operation", idempotencyKey = "bug036-new-caller-intent",
            deadlineEpochMillis = System.currentTimeMillis() + 600_000)
        val admitted = operation(original)
        val service = service(retry, admitted)
        coEvery { service.startupLifecycleOperations(account, device, "post_login", null) } returns listOf(admitted)
        coEvery { service.resumeLifecycleOperation(original.operationId) } returns admitted
        val store = Store()
        val coordinator = DefaultAppOperationalStateCoordinator(service, store)
        var applied = false
        assertThat(runCatching { coordinator.performResuming(retry) { applied = true } }.isFailure).isTrue()
        assertThat(applied).isFalse()
        assertThat(coordinator.operations.value[original.operationId]?.deadlineEpochMillis).isEqualTo(original.deadlineEpochMillis)
        assertThat(store.pendingClientReceipts()).isEmpty()
        coVerify(exactly = 0) { service.startLifecycleOperation(any()) }
        coVerify(exactly = 0) { service.recordLifecycleClientReceipt(any()) }
    }

    @Test
    fun `authority cancellation during resume cannot run action or manufacture pending Android steps`() = runTest {
        val request = request()
        val admitted = operation(request)
        val cancelled = admitted.copy(status = LifecycleOperationStatus.CANCELLED, currentPhase = LifecyclePhase.RECEIPTED,
            terminalReceiptId = "signed-original-cancel-receipt", updatedAtEpochMillis = admitted.updatedAtEpochMillis + 1)
        val service = service(request, admitted)
        coEvery { service.startupLifecycleOperations(account, device, "post_login", null) } returns listOf(admitted)
        coEvery { service.resumeLifecycleOperation(request.operationId) } returns cancelled
        val store = Store()
        val coordinator = DefaultAppOperationalStateCoordinator(service, store)
        var applied = false
        assertThat(runCatching { coordinator.performResuming(request) { applied = true } }.isFailure).isTrue()
        assertThat(applied).isFalse()
        assertThat(coordinator.operations.value[request.operationId]).isEqualTo(cancelled)
        assertThat(store.pendingClientReceipts()).isEmpty()
        assertThat(cancelled.steps.all { it.status == LifecycleStepStatus.PENDING }).isTrue()
        coVerify(exactly = 0) { service.recordLifecycleClientReceipt(any()) }
    }

    @Test
    fun `resume response cannot replace original LC06 identity before applying startup action`() = runTest {
        val request = request()
        val admitted = operation(request)
        val service = service(request, admitted)
        coEvery { service.startupLifecycleOperations(account, device, "post_login", null) } returns listOf(admitted)
        coEvery { service.resumeLifecycleOperation(request.operationId) } returns admitted.copy(operationId = "bug036-wrong-resumed-operation")
        val store = Store()
        val coordinator = DefaultAppOperationalStateCoordinator(service, store)
        var applied = false
        assertThat(runCatching { coordinator.performResuming(request) { applied = true } }.isFailure).isTrue()
        assertThat(applied).isFalse()
        assertThat(coordinator.operations.value[request.operationId]?.deadlineEpochMillis).isEqualTo(request.deadlineEpochMillis)
        assertThat(store.pendingClientReceipts()).isEmpty()
        coVerify(exactly = 0) { service.startLifecycleOperation(any()) }
        coVerify(exactly = 0) { service.recordLifecycleClientReceipt(any()) }
    }

    @Test
    fun `startup admission with mismatched account device or lifecycle never executes product action`() = runTest {
        val request = request()
        val admitted = operation(request)
        for (invalid in listOf(
            admitted.copy(subjects = admitted.subjects.copy(accountIdHash = "c".repeat(64))),
            admitted.copy(subjects = admitted.subjects.copy(matrixDeviceIdHash = "d".repeat(64))),
            admitted.copy(subjects = admitted.subjects.copy(installationId = "another-installation-bug036")),
            admitted.copy(subjects = admitted.subjects.copy(roomIdHash = "e".repeat(64))),
            admitted.copy(lifecycleId = FullAppLifecycleId.LC_02),
        )) {
            val service = service(request, invalid)
            val store = Store()
            val coordinator = DefaultAppOperationalStateCoordinator(service, store, draftInstallationProvider = { installation })
            var applied = false
            assertThat(runCatching { coordinator.performResuming(request) { applied = true } }.isFailure).isTrue()
            assertThat(applied).isFalse()
            assertThat(store.pendingClientReceipts()).isEmpty()
            coVerify(exactly = 0) { service.recordLifecycleClientReceipt(any()) }
        }
    }

    @Test
    fun `retained startup reconciles expired terminal predecessor and admits current intent without manual retry`() = runTest {
        val original = request().copy(deadlineEpochMillis = System.currentTimeMillis() + 60_000)
        val current = request()
        val old = operation(original)
        val terminal = old.copy(status = LifecycleOperationStatus.TERMINATED, currentPhase = LifecyclePhase.RECEIPTED,
            terminalReceiptId = "original-expiry-receipt", updatedAtEpochMillis = old.updatedAtEpochMillis + 1)
        val fresh = operation(current)
        val service = service(current, fresh)
        coEvery { service.startLifecycleOperation(original) } returns old
        coEvery { service.resumeLifecycleOperation(original.operationId) } returns terminal
        val coordinator = DefaultAppOperationalStateCoordinator(service, Store(), draftInstallationProvider = { installation },
            pendingStartRetryClock = { original.deadlineEpochMillis + 1 })
        coordinator.start(original)

        assertThat(coordinator.performResuming(current) { it }).isEqualTo(current.operationId)

        assertThat(coordinator.operations.value[original.operationId]).isEqualTo(terminal)
        coVerify(exactly = 1) { service.resumeLifecycleOperation(original.operationId) }
        coVerify(exactly = 1) { service.startLifecycleOperation(current) }
        coVerify(exactly = 0) { service.recordLifecycleClientReceipt(match { it.operationId == original.operationId }) }
    }

    @Test
    fun `automatic startup handoff never retries cancellation rejection or missing new intent`() = runTest {
        for ((status, newIntent) in listOf(
            LifecycleOperationStatus.CANCELLED to true,
            LifecycleOperationStatus.FAILED_FINAL to true,
            LifecycleOperationStatus.TERMINATED to false,
        )) {
            val original = request().copy(deadlineEpochMillis = System.currentTimeMillis() + 60_000)
            val current = request()
            val old = operation(original)
            val terminal = old.copy(status = status, currentPhase = LifecyclePhase.RECEIPTED,
                terminalReceiptId = "original-terminal-receipt", updatedAtEpochMillis = old.updatedAtEpochMillis + 1)
            val service = service(original, old)
            coEvery { service.resumeLifecycleOperation(original.operationId) } returns terminal
            val coordinator = DefaultAppOperationalStateCoordinator(service, Store(), pendingStartRetryClock = { original.deadlineEpochMillis + 1 })
            coordinator.start(original)

            assertThat(runCatching {
                coordinator.performResuming(current, hasNewStartupIntent = newIntent) { error("must stay closed") }
            }.isFailure).isTrue()
            coVerify(exactly = 0) { service.startLifecycleOperation(current) }
            coVerify(exactly = 0) { service.recordLifecycleClientReceipt(any()) }
        }
    }

    @Test
    fun `automatic startup handoff cannot use terminal reply from another installation`() = runTest {
        val original = request().copy(deadlineEpochMillis = System.currentTimeMillis() + 60_000)
        val current = request()
        val old = operation(original)
        val terminal = old.copy(status = LifecycleOperationStatus.TERMINATED, currentPhase = LifecyclePhase.RECEIPTED,
            subjects = old.subjects.copy(installationId = "wrong-installation-background"),
            terminalReceiptId = "original-terminal-receipt", updatedAtEpochMillis = old.updatedAtEpochMillis + 1)
        val service = service(original, old)
        coEvery { service.resumeLifecycleOperation(original.operationId) } returns terminal
        val coordinator = DefaultAppOperationalStateCoordinator(service, Store(), draftInstallationProvider = { installation },
            pendingStartRetryClock = { original.deadlineEpochMillis + 1 })
        coordinator.start(original)
        assertThat(runCatching { coordinator.performResuming(current) { error("must stay closed") } }.isFailure).isTrue()
        coVerify(exactly = 0) { service.startLifecycleOperation(current) }
    }

    @Test
    fun `automatic startup handoff is bounded when index discovers another ended predecessor`() = runTest {
        val original = request().copy(deadlineEpochMillis = System.currentTimeMillis() + 60_000)
        val another = request().copy(deadlineEpochMillis = System.currentTimeMillis() + 60_000)
        val current = request()
        val old = operation(original)
        val second = operation(another)
        fun ended(value: LifecycleOperation) = value.copy(status = LifecycleOperationStatus.TERMINATED,
            currentPhase = LifecyclePhase.RECEIPTED, terminalReceiptId = "terminal:${value.operationId}",
            updatedAtEpochMillis = value.updatedAtEpochMillis + 1)
        val service = service(original, old)
        coEvery { service.resumeLifecycleOperation(original.operationId) } returns ended(old)
        coEvery { service.startupLifecycleOperations(account, device, "post_login", null) } returns listOf(second)
        coEvery { service.resumeLifecycleOperation(another.operationId) } returns ended(second)
        val coordinator = DefaultAppOperationalStateCoordinator(service, Store(), pendingStartRetryClock = { original.deadlineEpochMillis + 1 })
        coordinator.start(original)

        assertThat(runCatching { coordinator.performResuming(current) { error("must stay closed") } }.isFailure).isTrue()
        coVerify(exactly = 1) { service.resumeLifecycleOperation(original.operationId) }
        coVerify(exactly = 1) { service.resumeLifecycleOperation(another.operationId) }
        coVerify(exactly = 0) { service.startLifecycleOperation(current) }
    }

    @Test
    fun `expired retained attempt stays unresolved while signed index admits a real new launch`() = runTest {
        val original = request().copy(deadlineEpochMillis = System.currentTimeMillis() + 60_000)
        val current = request()
        val old = operation(original)
        val service = service(current, operation(current))
        coEvery { service.startLifecycleOperation(original) } returns old
        coEvery { service.resumeLifecycleOperation(original.operationId) } returns old
        val coordinator = DefaultAppOperationalStateCoordinator(service, Store(), draftInstallationProvider = { installation },
            pendingStartRetryClock = { original.deadlineEpochMillis + 1 })
        coordinator.start(original)

        assertThat(coordinator.performResuming(current) { it }).isEqualTo(current.operationId)

        assertThat(coordinator.operations.value[original.operationId]).isEqualTo(old)
        assertThat(coordinator.operations.value[original.operationId]?.terminalReceiptId).isNull()
        coVerify(exactly = 1) { service.startupLifecycleOperations(account, device, "post_login", null) }
        coVerify(exactly = 1) { service.startLifecycleOperation(current) }
        coVerify(exactly = 0) { service.recordLifecycleClientReceipt(match { it.operationId == original.operationId }) }
    }

    @Test
    fun `failed index read cannot replace expired original and automatic retry keeps current request`() = runTest {
        val original = request().copy(deadlineEpochMillis = System.currentTimeMillis() + 60_000)
        val current = request()
        val old = operation(original)
        val service = service(current, operation(current))
        coEvery { service.startLifecycleOperation(original) } returns old
        coEvery { service.resumeLifecycleOperation(original.operationId) } returns old
        coEvery { service.startupLifecycleOperations(account, device, "post_login", null) } throws java.io.IOException("offline")
        val coordinator = DefaultAppOperationalStateCoordinator(service, Store(),
            pendingStartRetryClock = { original.deadlineEpochMillis + 1 })
        coordinator.start(original)

        assertThat(runCatching { coordinator.performResuming(current) { error("must not execute") } }.isFailure).isTrue()
        coVerify(exactly = 0) { service.startLifecycleOperation(current) }
        assertThat(coordinator.operations.value[original.operationId]).isEqualTo(old)
        coEvery { service.startupLifecycleOperations(account, device, "post_login", null) } returns emptyList()
        assertThat(coordinator.performResuming(current) { it }).isEqualTo(current.operationId)
        coVerify(exactly = 1) { service.startLifecycleOperation(current) }
    }

    @Test
    fun `expiry reconciliation refuses wrong index scope original index entry and outstanding receipts`() = runTest {
        for (scenario in listOf("account", "installation", "still-indexed", "pending-receipt", "same-intent", "no-intent")) {
            val original = request().copy(deadlineEpochMillis = System.currentTimeMillis() + 60_000)
            val current = if (scenario == "same-intent") original.copy(deadlineEpochMillis = original.deadlineEpochMillis + 600_000) else request()
            val old = operation(original)
            val service = service(original, old)
            coEvery { service.resumeLifecycleOperation(original.operationId) } returns old
            val indexEntry = when (scenario) {
                "account" -> operation(current).copy(subjects = old.subjects.copy(accountIdHash = "c".repeat(64)))
                "installation" -> operation(current).copy(subjects = old.subjects.copy(installationId = "another-installation"))
                "still-indexed" -> old
                else -> null
            }
            coEvery { service.startupLifecycleOperations(account, device, "post_login", null) } returns listOfNotNull(indexEntry)
            val store = Store()
            val coordinator = DefaultAppOperationalStateCoordinator(service, store, draftInstallationProvider = { installation },
                pendingStartRetryClock = { original.deadlineEpochMillis + 1 })
            coordinator.start(original)
            if (scenario == "pending-receipt") coordinator.persistClientReceipts(facts(original))

            assertThat(runCatching {
                coordinator.performResuming(current, hasNewStartupIntent = scenario != "no-intent") { error("must not execute") }
            }.isFailure).isTrue()
            coVerify(exactly = 1) { service.startLifecycleOperation(any()) }
            coVerify(exactly = 0) { service.recordLifecycleClientReceipt(any()) }
            assertThat(coordinator.operations.value[original.operationId]).isEqualTo(old)
            if (scenario == "pending-receipt") assertThat(store.pendingClientReceipts()).hasSize(2)
        }
    }

    @Test
    fun `multiple expired records cannot trap later launches and remain individually unresolved`() = runTest {
        val original = request().copy(deadlineEpochMillis = System.currentTimeMillis() + 60_000)
        val older = request().copy(deadlineEpochMillis = original.deadlineEpochMillis)
        val current = request()
        val old = operation(original)
        val earlier = operation(older).copy(updatedAtEpochMillis = old.updatedAtEpochMillis - 1)
        val service = service(current, operation(current))
        coEvery { service.startLifecycleOperation(original) } returns old
        coEvery { service.startLifecycleOperation(older) } returns earlier
        coEvery { service.resumeLifecycleOperation(original.operationId) } returns old
        val coordinator = DefaultAppOperationalStateCoordinator(service, Store(),
            pendingStartRetryClock = { original.deadlineEpochMillis + 1 })
        coordinator.start(older)
        coordinator.start(original)

        assertThat(coordinator.performResuming(current) { it }).isEqualTo(current.operationId)
        assertThat(coordinator.operations.value[older.operationId]).isEqualTo(earlier)
        assertThat(coordinator.operations.value[original.operationId]).isEqualTo(old)
        coVerify(exactly = 1) { service.startLifecycleOperation(current) }
        coVerify(exactly = 0) { service.resumeLifecycleOperation(older.operationId) }
    }

    @Test
    fun `transient index transport refreshes automatically before any action with one current admission`() = runTest {
        val request = request()
        val service = service(request, operation(request))
        var calls = 0
        coEvery { service.startupLifecycleOperations(account, device, "post_login", null) } answers {
            calls += 1
            if (calls < 3) throw java.io.IOException("temporary network loss")
            emptyList()
        }
        val coordinator = DefaultAppOperationalStateCoordinator(service, Store())
        var actions = 0
        coordinator.performResuming(request) { actions += 1 }
        assertThat(calls).isEqualTo(3)
        assertThat(actions).isEqualTo(1)
        assertThat(testScheduler.currentTime).isEqualTo(3_000L)
        coVerify(exactly = 1) { service.startLifecycleOperation(request) }
    }

    @Test
    fun `transport error after business action begins cannot automatically repeat its effects`() = runTest {
        val request = request()
        val service = service(request, operation(request))
        val coordinator = DefaultAppOperationalStateCoordinator(service, Store())
        var actions = 0
        assertThat(runCatching {
            coordinator.performResuming(request) { actions += 1; throw java.io.IOException("action interrupted") }
        }.isFailure).isTrue()
        assertThat(actions).isEqualTo(1)
        assertThat(testScheduler.currentTime).isEqualTo(0L)
        coVerify(exactly = 1) { service.startLifecycleOperation(request) }
        coVerify(exactly = 0) { service.recordLifecycleClientReceipt(any()) }
    }

    private fun request() = lifecycleOperationRequest(FullAppLifecycleId.LC_06, "post_login", accountIdHash = account,
        matrixDeviceIdHash = device, deadlineEpochMillis = System.currentTimeMillis() + 600_000)

    private fun operation(request: LifecycleOperationRequest) = LifecycleOperation(
        operationId = request.operationId, lifecycleId = request.lifecycleId, idempotencyKey = request.idempotencyKey,
        subjects = LifecycleSubjectHashes(accountIdHash = account, matrixDeviceIdHash = device, installationId = installation),
        status = LifecycleOperationStatus.RUNNING, currentPhase = LifecyclePhase.PROJECTED,
        deadlineEpochMillis = request.deadlineEpochMillis, createdAtEpochMillis = minOf(System.currentTimeMillis(), request.deadlineEpochMillis - 600_000),
        updatedAtEpochMillis = System.currentTimeMillis(),
        steps = listOf(LifecycleComponent.ANDROID_SERVICE, LifecycleComponent.ANDROID_FRONTEND).map {
            LifecycleStep(stepId = "${request.operationId}:${it.name}", component = it, status = LifecycleStepStatus.PENDING,
                attemptCount = 0, updatedAtEpochMillis = System.currentTimeMillis())
        },
    )

    private fun service(request: LifecycleOperationRequest, admitted: LifecycleOperation) = mockk<ManagementControlService>().also {
        coEvery { it.startupLifecycleOperations(account, device, "post_login", null) } returns emptyList()
        coEvery { it.startLifecycleOperation(request) } returns admitted
        coEvery { it.recordLifecycleClientReceipt(any()) } returns admitted
    }

    private fun facts(request: LifecycleOperationRequest) = listOf(LifecycleComponent.ANDROID_SERVICE, LifecycleComponent.ANDROID_FRONTEND).map {
        LifecycleClientReceipt(request.operationId, it, LifecyclePhase.CLIENT_APPLIED, "${it.name}_APPLIED",
            "${it.name.lowercase().replace('_', '-')}:${request.operationId}", System.currentTimeMillis())
    }

    private fun snapshot(ids: List<String> = emptyList()): AppOperationalSnapshot {
        val now = System.currentTimeMillis()
        return AppOperationalSnapshot(accountIdHash = account, matrixDeviceIdHash = device, installationId = installation,
            issuedAtEpochMillis = now, expiresAtEpochMillis = now + 60_000, overallStatus = AppOperationalStatus.READY,
            components = REQUIRED_OPERATIONAL_COMPONENTS.associateWith {
                ComponentOperationalState(AppOperationalStatus.READY, it, "mock-revision", now, now + 60_000,
                    receiptReference = "mock-component-receipt")
            }, activeOperationIds = ids, signingKeyId = "mock-authority-key", signatureBase64 = "mock-signature-for-test")
    }

    private class Store : LifecycleOperationIdStore {
        private var receipts = emptyList<LifecycleClientReceipt>()
        override fun latest(lifecycleId: FullAppLifecycleId): String? = null
        override fun remember(operation: LifecycleOperation) = Unit
        override fun pendingClientReceipts() = receipts.toList()
        override fun savePendingClientReceipts(receipts: List<LifecycleClientReceipt>) { this.receipts = receipts.toList() }
    }
}
