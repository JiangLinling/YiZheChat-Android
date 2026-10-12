/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.timeline.postprocessor

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.UniqueId
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.matrix.api.timeline.item.virtual.VirtualTimelineItem
import io.element.android.libraries.matrix.test.AN_EVENT_ID
import org.junit.Test
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LastForwardIndicatorsPostProcessorTest {
    @Test
    fun `a later collector does not inherit another collectors scroll anchors`() = runTest {
        val source = MutableSharedFlow<List<MatrixTimelineItem>>(replay = 1)
        val processed = source.withLastForwardIndicators(Timeline.Mode.FocusedOnEvent(AN_EVENT_ID))
        val first = mutableListOf<List<MatrixTimelineItem>>()
        val second = mutableListOf<List<MatrixTimelineItem>>()
        val job = launch { processed.take(2).toList(first) }
        runCurrent()
        source.emit(listOf(messageEvent))
        runCurrent()
        source.emit(listOf(messageEvent, messageEvent2))
        runCurrent()
        val other = launch { processed.take(1).toList(second) }
        runCurrent()
        job.join()
        other.join()
        assertThat(first.last().filterIsInstance<MatrixTimelineItem.Virtual>()).hasSize(2)
        assertThat(second.single().filterIsInstance<MatrixTimelineItem.Virtual>()).hasSize(1)
        assertThat(second.single().filterIsInstance<MatrixTimelineItem.Event>()).containsExactly(messageEvent, messageEvent2).inOrder()
    }

    @Test
    fun `overlapping processing cannot duplicate the current tail key`() {
        val sut = LastForwardIndicatorsPostProcessor(Timeline.Mode.FocusedOnEvent(AN_EVENT_ID))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        // Pause after latestEventIdentifier has been found, while the output is being built.
        val items = object : AbstractList<MatrixTimelineItem>() {
            override val size = 1
            private var reads = 0
            override fun get(index: Int): MatrixTimelineItem {
                if (++reads == 2) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                return messageEvent
            }
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val first = executor.submit<List<MatrixTimelineItem>> { sut.process(items) }
            check(entered.await(5, TimeUnit.SECONDS))
            val second = sut.process(listOf(messageEvent))
            release.countDown()
            for (result in listOf(first.get(5, TimeUnit.SECONDS), second)) {
                assertThat(result.filterIsInstance<MatrixTimelineItem.Virtual>().map { it.uniqueId }).containsNoDuplicates()
                assertThat(result.filterIsInstance<MatrixTimelineItem.Virtual>()).hasSize(1)
            }
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `LastForwardIndicatorsPostProcessor does not alter the items with mode not FOCUSED_ON_EVENT`() {
        val sut = LastForwardIndicatorsPostProcessor(Timeline.Mode.Live)
        val result = sut.process(listOf(messageEvent))
        assertThat(result).containsExactly(messageEvent)
    }

    @Test
    fun `LastForwardIndicatorsPostProcessor add virtual items`() {
        val sut = LastForwardIndicatorsPostProcessor(Timeline.Mode.FocusedOnEvent(AN_EVENT_ID))
        val result = sut.process(listOf(messageEvent))
        assertThat(result).containsExactly(
            messageEvent,
            MatrixTimelineItem.Virtual(
                uniqueId = UniqueId("last_forward_indicator_${messageEvent.uniqueId}"),
                virtual = VirtualTimelineItem.LastForwardIndicator
            )
        )
    }

    @Test
    fun `LastForwardIndicatorsPostProcessor add virtual items on empty list`() {
        val sut = LastForwardIndicatorsPostProcessor(Timeline.Mode.FocusedOnEvent(AN_EVENT_ID))
        val result = sut.process(listOf())
        assertThat(result).containsExactly(
            MatrixTimelineItem.Virtual(
                uniqueId = UniqueId("last_forward_indicator_fake_id"),
                virtual = VirtualTimelineItem.LastForwardIndicator
            )
        )
    }

    @Test
    fun `LastForwardIndicatorsPostProcessor add virtual items but does not alter the list if called a second time`() {
        val sut = LastForwardIndicatorsPostProcessor(Timeline.Mode.FocusedOnEvent(AN_EVENT_ID))
        // Process a first time
        sut.process(listOf(messageEvent))
        // Process a second time with the same Event
        val result = sut.process(listOf(messageEvent))
        assertThat(result).containsExactly(
            messageEvent,
            MatrixTimelineItem.Virtual(
                uniqueId = UniqueId("last_forward_indicator_${messageEvent.uniqueId}"),
                virtual = VirtualTimelineItem.LastForwardIndicator
            )
        )
    }

    @Test
    fun `LastForwardIndicatorsPostProcessor add virtual items each time it is called with new Events`() {
        val sut = LastForwardIndicatorsPostProcessor(Timeline.Mode.FocusedOnEvent(AN_EVENT_ID))
        // Process a first time
        sut.process(listOf(dayEvent, messageEvent))
        // Process a second time with the same Event
        val result = sut.process(listOf(dayEvent, messageEvent, messageEvent2))
        assertThat(result).containsExactly(
            dayEvent,
            messageEvent,
            MatrixTimelineItem.Virtual(
                uniqueId = UniqueId("last_forward_indicator_${messageEvent.uniqueId}"),
                virtual = VirtualTimelineItem.LastForwardIndicator
            ),
            messageEvent2,
            MatrixTimelineItem.Virtual(
                uniqueId = UniqueId("last_forward_indicator_${messageEvent2.uniqueId}"),
                virtual = VirtualTimelineItem.LastForwardIndicator
            )
        )
    }
}
