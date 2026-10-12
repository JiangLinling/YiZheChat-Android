/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.home.impl.notifications

import com.google.common.truth.Truth.assertThat
import io.element.android.features.home.impl.model.RoomSummaryDisplayType
import io.element.android.features.home.impl.model.aRoomListRoomSummary
import io.element.android.features.invite.api.InviteNotification
import org.junit.Test

class NotificationProjectionTest {
    private val invite = aRoomListRoomSummary(displayType = RoomSummaryDisplayType.INVITE, isDm = true)
    private val notice = requireNotNull(invite.toNotification())

    @Test fun `invites classify friend group and space`() {
        assertThat(notice.kind).isEqualTo(InviteNotification.Kind.FRIEND)
        assertThat(invite.copy(isDm = false).toNotification()?.kind).isEqualTo(InviteNotification.Kind.GROUP)
        assertThat(invite.copy(isSpace = true).toNotification()?.kind).isEqualTo(InviteNotification.Kind.SPACE)
    }
    @Test fun `absence and pending binding never claim accepted`() {
        assertThat(reconciledOutcome(notice, null)).isNull()
        assertThat(reconciledOutcome(notice, invite)).isNull()
        assertThat(reconciledOutcome(notice, invite.copy(displayType = RoomSummaryDisplayType.ROOM,
            activeMembersCount = 2, joinedMembersCount = 1, invitedMembersCount = 1))).isNull()
    }
    @Test fun `accepted friend requires actual two joined members`() {
        assertThat(reconciledOutcome(notice, invite.copy(displayType = RoomSummaryDisplayType.ROOM,
            activeMembersCount = 2, joinedMembersCount = 2, invitedMembersCount = 0)))
            .isEqualTo(InviteNotification.Outcome.ACCEPTED)
    }
    @Test fun `unavailable projection recovers only from confirmed membership`() {
        val unavailable = notice.copy(outgoing = true, outcome = InviteNotification.Outcome.UNAVAILABLE)
        val accepted = invite.copy(displayType = RoomSummaryDisplayType.ROOM,
            activeMembersCount = 2, joinedMembersCount = 2, invitedMembersCount = 0)
        assertThat(reconciledOutcome(unavailable, accepted)).isEqualTo(InviteNotification.Outcome.ACCEPTED)
        assertThat(reconciledOutcome(unavailable, accepted.copy(joinedMembersCount = 1, activeMembersCount = 1))).isNull()
        assertThat(reconciledOutcome(unavailable, null)).isNull()
    }
    @Test fun `confirmed group membership accepts group notice`() {
        assertThat(reconciledOutcome(notice.copy(kind = InviteNotification.Kind.GROUP),
            invite.copy(displayType = RoomSummaryDisplayType.ROOM, isDm = false)))
            .isEqualTo(InviteNotification.Outcome.ACCEPTED)
    }
    @Test fun `declined history remains declined even with stale room`() {
        assertThat(reconciledOutcome(notice.copy(outcome = InviteNotification.Outcome.DECLINED),
            invite.copy(displayType = RoomSummaryDisplayType.ROOM))).isNull()
    }
    @Test fun `account tombstone hides friend history but retains another account group`() {
        val friend = notice.copy(senderId = "@deleted:example.test")
        val group = friend.copy(kind = InviteNotification.Kind.GROUP)
        assertThat(visibleNotifications(listOf(friend, group), emptyList()) { it == "@deleted:example.test" })
            .containsExactly(group)
    }
    @Test fun `ended outgoing friend request stops waiting without fabricating refusal`() {
        val ended = invite.copy(displayType = RoomSummaryDisplayType.ROOM, activeMembersCount = 1, joinedMembersCount = 1, invitedMembersCount = 0)
        assertThat(reconciledOutcome(notice.copy(outgoing = true), ended)).isEqualTo(InviteNotification.Outcome.UNAVAILABLE)
        assertThat(reconciledOutcome(notice, ended)).isNull()
    }
    @Test fun `group filter excludes spaces and retains arrival order`() {
        val group = notice.copy(kind = InviteNotification.Kind.GROUP, sequence = 1, timestamp = 99999)
        val newest = group.copy(roomId = io.element.android.libraries.matrix.api.core.RoomId("!new:example.test"), sequence = 2, timestamp = 0)
        val space = notice.copy(kind = InviteNotification.Kind.SPACE)
        assertThat(filteredNotifications(listOf(group, space, newest), "GROUP", "ALL")).containsExactly(newest, group).inOrder()
        assertThat(filteredNotifications(listOf(group, space), "SPACE", "PENDING")).containsExactly(space)
    }
    @Test fun `missing sender still checks live direct room heroes`() {
        val hero = io.element.android.libraries.designsystem.components.avatar.AvatarData(
            id = "@deleted:example.test", name = "Deleted", size = io.element.android.libraries.designsystem.components.avatar.AvatarSize.RoomListItem)
        val room = invite.copy(heroes = kotlinx.collections.immutable.persistentListOf(hero))
        assertThat(visibleNotifications(listOf(notice.copy(senderId = null)), listOf(room)) { it == hero.id }).isEmpty()
    }

}
