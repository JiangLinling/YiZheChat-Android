/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.report

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.architecture.AsyncAction
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.matrix.test.AN_EVENT_ID
import io.element.android.libraries.matrix.test.A_USER_ID
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.tests.testutils.WarmUpRule
import io.element.android.tests.testutils.lambda.lambdaRecorder
import io.element.android.tests.testutils.lambda.value
import io.element.android.tests.testutils.test
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class ReportMessagePresenterTest {
    @get:Rule
    val warmUpRule = WarmUpRule()

    @Test
    fun `initial state is not submitted`() = runTest {
        createPresenter().test {
            val state = awaitItem()
            assertThat(state.reason).isEmpty()
            assertThat(state.blockUser).isFalse()
            assertThat(state.reportSubmitted).isFalse()
            assertThat(state.result).isInstanceOf(AsyncAction.Uninitialized::class.java)
        }
    }

    @Test
    fun `reason and block choice update before submission`() = runTest {
        createPresenter().test {
            val initial = awaitItem()
            initial.eventSink(ReportMessageEvent.UpdateReason("Spam"))
            assertThat(awaitItem().reason).isEqualTo("Spam")
            initial.eventSink(ReportMessageEvent.ToggleBlockUser)
            assertThat(awaitItem().blockUser).isTrue()
            initial.eventSink(ReportMessageEvent.ToggleBlockUser)
            assertThat(awaitItem().blockUser).isFalse()
        }
    }

    @Test
    fun `report passes no block user then ignores user separately`() = runTest {
        val report = lambdaRecorder<EventId, String, UserId?, Result<Unit>> { _, _, _ -> Result.success(Unit) }
        val ignore = lambdaRecorder<UserId, Result<Unit>> { Result.success(Unit) }
        createPresenter(
            room = FakeJoinedRoom(reportContentResult = report),
            client = FakeMatrixClient(ignoreUserResult = ignore),
        ).test {
            val initial = awaitItem()
            initial.eventSink(ReportMessageEvent.ToggleBlockUser)
            val blockEnabled = awaitItem()
            blockEnabled.eventSink(ReportMessageEvent.Report)
            assertThat(awaitItem().result).isInstanceOf(AsyncAction.Loading::class.java)
            var submitted = awaitItem()
            while (submitted.result is AsyncAction.Loading) {
                assertThat(submitted.reportSubmitted).isTrue()
                submitted = awaitItem()
            }
            assertThat(submitted.result).isInstanceOf(AsyncAction.Success::class.java)
        }
        report.assertions().isCalledOnce().with(value(AN_EVENT_ID), value(""), value(null))
        ignore.assertions().isCalledOnce().with(value(A_USER_ID))
    }

    @Test
    fun `report without block does not ignore user`() = runTest {
        val report = lambdaRecorder<EventId, String, UserId?, Result<Unit>> { _, _, _ -> Result.success(Unit) }
        val ignore = lambdaRecorder<UserId, Result<Unit>> { Result.success(Unit) }
        createPresenter(
            room = FakeJoinedRoom(reportContentResult = report),
            client = FakeMatrixClient(ignoreUserResult = ignore),
        ).test {
            val initial = awaitItem()
            initial.eventSink(ReportMessageEvent.Report)
            assertThat(awaitItem().result).isInstanceOf(AsyncAction.Loading::class.java)
            var submitted = awaitItem()
            while (submitted.result is AsyncAction.Loading) {
                assertThat(submitted.reportSubmitted).isTrue()
                submitted = awaitItem()
            }
            assertThat(submitted.reportSubmitted).isTrue()
            assertThat(submitted.result).isInstanceOf(AsyncAction.Success::class.java)
        }
        report.assertions().isCalledOnce()
        ignore.assertions().isNeverCalled()
    }

    @Test
    fun `failed report remains unsubmitted`() = runTest {
        val report = lambdaRecorder<EventId, String, UserId?, Result<Unit>> { _, _, _ ->
            Result.failure(Exception("Failed to report content"))
        }
        createPresenter(room = FakeJoinedRoom(reportContentResult = report)).test {
            val initial = awaitItem()
            initial.eventSink(ReportMessageEvent.Report)
            assertThat(awaitItem().result).isInstanceOf(AsyncAction.Loading::class.java)
            val failed = awaitItem()
            assertThat(failed.reportSubmitted).isFalse()
            assertThat(failed.result).isInstanceOf(AsyncAction.Failure::class.java)
            failed.eventSink(ReportMessageEvent.ClearError)
            assertThat(awaitItem().result).isInstanceOf(AsyncAction.Uninitialized::class.java)
        }
    }

    @Test
    fun `block retry does not submit report again`() = runTest {
        val report = lambdaRecorder<EventId, String, UserId?, Result<Unit>> { _, _, _ -> Result.success(Unit) }
        var attempt = 0
        val ignore = lambdaRecorder<UserId, Result<Unit>> {
            attempt += 1
            if (attempt == 1) Result.failure(Exception("Failed to block user")) else Result.success(Unit)
        }
        createPresenter(
            room = FakeJoinedRoom(reportContentResult = report),
            client = FakeMatrixClient(ignoreUserResult = ignore),
        ).test {
            val initial = awaitItem()
            initial.eventSink(ReportMessageEvent.ToggleBlockUser)
            val blockEnabled = awaitItem()
            blockEnabled.eventSink(ReportMessageEvent.Report)
            assertThat(awaitItem().result).isInstanceOf(AsyncAction.Loading::class.java)
            var failedBlock = awaitItem()
            while (failedBlock.result is AsyncAction.Loading) {
                assertThat(failedBlock.reportSubmitted).isTrue()
                failedBlock = awaitItem()
            }
            assertThat(failedBlock.reportSubmitted).isTrue()
            assertThat(failedBlock.result).isInstanceOf(AsyncAction.Failure::class.java)
            failedBlock.eventSink(ReportMessageEvent.UpdateReason("different payload"))
            failedBlock.eventSink(ReportMessageEvent.ToggleBlockUser)
            failedBlock.eventSink(ReportMessageEvent.ClearError)
            val retry = awaitItem()
            assertThat(retry.reason).isEmpty()
            assertThat(retry.blockUser).isTrue()
            retry.eventSink(ReportMessageEvent.Report)
            assertThat(awaitItem().result).isInstanceOf(AsyncAction.Loading::class.java)
            assertThat(awaitItem().result).isInstanceOf(AsyncAction.Success::class.java)
        }
        report.assertions().isCalledOnce()
        ignore.assertions().isCalledExactly(2)
    }

    @Test
    fun `repeated taps while submitting call report once`() = runTest {
        val report = lambdaRecorder<EventId, String, UserId?, Result<Unit>> { _, _, _ -> Result.success(Unit) }
        createPresenter(room = FakeJoinedRoom(reportContentResult = report)).test {
            val initial = awaitItem()
            initial.eventSink(ReportMessageEvent.Report)
            initial.eventSink(ReportMessageEvent.Report)
            assertThat(awaitItem().result).isInstanceOf(AsyncAction.Loading::class.java)
            var submitted = awaitItem()
            while (submitted.result is AsyncAction.Loading) {
                assertThat(submitted.reportSubmitted).isTrue()
                submitted = awaitItem()
            }
            assertThat(submitted.reportSubmitted).isTrue()
            assertThat(submitted.result).isInstanceOf(AsyncAction.Success::class.java)
        }
        report.assertions().isCalledOnce()
    }

    private fun createPresenter(
        room: JoinedRoom = FakeJoinedRoom(),
        client: MatrixClient = FakeMatrixClient(),
    ) = ReportMessagePresenter(
        inputs = ReportMessagePresenter.Inputs(AN_EVENT_ID, A_USER_ID),
        room = room,
        matrixClient = client,
    )
}
