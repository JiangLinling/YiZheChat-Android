/* Copyright (c) 2026 YiZheChat.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package io.element.android.features.messages.impl.timeline

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.element.android.features.messages.impl.timeline.model.TimelineItem
import io.element.android.features.messages.impl.timeline.model.virtual.TimelineItemLoadingIndicatorModel
import io.element.android.libraries.matrix.api.core.UniqueId
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.tests.testutils.setSafeContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimelineLiveEdgeTest {
    @Test
    fun `a partly scrolled tall grid at index zero is history`() = runAndroidComposeUiTest<ComponentActivity> {
        val rows = List<TimelineItem>(5) { aTimelineItemEvent().copy(displayId = UniqueId("event-$it")) }
        val list = LazyListState()
        lateinit var scope: CoroutineScope
        setSafeContent {
            scope = rememberCoroutineScope()
            LazyColumn(Modifier.height(400.dp), state = list, reverseLayout = true, contentPadding = PaddingValues(bottom = 8.dp)) {
                itemsIndexed(rows, key = { _, row -> row.identifier() }) { _, _ -> Box(Modifier.fillMaxWidth().height(800.dp)) }
            }
        }
        runOnIdle { assertThat(isTimelineAtLiveEdge(rows, list.layoutInfo)).isTrue() }
        runOnIdle { scope.launch { list.scrollToItem(0, 220) } }
        waitForIdle()
        runOnIdle {
            assertThat(list.firstVisibleItemIndex).isEqualTo(0)
            assertThat(list.firstVisibleItemScrollOffset).isGreaterThan(0)
            assertThat(isTimelineAtLiveEdge(rows, list.layoutInfo)).isFalse()
            assertThat(hasCurrentTimelineMeasurement(rows, list.layoutInfo)).isTrue()
        }
    }

    @Test
    fun `virtual rows do not hide a fully visible latest message edge`() = runAndroidComposeUiTest<ComponentActivity> {
        val virtual = TimelineItem.Virtual(UniqueId("virtual"), TimelineItemLoadingIndicatorModel(Timeline.PaginationDirection.BACKWARDS, 0))
        val rows = listOf(virtual, virtual.copy(id = UniqueId("typing")), aTimelineItemEvent(),
            aTimelineItemEvent().copy(displayId = UniqueId("older")))
        val list = LazyListState()
        setSafeContent {
            LazyColumn(Modifier.height(400.dp), state = list, reverseLayout = true) {
                itemsIndexed(rows, key = { _, row -> row.identifier() }) { index, _ ->
                    Box(Modifier.fillMaxWidth().height(when (index) { 0 -> 0.dp; 1 -> 16.dp; else -> 300.dp }))
                }
            }
        }
        runOnIdle { assertThat(isTimelineAtLiveEdge(rows, list.layoutInfo)).isTrue() }
    }

    @Test
    fun `a completed real drag within the first row does not become latest again`() = runAndroidComposeUiTest<ComponentActivity> {
        val rows = List<TimelineItem>(5) { aTimelineItemEvent().copy(displayId = UniqueId("event-$it")) }
        val list = LazyListState()
        setSafeContent {
            LazyColumn(Modifier.height(400.dp).testTag("history"), state = list, reverseLayout = true) {
                itemsIndexed(rows, key = { _, row -> row.identifier() }) { _, _ -> Box(Modifier.fillMaxWidth().height(800.dp)) }
            }
        }
        onNodeWithTag("history").performTouchInput { swipeDown(durationMillis = 600L) }
        waitForIdle()
        runOnIdle {
            assertThat(list.isScrollInProgress).isFalse()
            assertThat(isTimelineAtLiveEdge(rows, list.layoutInfo)).isFalse()
        }
    }

    @Test
    fun `a measurement from another timeline cannot authorize following latest`() = runAndroidComposeUiTest<ComponentActivity> {
        val rows = listOf(aTimelineItemEvent())
        val list = LazyListState()
        setSafeContent {
            LazyColumn(Modifier.height(400.dp), state = list, reverseLayout = true) {
                itemsIndexed(rows, key = { _, row -> row.identifier() }) { _, _ -> Box(Modifier.fillMaxWidth().height(100.dp)) }
            }
        }
        runOnIdle {
            assertThat(isTimelineAtLiveEdge(rows, list.layoutInfo)).isTrue()
            assertThat(isTimelineAtLiveEdge(listOf(rows.single().copy(displayId = UniqueId("replacement"))), list.layoutInfo)).isFalse()
            assertThat(hasCurrentTimelineMeasurement(rows, list.layoutInfo)).isTrue()
            assertThat(hasCurrentTimelineMeasurement(listOf(rows.single().copy(displayId = UniqueId("replacement"))), list.layoutInfo)).isFalse()
            assertThat(hasCurrentTimelineMeasurement(rows + rows.single().copy(displayId = UniqueId("incoming")), list.layoutInfo)).isFalse()
        }
    }
}
