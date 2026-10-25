/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalTestApi::class)

package io.element.android.features.messages.impl.timeline

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.AndroidComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.element.android.features.messages.impl.timeline.components.MessageShieldData
import io.element.android.features.messages.impl.timeline.components.aCriticalShield
import io.element.android.features.messages.impl.timeline.model.TimelineItem
import io.element.android.features.messages.impl.timeline.model.GroupedEventsKind
import io.element.android.features.messages.impl.timeline.model.NewEventState
import io.element.android.features.messages.impl.timeline.model.event.aTimelineItemImageContent
import io.element.android.features.messages.impl.timeline.model.event.aTimelineItemUnknownContent
import io.element.android.features.messages.impl.timeline.model.virtual.TimelineItemLoadingIndicatorModel
import io.element.android.features.messages.impl.timeline.protection.TimelineProtectionState
import io.element.android.features.messages.impl.timeline.protection.aTimelineProtectionState
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.UniqueId
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.matrix.api.timeline.item.event.MessageShield
import io.element.android.libraries.matrix.api.user.MatrixUser
import io.element.android.libraries.ui.strings.CommonStrings
import io.element.android.tests.testutils.EnsureNeverCalledWithParam
import io.element.android.tests.testutils.EnsureNeverCalledWithTwoParams
import io.element.android.tests.testutils.EventsRecorder
import io.element.android.tests.testutils.clickOn
import io.element.android.tests.testutils.setSafeContent
import io.element.android.wysiwyg.link.Link
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimelineViewTest {
    @Test
    fun `a new incoming event preserves the partly scrolled three image grid`() = assertThreeImageCommit(resetPosition = false)

    @Test
    fun `a reset before incoming group reconstruction preserves the partly scrolled three image grid`() = assertThreeImageCommit(resetPosition = true)

    private fun assertThreeImageCommit(resetPosition: Boolean) = runAndroidComposeUiTest<ComponentActivity> {
        val grid = TimelineItem.GroupedEvents(
            id = UniqueId("three-image-grid"),
            events = List(3) { aTimelineItemEvent(content = aTimelineItemImageContent()) }.toImmutableList(),
            aggregatedReadReceipts = persistentListOf(),
            kind = GroupedEventsKind.MEDIA_ALBUM,
        )
        val originalRows = (listOf(grid) + List(10) { aTimelineItemEvent(content = aTimelineItemUnknownContent()) }).toImmutableList()
        val state = mutableStateOf(aTimelineState(timelineItems = originalRows, isLive = true, focusedEventIndex = -1))
        val list = LazyListState()
        setTimelineView(state.value, stateProvider = { state.value }, lazyListState = list, modifier = Modifier.height(220.dp))
        waitForIdle()
        runOnIdle { list.requestScrollToItem(0, 70) }
        waitForIdle()
        var gridOffset = 0
        runOnIdle {
            assertThat(list.firstVisibleItemIndex).isEqualTo(0)
            gridOffset = list.layoutInfo.visibleItemsInfo.single { it.key == grid.id }.offset
            assertThat(gridOffset).isLessThan(0)
            if (resetPosition) list.requestScrollToItem(0)
            val rebuilt = if (resetPosition) originalRows.map { row ->
                when (row) {
                    is TimelineItem.GroupedEvents -> row.copy(events = row.events.map { it.copy() }.toImmutableList())
                    is TimelineItem.Event -> row.copy()
                    else -> row
                }
            }.toImmutableList() else originalRows
            state.value = state.value.copy(
                timelineItems = (listOf(aTimelineItemEvent(content = aTimelineItemUnknownContent())) + rebuilt).toImmutableList(),
                newEventState = NewEventState.FromOther,
            )
        }
        mainClock.advanceTimeBy(1000)
        waitForIdle()
        runOnIdle {
            assertThat(list.layoutInfo.visibleItemsInfo.single { it.key == grid.id }.offset).isEqualTo(gridOffset)
            assertThat(list.firstVisibleItemIndex).isEqualTo(1)
        }
    }

    @Test
    fun `viewport maps visible rows to raw matrix identities`() {
        val event = aTimelineItemEvent(content = aTimelineItemUnknownContent()).copy(
            id = UniqueId("raw-event"),
            displayId = UniqueId("optimistic-display-id"),
        )
        val grouped = aGroupedEvents().let { group ->
            group.copy(
                events = group.events.mapIndexed { index, groupedEvent ->
                    groupedEvent.copy(
                        id = UniqueId("raw-grouped-$index"),
                        displayId = UniqueId("grouped-display-$index"),
                    )
                }.toImmutableList()
            )
        }
        val virtual = TimelineItem.Virtual(
            id = UniqueId("virtual"),
            model = TimelineItemLoadingIndicatorModel(Timeline.PaginationDirection.BACKWARDS, 0),
        )

        assertThat(timelineViewportItemIds(listOf(event, grouped, virtual), listOf(0, 1, 2)))
            .containsExactly(
                UniqueId("raw-event"),
                UniqueId("raw-grouped-0"),
                UniqueId("raw-grouped-1"),
            )
            .inOrder()
    }

    @Test
    fun `viewport rejects an index from another timeline snapshot`() {
        val item = aTimelineItemEvent(content = aTimelineItemUnknownContent())

        assertThat(timelineViewportItemIds(listOf(item), listOf(1))).isNull()
        assertThat(timelineViewportItemIds(emptyList(), emptyList())).isEmpty()
    }

    @Test
    fun `measured Compose viewport follows scrolling and reports raw ids`() = runAndroidComposeUiTest {
        val items = List<TimelineItem>(40) { index ->
            aTimelineItemEvent(
                eventId = EventId("\$viewport_$index"),
                content = aTimelineItemUnknownContent(),
            ).copy(
                id = UniqueId("raw-$index"),
                displayId = UniqueId("display-$index"),
            )
        }.toImmutableList()
        val viewports = mutableListOf<List<UniqueId>>()
        setTimelineView(
            state = aTimelineState(
                timelineItems = items,
                isLive = false,
                focusedEventIndex = -1,
            ),
            lazyListState = LazyListState(firstVisibleItemIndex = 10),
            onViewportChanged = { viewports += it },
        )
        waitForIdle()

        assertThat(viewports).isNotEmpty()
        assertThat(viewports.last()).contains(UniqueId("raw-10"))
        assertThat(viewports.last()).doesNotContain(UniqueId("display-10"))

        onNodeWithTag("timeline").performScrollToIndex(20)
        waitForIdle()

        assertThat(viewports.last()).contains(UniqueId("raw-20"))
    }

    @Test
    fun `atomic timeline replacement preserves stable visible event and pixel offset`() = runAndroidComposeUiTest {
        val initialItems = List<TimelineItem>(40) { index ->
            aTimelineItemEvent(
                eventId = EventId("\$anchor_$index"),
                content = aTimelineItemImageContent(),
            ).copy(displayId = UniqueId("anchor-$index"))
        }.toImmutableList()
        val stateHolder = mutableStateOf(
            aTimelineState(
                timelineItems = initialItems,
                isLive = true,
            )
        )
        val lazyListState = LazyListState(
            firstVisibleItemIndex = 10,
            firstVisibleItemScrollOffset = 17,
        )
        setTimelineView(
            state = stateHolder.value,
            stateProvider = { stateHolder.value },
            lazyListState = lazyListState,
        )
        waitForIdle()

        // First live entry intentionally starts at the latest message. Establish the
        // user's history-reading viewport only after that entry placement has completed.
        assertThat(lazyListState.firstVisibleItemIndex).isEqualTo(0)
        runOnUiThread { lazyListState.requestScrollToItem(10, 17) }
        waitForIdle()
        assertThat(lazyListState.firstVisibleItemIndex).isEqualTo(10)
        assertThat(lazyListState.firstVisibleItemScrollOffset).isEqualTo(17)

        runOnUiThread {
            val newItem = aTimelineItemEvent(
                eventId = EventId("\$new_event"),
                content = aTimelineItemImageContent(),
            ).copy(displayId = UniqueId("new-event"))
            stateHolder.value = stateHolder.value.copy(
                timelineItems = (listOf<TimelineItem>(newItem) + initialItems).toImmutableList()
            )
        }
        waitForIdle()

        assertThat(lazyListState.firstVisibleItemIndex).isEqualTo(11)
        assertThat(lazyListState.firstVisibleItemScrollOffset).isEqualTo(17)
    }

    @Test
    fun `reaching the end of the timeline with more events to load emits a LoadMore event`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<TimelineEvent>()
        setTimelineView(
            state = aTimelineState(
                timelineItems = persistentListOf<TimelineItem>(
                    TimelineItem.Virtual(
                        id = UniqueId("backward_pagination"),
                        model = TimelineItemLoadingIndicatorModel(Timeline.PaginationDirection.BACKWARDS, 0)
                    ),
                ),
                eventSink = eventsRecorder,
            ),
        )
        eventsRecorder.assertSingle(TimelineEvent.LoadMore(Timeline.PaginationDirection.BACKWARDS))
    }

    @Test
    fun `reaching the end of the timeline does not send a LoadMore event`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<TimelineEvent>()
        setTimelineView(
            state = aTimelineState(
                timelineItems = persistentListOf(aTimelineItemEvent(content = aTimelineItemImageContent())),
                eventSink = eventsRecorder,
            ),
        )
        eventsRecorder.assertList(listOf(
            TimelineEvent.OnScrollFinished(firstIndex = 0, isUserVisible = false),
            TimelineEvent.OnScrollFinished(firstIndex = 0, isUserVisible = true),
        ))
    }

    @Test
    fun `scroll to bottom on live timeline does not emit the Event`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<TimelineEvent>()
        setTimelineView(
            state = aTimelineState(
                timelineItems = persistentListOf(aTimelineItemEvent(content = aTimelineItemImageContent())),
                isLive = true,
                eventSink = eventsRecorder,
            ),
            forceJumpToBottomVisibility = true,
        )

        eventsRecorder.assertList(listOf(
            TimelineEvent.OnScrollFinished(firstIndex = 0, isUserVisible = false),
            TimelineEvent.OnScrollFinished(firstIndex = 0, isUserVisible = true),
        ))
        eventsRecorder.clear()

        onNodeWithTag("view_latest_messages").performClick()
        waitForIdle()
        eventsRecorder.assertEmpty()
    }

    @Test
    fun `scroll to bottom on detached timeline emits the expected Event`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<TimelineEvent>()
        setTimelineView(
            state = aTimelineState(
                timelineItems = persistentListOf(aTimelineItemEvent(content = aTimelineItemImageContent())),
                isLive = false,
                eventSink = eventsRecorder,
            ),
        )

        eventsRecorder.assertList(listOf(
            TimelineEvent.OnScrollFinished(firstIndex = 0, isUserVisible = false),
            TimelineEvent.OnScrollFinished(firstIndex = 0, isUserVisible = true),
        ))
        eventsRecorder.clear()

        onNodeWithTag("view_latest_messages").performClick()
        eventsRecorder.assertSingle(TimelineEvent.JumpToLive)
    }

    @Test
    fun `an empty timeline triggers a prefetch`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<TimelineEvent>()
        setTimelineView(
            state = aTimelineState(
                timelineItems = persistentListOf(),
                eventSink = eventsRecorder,
            ),
        )

        eventsRecorder.assertSingle(TimelineEvent.LoadMore(Timeline.PaginationDirection.BACKWARDS))
    }

    @Test
    fun `show shield dialog`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<TimelineEvent>()
        setTimelineView(
            state = aTimelineState(
                timelineItems = persistentListOf<TimelineItem>(
                    aTimelineItemEvent(
                        // Do not use a Text because EditorStyledText cannot be used in UI test.
                        content = aTimelineItemImageContent(),
                        messageShield = MessageShield.UnverifiedIdentity(true),
                    ),
                ),
                eventSink = eventsRecorder,
            ),
        )
        val contentDescription = activity!!.getString(CommonStrings.a11y_encryption_details)
        onNodeWithContentDescription(contentDescription).performClick()
        eventsRecorder.assertList(
            listOf(
                TimelineEvent.OnScrollFinished(0, isUserVisible = false),
                TimelineEvent.OnScrollFinished(0, isUserVisible = true),
                TimelineEvent.ShowShieldDialog(MessageShieldData(MessageShield.UnverifiedIdentity(true))),
            )
        )
    }

    @Test
    fun `hide shield dialog`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<TimelineEvent>()
        setTimelineView(
            state = aTimelineState(
                timelineItems = persistentListOf(aTimelineItemEvent(content = aTimelineItemImageContent())),
                isLive = false,
                eventSink = eventsRecorder,
                messageShield = aCriticalShield(),
            ),
        )
        eventsRecorder.assertList(listOf(
            TimelineEvent.OnScrollFinished(firstIndex = 0, isUserVisible = false),
            TimelineEvent.OnScrollFinished(firstIndex = 0, isUserVisible = true),
        ))
        eventsRecorder.clear()

        clickOn(CommonStrings.action_ok)
        eventsRecorder.assertSingle(TimelineEvent.HideShieldDialog)
    }

    @Ignore(
        "performScrollToIndex in compose tests no longer sets LazyListState.isScrollInProgress to true, so the LoadMore event is not emitted." +
            "This needs to be reworked to use a different approach to check the LoadMore event was emitted."
    )
    @Test
    fun `scrolling near to the start of the loaded items triggers a pre-fetch`() = runAndroidComposeUiTest {
        val eventsRecorder = EventsRecorder<TimelineEvent>()
        val items = List<TimelineItem>(200) {
            aTimelineItemEvent(
                eventId = EventId("\$event_$it"),
                content = aTimelineItemUnknownContent(),
            )
        }.toImmutableList()

        setTimelineView(
            state = aTimelineState(
                timelineItems = items,
                eventSink = eventsRecorder,
                focusedEventIndex = -1,
                isLive = false,
            ),
        )

        onNodeWithTag("timeline").performScrollToIndex(180)

        mainClock.advanceTimeBy(1000)

        eventsRecorder.assertList(
            listOf(
                TimelineEvent.OnScrollFinished(firstIndex = 0),
                TimelineEvent.LoadMore(Timeline.PaginationDirection.BACKWARDS),
            )
        )
    }
}

private fun AndroidComposeUiTest<ComponentActivity>.setTimelineView(
    state: TimelineState,
    stateProvider: () -> TimelineState = { state },
    lazyListState: LazyListState = LazyListState(),
    timelineProtectionState: TimelineProtectionState = aTimelineProtectionState(),
    onUserDataClick: (MatrixUser) -> Unit = EnsureNeverCalledWithParam(),
    onLinkClick: (Link) -> Unit = EnsureNeverCalledWithParam(),
    onMessageClick: (TimelineItem.Event) -> Unit = EnsureNeverCalledWithParam(),
    onMessageLongClick: (TimelineItem.Event, IntRect) -> Unit = EnsureNeverCalledWithTwoParams(),
    onSwipeToReply: (TimelineItem.Event) -> Unit = EnsureNeverCalledWithParam(),
    onReactionClick: (emoji: String, TimelineItem.Event) -> Unit = EnsureNeverCalledWithTwoParams(),
    onReactionLongClick: (emoji: String, TimelineItem.Event) -> Unit = EnsureNeverCalledWithTwoParams(),
    onMoreReactionsClick: (TimelineItem.Event) -> Unit = EnsureNeverCalledWithParam(),
    onReadReceiptClick: (TimelineItem.Event) -> Unit = EnsureNeverCalledWithParam(),
    onViewportChanged: (List<UniqueId>) -> Unit = {},
    forceJumpToBottomVisibility: Boolean = false,
    modifier: Modifier = Modifier,
) {
    setSafeContent(clearAndroidUiDispatcher = true) {
        TimelineView(
            modifier = modifier,
            state = stateProvider(),
            timelineProtectionState = timelineProtectionState,
            onUserDataClick = onUserDataClick,
            onLinkClick = onLinkClick,
            onContentClick = onMessageClick,
            onMessageLongClick = onMessageLongClick,
            onSwipeToReply = onSwipeToReply,
            onReactionClick = onReactionClick,
            onReactionLongClick = onReactionLongClick,
            onMoreReactionsClick = onMoreReactionsClick,
            onReadReceiptClick = onReadReceiptClick,
            onViewportChanged = onViewportChanged,
            forceJumpToBottomVisibility = forceJumpToBottomVisibility,
            lazyListState = lazyListState,
        )
    }
}
