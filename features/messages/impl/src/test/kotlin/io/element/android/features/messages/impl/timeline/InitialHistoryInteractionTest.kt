/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package io.element.android.features.messages.impl.timeline

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.element.android.features.messages.impl.R
import io.element.android.features.messages.impl.timeline.components.TimelineItemVirtualRow
import io.element.android.features.messages.impl.timeline.components.virtual.TimelineInitialHistoryLoadingView
import io.element.android.features.messages.impl.timeline.model.TimelineItem
import io.element.android.features.messages.impl.timeline.model.virtual.TimelineItemInitialHistoryLoadingModel
import io.element.android.tests.testutils.setSafeContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InitialHistoryInteractionTest {
    @Test
    fun `loading region is not a pagination action or a measured message`() = runAndroidComposeUiTest<ComponentActivity> {
        val control = InitialHistoryPresentationControl()
        val row = TimelineItem.Virtual(control.id, TimelineItemInitialHistoryLoadingModel(control))
        val events = mutableListOf<TimelineEvent>()
        setSafeContent { TimelineItemVirtualRow(row, aTimelineRoomInfo(), events::add) }
        mainClock.advanceTimeBy(1000)
        waitForIdle()
        val label = ApplicationProvider.getApplicationContext<Context>().getString(R.string.screen_room_initial_history_loading)
        onNodeWithText(label).assertHasNoClickAction()
        assertThat(events).isEmpty()
        assertThat(row.events()).isEmpty()
        assertThat(timelineViewportItemIds(listOf(row), listOf(0))).isEmpty()
    }

    @Test
    fun `real drag on a loading-only short list retires its own presentation`() = runAndroidComposeUiTest<ComponentActivity> {
        val control = InitialHistoryPresentationControl()
        val list = LazyListState()
        val events = mutableListOf<TimelineEvent>()
        setSafeContent {
            LazyColumn(Modifier.height(400.dp).testTag("initial-list"), state = list) {
                item { TimelineInitialHistoryLoadingView() }
            }
            TimelineInitialHistoryInteraction("entry", control, list, true, true, false, false, events::add)
        }
        waitForIdle()
        onNodeWithTag("initial-list").performTouchInput { swipeUp(durationMillis = 350L) }
        waitForIdle()
        assertThat(events).hasSize(1)
        assertThat((events.single() as TimelineEvent.RetireInitialHistoryPresentation).control).isSameInstanceAs(control)
    }

    @Test
    fun `programmatic position and viewport size changes do not become a browse intent`() = runAndroidComposeUiTest<ComponentActivity> {
        val control = InitialHistoryPresentationControl()
        val list = LazyListState()
        val height = mutableStateOf(400.dp)
        val events = mutableListOf<TimelineEvent>()
        lateinit var scope: CoroutineScope
        setSafeContent {
            scope = rememberCoroutineScope()
            LazyColumn(Modifier.height(height.value), state = list) { item { TimelineInitialHistoryLoadingView() } }
            TimelineInitialHistoryInteraction("entry", control, list, true, true, true, false, events::add)
        }
        runOnIdle {
            scope.launch { list.scrollToItem(0) }
            height.value = 250.dp
        }
        waitForIdle()
        assertThat(events).isEmpty()
    }

    @Test
    fun `initial inactive does not retire but hiding an activated entry does`() = runAndroidComposeUiTest<ComponentActivity> {
        val control = InitialHistoryPresentationControl()
        val visible = mutableStateOf(false)
        val list = LazyListState()
        val events = mutableListOf<TimelineEvent>()
        setSafeContent {
            TimelineInitialHistoryInteraction("entry", control, list, visible.value, true, false, false, events::add)
        }
        waitForIdle()
        assertThat(events).isEmpty()
        runOnIdle { visible.value = true }
        waitForIdle()
        runOnIdle { visible.value = false }
        waitForIdle()
        assertThat(events).hasSize(1)
        assertThat((events.single() as TimelineEvent.RetireInitialHistoryPresentation).control).isSameInstanceAs(control)
    }
}
