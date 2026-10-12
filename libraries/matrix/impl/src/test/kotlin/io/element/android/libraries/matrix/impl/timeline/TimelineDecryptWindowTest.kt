/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.timeline

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.UniqueId
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.test.timeline.anEventTimelineItem
import org.junit.Test

class TimelineDecryptWindowTest {
    private val history = List(1000) { index -> MatrixTimelineItem.Event(UniqueId("item-$index"), anEventTimelineItem()) }

    @Test
    fun `startup keeps raw history intact while scheduling only the latest ninety slots`() {
        val window = timelineDecryptWindow(history, null)
        assertThat(window).hasSize(90)
        assertThat(window.first()).isEqualTo(history.last())
        assertThat(window).doesNotContain(history.first())
        assertThat(history).hasSize(1000)
    }

    @Test
    fun `scrolling schedules the visible old event and nearby history`() {
        val window = timelineDecryptWindow(history, listOf("event:item-100"))
        assertThat(window.first()).isEqualTo(history[100])
        assertThat(window).hasSize(61)
        assertThat(window).contains(history[70])
        assertThat(window).doesNotContain(history[900])
    }

    @Test
    fun `moving viewport retires queued history without canceling real new arrivals`() {
        val queue = PendingDecryptQueue(4096, 30)
        queue.offer(visible = emptyList(), history = listOf(history[10]), recentHistory = listOf(history.last()))
        queue.retainHistoryWindow(setOf("event:item-100"))
        val work = checkNotNull(queue.pollVisibleOrHistory())
        assertThat(work.item).isEqualTo(history.last())
        assertThat(work.priority).isEqualTo(DecryptPriority.HISTORY)
        queue.complete(work)
        assertThat(queue.pollVisibleOrHistory()).isNull()
    }
}
