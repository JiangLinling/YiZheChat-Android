/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.home.impl.notifications

import io.element.android.features.home.impl.model.RoomListRoomSummary
import io.element.android.features.home.impl.model.RoomSummaryDisplayType
import io.element.android.features.home.impl.model.referencesDeletedDirectPeer
import io.element.android.features.invite.api.InviteNotification
import io.element.android.libraries.matrix.api.room.DirectRoomRelationState

internal fun filteredNotifications(records: List<InviteNotification>, kind: String, status: String): List<InviteNotification> =
    records.filter {
        (kind == "ALL" || it.kind.name == kind) &&
            (status == "ALL" || status == "PENDING" && !it.outgoing && it.outcome == InviteNotification.Outcome.PENDING ||
                status == "HANDLED" && it.outcome != InviteNotification.Outcome.PENDING)
    }.sortedWith(compareByDescending<InviteNotification> { it.sequence }.thenByDescending { it.notificationTime }.thenBy { it.roomId.value })

internal fun RoomListRoomSummary.toNotification(): InviteNotification? {
    val outgoing = directRoomRelationState() == DirectRoomRelationState.OUTGOING_REQUEST
    if (displayType != RoomSummaryDisplayType.INVITE && !outgoing) return null
    return InviteNotification(
        roomId = roomId,
        kind = when { isSpace -> InviteNotification.Kind.SPACE; isDm -> InviteNotification.Kind.FRIEND; else -> InviteNotification.Kind.GROUP },
        senderId = if (outgoing) heroes.firstOrNull()?.id else inviteSender?.userId?.value,
        timestamp = latestEventTimestamp.coerceAtLeast(0L),
        outgoing = outgoing,
        displayName = name,
        avatarUrl = avatarData.url,
    )
}

/** A remembered request may only become accepted from a confirmed relationship/membership projection. */
internal fun reconciledOutcome(record: InviteNotification, room: RoomListRoomSummary?): InviteNotification.Outcome? {
    if (record.outcome !in setOf(InviteNotification.Outcome.PENDING, InviteNotification.Outcome.UNAVAILABLE) ||
        room?.displayType != RoomSummaryDisplayType.ROOM) return null
    return when (record.kind) {
        InviteNotification.Kind.FRIEND -> when (room.directRoomRelationState()) {
            DirectRoomRelationState.ACCEPTED -> InviteNotification.Outcome.ACCEPTED
            DirectRoomRelationState.STALE -> InviteNotification.Outcome.UNAVAILABLE.takeIf {
                record.outgoing && record.outcome == InviteNotification.Outcome.PENDING
            }
            else -> null
        }
        InviteNotification.Kind.GROUP, InviteNotification.Kind.SPACE -> InviteNotification.Outcome.ACCEPTED
    }
}

/** Reuse the authoritative account tombstone projection used by the contacts directory. */
internal fun visibleNotifications(
    records: List<InviteNotification>,
    rooms: List<RoomListRoomSummary>,
    isDeletedUser: (String) -> Boolean,
): List<InviteNotification> = records.filterNot { notice ->
    notice.kind == InviteNotification.Kind.FRIEND && (
        notice.senderId?.let(isDeletedUser) == true ||
            rooms.firstOrNull { it.roomId == notice.roomId }?.referencesDeletedDirectPeer(isDeletedUser) == true
        )
}
