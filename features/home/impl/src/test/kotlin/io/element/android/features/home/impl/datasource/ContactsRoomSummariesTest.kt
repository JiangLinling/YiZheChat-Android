/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.home.impl.datasource

import com.google.common.truth.Truth.assertThat
import io.element.android.features.home.impl.buildContactsDirectoryData
import io.element.android.features.home.impl.model.LatestEvent
import io.element.android.features.home.impl.model.RoomSummaryDisplayType
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.room.CurrentUserMembership
import io.element.android.libraries.matrix.api.roomlist.RoomList
import io.element.android.libraries.matrix.api.user.MatrixUser
import io.element.android.libraries.matrix.test.room.aRemoteLatestEvent
import io.element.android.libraries.matrix.test.room.aRoomInfo
import io.element.android.libraries.matrix.test.room.aRoomSummary
import io.element.android.libraries.matrix.test.timeline.aMessageContent
import org.junit.Test

class ContactsRoomSummariesTest {
    @Test
    fun `not loaded and missing nonempty snapshot do not report an empty directory`() {
        assertThat(contactsRoomSummaries(emptyList(), RoomList.LoadingState.NotLoaded)).isInstanceOf(AsyncData.Loading::class.java)
        assertThat(contactsRoomSummaries(null, RoomList.LoadingState.Loaded(1))).isInstanceOf(AsyncData.Loading::class.java)
    }

    @Test
    fun `a genuinely loaded zero room account has an empty directory`() {
        val result = contactsRoomSummaries(null, RoomList.LoadingState.Loaded(0))
        assertThat(result.isSuccess()).isTrue()
        assertThat(result.dataOrNull()).isEmpty()
    }

    @Test
    fun `directory keeps relationship metadata without exposing even an authorized message or draft`() {
        val info = aRoomInfo(isDm = true, activeMembersCount = 2, joinedMembersCount = 2, invitedMembersCount = 0)
        val summary = aRoomSummary(
            info = info,
            latestEventValue = aRemoteLatestEvent(content = aMessageContent().copy(isContentProtectionAuthorized = true), timestamp = 1234L),
        )
        val room = contactsRoomSummaries(listOf(summary), RoomList.LoadingState.Loaded(1)).dataOrNull()!!.single()
        assertThat(room.roomId).isEqualTo(summary.roomId)
        assertThat(room.isDm).isTrue()
        assertThat(room.joinedMembersCount).isEqualTo(2L)
        assertThat(room.latestEvent).isEqualTo(LatestEvent.None)
        assertThat(room.latestEventTimestamp).isEqualTo(1234L)
        assertThat(room.timestamp).isNull()
        assertThat(room.draftPreview).isNull()
    }

    @Test
    fun `joined invited and knocked membership are preserved while left and banned rooms are excluded`() {
        val summaries = CurrentUserMembership.entries.map { membership ->
            aRoomSummary(roomId = RoomId("!${membership.name}:test"), currentUserMembership = membership)
        }
        val result = contactsRoomSummaries(summaries, RoomList.LoadingState.Loaded(5)).dataOrNull()!!
        assertThat(result.map { it.displayType }).containsExactly(
            RoomSummaryDisplayType.ROOM, RoomSummaryDisplayType.INVITE, RoomSummaryDisplayType.KNOCKED,
        )
    }

    @Test
    fun `raw all rooms still applies deleted identity exclusion and preserves the unrelated group`() {
        val peer = UserId("@deleted:test")
        val hero = MatrixUser(peer, displayName = "Deleted peer", avatarUrl = null)
        val deletedDm = aRoomSummary(info = aRoomInfo(
            id = RoomId("!dm:test"), isDm = true, activeMembersCount = 2, joinedMembersCount = 2, invitedMembersCount = 0, heroes = listOf(hero),
        ))
        val group = aRoomSummary(info = aRoomInfo(id = RoomId("!group:test"), heroes = listOf(hero)))
        val metadata = contactsRoomSummaries(listOf(deletedDm, group), RoomList.LoadingState.Loaded(2)).dataOrNull()!!
        val result = buildContactsDirectoryData(
            summaries = metadata,
            displayName = { it.name.orEmpty() },
            sortKey = { it },
            isDeletedMatrixUserId = { it == peer.value },
        )
        assertThat(result.friendSections).isEmpty()
        assertThat(result.groups.map { it.roomId }).containsExactly(group.roomId)
    }
}
