/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.timeline

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.impl.fixtures.factories.aRustEventTimelineItem
import io.element.android.libraries.matrix.impl.fixtures.fakes.FakeFfiTimeline
import io.element.android.libraries.matrix.impl.fixtures.fakes.FakeFfiTimelineItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.matrix.rustcomponents.sdk.Timeline
import org.matrix.rustcomponents.sdk.TimelineDiff
import uniffi.matrix_sdk_ui.EventItemOrigin

@OptIn(ExperimentalCoroutinesApi::class)
class TimelineItemsSubscriberTest {
    @Test
    fun `when timeline emits an empty list of items, the flow must emits an empty list`() = runTest {
        val timelineItems: MutableSharedFlow<List<MatrixTimelineItem>> =
            MutableSharedFlow(replay = 1, extraBufferCapacity = Int.MAX_VALUE)
        val timeline = FakeFfiTimeline()
        val diffProcessor = createMatrixTimelineDiffProcessor(
            timelineItems = timelineItems,
        )
        val timelineItemsSubscriber = createTimelineItemsSubscriber(
            timeline = timeline,
            timelineDiffProcessor = diffProcessor,
        )
        timelineItems.test {
            timelineItemsSubscriber.subscribeIfNeeded()
            // Wait for the listener to be set.
            runCurrent()
            timeline.emitDiff(listOf(TimelineDiff.Reset(emptyList())))
            val final = awaitItem()
            assertThat(final).isEmpty()
            timelineItemsSubscriber.unsubscribeIfNeeded()
        }
    }

    @Test
    fun `when timeline emits a non empty list of items, the flow must emits a non empty list`() = runTest {
        val timelineItems: MutableSharedFlow<List<MatrixTimelineItem>> =
            MutableSharedFlow(replay = 1, extraBufferCapacity = Int.MAX_VALUE)
        val timeline = FakeFfiTimeline()
        val diffProcessor = createMatrixTimelineDiffProcessor(
            timelineItems = timelineItems,
        )
        val timelineItemsSubscriber = createTimelineItemsSubscriber(
            timeline = timeline,
            timelineDiffProcessor = diffProcessor,
        )
        timelineItems.test {
            timelineItemsSubscriber.subscribeIfNeeded()
            // Wait for the listener to be set.
            runCurrent()
            timeline.emitDiff(listOf(TimelineDiff.Reset(listOf(FakeFfiTimelineItem()))))
            val final = awaitItem()
            assertThat(final).isNotEmpty()
            timelineItemsSubscriber.unsubscribeIfNeeded()
        }
    }

    @Test
    fun `when timeline emits an item with SYNC origin`() = runTest {
        val timelineItems: MutableSharedFlow<List<MatrixTimelineItem>> =
            MutableSharedFlow(replay = 1, extraBufferCapacity = Int.MAX_VALUE)
        val timeline = FakeFfiTimeline()
        val diffProcessor = createMatrixTimelineDiffProcessor(
            timelineItems = timelineItems,
        )
        val timelineItemsSubscriber = createTimelineItemsSubscriber(
            timeline = timeline,
            timelineDiffProcessor = diffProcessor,
        )
        timelineItems.test {
            timelineItemsSubscriber.subscribeIfNeeded()
            // Wait for the listener to be set.
            runCurrent()
            timeline.emitDiff(
                listOf(
                    TimelineDiff.Reset(
                        listOf(FakeFfiTimelineItem(
                            asEventResult = aRustEventTimelineItem(origin = EventItemOrigin.SYNC),
                        ))
                    )
                )
            )
            val final = awaitItem()
            assertThat(final).isNotEmpty()
            timelineItemsSubscriber.unsubscribeIfNeeded()
        }
    }

    @Test
    fun `isolated Clear waits for its structural replacement instead of publishing an empty timeline`() = runTest {
        val timelineItems: MutableSharedFlow<List<MatrixTimelineItem>> =
            MutableSharedFlow(replay = 1, extraBufferCapacity = Int.MAX_VALUE)
        val timeline = FakeFfiTimeline()
        val diffProcessor = createMatrixTimelineDiffProcessor(timelineItems = timelineItems)
        val timelineItemsSubscriber = createTimelineItemsSubscriber(
            timeline = timeline,
            timelineDiffProcessor = diffProcessor,
        )
        timelineItems.test {
            timelineItemsSubscriber.subscribeIfNeeded()
            runCurrent()
            timeline.emitDiff(listOf(TimelineDiff.Reset(listOf(FakeFfiTimelineItem(), FakeFfiTimelineItem()))))
            assertThat(awaitItem()).hasSize(2)

            timeline.emitDiff(listOf(TimelineDiff.Clear))
            advanceTimeBy(100L)
            runCurrent()

            expectNoEvents()
            assertThat(timelineItems.replayCache.single()).isNotEmpty()

            timeline.emitDiff(listOf(TimelineDiff.Reset(listOf(FakeFfiTimelineItem()))))
            assertThat(awaitItem()).isNotEmpty()
            timelineItemsSubscriber.unsubscribeIfNeeded()
        }
    }

    @Test
    fun `ignore list Clear followed by Append resumes the existing subscriber without restart`() = runTest {
        val timelineItems: MutableSharedFlow<List<MatrixTimelineItem>> =
            MutableSharedFlow(replay = 1, extraBufferCapacity = Int.MAX_VALUE)
        val timeline = FakeFfiTimeline()
        val diffProcessor = createMatrixTimelineDiffProcessor(timelineItems = timelineItems)
        val timelineItemsSubscriber = createTimelineItemsSubscriber(
            timeline = timeline,
            timelineDiffProcessor = diffProcessor,
        )
        timelineItems.test {
            timelineItemsSubscriber.subscribeIfNeeded()
            runCurrent()
            timeline.emitDiff(listOf(TimelineDiff.Reset(listOf(FakeFfiTimelineItem(), FakeFfiTimelineItem()))))
            assertThat(awaitItem()).hasSize(2)

            timeline.emitDiff(listOf(TimelineDiff.Clear))
            advanceTimeBy(100L)
            runCurrent()

            expectNoEvents()
            assertThat(timelineItems.replayCache.single()).isNotEmpty()

            timeline.emitDiff(listOf(TimelineDiff.Append(listOf(FakeFfiTimelineItem()))))
            assertThat(awaitItem()).hasSize(1)
            timeline.emitDiff(listOf(TimelineDiff.PushBack(FakeFfiTimelineItem())))
            assertThat(awaitItem()).hasSize(2)
            timelineItemsSubscriber.unsubscribeIfNeeded()
        }
    }

    @Test
    fun `a newer Clear supersedes an uncommitted replacement without publishing an empty timeline`() = runTest {
        val timelineItems: MutableSharedFlow<List<MatrixTimelineItem>> =
            MutableSharedFlow(replay = 1, extraBufferCapacity = Int.MAX_VALUE)
        val timeline = FakeFfiTimeline()
        val diffProcessor = createMatrixTimelineDiffProcessor(timelineItems = timelineItems)
        val timelineItemsSubscriber = createTimelineItemsSubscriber(
            timeline = timeline,
            timelineDiffProcessor = diffProcessor,
        )
        val initial = FakeFfiTimelineItem(asEventResult = aRustEventTimelineItem(), uniqueId = "initial")
        val superseded = FakeFfiTimelineItem(asEventResult = aRustEventTimelineItem(), uniqueId = "superseded")
        val replacement = FakeFfiTimelineItem(asEventResult = aRustEventTimelineItem(), uniqueId = "replacement")

        timelineItems.test {
            timelineItemsSubscriber.subscribeIfNeeded()
            runCurrent()
            timeline.emitDiff(listOf(TimelineDiff.Reset(listOf(initial))))
            advanceTimeBy(20L)
            runCurrent()
            assertThat(awaitItem().single().uniqueIdValue()).isEqualTo("initial")

            timeline.emitDiff(listOf(TimelineDiff.Clear))
            advanceTimeBy(20L)
            runCurrent()
            timeline.emitDiff(listOf(TimelineDiff.Reset(listOf(superseded)), TimelineDiff.Clear))
            advanceTimeBy(20L)
            runCurrent()

            expectNoEvents()
            assertThat(timelineItems.replayCache.single().single().uniqueIdValue()).isEqualTo("initial")

            timeline.emitDiff(listOf(TimelineDiff.Reset(listOf(replacement))))
            advanceTimeBy(20L)
            runCurrent()
            assertThat(awaitItem().single().uniqueIdValue()).isEqualTo("replacement")
            timelineItemsSubscriber.unsubscribeIfNeeded()
        }
    }

    @Test
    fun `only the replacement after the last Clear in one emission is committed`() = runTest {
        val timelineItems: MutableSharedFlow<List<MatrixTimelineItem>> =
            MutableSharedFlow(replay = 1, extraBufferCapacity = Int.MAX_VALUE)
        val timeline = FakeFfiTimeline()
        val diffProcessor = createMatrixTimelineDiffProcessor(timelineItems = timelineItems)
        val timelineItemsSubscriber = createTimelineItemsSubscriber(
            timeline = timeline,
            timelineDiffProcessor = diffProcessor,
        )
        val initial = FakeFfiTimelineItem(asEventResult = aRustEventTimelineItem(), uniqueId = "initial")
        val superseded = FakeFfiTimelineItem(asEventResult = aRustEventTimelineItem(), uniqueId = "superseded")
        val replacement = FakeFfiTimelineItem(asEventResult = aRustEventTimelineItem(), uniqueId = "replacement")

        timelineItems.test {
            timelineItemsSubscriber.subscribeIfNeeded()
            runCurrent()
            timeline.emitDiff(listOf(TimelineDiff.Reset(listOf(initial))))
            advanceTimeBy(20L)
            runCurrent()
            assertThat(awaitItem().single().uniqueIdValue()).isEqualTo("initial")

            timeline.emitDiff(
                listOf(
                    TimelineDiff.Clear,
                    TimelineDiff.Reset(listOf(superseded)),
                    TimelineDiff.Clear,
                )
            )
            advanceTimeBy(20L)
            runCurrent()

            expectNoEvents()
            assertThat(timelineItems.replayCache.single().single().uniqueIdValue()).isEqualTo("initial")

            timeline.emitDiff(listOf(TimelineDiff.Reset(listOf(replacement))))
            advanceTimeBy(20L)
            runCurrent()
            assertThat(awaitItem().single().uniqueIdValue()).isEqualTo("replacement")
            timelineItemsSubscriber.unsubscribeIfNeeded()
        }
    }

    @Test
    fun `multiple subscriptions does not have side effect`() = runTest {
        val timelineItemsSubscriber = createTimelineItemsSubscriber()
        timelineItemsSubscriber.subscribeIfNeeded()
        timelineItemsSubscriber.subscribeIfNeeded()
        timelineItemsSubscriber.unsubscribeIfNeeded()
        timelineItemsSubscriber.unsubscribeIfNeeded()
    }
}

private fun MatrixTimelineItem.uniqueIdValue(): String = when (this) {
    is MatrixTimelineItem.Event -> uniqueId.value
    is MatrixTimelineItem.Virtual -> uniqueId.value
    MatrixTimelineItem.Other -> "other"
}

private fun TestScope.createTimelineItemsSubscriber(
    timeline: Timeline = FakeFfiTimeline(),
    timelineDiffProcessor: MatrixTimelineDiffProcessor = createMatrixTimelineDiffProcessor(),
): TimelineItemsSubscriber {
    return TimelineItemsSubscriber(
        timelineCoroutineScope = backgroundScope,
        dispatcher = StandardTestDispatcher(testScheduler),
        timeline = timeline,
        timelineDiffProcessor = timelineDiffProcessor,
    )
}
