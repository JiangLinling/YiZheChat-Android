/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.userprofile.impl

import app.cash.turbine.ReceiveTurbine
import com.google.common.truth.Truth.assertThat
import io.element.android.features.enterprise.test.FakeSessionEnterpriseService
import io.element.android.features.invitepeople.test.FakeStartDMAction
import io.element.android.features.userprofile.api.UserProfileEvents
import io.element.android.features.userprofile.api.UserProfileState
import io.element.android.features.userprofile.impl.root.UserProfilePresenter
import io.element.android.features.userprofile.test.FakeFriendProfileRepository
import io.element.android.libraries.architecture.AsyncAction
import io.element.android.libraries.matrix.api.room.CurrentUserMembership
import io.element.android.libraries.matrix.api.room.RoomMember
import io.element.android.libraries.matrix.api.room.RoomMembershipState
import io.element.android.libraries.matrix.test.A_ROOM_ID
import io.element.android.libraries.matrix.test.A_USER_ID_2
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.libraries.matrix.test.room.FakeBaseRoom
import io.element.android.libraries.matrix.test.room.aRoomInfo
import io.element.android.tests.testutils.WarmUpRule
import io.element.android.tests.testutils.test
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.util.Optional

class UserProfileRemoveFriendPresenterTest {
    @get:Rule val warmUpRule = WarmUpRule()
    private val accepted = aRoomInfo(isDm = true, joinedMembersCount = 2, invitedMembersCount = 0)
    private fun presenter(room: FakeBaseRoom, flow: MutableStateFlow<Optional<io.element.android.libraries.matrix.api.room.RoomInfo>>) =
        UserProfilePresenter(A_USER_ID_2, FakeMatrixClient(getRemoteJoinedRoomIdsResult = { Result.success(setOf(A_ROOM_ID)) }).apply {
            givenFindDmResult(Result.success(A_ROOM_ID))
            givenGetRoomResult(A_ROOM_ID, room)
            getRoomInfoFlowLambda = { flow }
        }, FakeStartDMAction(), FakeSessionEnterpriseService(isElementCallAvailableResult = { false }), FakeFriendProfileRepository(), FakeFriendshipRemovalProtocol())

    @Test
    fun `CP accepted friend retains recovery action when Matrix peer leaves`() = runTest {
        val left = accepted.copy(currentUserMembership = CurrentUserMembership.LEFT, joinedMembersCount = 1)
        val flow = MutableStateFlow(Optional.of(left))
        val room = FakeBaseRoom(initialRoomInfo = left)
        presenter(room, flow).test {
            val state = awaitWhere { it.canRemoveFriend }
            assertThat(state.canCall).isFalse()
        }
    }

    @Test
    fun `cancel never leaves and confirmed failure retries original target once`() = runTest {
        val flow = MutableStateFlow(Optional.of(accepted))
        var attempts = 0
        lateinit var room: FakeBaseRoom
        val member = RoomMember(A_USER_ID_2, null, null, RoomMembershipState.JOIN, false, 0, false, RoomMember.Role.User, null, false)
        room = FakeBaseRoom(initialRoomInfo = accepted, getDirectRoomMemberResult = { member }, leaveRoomLambda = {
            attempts++
            if (attempts == 1) Result.failure(IllegalStateException("Offline")) else {
                val left = accepted.copy(currentUserMembership = CurrentUserMembership.LEFT)
                room.givenRoomInfo(left); flow.value = Optional.of(left)
                Result.success(Unit)
            }
        })
        presenter(room, flow).test {
            awaitWhere { it.canRemoveFriend }.eventSink(UserProfileEvents.RemoveFriend())
            awaitWhere { it.removeFriendAction is AsyncAction.Confirming }.eventSink(UserProfileEvents.ClearRemoveFriendState)
            val cancelled = awaitWhere { it.removeFriendAction is AsyncAction.Uninitialized }
            assertThat(attempts).isEqualTo(0)
            cancelled.eventSink(UserProfileEvents.RemoveFriend())
            awaitWhere { it.removeFriendAction is AsyncAction.Confirming }.eventSink(UserProfileEvents.RemoveFriend(false))
            val failed = awaitWhere { it.removeFriendAction is AsyncAction.Failure }
            assertThat(failed.canRemoveFriend).isTrue()
            assertThat(failed.removeFriendDiagnostic).isEqualTo("NATIVE_LEAVE:STATE_REJECTED")
            failed.eventSink(UserProfileEvents.RemoveFriend(false))
            failed.eventSink(UserProfileEvents.RemoveFriend(false))
            val done = awaitWhere { it.removeFriendAction is AsyncAction.Success }
            assertThat(done.canRemoveFriend).isFalse()
            assertThat(done.removeFriendDiagnostic).isNull()
            assertThat(done.canCall).isFalse()
            assertThat(attempts).isEqualTo(2)
        }
    }

    private suspend fun ReceiveTurbine<UserProfileState>.awaitWhere(predicate: (UserProfileState) -> Boolean): UserProfileState {
        while (true) {
            val state = awaitItem()
            if (predicate(state)) return state
        }
    }
}
