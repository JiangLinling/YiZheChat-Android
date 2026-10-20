/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

@file:OptIn(ExperimentalTestApi::class)

package io.element.android.features.preferences.impl.chatsecurity

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.AndroidComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.element.android.features.preferences.impl.R
import io.element.android.tests.testutils.EnsureNeverCalled
import io.element.android.tests.testutils.clickOn
import io.element.android.tests.testutils.pressBack
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatSecurityViewTest {
    @Test
    fun `device code stays in recovery details and back returns to security`() = runAndroidComposeUiTest<ComponentActivity> {
        setView()
        val codeTitle = activity!!.getString(R.string.screen_chat_security_device_code)
        onNodeWithText(codeTitle).assertDoesNotExist()
        clickOn(R.string.screen_settings_device_recovery)
        onNodeWithText(codeTitle).assertExists()
        pressBack()
        onNodeWithText(codeTitle).assertDoesNotExist()
        onNodeWithText(activity!!.getString(R.string.screen_settings_phone_permissions)).assertExists()
    }

    @Test
    fun `zero unavailable records never offers local cleanup`() = runAndroidComposeUiTest<ComponentActivity> {
        setView(unavailableCount = 0)
        clickOn(R.string.screen_chat_security_history_recovery)
        onNodeWithText(activity!!.getString(R.string.screen_chat_security_clear_unavailable_history)).assertDoesNotExist()
    }

    @Test
    fun `eligible nonempty history keeps cleanup in history details`() = runAndroidComposeUiTest<ComponentActivity> {
        setView(unavailableCount = 3)
        val cleanup = activity!!.getString(R.string.screen_chat_security_clear_unavailable_history)
        onNodeWithText(cleanup).assertDoesNotExist()
        clickOn(R.string.screen_chat_security_history_recovery)
        onNodeWithText(cleanup).assertExists()
    }
}

private fun AndroidComposeUiTest<ComponentActivity>.setView(unavailableCount: Int = 0) {
    setContent {
        ChatSecurityView(
            state = ChatSecurityState(
                deviceSecurityState = PublicDeviceSecurityState.PASSED,
                historyRecoveryState = PublicHistoryRecoveryState.NO_MATRIX_BACKUP,
                currentDeviceCode = "A1B2C3D4",
                deviceRecoveryStatus = null,
                pendingDeviceRecoveries = emptyList(),
                isRefreshing = false,
                isRecoveryActionRunning = false,
                recoveryActionFailure = null,
                unavailableHistoryCount = unavailableCount,
                eventSink = {},
            ),
            onBackClick = EnsureNeverCalled(),
            onRestoreHistoryClick = EnsureNeverCalled(),
            onEncryptionClick = EnsureNeverCalled(),
        )
    }
}
