/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package io.element.android.features.messages.impl.emotions

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.element.android.features.messages.impl.timeline.aTimelineItemEvent
import io.element.android.features.messages.impl.timeline.aTimelineState
import io.element.android.features.messages.impl.timeline.model.event.aTimelineItemTextContent
import io.element.android.features.messages.impl.timeline.protection.aTimelineProtectionState
import io.element.android.tests.testutils.setSafeContent
import io.element.android.libraries.matrix.api.timeline.item.event.TimelineItemEventOrigin
import io.element.android.libraries.matrix.api.core.UniqueId
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimelineEmotionsTest {
    @Test fun `first arrival in empty room waits for current measurement and plays once`() = assertMeasurementArrival(true, false)

    @Test fun `arrival measured in history is consumed without replay on returning to latest`() = assertMeasurementArrival(false, false)

    @Test fun `blocked arrival is consumed even while measurement is pending`() = assertMeasurementArrival(true, true)

    private fun assertMeasurementArrival(measuredAtLatest: Boolean, initiallyBlocked: Boolean) = runAndroidComposeUiTest<ComponentActivity> {
        val state = mutableStateOf(aTimelineState())
        val measured = mutableStateOf(false)
        val latest = mutableStateOf(false)
        val blocked = mutableStateOf(initiallyBlocked)
        val playback = TimelineEmotionState()
        setSafeContent {
            Box(Modifier.fillMaxSize()) {
                TimelineEmotions(state.value, playback, "measurement", latest.value, blocked.value,
                    aTimelineProtectionState(), measurementCurrent = measured.value)
            }
        }
        waitForIdle()
        mainClock.autoAdvance = false
        val incoming = aTimelineItemEvent(content = aTimelineItemTextContent(body = "🎆")).copy(
            displayId = UniqueId("incoming"), sentTimeMillis = System.currentTimeMillis(), origin = TimelineItemEventOrigin.SYNC,
        )
        runOnIdle { state.value = aTimelineState(timelineItems = persistentListOf(incoming)); Snapshot.sendApplyNotifications() }
        mainClock.advanceTimeBy(100)
        runOnIdle { assertEquals(null, playback.request) }
        runOnIdle {
            measured.value = true
            latest.value = measuredAtLatest
            blocked.value = false
            Snapshot.sendApplyNotifications()
        }
        mainClock.advanceTimeBy(100)
        val expected = if (measuredAtLatest && !initiallyBlocked) "incoming" else null
        runOnIdle { assertEquals(expected, playback.request?.first) }
        runOnIdle { latest.value = true; Snapshot.sendApplyNotifications() }
        mainClock.advanceTimeBy(100)
        runOnIdle { assertEquals(expected, playback.request?.first) }
        mainClock.advanceTimeBy(4500)
        runOnIdle { assertEquals(null, playback.request) }
        mainClock.autoAdvance = true
    }

    @Test fun `a new scene replaces an unfinished scene on its first arrival`() = assertConsecutiveArrival("❤️", "🎆", "fireworks")

    @Test fun `repeated copies of the same emotion each start their own playback`() = assertConsecutiveArrival("🎆", "🎆", "fireworks")

    @Test fun `a scene replaces a daily emotion without the old timeout cancelling it`() = assertConsecutiveArrival("😂", "🎆", "fireworks")

    private fun assertConsecutiveArrival(firstBody: String, secondBody: String, secondEffect: String) = runAndroidComposeUiTest<ComponentActivity> {
        val state = mutableStateOf(aTimelineState())
        val playback = TimelineEmotionState()
        setSafeContent { Box(Modifier.fillMaxSize()) { TimelineEmotions(state.value, playback, "test", true, false, aTimelineProtectionState()) } }
        waitForIdle()
        mainClock.autoAdvance = false
        val first = aTimelineItemEvent(content = aTimelineItemTextContent(body = firstBody)).copy(
            displayId = UniqueId("first"), sentTimeMillis = System.currentTimeMillis(), origin = TimelineItemEventOrigin.LOCAL,
        )
        runOnIdle { state.value = aTimelineState(timelineItems = persistentListOf(first)); Snapshot.sendApplyNotifications() }
        mainClock.advanceTimeBy(200)
        runOnIdle { assertEquals("first", playback.request?.first) }
        mainClock.advanceTimeBy(800)
        val second = aTimelineItemEvent(content = aTimelineItemTextContent(body = secondBody)).copy(
            displayId = UniqueId("second"), sentTimeMillis = System.currentTimeMillis(), origin = TimelineItemEventOrigin.LOCAL,
        )
        runOnIdle { state.value = aTimelineState(timelineItems = persistentListOf(second, first)); Snapshot.sendApplyNotifications() }
        mainClock.advanceTimeBy(200)
        runOnIdle { assertEquals("second", playback.request?.first) }
        onNodeWithTag("emotion_playback_$secondEffect").assertExists()
        // The old request's deadline passes before the new four-second effect finishes.
        mainClock.advanceTimeBy(3100)
        runOnIdle { assertEquals("second", playback.request?.first) }
        onNodeWithTag("emotion_playback_$secondEffect").assertExists()
        mainClock.advanceTimeBy(1200)
        onNodeWithTag("emotion_playback_$secondEffect").assertDoesNotExist()
        mainClock.autoAdvance = true
    }

    @Test fun `new foreground message starts effect and removal cancels it`() = runAndroidComposeUiTest<ComponentActivity> {
        val state = mutableStateOf(aTimelineState())
        val playback = TimelineEmotionState()
        setSafeContent { Box(Modifier.fillMaxSize()) { TimelineEmotions(state.value, playback, "test", true, false, aTimelineProtectionState()) } }
        waitForIdle()
        mainClock.autoAdvance = false
        runOnIdle {
            state.value = aTimelineState(timelineItems = persistentListOf(aTimelineItemEvent(
                content = aTimelineItemTextContent(body = "🎆"),
            ).copy(sentTimeMillis = System.currentTimeMillis(), origin = TimelineItemEventOrigin.SYNC)))
        }
        // Incoming state is changed outside an input event: commit it before advancing the frozen frame clock.
        runOnIdle { Snapshot.sendApplyNotifications() }
        mainClock.advanceTimeBy(100)
        runOnIdle { Snapshot.sendApplyNotifications() }
        mainClock.advanceTimeBy(100)
        onNodeWithTag("emotion_playback_fireworks").assertExists()
        runOnIdle { state.value = aTimelineState() }
        runOnIdle { Snapshot.sendApplyNotifications() }
        mainClock.advanceTimeBy(100)
        onNodeWithTag("emotion_playback_fireworks").assertDoesNotExist()
    }

    @Test fun `history stays still until explicitly replayed`() = runAndroidComposeUiTest<ComponentActivity> {
        val event = aTimelineItemEvent(content = aTimelineItemTextContent(body = "🎆")).copy(sentTimeMillis = 1L)
        val playback = TimelineEmotionState()
        setSafeContent { Box(Modifier.fillMaxSize()) { TimelineEmotions(aTimelineState(timelineItems = persistentListOf(event)), playback, "test", true, false,
            aTimelineProtectionState()) } }
        waitForIdle()
        onNodeWithTag("emotion_playback_fireworks").assertDoesNotExist()
        mainClock.autoAdvance = false
        runOnIdle { playback.replay(event) }
        runOnIdle { Snapshot.sendApplyNotifications() }
        mainClock.advanceTimeBy(100)
        onNodeWithTag("emotion_playback_fireworks").assertExists()
        mainClock.autoAdvance = true
    }
}
