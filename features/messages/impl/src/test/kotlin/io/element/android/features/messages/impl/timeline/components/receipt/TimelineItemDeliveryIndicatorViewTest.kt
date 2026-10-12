/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

@file:OptIn(ExperimentalTestApi::class)

package io.element.android.features.messages.impl.timeline.components.receipt

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.element.android.features.messages.impl.R
import io.element.android.libraries.matrix.api.timeline.item.event.LocalEventSendState
import io.element.android.libraries.ui.strings.CommonStrings
import io.element.android.tests.testutils.setSafeContent
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimelineItemDeliveryIndicatorViewTest {
    @Test
    fun `encryption to sending changes the label without removing the progress indicator`() = runAndroidComposeUiTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val encrypting = context.getString(R.string.screen_room_send_encrypting)
        val sending = context.getString(R.string.screen_room_read_status_sending)
        val state = mutableStateOf(aReadReceiptViewState(sendState = LocalEventSendState.Sending.Encrypting))
        mainClock.autoAdvance = false
        setSafeContent {
            TimelineItemDeliveryIndicator(state.value, true, {}, {})
        }
        mainClock.advanceTimeBy(300)
        val progressId = onNodeWithContentDescription(encrypting).assertIsDisplayed().fetchSemanticsNode().id

        runOnIdle { state.value = state.value.copy(sendState = LocalEventSendState.Sending.Event) }
        mainClock.advanceTimeByFrame()
        assertThat(onNodeWithContentDescription(sending).assertIsDisplayed().fetchSemanticsNode().id).isEqualTo(progressId)

        mainClock.advanceTimeBy(300)
        assertThat(onNodeWithContentDescription(sending).assertIsDisplayed().fetchSemanticsNode().id).isEqualTo(progressId)
        runOnIdle { state.value = state.value.copy(sendState = null) }
        mainClock.advanceTimeByFrame()
        onNodeWithContentDescription(sending).assertDoesNotExist()
    }

    @Test
    fun `failure is actionable and retry starts a new pending cycle`() = runAndroidComposeUiTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val encrypting = context.getString(R.string.screen_room_send_encrypting)
        val failed = context.getString(CommonStrings.common_sending_failed)
        val state = mutableStateOf(aReadReceiptViewState(sendState = LocalEventSendState.Sending.Encrypting))
        var failureClicks = 0
        mainClock.autoAdvance = false
        setSafeContent {
            TimelineItemDeliveryIndicator(state.value, true, { failureClicks++ }, {})
        }
        mainClock.advanceTimeBy(300)
        onNodeWithContentDescription(encrypting).assertIsDisplayed()

        runOnIdle { state.value = state.value.copy(sendState = LocalEventSendState.Failed.Unknown("test failure")) }
        mainClock.advanceTimeByFrame()
        onNodeWithContentDescription(encrypting).assertDoesNotExist()
        onNodeWithContentDescription(failed).assertIsDisplayed().performClick()
        runOnIdle { assertThat(failureClicks).isEqualTo(1) }

        runOnIdle { state.value = state.value.copy(sendState = LocalEventSendState.Sending.Encrypting) }
        mainClock.advanceTimeBy(48)
        waitForIdle()
        onNodeWithContentDescription(encrypting).assertDoesNotExist()
        mainClock.advanceTimeBy(250)
        onNodeWithContentDescription(encrypting).assertIsDisplayed()
        onNodeWithContentDescription(failed).assertDoesNotExist()
    }
}
