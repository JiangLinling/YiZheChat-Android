/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.features.messages.api.MessageReferenceState
import io.element.android.features.messages.impl.timeline.aTimelineItemEvent
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.test.A_ROOM_ID
import org.junit.Test

class LocallyDeletedMessageFilterTest {
    @Test
    fun `a locally deleted event is removed from the visible timeline`() {
        val eventId = EventId("\$locally-deleted")
        val event = aTimelineItemEvent(eventId = eventId)
        val state = MessageReferenceState(
            locallyDeletedKeys = setOf(MessageReferenceState.key(A_ROOM_ID, eventId)),
        )

        assertThat(event.withoutLocallyDeletedEvents(A_ROOM_ID, state)).isNull()
    }

    @Test
    fun `a terminal event may be retained only for its destruction transition`() {
        val eventId = EventId("\$destroying")
        val event = aTimelineItemEvent(eventId = eventId)
        val state = MessageReferenceState(
            locallyDeletedKeys = setOf(MessageReferenceState.key(A_ROOM_ID, eventId)),
        )

        assertThat(event.withoutLocallyDeletedEvents(A_ROOM_ID, state, setOf(eventId))).isSameInstanceAs(event)
        assertThat(event.withoutLocallyDeletedEvents(A_ROOM_ID, state)).isNull()
    }

    @Test
    fun `an event not deleted locally remains visible`() {
        val event = aTimelineItemEvent(eventId = EventId("\$visible"))

        assertThat(event.withoutLocallyDeletedEvents(A_ROOM_ID, MessageReferenceState())).isSameInstanceAs(event)
    }
}
