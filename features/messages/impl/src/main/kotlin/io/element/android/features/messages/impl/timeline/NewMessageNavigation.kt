/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.messages.impl.timeline

import io.element.android.features.messages.api.RoomReadingNavigation
import io.element.android.features.messages.impl.timeline.model.TimelineItem
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemLegacyCallInviteContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemRedactedContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemRtcNotificationContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemStateContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemUnknownContent
import io.element.android.features.messages.impl.timeline.model.virtual.TimelineItemReadMarkerModel
import io.element.android.libraries.matrix.api.core.EventId

internal const val NEW_MESSAGE_HINT_MILLIS = 8_000L

internal fun TimelineItem.events(): List<TimelineItem.Event> = when (this) {
    is TimelineItem.Event -> listOf(this)
    is TimelineItem.GroupedEvents -> events
    else -> emptyList()
}

internal fun TimelineItem.Event.isIncomingMessage(): Boolean = eventId != null && !isMine &&
    content !is TimelineItemStateContent && content !is TimelineItemRedactedContent &&
    content !is TimelineItemLegacyCallInviteContent && content !is TimelineItemRtcNotificationContent &&
    content != TimelineItemUnknownContent && !content.isTerminalProtectedContentShell

/** Freeze the SDK boundary before any entry read receipt. Rows are ordered newest to oldest. */
internal fun captureNewMessageNavigation(
    items: List<TimelineItem>,
    saved: RoomReadingNavigation,
): RoomReadingNavigation {
    if (saved.firstNewEventId != null) return saved
    val marker = items.indexOfFirst { it is TimelineItem.Virtual && it.model == TimelineItemReadMarkerModel }
    val localBoundary = items.indexOfFirst { row ->
        row.events().any { it.eventId?.value == saved.latestSeenEventId }
    }
    // Managed groups may suppress public receipts, leaving the SDK marker behind local viewing.
    val boundary = listOf(marker, localBoundary).filter { it >= 0 }.minOrNull() ?: -1
    if (boundary < 0) return saved
    val newEvents = items.take(boundary).flatMap { it.events() }.filter { it.isIncomingMessage() }
    return saved.copy(
        firstNewEventId = newEvents.lastOrNull()?.eventId?.value,
        batchEndEventId = newEvents.firstOrNull()?.eventId?.value,
        hintRemainingMillis = NEW_MESSAGE_HINT_MILLIS,
        hintActivated = false,
    )
}

internal fun newMessageItemIndex(items: List<TimelineItem>, eventId: String?): Int =
    if (eventId == null) -1 else items.indexOfFirst { row -> row.events().any { it.eventId?.value == eventId } }

/** No count threshold: a clipped first row or an earlier, unmeasured row is above the viewport. */
internal fun firstNewMessageAboveViewport(
    targetIndex: Int,
    visibleIndices: List<Int>,
    targetOffset: Int?,
    viewportStart: Int,
    reverseLayout: Boolean,
): Boolean {
    if (targetIndex < 0 || visibleIndices.isEmpty()) return false
    return targetOffset?.let { it < viewportStart } ?: if (reverseLayout) {
        targetIndex > visibleIndices.max()
    } else {
        targetIndex < visibleIndices.min()
    }
}

internal fun RoomReadingNavigation.consumeHintTime(elapsedMillis: Long): RoomReadingNavigation =
    copy(hintRemainingMillis = (hintRemainingMillis - elapsedMillis.coerceAtLeast(0L)).coerceIn(0L, NEW_MESSAGE_HINT_MILLIS))

internal fun nextAvailableNewMessage(
    items: List<TimelineItem>,
    failedId: EventId,
    entryCandidateIds: List<EventId>,
): EventId? {
    val remaining = entryCandidateIds.dropWhile { it != failedId }.drop(1)
    val available = items.flatMap { it.events() }.filter { it.isIncomingMessage() }.mapNotNull { it.eventId }.toSet()
    return remaining.firstOrNull { it in available }
}

/** Count only incoming messages after a known local viewing identity; unknown boundaries omit the badge. */
internal fun incomingMessagesSinceLastSeen(items: List<TimelineItem>, latestSeenEventId: String?): Int {
    val boundary = newMessageItemIndex(items, latestSeenEventId)
    if (boundary < 0) return 0
    return items.take(boundary).flatMap { it.events() }.count { it.isIncomingMessage() }
}
