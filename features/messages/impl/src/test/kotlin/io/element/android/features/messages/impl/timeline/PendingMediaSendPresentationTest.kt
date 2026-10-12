/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline

import com.google.common.truth.Truth.assertThat
import io.element.android.features.messages.impl.fixtures.aMessageEvent
import io.element.android.features.messages.impl.timeline.groups.TimelineItemGrouper
import io.element.android.features.messages.impl.timeline.model.TimelineItem
import io.element.android.features.messages.impl.timeline.model.event.aTimelineItemImageContent
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.UniqueId
import io.element.android.libraries.matrix.api.timeline.item.event.ProtectedMediaAlbum
import org.junit.Test

class PendingMediaSendPresentationTest {
    @Test
    fun `album conversion acknowledges every rendered member but retains an unrelated pending send`() {
        val members = (0..2).map { index ->
            aMessageEvent(
                eventId = EventId("\$album-$index"),
                content = aTimelineItemImageContent().copy(
                    mediaAlbum = ProtectedMediaAlbum(id = "album1234567890", index = index, count = 3),
                ),
            ).copy(displayId = UniqueId("pending-$index"))
        }
        val grouped = TimelineItemGrouper().group(members)
        assertThat(grouped.single()).isInstanceOf(TimelineItem.GroupedEvents::class.java)
        val unrelated = PendingMediaSendPresentation(UniqueId("another-send"))
        val pending = members.map { PendingMediaSendPresentation(it.displayId) } + unrelated

        assertThat(unrenderedPendingMediaSendPresentations(pending, renderedMediaPresentationIds(grouped)))
            .containsExactly(unrelated)
        assertThat(unrenderedPendingMediaSendPresentations(pending, renderedMediaPresentationIds(members.take(1))))
            .containsExactly(pending[1], pending[2], unrelated)
    }

    @Test
    fun `fast path stays visible until the converted row with the same identity exists`() {
        val displayId = UniqueId("pending-photo")
        val pending = listOf(PendingMediaSendPresentation(displayId))

        assertThat(
            unrenderedPendingMediaSendPresentations(pending, renderedTimelineItemIds = emptySet())
        ).containsExactlyElementsIn(pending)

        assertThat(
            unrenderedPendingMediaSendPresentations(pending, renderedTimelineItemIds = setOf(displayId))
        ).isEmpty()
    }
}
