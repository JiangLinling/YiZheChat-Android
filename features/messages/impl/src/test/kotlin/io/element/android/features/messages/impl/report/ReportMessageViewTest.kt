/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package io.element.android.features.messages.impl.report

import com.google.common.truth.Truth.assertThat
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.element.android.libraries.architecture.AsyncAction
import io.element.android.libraries.ui.strings.CommonStrings
import io.element.android.tests.testutils.lambda.lambdaRecorder
import io.element.android.tests.testutils.setSafeContent
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReportMessageViewTest {
    @Test
    fun `success uses report callback`() = runAndroidComposeUiTest {
        val back = lambdaRecorder<Unit> {}
        val success = lambdaRecorder<Unit> {}
        setSafeContent {
            ReportMessageView(aReportMessageState(result = AsyncAction.Success(Unit)), back, success)
        }
        waitForIdle()
        success.assertions().isCalledOnce()
        back.assertions().isNeverCalled()
    }

    @Test
    fun `submitted report after block error dismissal offers retry`() = runAndroidComposeUiTest<ComponentActivity> {
        val events = mutableListOf<ReportMessageEvent>()
        setSafeContent {
            ReportMessageView(
                state = aReportMessageState(
                    reason = "Spam",
                    blockUser = true,
                    reportSubmitted = true,
                    result = AsyncAction.Uninitialized,
                ).copy(eventSink = events::add),
                onBackClick = {},
                onReportSuccess = {},
            )
        }
        onNodeWithText(activity!!.getString(CommonStrings.common_report_submitted)).assertExists()
        onNodeWithText(activity!!.getString(CommonStrings.action_retry)).performClick()
        assertThat(events).containsExactly(ReportMessageEvent.Report)
    }
}
