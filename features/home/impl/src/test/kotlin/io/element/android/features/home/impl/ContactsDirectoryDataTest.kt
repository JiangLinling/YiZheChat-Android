/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.home.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.features.home.impl.model.RoomSummaryDisplayType
import io.element.android.features.home.impl.model.anInviteSender
import io.element.android.features.home.impl.model.aRoomListRoomSummary
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.security.dataprotection.api.isDeletedMatrixUserId
import io.element.android.security.dataprotection.test.FakeDeletedIdentityProjection
import org.junit.Test
import java.security.MessageDigest

class ContactsDirectoryDataTest {
    @Test
    fun `leaving direct chat removes friend on both membership projections`() {
        val accepted = aRoomListRoomSummary(id = "!friend:server", name = "Friend", isDm = true)
        fun friends(room: io.element.android.features.home.impl.model.RoomListRoomSummary) = buildContactsDirectoryData(
            summaries = listOf(room), displayName = { it.name.orEmpty() }, sortKey = { it.lowercase() },
        ).friendSections.values.flatten()
        assertThat(friends(accepted)).hasSize(1)
        assertThat(friends(accepted.copy(displayType = RoomSummaryDisplayType.PLACEHOLDER))).isEmpty()
        assertThat(friends(accepted.copy(joinedMembersCount = 1, activeMembersCount = 1))).isEmpty()
        assertThat(friends(accepted.copy(joinedMembersCount = 1, invitedMembersCount = 1))).isEmpty()
        assertThat(friends(accepted)).hasSize(1)
    }

    @Test
    fun `alphabet index keeps empty letters selectable and targets the nearest populated section`() {
        val sectionIndices = linkedMapOf("F" to 4, "J" to 8, "S" to 12, "#" to 16)

        assertThat(contactSectionTargetIndex("F", sectionIndices)).isEqualTo(4)
        assertThat(contactSectionTargetIndex("H", sectionIndices)).isEqualTo(8)
        assertThat(contactSectionTargetIndex("Z", sectionIndices)).isEqualTo(16)
        assertThat(contactSectionTargetIndex("A", sectionIndices)).isEqualTo(4)
    }

    @Test
    fun `alphabet index remains usable when there are no contacts`() {
        assertThat(contactSectionTargetIndex("↑", emptyMap())).isEqualTo(0)
        assertThat(contactSectionTargetIndex("☆", emptyMap())).isEqualTo(0)
        assertThat(contactSectionTargetIndex("M", emptyMap())).isEqualTo(0)
        assertThat(contactSectionTargetIndex("#", emptyMap())).isEqualTo(0)
    }

    @Test
    fun `deleted peer invite disappears from contacts while unrelated invite and group remain`() {
        val deletedUserId = UserId("@deleted:server.org")
        val deletedInvite = aRoomListRoomSummary(
            id = "!deleted-invite:server",
            isDm = true,
            displayType = RoomSummaryDisplayType.INVITE,
            inviteSender = anInviteSender(userId = deletedUserId),
        )
        val unrelatedInvite = aRoomListRoomSummary(
            id = "!unrelated-invite:server",
            isDm = true,
            displayType = RoomSummaryDisplayType.INVITE,
        )
        val group = aRoomListRoomSummary(id = "!group:server", name = "Shared group")
        val projection = FakeDeletedIdentityProjection(setOf(hash(deletedUserId.value)))

        val result = buildContactsDirectoryData(
            summaries = listOf(deletedInvite, unrelatedInvite, group),
            displayName = { it.name.orEmpty() },
            sortKey = { it.lowercase() },
            isDeletedMatrixUserId = projection::isDeletedMatrixUserId,
        )

        assertThat(result.friendRequests.map { it.id }).containsExactly(unrelatedInvite.id)
        assertThat(result.groups.map { it.id }).containsExactly(group.id)
    }

    @Test
    fun `build computes each friend sort key once and preserves section order`() {
        var sortKeyCalls = 0
        val alice = aRoomListRoomSummary(id = "!alice:server", name = "Alice", isDm = true)
        val bob = aRoomListRoomSummary(id = "!bob:server", name = "Bob", isDm = true)
        val numeric = aRoomListRoomSummary(id = "!numeric:server", name = "123", isDm = true)

        val result = buildContactsDirectoryData(
            summaries = listOf(numeric, bob, alice),
            displayName = { it.name.orEmpty() },
            sortKey = {
                sortKeyCalls += 1
                it.lowercase()
            },
        )

        assertThat(sortKeyCalls).isEqualTo(3)
        assertThat(result.friendSections.keys).containsExactly("A", "B", "#").inOrder()
        assertThat(result.friendSections.values.flatten().map { it.room.id })
            .containsExactly(alice.id, bob.id, numeric.id)
            .inOrder()
    }

    @Test
    fun `build keeps invites groups and direct friends in separate projections`() {
        val invite = aRoomListRoomSummary(
            id = "!invite:server",
            name = "Invite",
            isDm = true,
            displayType = RoomSummaryDisplayType.INVITE,
        ).copy(latestEventTimestamp = 20L)
        val friend = aRoomListRoomSummary(id = "!friend:server", name = "Friend", isDm = true)
        val outgoing = aRoomListRoomSummary(
            id = "!outgoing:server",
            name = "Pending",
            isDm = true,
            activeMembersCount = 2,
            invitedMembersCount = 1,
            joinedMembersCount = 1,
        )
        val group = aRoomListRoomSummary(id = "!group:server", name = "Group")
        val space = aRoomListRoomSummary(id = "!space:server", name = "Space", isSpace = true)

        val result = buildContactsDirectoryData(
            summaries = listOf(group, invite, outgoing, friend, space),
            displayName = { it.name.orEmpty() },
            sortKey = { it.lowercase() },
        )

        assertThat(result.friendRequests.map { it.id }).containsExactly(invite.id)
        assertThat(result.outgoingFriendRequests.map { it.id }).containsExactly(outgoing.id)
        assertThat(result.friendSections.values.flatten().map { it.room.id }).containsExactly(friend.id)
        assertThat(result.groups.map { it.id }).containsExactly(group.id)
    }
}

private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.encodeToByteArray())
    .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
