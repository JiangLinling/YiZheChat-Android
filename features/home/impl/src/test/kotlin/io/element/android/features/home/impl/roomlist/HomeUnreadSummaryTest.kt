/*
 * Copyright (c) 2026 YiZheChat.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.features.home.impl.roomlist

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.room.CurrentUserMembership
import io.element.android.libraries.matrix.test.room.aRoomInfo
import io.element.android.libraries.matrix.test.room.aRoomSummary
import org.junit.Test

class HomeUnreadSummaryTest {
    @Test
    fun `counts joined unread once using the strongest Matrix counter`() {
        val result = calculateHomeUnreadSummary(
            roomSummaries = listOf(
                aRoomSummary(
                    info = aRoomInfo(
                        id = RoomId("!joined:example.org"),
                        currentUserMembership = CurrentUserMembership.JOINED,
                        numUnreadMessages = 4,
                        numUnreadNotifications = 2,
                        numUnreadMentions = 1,
                    )
                ),
                aRoomSummary(
                    info = aRoomInfo(
                        id = RoomId("!marked:example.org"),
                        currentUserMembership = CurrentUserMembership.JOINED,
                        isMarkedUnread = true,
                    )
                ),
            ),
            seenInviteRoomIds = emptySet(),
        )

        assertThat(result.joinedRoomUnreadCount).isEqualTo(5)
        assertThat(result.chatAttentionCount).isEqualTo(5)
    }

    @Test
    fun `classifies friend group and space invitations and excludes seen invites from badges`() {
        val seenFriendId = RoomId("!friend-seen:example.org")
        val result = calculateHomeUnreadSummary(
            roomSummaries = listOf(
                invitedRoom("!friend-new:example.org", isDm = true),
                invitedRoom(seenFriendId.value, isDm = true),
                invitedRoom("!group:example.org"),
                invitedRoom("!space:example.org", isSpace = true),
            ),
            seenInviteRoomIds = setOf(seenFriendId),
        )

        assertThat(result.pendingFriendRequestCount).isEqualTo(2)
        assertThat(result.unseenFriendRequestCount).isEqualTo(1)
        assertThat(result.pendingGroupInviteCount).isEqualTo(1)
        assertThat(result.unseenGroupInviteCount).isEqualTo(1)
        assertThat(result.pendingSpaceInviteCount).isEqualTo(1)
        assertThat(result.unseenSpaceInviteCount).isEqualTo(1)
        assertThat(result.pendingInviteCount).isEqualTo(4)
        assertThat(result.unseenInviteCount).isEqualTo(3)
        assertThat(result.contactsAttentionCount).isEqualTo(1)
    }

    @Test
    fun `does not count invite counters as joined message unread`() {
        val result = calculateHomeUnreadSummary(
            roomSummaries = listOf(
                aRoomSummary(
                    info = aRoomInfo(
                        id = RoomId("!invite:example.org"),
                        currentUserMembership = CurrentUserMembership.INVITED,
                        isDm = true,
                        numUnreadMessages = 99,
                        numUnreadNotifications = 99,
                    )
                )
            ),
            seenInviteRoomIds = emptySet(),
        )

        assertThat(result.joinedRoomUnreadCount).isEqualTo(0)
        assertThat(result.chatAttentionCount).isEqualTo(1)
    }

    private fun invitedRoom(
        roomId: String,
        isDm: Boolean = false,
        isSpace: Boolean = false,
    ) = aRoomSummary(
        info = aRoomInfo(
            id = RoomId(roomId),
            currentUserMembership = CurrentUserMembership.INVITED,
            isDm = isDm,
            isSpace = isSpace,
        )
    )
}
