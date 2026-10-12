/*
 * Copyright (c) 2026 Element Creations Ltd.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
@file:OptIn(ExperimentalTestApi::class)

package io.element.android.features.roomdetails.impl.members

import com.google.common.truth.Truth.assertThat
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.element.android.features.roomdetails.impl.R
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.room.RoomMembershipState
import io.element.android.tests.testutils.EventsRecorder
import io.element.android.tests.testutils.ensureCalledOnceWithParam
import kotlinx.collections.immutable.persistentListOf
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomMemberListViewTest {
    private val member = aRoomMember(displayName = "Alice")
    private val members = AsyncData.Success(RoomMembers(
        invited = persistentListOf(),
        joined = persistentListOf(RoomMemberWithIdentityState(member, null)),
        banned = persistentListOf(),
    ))

    @Test
    fun `member row opens profile and ordinary members see no management`() = runAndroidComposeUiTest<ComponentActivity> {
        val events = EventsRecorder<RoomMemberListEvent>(expectEvents = false)
        ensureCalledOnceWithParam(member.userId) { callback ->
            setContent {
                RoomMemberListView(
                    state = aRoomMemberListState(roomMembers = members, eventSink = events),
                    navigator = object : RoomMemberListNavigator {
                        override fun openRoomMemberDetails(roomMemberId: UserId) = callback(roomMemberId)
                    },
                )
            }
            onNodeWithText("Alice").performClick()
        }
        onAllNodesWithContentDescription(activity!!.getString(R.string.screen_room_details_manage_member)).assertCountEquals(0)
        events.assertEmpty()
    }

    @Test
    fun `explicit management button uses existing permission checked moderation flow`() = runAndroidComposeUiTest<ComponentActivity> {
        val events = EventsRecorder<RoomMemberListEvent>()
        setContent {
            RoomMemberListView(
                state = aRoomMemberListState(
                    roomMembers = members,
                    moderationState = aRoomMemberModerationState(canKick = true),
                    eventSink = events,
                ).copy(ownPowerLevel = 100),
                navigator = object : RoomMemberListNavigator {},
            )
        }
        onNodeWithContentDescription(activity!!.getString(R.string.screen_room_details_manage_member)).performClick()
        events.assertSingle(RoomMemberListEvent.RoomMemberSelected(member))
    }
    @Test
    fun `same power and unknown power hide management even when global permission exists`() = runAndroidComposeUiTest<ComponentActivity> {
        setContent {
            RoomMemberListView(
                state = aRoomMemberListState(
                    roomMembers = members,
                    moderationState = aRoomMemberModerationState(canKick = true, canBan = true),
                ).copy(ownPowerLevel = member.powerLevel),
                navigator = object : RoomMemberListNavigator {},
            )
        }
        onAllNodesWithContentDescription(activity!!.getString(R.string.screen_room_details_manage_member)).assertCountEquals(0)
        assertThat(aRoomMemberListState(
            moderationState = aRoomMemberModerationState(canKick = true, canBan = true),
        ).canManage(member)).isFalse()
    }

    @Test
    fun `unban needs both permissions and leave membership cannot be kicked`() {
        val banOnly = aRoomMemberListState(moderationState = aRoomMemberModerationState(canBan = true)).copy(ownPowerLevel = 100)
        val kickOnly = aRoomMemberListState(moderationState = aRoomMemberModerationState(canKick = true)).copy(ownPowerLevel = 100)
        val both = aRoomMemberListState(moderationState = aRoomMemberModerationState(canBan = true, canKick = true)).copy(ownPowerLevel = 100)
        val banned = member.copy(membership = RoomMembershipState.BAN)
        val left = member.copy(membership = RoomMembershipState.LEAVE)
        assertThat(banOnly.canManage(banned)).isFalse()
        assertThat(both.canManage(banned)).isTrue()
        assertThat(kickOnly.canManage(left)).isFalse()
        assertThat(banOnly.canManage(left)).isTrue()
    }

    @Test
    fun `higher power targets are never manageable`() {
        val state = aRoomMemberListState(moderationState = aRoomMemberModerationState(canBan = true, canKick = true))
            .copy(ownPowerLevel = 50)
        assertThat(state.canManage(member.copy(powerLevel = 100))).isFalse()
    }

}
