/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package io.element.android.features.messages.impl

import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarDuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.element.android.features.messages.impl.pinned.list.PinnedMessagesListState
import io.element.android.features.messages.impl.pinned.list.PinnedMessagesListView
import io.element.android.libraries.designsystem.utils.snackbar.LocalSnackbarDispatcher
import io.element.android.libraries.designsystem.utils.snackbar.SnackbarDispatcher
import io.element.android.libraries.designsystem.utils.snackbar.SnackbarMessage
import io.element.android.libraries.ui.strings.CommonStrings
import io.element.android.tests.testutils.setSafeContent
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FeedbackRoutingViewTest {
    @Test
    fun `queued retry feedback is not consumed behind a room action snackbar`() = runAndroidComposeUiTest<ComponentActivity> {
        val dispatcher = SnackbarDispatcher()
        val retry = SnackbarMessage(R.string.error_message_retry_failed, duration = SnackbarDuration.Indefinite)
        val action = mutableStateOf<SnackbarMessage?>(SnackbarMessage(R.string.message_favourited, duration = SnackbarDuration.Indefinite))
        val state = aMessagesState().copy(snackbarMessage = retry)
        dispatcher.post(retry)
        setSafeContent {
            CompositionLocalProvider(LocalSnackbarDispatcher provides dispatcher, LocalInspectionMode provides true) {
                MessagesView(
                    state = state,
                    roomActionSnackbarMessage = action.value,
                    onBackClick = {},
                    onRoomDetailsClick = {},
                    onEventContentClick = { _, _ -> false },
                    onUserDataClick = {},
                    onLinkClick = { _, _ -> },
                    onSendLocationClick = {},
                    onCreatePollClick = {},
                    onJoinCallClick = {},
                    onViewAllPinnedMessagesClick = {},
                    onThreadsListClick = {},
                    knockRequestsBannerView = {},
                )
            }
        }
        onNodeWithText(activity!!.getString(R.string.message_favourited)).assertIsDisplayed()
        runOnIdle { assertThat(retry.isDisplayed.get()).isFalse(); action.value = null }
        onNodeWithText(activity!!.getString(R.string.error_message_retry_failed)).assertIsDisplayed()
        runOnIdle { assertThat(retry.isDisplayed.get()).isTrue() }
    }

    @Test
    fun `pinned message failure is displayed on the pinned screen`() = runAndroidComposeUiTest<ComponentActivity> {
        val dispatcher = SnackbarDispatcher()
        setSafeContent {
            CompositionLocalProvider(LocalSnackbarDispatcher provides dispatcher) {
                PinnedMessagesListView(
                    state = PinnedMessagesListState.Empty,
                    onBackClick = {},
                    onEventClick = {},
                    onUserDataClick = {},
                    onLinkClick = {},
                    onLinkLongClick = {},
                )
            }
        }
        runOnIdle { dispatcher.post(SnackbarMessage(CommonStrings.common_error, duration = SnackbarDuration.Indefinite)) }
        onNodeWithText(activity!!.getString(CommonStrings.common_error)).assertIsDisplayed()
    }
}
