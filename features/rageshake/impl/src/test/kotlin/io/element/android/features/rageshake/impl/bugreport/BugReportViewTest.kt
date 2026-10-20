/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

@file:OptIn(ExperimentalTestApi::class)

package io.element.android.features.rageshake.impl.bugreport

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.element.android.features.rageshake.impl.R
import io.element.android.tests.testutils.EnsureNeverCalled
import io.element.android.tests.testutils.clickOn
import io.element.android.tests.testutils.pressBack
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class BugReportViewTest {
    @Test
    @Config(qualifiers = "h1280dp")
    fun `diagnostics round trip preserves description and never sends the report`() = runAndroidComposeUiTest<ComponentActivity> {
        setContent {
            var form by remember { mutableStateOf(BugReportFormState.Default) }
            BugReportView(
                state = aBugReportState().copy(
                    formState = form,
                    eventSink = { event ->
                        when (event) {
                            is BugReportEvents.SetDescription -> form = form.copy(description = event.description)
                            else -> error("Unexpected report event: $event")
                        }
                    },
                ),
                onViewLogs = EnsureNeverCalled(),
                onAddAttachments = EnsureNeverCalled(),
                onSuccess = EnsureNeverCalled(),
                onBackClick = EnsureNeverCalled(),
            )
        }
        onNode(hasSetTextAction()).performTextInput("Notification missing after the phone was locked.")
        onNodeWithText(activity!!.getString(R.string.screen_bug_report_view_logs)).assertDoesNotExist()
        clickOn(R.string.screen_bug_report_diagnostics)
        onNodeWithText(activity!!.getString(R.string.screen_bug_report_view_logs)).assertExists()
        pressBack()
        onNodeWithText("Notification missing after the phone was locked.").assertExists()
        onNodeWithText(activity!!.getString(R.string.screen_bug_report_view_logs)).assertDoesNotExist()
    }
}
