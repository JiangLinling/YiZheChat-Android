/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.startchat.impl

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.architecture.AsyncAction
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.test.A_ROOM_ID
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.libraries.matrix.ui.components.aMatrixUser
import io.element.android.security.dataprotection.api.*
import io.element.android.security.securestorage.api.accountIdHash
import io.element.android.services.analytics.test.FakeAnalyticsService
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ManagedStartDMApplicationTest {
    private val matrix = FakeMatrixClient().apply { givenFindDmResult(Result.success(A_ROOM_ID)) }
    private val control = mockk<ManagementControlService>()
    private val coordinator = mockk<AppOperationalStateCoordinator>(relaxed=true)
    private val parent = "direct-"+"c".repeat(64)
    private val result = ManagedDirectRoomResult(A_ROOM_ID.value,"INVITED",listOf(parent))
    private val admitted = mutableListOf<String>()
    private fun original(id: String) = LifecycleOperation(operationId=id, lifecycleId=FullAppLifecycleId.LC_11,
        idempotencyKey="c".repeat(64), subjects=LifecycleSubjectHashes(accountIdHash=accountIdHash(matrix.sessionId.value), matrixDeviceIdHash=accountIdHash(matrix.deviceId.value)),
        status=LifecycleOperationStatus.RUNNING,currentPhase=LifecyclePhase.CLIENT_APPLIED,
        authorityRevisions=mapOf("direct_invitation_v1" to "original signed intent"),
        steps=listOf(LifecycleComponent.MATRIX,LifecycleComponent.CONTROL_PLANE,LifecycleComponent.DATABASE,LifecycleComponent.ANDROID_SERVICE,LifecycleComponent.ANDROID_FRONTEND).map {
            LifecycleStep("$id:${it.name}",it,status=LifecycleStepStatus.PENDING,attemptCount=0,updatedAtEpochMillis=1000)
        },deadlineEpochMillis=2000,createdAtEpochMillis=1000,updatedAtEpochMillis=1000)
    init {
        every { control.supportsManagedRoomCreation } returns true
        coEvery { control.managedDirectRoom(any(),any(),any(),false) } returns result
        coEvery { coordinator.operation(any()) } coAnswers { original(firstArg()).also { admitted.add(it.operationId) } }
    }
    private fun action() = DefaultStartDMAction(matrix,FakeAnalyticsService(),control,coordinator)

    @Test fun `frontend transition admits original then queues its actual applied fact`() = runTest {
        val state=mutableStateOf<AsyncAction<RoomId>>(AsyncAction.Uninitialized)
        coEvery { coordinator.enqueueClientReceipts(any()) } coAnswers {
            assertThat(state.value).isEqualTo(AsyncAction.Success(A_ROOM_ID))
            assertThat(admitted).containsExactly(parent)
        }
        action().execute(aMatrixUser(),true,state)
        assertThat(state.value).isEqualTo(AsyncAction.Success(A_ROOM_ID))
        coVerify(exactly=1) { coordinator.enqueueClientReceipts(match { it.single().operationId==parent && it.single().component==LifecycleComponent.ANDROID_FRONTEND }) }
        coVerify(exactly=0) { coordinator.start(any()) }
    }
    @Test fun `navigation cancellation after Success cannot lose the durable frontend enqueue`() = runTest {
        val stored=mutableStateOf<AsyncAction<RoomId>>(AsyncAction.Uninitialized)
        val owner=Job()
        val state=object : MutableState<AsyncAction<RoomId>> by stored {
            override var value: AsyncAction<RoomId>
                get()=stored.value
                set(value) { stored.value=value; if (value is AsyncAction.Success) owner.cancel() }
        }
        var persisted=false
        coEvery { coordinator.enqueueClientReceipts(any()) } coAnswers { yield();persisted=true }
        val task=launch(owner) { action().execute(aMatrixUser(),true,state) }
        task.join()
        assertThat(persisted).isTrue()
        assertThat(state.value).isEqualTo(AsyncAction.Success(A_ROOM_ID))
    }
    @Test fun `failed signed confirmation remains recoverable and never claims a frontend fact`() = runTest {
        val state=mutableStateOf<AsyncAction<RoomId>>(AsyncAction.Uninitialized)
        coEvery { control.managedDirectRoom(any(),any(),any(),false) } throws IllegalStateException("response loss")
        action().execute(aMatrixUser(),true,state)
        assertThat(state.value).isInstanceOf(AsyncAction.Failure::class.java)
        coVerify(exactly=0) { coordinator.enqueueClientReceipts(any()) }
        coEvery { control.managedDirectRoom(any(),any(),any(),false) } returns result
        action().execute(aMatrixUser(),true,state)
        assertThat(state.value).isEqualTo(AsyncAction.Success(A_ROOM_ID))
        coVerify(exactly=0) { coordinator.start(any()) }
    }
}
