/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.messages.impl.timeline

import com.google.common.truth.Truth.assertThat
import io.element.android.features.messages.api.RoomReadingNavigation
import io.element.android.features.messages.impl.timeline.model.TimelineItem
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemRedactedContent
import io.element.android.features.messages.impl.timeline.model.virtual.TimelineItemReadMarkerModel
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.UniqueId
import org.junit.Test

class NewMessageNavigationTest {
    private fun event(id: String, mine: Boolean = false) = aTimelineItemEvent(eventId = EventId("$$id"), isMine = mine)
    private val marker = TimelineItem.Virtual(UniqueId("read-marker"), TimelineItemReadMarkerModel)

    @Test fun `freeze first incoming new message before receipt removes SDK marker`() {
        val rows = listOf(event("newest"), event("own", true), event("first"), marker, event("old"))
        val captured = captureNewMessageNavigation(rows, RoomReadingNavigation())
        assertThat(captured.firstNewEventId).isEqualTo("\$first")
        assertThat(captured.batchEndEventId).isEqualTo("\$newest")
        assertThat(captureNewMessageNavigation(listOf(event("later")) + rows.filterNot { it == marker }, captured)).isEqualTo(captured)
    }

    @Test fun `local latest seen boundary excludes previously visible messages`() {
        val rows = listOf(event("new"), event("seen"), event("older"))
        assertThat(captureNewMessageNavigation(rows, RoomReadingNavigation(latestSeenEventId = "\$seen")).firstNewEventId).isEqualTo("\$new")
    }

    @Test fun `own messages and deleted messages alone do not activate new messages`() {
        val deleted = event("deleted").copy(content = TimelineItemRedactedContent)
        assertThat(captureNewMessageNavigation(listOf(event("own", true), deleted, marker), RoomReadingNavigation()).firstNewEventId).isNull()
    }

    @Test fun `managed group stale SDK marker never reopens an already viewed batch`() {
        val rows = listOf(event("latest"), event("earlier"), marker, event("old"))
        val saved = RoomReadingNavigation(latestSeenEventId = "\$latest")
        assertThat(captureNewMessageNavigation(rows, saved).firstNewEventId).isNull()
    }

    @Test fun `unknown boundary is never invented from the loaded window`() {
        assertThat(captureNewMessageNavigation(listOf(event("a"), event("b")), RoomReadingNavigation()).firstNewEventId).isNull()
    }

    @Test fun `actual viewport controls activation including clipped tall first row`() {
        assertThat(firstNewMessageAboveViewport(8, listOf(0, 1, 2), null, 0, true)).isTrue()
        assertThat(firstNewMessageAboveViewport(1, listOf(0, 1, 2), 20, 0, true)).isFalse()
        assertThat(firstNewMessageAboveViewport(0, listOf(0), -200, 0, true)).isTrue()
        assertThat(firstNewMessageAboveViewport(-1, listOf(0, 1), null, 0, true)).isFalse()
        assertThat(firstNewMessageAboveViewport(1, listOf(2, 3, 4), null, 0, false)).isTrue()
    }

    @Test fun `eight usable seconds expires hint without destroying navigation target`() {
        val position = RoomReadingNavigation(firstNewEventId = "\$first", hintActivated = true)
        val paused = position.consumeHintTime(3_000)
        assertThat(paused.hintRemainingMillis).isEqualTo(5_000)
        val expired = paused.consumeHintTime(5_000)
        assertThat(expired.hintRemainingMillis).isEqualTo(0)
        assertThat(expired.firstNewEventId).isEqualTo("\$first")
        assertThat(expired.consumeHintTime(1_000).hintRemainingMillis).isEqualTo(0)
    }

    @Test fun `missing target only falls forward within captured new batch`() {
        val first = EventId("\$first")
        val second = EventId("\$second")
        val candidates = listOf(first, second, EventId("\$end"))
        val rows = listOf(event("later"), event("second"), event("old"))
        assertThat(nextAvailableNewMessage(rows, first, candidates)).isEqualTo(second)
        assertThat(nextAvailableNewMessage(listOf(event("later")), first, candidates)).isNull()
    }
    @Test fun `latest count excludes own and deleted messages and omits unknown boundary`() {
        val rows = listOf(event("new"), event("mine", true), event("deleted").copy(content = TimelineItemRedactedContent), event("seen"))
        assertThat(incomingMessagesSinceLastSeen(rows, "\$seen")).isEqualTo(1)
        assertThat(incomingMessagesSinceLastSeen(rows, "\$missing")).isEqualTo(0)
        assertThat(incomingMessagesSinceLastSeen(rows, null)).isEqualTo(0)
    }
}
