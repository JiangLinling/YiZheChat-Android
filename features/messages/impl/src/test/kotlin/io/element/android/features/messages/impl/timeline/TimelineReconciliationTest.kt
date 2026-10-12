/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.features.messages.impl.timeline

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.UniqueId
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.matrix.api.timeline.item.virtual.VirtualTimelineItem
import io.element.android.libraries.matrix.test.timeline.aMessageContent
import io.element.android.libraries.matrix.test.timeline.anEventTimelineItem
import org.junit.Test

class TimelineReconciliationTest {
    @Test
    fun `pending and confirmed event keep the same canonical real-message index`() {
        val previous = event("previous", timestamp = 1L)
        val newer = event("newer", timestamp = 3L)
        val pending = event("pending", timestamp = 2L)
        val typing = virtual("typing", VirtualTimelineItem.TypingNotification)
        val forwardLoading = virtual(
            "forward-loading",
            VirtualTimelineItem.LoadingIndicator(Timeline.PaginationDirection.FORWARDS, timestamp = 3L),
        )

        val pendingResult = listOf(previous, newer, typing, forwardLoading)
            .insertPendingEventsBeforeTrailingVirtualItems(listOf(pending))
            .withCanonicalEventOrder { id -> 2L.takeIf { id == pending.displayUniqueId } }

        assertThat(pendingResult.map { it.reconciliationTestUniqueId() })
            .containsExactly(previous.uniqueId, pending.uniqueId, newer.uniqueId, typing.uniqueId, forwardLoading.uniqueId)
            .inOrder()

        val remoteId = UniqueId("remote")
        val confirmed = event("remote", timestamp = 4L).copy(displayUniqueId = pending.displayUniqueId)
        val confirmedResult = listOf(previous, newer, confirmed, typing, forwardLoading)
            .withCanonicalEventOrder { id -> 2L.takeIf { id == pending.displayUniqueId } }
        assertThat(confirmedResult.map { it.reconciliationTestUniqueId() })
            .containsExactly(previous.uniqueId, remoteId, newer.uniqueId, typing.uniqueId, forwardLoading.uniqueId)
            .inOrder()
    }
}

private fun event(id: String, timestamp: Long): MatrixTimelineItem.Event = MatrixTimelineItem.Event(
    uniqueId = UniqueId(id),
    event = anEventTimelineItem(
        isOwn = true,
        isRemote = true,
        timestamp = timestamp,
        content = aMessageContent(body = id),
    ),
)

private fun virtual(id: String, item: VirtualTimelineItem): MatrixTimelineItem.Virtual =
    MatrixTimelineItem.Virtual(UniqueId(id), item)

private fun MatrixTimelineItem.reconciliationTestUniqueId(): UniqueId = when (this) {
    is MatrixTimelineItem.Event -> uniqueId
    is MatrixTimelineItem.Virtual -> uniqueId
    MatrixTimelineItem.Other -> UniqueId("other")
}
