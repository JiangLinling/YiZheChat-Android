/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.logout.impl

import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.architecture.AsyncAction
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.test.AN_EXCEPTION
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.PermanentAccountDeletionHandle
import io.element.android.security.dataprotection.test.FakeAppOperationalStateCoordinator
import io.element.android.security.dataprotection.test.FakePermanentAccountDeletionCoordinator
import io.element.android.tests.testutils.WarmUpRule
import io.element.android.tests.testutils.lambda.lambdaRecorder
import io.element.android.tests.testutils.lambda.value
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class AccountDeactivationPresenterTest {
    @get:Rule
    val warmUpRule = WarmUpRule()

    @Test
    fun `present - initial state`() = runTest {
        val presenter = createPresenter()
        moleculeFlow(RecompositionMode.Immediate) {
            presenter.present()
        }.test {
            val initialState = awaitItem()
            assertThat(initialState.accountDeactivationAction).isEqualTo(AsyncAction.Uninitialized)
            assertThat(initialState.deactivateFormState).isEqualTo(DeactivateFormState.Default)
        }
    }

    @Test
    fun `present - form update`() = runTest {
        val presenter = createPresenter()
        moleculeFlow(RecompositionMode.Immediate) {
            presenter.present()
        }.test {
            val initialState = awaitItem()
            assertThat(initialState.deactivateFormState).isEqualTo(DeactivateFormState.Default)
            initialState.eventSink(AccountDeactivationEvents.SetPassword("password"))
            val updatedState2 = awaitItem()
            assertThat(updatedState2.deactivateFormState).isEqualTo(DeactivateFormState(password = "password", eraseData = true))
            assertThat(updatedState2.submitEnabled).isTrue()
        }
    }

    @Test
    fun `present - submit`() = runTest {
        val recorder = lambdaRecorder<String, Boolean, Result<Unit>> { _, _ ->
            Result.success(Unit)
        }
        val matrixClient = FakeMatrixClient(
            deactivateAccountResult = recorder
        )
        val coordinator = FakeAppOperationalStateCoordinator()
        val presenter = createPresenter(
            matrixClient = matrixClient,
            appOperationalStateCoordinator = coordinator,
        )
        moleculeFlow(RecompositionMode.Immediate) {
            presenter.present()
        }.test {
            val initialState = awaitItem()
            initialState.eventSink(AccountDeactivationEvents.SetPassword("password"))
            skipItems(1)
            initialState.eventSink(AccountDeactivationEvents.DeactivateAccount(isRetry = false))
            val updatedState = awaitItem()
            assertThat(updatedState.accountDeactivationAction).isEqualTo(AsyncAction.ConfirmingNoParams)
            updatedState.eventSink(AccountDeactivationEvents.DeactivateAccount(isRetry = false))
            val updatedState2 = awaitItem()
            assertThat(updatedState2.accountDeactivationAction).isEqualTo(AsyncAction.Loading)
            val finalState = awaitItem()
            assertThat(finalState.accountDeactivationAction).isEqualTo(AsyncAction.Success(Unit))
            recorder.assertions().isCalledOnce().with(value("password"), value(true))
            assertThat(coordinator.startedRequests).hasSize(1)
            assertThat(coordinator.resumingRequests).isEmpty()
            assertThat(coordinator.startedRequests.single().lifecycleId).isEqualTo(FullAppLifecycleId.LC_22)
            assertThat(coordinator.startedRequests.single().trigger).isEqualTo("permanent_delete")
            assertThat(coordinator.clientReceipts.map { it.operationId }.distinct()).containsExactly(
                coordinator.startedRequests.single().operationId
            )
        }
    }

    @Test
    fun `present - submit with error and retry`() = runTest {
        val recorder = lambdaRecorder<String, Boolean, Result<Unit>> { _, _ ->
            Result.failure(AN_EXCEPTION)
        }
        val matrixClient = FakeMatrixClient(
            deactivateAccountResult = recorder
        )
        val presenter = createPresenter(matrixClient)
        moleculeFlow(RecompositionMode.Immediate) {
            presenter.present()
        }.test {
            val initialState = awaitItem()
            initialState.eventSink(AccountDeactivationEvents.SetPassword("password"))
            initialState.eventSink(AccountDeactivationEvents.SetEraseData(true))
            skipItems(1)
            initialState.eventSink(AccountDeactivationEvents.DeactivateAccount(isRetry = false))
            val updatedState = awaitItem()
            assertThat(updatedState.accountDeactivationAction).isEqualTo(AsyncAction.ConfirmingNoParams)
            updatedState.eventSink(AccountDeactivationEvents.DeactivateAccount(isRetry = false))
            val updatedState2 = awaitItem()
            assertThat(updatedState2.accountDeactivationAction).isEqualTo(AsyncAction.Loading)
            val finalState = awaitItem()
            recorder.assertions().isCalledOnce().with(value("password"), value(true))
            assertThat(finalState.accountDeactivationAction.errorOrNull()).isInstanceOf(AN_EXCEPTION::class.java)
            assertThat(finalState.accountDeactivationAction.errorOrNull()?.message).isEqualTo(AN_EXCEPTION.message)
            // Retry
            finalState.eventSink(AccountDeactivationEvents.DeactivateAccount(isRetry = true))
            val finalState2 = awaitItem()
            assertThat(finalState2.accountDeactivationAction).isEqualTo(AsyncAction.Loading)
            val retryFailure = awaitItem().accountDeactivationAction.errorOrNull()
            assertThat(retryFailure).isInstanceOf(AN_EXCEPTION::class.java)
            assertThat(retryFailure?.message).isEqualTo(AN_EXCEPTION.message)
        }
    }

    @Test
    fun `present - frozen account resumes the durable LC22 operation instead of creating another identity`() = runTest {
        val handle = PermanentAccountDeletionHandle("operation-delete-resume-0001")
        val permanentDeletion = FakePermanentAccountDeletionCoordinator().apply {
            pendingHandle = handle
        }
        val coordinator = FakeAppOperationalStateCoordinator()
        coordinator.start(
            io.element.android.security.dataprotection.api.lifecycleOperationRequest(
                lifecycleId = FullAppLifecycleId.LC_22,
                trigger = "permanent_delete",
                accountIdHash = "0".repeat(64),
                matrixDeviceIdHash = "1".repeat(64),
                operationId = handle.value,
            )
        )
        val presenter = createPresenter(
            matrixClient = FakeMatrixClient(
                deactivateAccountResult = { _, _ -> Result.success(Unit) },
            ),
            appOperationalStateCoordinator = coordinator,
            permanentAccountDeletionCoordinator = permanentDeletion,
        )

        moleculeFlow(RecompositionMode.Immediate) { presenter.present() }.test {
            val initial = awaitItem()
            initial.eventSink(AccountDeactivationEvents.SetPassword("password"))
            skipItems(1)
            initial.eventSink(AccountDeactivationEvents.DeactivateAccount(isRetry = false))
            awaitItem().eventSink(AccountDeactivationEvents.DeactivateAccount(isRetry = false))
            assertThat(awaitItem().accountDeactivationAction).isEqualTo(AsyncAction.Loading)
            assertThat(awaitItem().accountDeactivationAction).isEqualTo(AsyncAction.Success(Unit))
        }

        assertThat(coordinator.startedRequests).hasSize(1)
        assertThat(coordinator.clientReceipts.map { it.operationId }.distinct()).containsExactly(handle.value)
    }

    @Test
    fun `present - submit with error and cancel`() = runTest {
        val recorder = lambdaRecorder<String, Boolean, Result<Unit>> { _, _ ->
            Result.failure(AN_EXCEPTION)
        }
        val matrixClient = FakeMatrixClient(
            deactivateAccountResult = recorder
        )
        val presenter = createPresenter(matrixClient)
        moleculeFlow(RecompositionMode.Immediate) {
            presenter.present()
        }.test {
            val initialState = awaitItem()
            initialState.eventSink(AccountDeactivationEvents.SetPassword("password"))
            initialState.eventSink(AccountDeactivationEvents.SetEraseData(true))
            skipItems(1)
            initialState.eventSink(AccountDeactivationEvents.DeactivateAccount(isRetry = false))
            val updatedState = awaitItem()
            assertThat(updatedState.accountDeactivationAction).isEqualTo(AsyncAction.ConfirmingNoParams)
            updatedState.eventSink(AccountDeactivationEvents.DeactivateAccount(isRetry = false))
            val updatedState2 = awaitItem()
            assertThat(updatedState2.accountDeactivationAction).isEqualTo(AsyncAction.Loading)
            val finalState = awaitItem()
            recorder.assertions().isCalledOnce().with(value("password"), value(true))
            assertThat(finalState.accountDeactivationAction.errorOrNull()).isInstanceOf(AN_EXCEPTION::class.java)
            assertThat(finalState.accountDeactivationAction.errorOrNull()?.message).isEqualTo(AN_EXCEPTION.message)
            // Cancel
            finalState.eventSink(AccountDeactivationEvents.CloseDialogs)
            val finalState2 = awaitItem()
            assertThat(finalState2.accountDeactivationAction).isEqualTo(AsyncAction.Uninitialized)
        }
    }
}

internal fun createPresenter(
    matrixClient: MatrixClient = FakeMatrixClient(),
    appOperationalStateCoordinator: FakeAppOperationalStateCoordinator = FakeAppOperationalStateCoordinator(),
    permanentAccountDeletionCoordinator: FakePermanentAccountDeletionCoordinator = FakePermanentAccountDeletionCoordinator(),
) = AccountDeactivationPresenter(
    matrixClient = matrixClient,
    appOperationalStateCoordinator = appOperationalStateCoordinator,
    permanentAccountDeletionCoordinator = permanentAccountDeletionCoordinator,
)
