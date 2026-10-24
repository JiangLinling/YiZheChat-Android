/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package io.element.android.features.messages.impl.timeline.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.element.android.features.messages.impl.timeline.aTimelineItemEvent
import io.element.android.features.messages.impl.timeline.aTimelineRoomInfo
import io.element.android.features.messages.impl.timeline.model.event.RtcNotificationState
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemRtcNotificationContent
import io.element.android.libraries.matrix.api.notification.CallIntent
import io.element.android.libraries.testtags.TestTags
import io.element.android.tests.testutils.setSafeContent
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimelineItemCallNotifyLayoutTest {
    @Test
    fun `incoming call details do not move the next message at normal font size`() = verifyStableDetails(false, 1f)

    @Test
    fun `outgoing call details do not move the next message at large font size`() = verifyStableDetails(true, 1.5f)

    private fun verifyStableDetails(isMine: Boolean, fontScale: Float) = runAndroidComposeUiTest<ComponentActivity> {
        val state = mutableStateOf<RtcNotificationState>(RtcNotificationState.Started)
        val event = aTimelineItemEvent(isMine = isMine)
        setSafeContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                Column {
                    TimelineItemCallNotifyView(
                        timelineRoomInfo = aTimelineRoomInfo(isDm = true),
                        event = event,
                        content = TimelineItemRtcNotificationContent(CallIntent.AUDIO, state.value),
                        renderReadReceipts = false,
                        isLastOutgoingMessage = false,
                        onClick = {},
                        onLongClick = {},
                        onReadReceiptsClick = {},
                    )
                    Text("Next message", Modifier.testTag("next-message"))
                }
            }
        }
        val initialBubble = onNodeWithTag(TestTags.messageBubble.value).fetchSemanticsNode().boundsInRoot
        val nextTop = onNodeWithTag("next-message").fetchSemanticsNode().boundsInRoot.top
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val connected = context.getString(io.element.android.features.messages.impl.R.string.screen_room_call_detail_connected)
        onNodeWithText(connected, useUnmergedTree = true).assertDoesNotExist()
        for (terminal in listOf(RtcNotificationState.Ended(65000L), RtcNotificationState.Missed(byMe = false))) {
            runOnIdle { state.value = terminal }
            val bubble = onNodeWithTag(TestTags.messageBubble.value).fetchSemanticsNode().boundsInRoot
            assertThat(bubble.height).isEqualTo(initialBubble.height)
            assertThat(onNodeWithTag("next-message").fetchSemanticsNode().boundsInRoot.top).isEqualTo(nextTop)
        }
    }
}
