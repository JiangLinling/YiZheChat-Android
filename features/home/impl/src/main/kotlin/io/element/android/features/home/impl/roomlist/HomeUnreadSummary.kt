/*
 * Copyright (c) 2026 YiZheChat.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.features.home.impl.roomlist

import androidx.compose.runtime.Immutable
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.room.CurrentUserMembership
import io.element.android.libraries.matrix.api.room.directRoomRelationState
import io.element.android.libraries.matrix.api.roomlist.RoomSummary

/**
 * One stable source of truth for all badges rendered by the home screen.
 *
 * Pending counts describe actionable invitations. Unseen counts describe badge attention and
 * therefore exclude invitations which the user has already opened. Message unread counts only
 * include joined rooms; invites never get counted twice as messages.
 */
@Immutable
data class HomeUnreadSummary(
    val joinedRoomUnreadCount: Long = 0,
    val pendingFriendRequestCount: Int = 0,
    val unseenFriendRequestCount: Int = 0,
    val pendingGroupInviteCount: Int = 0,
    val unseenGroupInviteCount: Int = 0,
    val pendingSpaceInviteCount: Int = 0,
    val unseenSpaceInviteCount: Int = 0,
) {
    val pendingInviteCount: Int
        get() = pendingFriendRequestCount + pendingGroupInviteCount + pendingSpaceInviteCount

    val unseenInviteCount: Int
        get() = unseenFriendRequestCount + unseenGroupInviteCount + unseenSpaceInviteCount

    /** Used by both the home title and the Chats tab. */
    val chatAttentionCount: Long
        get() = joinedRoomUnreadCount + unseenInviteCount

    /** Used by both the New friends entry and the Contacts tab. */
    val contactsAttentionCount: Int
        get() = unseenFriendRequestCount
}

internal fun calculateHomeUnreadSummary(
    roomSummaries: List<RoomSummary>,
    seenInviteRoomIds: Set<RoomId>,
): HomeUnreadSummary {
    var joinedUnread = 0L
    var pendingFriends = 0
    var unseenFriends = 0
    var pendingGroups = 0
    var unseenGroups = 0
    var pendingSpaces = 0
    var unseenSpaces = 0

    roomSummaries.forEach { summary ->
        val info = summary.info
        when (info.currentUserMembership) {
            CurrentUserMembership.JOINED -> {
                if (info.directRoomRelationState().canInteract) {
                    joinedUnread += maxOf(
                        info.numUnreadMessages,
                        info.numUnreadNotifications,
                        info.numUnreadMentions,
                        if (info.isMarkedUnread) 1L else 0L,
                    )
                }
            }
            CurrentUserMembership.INVITED -> {
                val isUnseen = summary.roomId !in seenInviteRoomIds
                when {
                    info.isSpace -> {
                        pendingSpaces++
                        if (isUnseen) unseenSpaces++
                    }
                    info.isDm -> {
                        pendingFriends++
                        if (isUnseen) unseenFriends++
                    }
                    else -> {
                        pendingGroups++
                        if (isUnseen) unseenGroups++
                    }
                }
            }
            CurrentUserMembership.LEFT,
            CurrentUserMembership.KNOCKED,
            CurrentUserMembership.BANNED -> Unit
        }
    }

    return HomeUnreadSummary(
        joinedRoomUnreadCount = joinedUnread,
        pendingFriendRequestCount = pendingFriends,
        unseenFriendRequestCount = unseenFriends,
        pendingGroupInviteCount = pendingGroups,
        unseenGroupInviteCount = unseenGroups,
        pendingSpaceInviteCount = pendingSpaces,
        unseenSpaceInviteCount = unseenSpaces,
    )
}
