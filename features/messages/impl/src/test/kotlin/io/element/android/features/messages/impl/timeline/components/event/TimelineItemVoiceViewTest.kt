/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
@file:OptIn(ExperimentalTestApi::class)
package io.element.android.features.messages.impl.timeline.components.event

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.element.android.features.messages.impl.timeline.model.event.aTimelineItemVoiceContent
import io.element.android.features.messages.impl.timeline.components.MessageEventBubble
import io.element.android.features.messages.impl.timeline.model.bubble.BubbleStateProvider
import io.element.android.libraries.voiceplayer.api.VoiceMessageEvent
import io.element.android.libraries.voiceplayer.api.VoiceMessageState
import io.element.android.libraries.voiceplayer.api.aVoiceMessageState
import io.element.android.tests.testutils.setSafeContent
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.seconds

@RunWith(AndroidJUnit4::class)
class TimelineItemVoiceViewTest {
    @Test fun `downloading voice retains long press without playback actions`() = checkUnavailableLongPress(VoiceMessageState.ButtonType.Downloading)

    @Test fun `disabled voice retains long press without playback actions`() = checkUnavailableLongPress(VoiceMessageState.ButtonType.Disabled)

    private fun checkUnavailableLongPress(button: VoiceMessageState.ButtonType) = runAndroidComposeUiTest {
        val events = mutableListOf<VoiceMessageEvent>()
        var menus = 0
        setSafeContent {
            MessageEventBubble(BubbleStateProvider().values.first(), null, {}, { menus++ }, contentHandlesGestures = true) {
                TimelineItemVoiceView(
                    aVoiceMessageState().copy(buttonType = button, eventSink = events::add), aTimelineItemVoiceContent(), {},
                    Modifier.testTag("voice"), onLongClick = { menus++ },
                )
            }
        }
        onNodeWithTag("voice", useUnmergedTree = true).performTouchInput { longClick() }
        onNodeWithTag("voice", useUnmergedTree = true).performTouchInput { doubleClick() }
        mainClock.advanceTimeBy(400)
        runOnIdle { assertThat(menus).isEqualTo(1); assertThat(events).isEmpty() }
    }

    @Test fun `long press inside the real message bubble opens the menu exactly once`() = runAndroidComposeUiTest {
        val events = mutableListOf<VoiceMessageEvent>()
        var menus = 0
        setSafeContent {
            MessageEventBubble(BubbleStateProvider().values.first(), null, {}, { menus++ }, contentHandlesGestures = true) {
                TimelineItemVoiceView(
                    aVoiceMessageState().copy(eventSink = events::add), aTimelineItemVoiceContent(), {},
                    Modifier.testTag("voice"), onLongClick = { menus++ },
                )
            }
        }
        onNodeWithTag("voice", useUnmergedTree = true).performTouchInput { longClick() }
        mainClock.advanceTimeBy(400)
        runOnIdle { assertThat(menus).isEqualTo(1); assertThat(events).isEmpty() }
    }

    @Test fun `voice owns double tap inside the real bubble without opening media viewer`() = runAndroidComposeUiTest {
        val events = mutableListOf<VoiceMessageEvent>()
        var opens = 0
        setSafeContent {
            MessageEventBubble(BubbleStateProvider().values.first(), null, { opens++ }, {}, contentHandlesGestures = true) {
                TimelineItemVoiceView(
                    aVoiceMessageState().copy(eventSink = events::add), aTimelineItemVoiceContent(), {},
                    Modifier.testTag("voice"),
                )
            }
        }
        onNodeWithTag("voice", useUnmergedTree = true).performTouchInput { doubleClick() }
        mainClock.advanceTimeBy(400)
        runOnIdle { assertThat(opens).isEqualTo(0); assertThat(events).containsExactly(VoiceMessageEvent.ChangePlaybackSpeed) }
    }

    @Test fun `single tap toggles playback without restarting`() = runAndroidComposeUiTest {
        val events = mutableListOf<VoiceMessageEvent>()
        setSafeContent { TimelineItemVoiceView(aVoiceMessageState().copy(eventSink = events::add), aTimelineItemVoiceContent(), {}, Modifier.testTag("voice")) }
        onNodeWithTag("voice").performTouchInput { click() }
        mainClock.advanceTimeBy(400)
        runOnIdle { assertThat(events).containsExactly(VoiceMessageEvent.PlayPause) }
    }

    @Test fun `double tap changes speed without also toggling playback`() = runAndroidComposeUiTest {
        val events = mutableListOf<VoiceMessageEvent>()
        setSafeContent { TimelineItemVoiceView(aVoiceMessageState().copy(eventSink = events::add), aTimelineItemVoiceContent(), {}, Modifier.testTag("voice")) }
        onNodeWithTag("voice").performTouchInput { doubleClick() }
        mainClock.advanceTimeBy(400)
        runOnIdle { assertThat(events).containsExactly(VoiceMessageEvent.ChangePlaybackSpeed) }
    }

    @Test fun `drag displays target time only until release and commits one seek`() = runAndroidComposeUiTest {
        val events = mutableListOf<VoiceMessageEvent>()
        setSafeContent { TimelineItemVoiceView(aVoiceMessageState(showCursor = true).copy(eventSink = events::add), aTimelineItemVoiceContent(duration = 60.seconds), {}, Modifier.testTag("voice")) }
        val bubble = onNodeWithTag("voice")
        bubble.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "1.0×"))
        bubble.performTouchInput { down(Offset(width * 0.1f, height / 2f)); moveTo(Offset(width * 0.75f, height / 2f), delayMillis = 200) }
        bubble.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "1.0× 00:45"))
        bubble.performTouchInput { up() }
        mainClock.advanceTimeBy(400)
        bubble.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "1.0×"))
        runOnIdle {
            assertThat(events).hasSize(1)
            assertThat((events.single() as VoiceMessageEvent.Seek).percentage).isWithin(0.01f).of(0.75f)
        }
    }

    @Test fun `disabled bubble cannot play seek or change speed`() = runAndroidComposeUiTest {
        val events = mutableListOf<VoiceMessageEvent>()
        setSafeContent { TimelineItemVoiceView(aVoiceMessageState(buttonType = VoiceMessageState.ButtonType.Disabled).copy(eventSink = events::add), aTimelineItemVoiceContent(), {}, Modifier.testTag("voice")) }
        onNodeWithTag("voice").performTouchInput { doubleClick(); swipeRight() }
        runOnIdle { assertThat(events).isEmpty() }
    }

    @Test fun `voice timestamp uses two digit minute and second fields`() {
        assertThat(8.seconds.formatVoiceTimestamp()).isEqualTo("00:08")
        assertThat(78.seconds.formatVoiceTimestamp()).isEqualTo("01:18")
    }
}
