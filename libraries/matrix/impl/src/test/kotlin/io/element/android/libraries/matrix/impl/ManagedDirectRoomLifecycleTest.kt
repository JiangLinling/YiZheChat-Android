package io.element.android.libraries.matrix.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleClientReceipt
import io.element.android.security.dataprotection.api.LifecycleComponent
import io.element.android.security.dataprotection.api.LifecycleOperation
import io.element.android.security.dataprotection.api.LifecycleOperationStatus
import io.element.android.security.dataprotection.api.LifecyclePhase
import io.element.android.security.dataprotection.api.LifecycleStep
import io.element.android.security.dataprotection.api.LifecycleStepStatus
import io.element.android.security.dataprotection.api.LifecycleSubjectHashes
import io.element.android.security.dataprotection.api.ManagedDirectRoomResult
import io.element.android.security.dataprotection.api.ManagementControlService
import io.element.android.security.dataprotection.api.admitManagedDirectRoomOperations
import io.element.android.security.dataprotection.api.managedDirectRoomApplicationReceipts
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ManagedDirectRoomLifecycleTest {
    private val service = mockk<ManagementControlService>()
    private val coordinator = mockk<AppOperationalStateCoordinator>(relaxed = true)
    private val result = ManagedDirectRoomResult("!original:test", "INVITED", listOf("direct-" + "c".repeat(64), "dmroom-" + "c".repeat(64)))
    private fun original(id: String): LifecycleOperation {
        val child = id.startsWith("dmroom-")
        val components = listOf(
            LifecycleComponent.MATRIX,
            LifecycleComponent.CONTROL_PLANE,
            LifecycleComponent.DATABASE,
            LifecycleComponent.ANDROID_SERVICE,
            LifecycleComponent.ANDROID_FRONTEND
        ) + if (child) listOf(LifecycleComponent.KMS) else emptyList()
        return LifecycleOperation(
            operationId = id,
            lifecycleId = if (child) FullAppLifecycleId.LC_12 else FullAppLifecycleId.LC_11,
            idempotencyKey = "c".repeat(64),
            subjects = LifecycleSubjectHashes(accountIdHash = "a".repeat(64), matrixDeviceIdHash = "b".repeat(64)),
            status = LifecycleOperationStatus.RUNNING,
            currentPhase = LifecyclePhase.CLIENT_APPLIED,
            authorityRevisions = if (child) {
                mapOf("room_create_protocol" to "managed-v1", "direct_invitation_operation" to "direct-" + "c".repeat(64))
            } else {
                mapOf("direct_invitation_v1" to "original signed intent")
            },
            steps = components.map { LifecycleStep("$id:${it.name}", it, status = LifecycleStepStatus.PENDING, attemptCount = 0, updatedAtEpochMillis = 1000) },
            deadlineEpochMillis = 2000,
            createdAtEpochMillis = 1000,
            updatedAtEpochMillis = 1000
        )
    }
    init {
        coEvery { coordinator.operation(any()) } coAnswers { original(firstArg()) }
    }
    private fun action(awaitRoom: suspend (String) -> Unit = {}) = ManagedDirectRoomLifecycle(service, coordinator, "a".repeat(64), "b".repeat(64), awaitRoom)
    @Test fun `response loss and cold start query same authority before applying original receipts`() = runTest {
        every { service.supportsManagedRoomCreation } returns true
        coEvery { service.managedDirectRoom(any(), any(), any(), true) } throws IllegalStateException("500 unknown")
        assertThat(runCatching { action().execute("@peer:test", true) }.isFailure).isTrue()
        coVerify(exactly = 0) { coordinator.enqueueClientReceipts(any()) }
        coEvery { service.managedDirectRoom(any(), any(), any(), false) } returns result
        val applied = mutableListOf<String>()
        assertThat(action { applied.add(it) }.execute("@peer:test", false)).isEqualTo(result.roomId)
        assertThat(applied).containsExactly(result.roomId)
        coVerify(exactly = 1) {
            coordinator.enqueueClientReceipts(match { receipts ->
                receipts.map { it.operationId } == result.clientOperationIds &&
            receipts.all { it.component == LifecycleComponent.ANDROID_SERVICE }
            })
        }
        coVerify(exactly = 0) { coordinator.start(any()) }
    }
    @Test fun `local sync timeout never claims applied or creates another operation`() = runTest {
        every { service.supportsManagedRoomCreation } returns true
        coEvery { service.managedDirectRoom(any(), any(), any(), true) } returns result
        assertThat(runCatching { action { throw IllegalStateException("sync timeout") }.execute("@peer:test", true) }.isFailure).isTrue()
        coVerify(exactly = 0) { coordinator.enqueueClientReceipts(any()) }
        coVerify(exactly = 0) { coordinator.start(any()) }
    }
    @Test fun `cancellation preserves unknown origin without applying receipts`() = runTest {
        every { service.supportsManagedRoomCreation } returns true
        coEvery { service.managedDirectRoom(any(), any(), any(), true) } throws CancellationException()
        assertThat(runCatching { action().execute("@peer:test", true) }.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
        coVerify(exactly = 0) { coordinator.enqueueClientReceipts(any()) }
    }
    @Test fun `absent lookup has no creation or applied receipt`() = runTest {
        every { service.supportsManagedRoomCreation } returns true
        coEvery { service.managedDirectRoom(any(), any(), any(), false) } returns null
        assertThat(action { error("must not apply") }.execute("@peer:test", false)).isNull()
        coVerify(exactly = 0) { coordinator.enqueueClientReceipts(any()) }
        coVerify(exactly = 0) { service.managedDirectRoom(any(), any(), any(), true) }
    }
    @Test fun `unavailable managed protocol cannot fall back to ordinary create`() = runTest {
        every { service.supportsManagedRoomCreation } returns false
        assertThat(runCatching { action().execute("@peer:test", true) }.isFailure).isTrue()
        coVerify(exactly = 0) { service.managedDirectRoom(any(), any(), any(), any()) }
    }

    @Test fun `cold coordinator admits original signed operations before persisting service facts`() = runTest {
        every { service.supportsManagedRoomCreation } returns true
        coEvery { service.managedDirectRoom(any(), any(), any(), true) } returns result
        val admitted = mutableSetOf<String>()
        coEvery { coordinator.operation(any()) } coAnswers { original(firstArg()).also { admitted.add(it.operationId) } }
        coEvery { coordinator.enqueueClientReceipts(any()) } coAnswers {
            assertThat(admitted).containsAtLeastElementsIn(firstArg<List<LifecycleClientReceipt>>().map { it.operationId })
        }
        assertThat(action().execute("@peer:test", true)).isEqualTo(result.roomId)
        coVerify(exactly = 2) { coordinator.operation(any()) }
        coVerify(exactly = 0) { coordinator.start(any()) }
    }
    @Test fun `foreign signed operation is rejected before any client acknowledgement`() = runTest {
        every { service.supportsManagedRoomCreation } returns true
        coEvery { service.managedDirectRoom(any(), any(), any(), true) } returns result
        coEvery { coordinator.operation(any()) } coAnswers
            { original(firstArg()).copy(subjects = LifecycleSubjectHashes(accountIdHash = "d".repeat(64), matrixDeviceIdHash = "b".repeat(64))) }
        assertThat(runCatching { action().execute("@peer:test", true) }.isFailure).isTrue()
        coVerify(exactly = 0) { coordinator.enqueueClientReceipts(any()) }
    }
    @Test fun `expired original deadline permits actual late acknowledgement without renewal`() = runTest {
        val operations = coordinator.admitManagedDirectRoomOperations(result, "a".repeat(64), "b".repeat(64))
        assertThat(operations.map { it.deadlineEpochMillis }).containsExactly(2000L, 2000L)
        assertThat(managedDirectRoomApplicationReceipts(operations, LifecycleComponent.ANDROID_FRONTEND)).hasSize(2)
        coVerify(exactly = 0) { coordinator.start(any()) }
    }
    @Test fun `one lookup carries its original binding and freshness after real local service admission`() = runTest {
        every { service.supportsManagedRoomCreation } returns true
        val signed = result.copy(installationId = "installation", issuedAtEpochMillis = 1000, expiresAtEpochMillis = 12000)
        coEvery { service.managedDirectRoom(any(), any(), any(), false) } returns signed
        val order = mutableListOf<String>()
        coEvery { coordinator.enqueueClientReceipts(any()) } coAnswers { order.add("service") }
        val resolved = action { order.add("local") }.resolve("@peer:test", false)!!
        assertThat(order).containsExactly("local", "service").inOrder()
        assertThat(resolved.roomId.value).isEqualTo(result.roomId)
        val binding = resolved.managedEvidence!!
        assertThat(binding.operationIds).containsExactlyElementsIn(result.clientOperationIds)
        assertThat(binding.accountIdHash).isEqualTo("a".repeat(64))
        assertThat(binding.matrixDeviceIdHash).isEqualTo("b".repeat(64))
        assertThat(binding.peerUserId.value).isEqualTo("@peer:test")
        assertThat(binding.installationId).isEqualTo("installation")
        assertThat(binding.expiresAtEpochMillis).isEqualTo(12000L)
        coVerify(exactly = 1) { service.managedDirectRoom(any(), any(), any(), false) }
        coVerify(exactly = 0) { coordinator.enqueueClientReceipts(match { it.any { r -> r.component == LifecycleComponent.ANDROID_FRONTEND } }) }
    }
    @Test fun `no pending owned operations does not rewrite or wake the durable receipt queue`() = runTest {
        every { service.supportsManagedRoomCreation } returns true
        coEvery { service.managedDirectRoom(any(), any(), any(), false) } returns result.copy(clientOperationIds = emptyList())
        assertThat(action().resolve("@peer:test", false)?.roomId?.value).isEqualTo(result.roomId)
        coVerify(exactly = 0) { coordinator.operation(any()) }
        coVerify(exactly = 0) { coordinator.enqueueClientReceipts(any()) }
    }
}
