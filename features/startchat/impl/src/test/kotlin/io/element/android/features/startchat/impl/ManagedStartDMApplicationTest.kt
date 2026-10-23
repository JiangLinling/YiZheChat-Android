/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.startchat.impl

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.architecture.AsyncAction
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.room.DirectRoomOperationEvidence
import io.element.android.libraries.matrix.api.room.DirectRoomResolution
import io.element.android.libraries.matrix.test.A_ROOM_ID
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.libraries.matrix.test.encryption.FakeEncryptionService
import io.element.android.libraries.matrix.ui.components.aMatrixUser
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
import io.element.android.security.securestorage.api.DeviceTrustService
import io.element.android.security.securestorage.api.DeviceTrustSnapshot
import io.element.android.security.securestorage.api.accountIdHash
import io.element.android.services.analytics.test.FakeAnalyticsService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Test

class ManagedStartDMApplicationTest {
    private val matrix = FakeMatrixClient(encryptionService = FakeEncryptionService(getUserIdentityResult = { Result.success(null) }))
        .apply { givenFindDmResult(Result.success(A_ROOM_ID)) }
    private val control = mockk<ManagementControlService>()
    private val coordinator = mockk<AppOperationalStateCoordinator>(relaxed = true)
    private val parent = "direct-" + "c".repeat(64)
    private val trust = mockk<DeviceTrustService>()
    private fun evidence() = DirectRoomOperationEvidence(
        A_ROOM_ID,
        accountIdHash(matrix.sessionId.value),
        accountIdHash(matrix.deviceId.value),
        aMatrixUser().userId,
        "INVITED",
        listOf(parent),
        "test-installation",
        System.currentTimeMillis() - 1000,
        System.currentTimeMillis() + 10000
    )
    private var resolveCount = 0
    private val admitted = mutableListOf<String>()
    private fun original(id: String) = LifecycleOperation(
        operationId = id,
        lifecycleId = FullAppLifecycleId.LC_11,
        idempotencyKey = "c".repeat(64),
        subjects = LifecycleSubjectHashes(accountIdHash = accountIdHash(matrix.sessionId.value), matrixDeviceIdHash = accountIdHash(matrix.deviceId.value)),
        status = LifecycleOperationStatus.RUNNING,
        currentPhase = LifecyclePhase.CLIENT_APPLIED,
        authorityRevisions = mapOf("direct_invitation_v1" to "original signed intent"),
        steps = listOf(
            LifecycleComponent.MATRIX,
            LifecycleComponent.CONTROL_PLANE,
            LifecycleComponent.DATABASE,
            LifecycleComponent.ANDROID_SERVICE,
            LifecycleComponent.ANDROID_FRONTEND
        ).map {
            LifecycleStep("$id:${it.name}", it, status = LifecycleStepStatus.PENDING, attemptCount = 0, updatedAtEpochMillis = 1000)
        },
        deadlineEpochMillis = 2000,
        createdAtEpochMillis = 1000,
        updatedAtEpochMillis = 1000
    )
    init {
        every { control.supportsManagedRoomCreation } returns true
        matrix.resolveDmResult = { _, _ ->
            resolveCount++
            Result.success(DirectRoomResolution(A_ROOM_ID, evidence()))
        }
        coEvery { trust.current() } returns mockk<DeviceTrustSnapshot> {
            every { installationId } returns "test-installation"
            every { allowsLocalVaultAccess } returns true
        }
        coEvery { coordinator.operation(any()) } coAnswers { original(firstArg()).also { admitted.add(it.operationId) } }
    }
    private fun action() = DefaultStartDMAction(matrix, FakeAnalyticsService(), control, coordinator, trust)

    @Test fun `frontend transition admits original then queues its actual applied fact`() = runTest {
        val state = mutableStateOf<AsyncAction<RoomId>>(AsyncAction.Uninitialized)
        coEvery { coordinator.enqueueClientReceipts(any()) } coAnswers {
            assertThat(state.value).isEqualTo(AsyncAction.Success(A_ROOM_ID))
            assertThat(admitted).containsExactly(parent)
        }
        action().execute(aMatrixUser(), true, state)
        assertThat(state.value).isEqualTo(AsyncAction.Success(A_ROOM_ID))
        coVerify(exactly = 1) {
            coordinator.enqueueClientReceipts(match {
                it.single().operationId == parent &&
            it.single().component == LifecycleComponent.ANDROID_FRONTEND
            })
        }
        coVerify(exactly = 0) { coordinator.start(any()) }
        assertThat(resolveCount).isEqualTo(1)
        coVerify(exactly = 0) { control.managedDirectRoom(any(), any(), any(), any()) }
    }
    @Test fun `navigation cancellation after Success cannot lose the durable frontend enqueue`() = runTest {
        val stored = mutableStateOf<AsyncAction<RoomId>>(AsyncAction.Uninitialized)
        val owner = Job()
        val state = object : MutableState<AsyncAction<RoomId>> by stored {
            override var value: AsyncAction<RoomId>
                get() = stored.value
                set(value) {
                    stored.value = value
                    if (value is AsyncAction.Success) owner.cancel()
                }
        }
        var persisted = false
        coEvery { coordinator.enqueueClientReceipts(any()) } coAnswers {
            yield()
            persisted = true
        }
        val task = launch(owner) { action().execute(aMatrixUser(), true, state) }
        task.join()
        assertThat(persisted).isTrue()
        assertThat(state.value).isEqualTo(AsyncAction.Success(A_ROOM_ID))
    }
    @Test fun `failed signed confirmation remains recoverable and never claims a frontend fact`() = runTest {
        val state = mutableStateOf<AsyncAction<RoomId>>(AsyncAction.Uninitialized)
        matrix.resolveDmResult = { _, _ -> Result.failure(IllegalStateException("response loss")) }
        action().execute(aMatrixUser(), true, state)
        assertThat(state.value).isInstanceOf(AsyncAction.Failure::class.java)
        coVerify(exactly = 0) { coordinator.enqueueClientReceipts(any()) }
        matrix.resolveDmResult = { _, _ -> Result.success(DirectRoomResolution(A_ROOM_ID, evidence())) }
        action().execute(aMatrixUser(), true, state)
        assertThat(state.value).isEqualTo(AsyncAction.Success(A_ROOM_ID))
        coVerify(exactly = 0) { coordinator.start(any()) }
    }

    @Test fun `managed response cannot lose its metadata or change scope before frontend admission`() = runTest {
        val good = evidence()
        val invalid = listOf(
            null,
            good.copy(roomId = RoomId("!different:test")),
            good.copy(accountIdHash = "d".repeat(64)),
            good.copy(matrixDeviceIdHash = "d".repeat(64)),
            good.copy(peerUserId = UserId("@other:test")),
            good.copy(installationId = "other-installation"),
            good.copy(status = "REMOVED"),
            good.copy(issuedAtEpochMillis = 0),
            good.copy(expiresAtEpochMillis = System.currentTimeMillis() - 1),
            good.copy(expiresAtEpochMillis = System.currentTimeMillis() + 60000)
        )
        for (candidate in invalid) {
            matrix.resolveDmResult = { _, _ -> Result.success(DirectRoomResolution(A_ROOM_ID, candidate)) }
            val state = mutableStateOf<AsyncAction<RoomId>>(AsyncAction.Uninitialized)
            action().execute(aMatrixUser(), false, state)
            assertThat(state.value).isInstanceOf(AsyncAction.Failure::class.java)
        }
        coVerify(exactly = 0) { coordinator.enqueueClientReceipts(any()) }
        coVerify(exactly = 0) { control.managedDirectRoom(any(), any(), any(), any()) }
    }

    @Test fun `local room wait returning an expired response fails with retry code and preserves frontend pending`() = runTest {
        matrix.resolveDmResult = { _, _ ->
            Result.success(DirectRoomResolution(
                A_ROOM_ID,
            evidence().copy(issuedAtEpochMillis = System.currentTimeMillis() - 16000, expiresAtEpochMillis = System.currentTimeMillis() - 1000)
            ))
        }
        val state = mutableStateOf<AsyncAction<RoomId>>(AsyncAction.Uninitialized)
        val action = action()
        action.execute(aMatrixUser(), false, state)
        assertThat((state.value as AsyncAction.Failure).error.message).isEqualTo("LC11_DIRECT_RESPONSE_EXPIRED_RETRY")
        coVerify(exactly = 0) { coordinator.enqueueClientReceipts(any()) }
        matrix.resolveDmResult = { _, _ -> Result.success(DirectRoomResolution(A_ROOM_ID, evidence())) }
        action.execute(aMatrixUser(), false, state)
        assertThat(state.value).isEqualTo(AsyncAction.Success(A_ROOM_ID))
    }

    @Test fun `a rejected original operation cannot become a frontend success`() = runTest {
        coEvery { coordinator.operation(any()) } coAnswers {
            original(firstArg()).copy(subjects = LifecycleSubjectHashes(accountIdHash = "d".repeat(64)))
        }
        val state = mutableStateOf<AsyncAction<RoomId>>(AsyncAction.Uninitialized)
        action().execute(aMatrixUser(), false, state)
        assertThat(state.value).isInstanceOf(AsyncAction.Failure::class.java)
        coVerify(exactly = 0) { coordinator.enqueueClientReceipts(any()) }
    }

    @Test fun `loading deduplicates clicks and cancellation releases only its own invocation for retry`() = runTest {
        val gate = CompletableDeferred<Unit>()
        matrix.resolveDmResult = { _, _ ->
            resolveCount++
            gate.await()
            Result.success(DirectRoomResolution(A_ROOM_ID, evidence()))
        }
        val state = mutableStateOf<AsyncAction<RoomId>>(AsyncAction.Uninitialized)
        val action = action()
        val first = launch { action.execute(aMatrixUser(), false, state) }
        yield()
        action.execute(aMatrixUser(), false, state)
        assertThat(resolveCount).isEqualTo(1)
        first.cancelAndJoin()
        assertThat(state.value).isEqualTo(AsyncAction.Uninitialized)
        coVerify(exactly = 0) { coordinator.enqueueClientReceipts(any()) }
        gate.complete(Unit)
        action.execute(aMatrixUser(), false, state)
        assertThat(resolveCount).isEqualTo(2)
        assertThat(state.value).isEqualTo(AsyncAction.Success(A_ROOM_ID))
    }

    @Test fun `missing room still requires confirmation and uses only the newly created binding`() = runTest {
        val creates = mutableListOf<Boolean>()
        matrix.resolveDmResult = { _, create ->
            creates.add(create)
            Result.success(if (create) DirectRoomResolution(A_ROOM_ID, evidence()) else null)
        }
        val state = mutableStateOf<AsyncAction<RoomId>>(AsyncAction.Uninitialized)
        val action = action()
        action.execute(aMatrixUser(), false, state)
        assertThat(state.value).isInstanceOf(io.element.android.features.startchat.api.ConfirmingStartDmWithMatrixUser::class.java)
        coVerify(exactly = 0) { coordinator.enqueueClientReceipts(any()) }
        action.execute(aMatrixUser(), true, state)
        assertThat(creates).containsExactly(false, false, true).inOrder()
        assertThat(state.value).isEqualTo(AsyncAction.Success(A_ROOM_ID))
        coVerify(exactly = 0) { control.managedDirectRoom(any(), any(), any(), any()) }
    }

    @Test fun `signed invited and accepted rooms can have no operations owned by this installation`() = runTest {
        for (status in listOf("INVITED", "ACCEPTED")) {
            matrix.resolveDmResult = { _, _ -> Result.success(DirectRoomResolution(A_ROOM_ID, evidence().copy(status = status, operationIds = emptyList()))) }
            val state = mutableStateOf<AsyncAction<RoomId>>(AsyncAction.Uninitialized)
            action().execute(aMatrixUser(), false, state)
            assertThat(state.value).isEqualTo(AsyncAction.Success(A_ROOM_ID))
        }
        coVerify(exactly = 0) { coordinator.operation(any()) }
        coVerify(exactly = 0) { coordinator.enqueueClientReceipts(any()) }
        coVerify(exactly = 0) { control.managedDirectRoom(any(), any(), any(), any()) }
    }
}
