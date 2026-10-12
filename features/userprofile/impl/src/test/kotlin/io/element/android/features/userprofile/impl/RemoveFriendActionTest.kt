/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.userprofile.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.features.userprofile.impl.root.RemoveFriendAction
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.room.CurrentUserMembership
import io.element.android.libraries.matrix.api.room.RoomMember
import io.element.android.libraries.matrix.api.room.RoomMembershipState
import io.element.android.libraries.matrix.api.room.StartDMResult
import io.element.android.libraries.matrix.api.room.startDM
import io.element.android.libraries.matrix.test.A_USER_ID
import io.element.android.libraries.matrix.test.A_USER_ID_2
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.libraries.matrix.test.room.FakeBaseRoom
import io.element.android.libraries.matrix.test.room.aRoomInfo
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RemoveFriendActionTest {
    private val first = RoomId("!first:server")
    private val second = RoomId("!second:server")
    private fun member() = RoomMember(A_USER_ID_2, null, null, RoomMembershipState.JOIN, false, 0,
        false, RoomMember.Role.User, null, false)
    private fun info(id: RoomId) = aRoomInfo(id = id, isDm = true, joinedMembersCount = 2, invitedMembersCount = 0)

    @Test
    fun `prepare and cancel have no membership side effect`() = runTest {
        var leaves = 0
        val room = FakeBaseRoom(roomId = first, initialRoomInfo = info(first), getDirectRoomMemberResult = { member() },
            leaveRoomLambda = { leaves++; Result.success(Unit) })
        val client = FakeMatrixClient(getRemoteJoinedRoomIdsResult = { Result.success(setOf(first)) }).apply { givenGetRoomResult(first, room) }
        RemoveFriendAction(client, A_USER_ID_2, FakeFriendshipRemovalProtocol(first)).prepare(first)
        assertThat(leaves).isEqualTo(0)
        room.assertDestroyed()
    }

    @Test
    fun `partial failure retry keeps exact targets and skips completed leave`() = runTest {
        val later = RoomId("!later:server")
        val ids = linkedSetOf(first, second)
        val calls = mutableListOf<RoomId>()
        var failSecond = true
        lateinit var room1: FakeBaseRoom
        lateinit var room2: FakeBaseRoom
        room1 = FakeBaseRoom(roomId = first, initialRoomInfo = info(first), getDirectRoomMemberResult = { member() },
            leaveRoomLambda = {
                calls += first
                room1.givenRoomInfo(info(first).copy(currentUserMembership = CurrentUserMembership.LEFT))
                Result.success(Unit)
            })
        room2 = FakeBaseRoom(roomId = second, initialRoomInfo = info(second), getDirectRoomMemberResult = { member() },
            leaveRoomLambda = {
                calls += second
                if (failSecond) Result.failure(IllegalStateException("Offline")) else {
                    room2.givenRoomInfo(info(second).copy(currentUserMembership = CurrentUserMembership.LEFT))
                    Result.success(Unit)
                }
            })
        val client = FakeMatrixClient(getRemoteJoinedRoomIdsResult = { Result.success(ids) }).apply {
            givenGetRoomResult(first, room1); givenGetRoomResult(second, room2)
        }
        val action = RemoveFriendAction(client, A_USER_ID_2, FakeFriendshipRemovalProtocol(first))
        action.prepare(first)
        assertThat(runCatching { action.execute() }.isFailure).isTrue()
        ids += later
        failSecond = false
        action.execute()
        assertThat(calls).containsExactly(first, second, second).inOrder()
        assertThat(runCatching { action.execute() }.isSuccess).isTrue()
        assertThat(calls).hasSize(3)
    }

    @Test
    fun `unknown peer group and self are rejected without leave`() = runTest {
        for (roomInfo in listOf(info(first).copy(isSpace = true), info(first).copy(isDm = false))) {
            val room = FakeBaseRoom(roomId = first, initialRoomInfo = roomInfo)
            val client = FakeMatrixClient(getRemoteJoinedRoomIdsResult = { Result.success(setOf(first)) }).apply { givenGetRoomResult(first, room) }
            assertThat(runCatching { RemoveFriendAction(client, A_USER_ID_2, FakeFriendshipRemovalProtocol(first)).prepare(first) }.isFailure).isTrue()
        }
        val client = FakeMatrixClient()
        assertThat(runCatching { RemoveFriendAction(client, A_USER_ID, FakeFriendshipRemovalProtocol(first)).prepare(first) }.isFailure).isTrue()
    }

    @Test
    fun `leave success with stale sync remains failure rather than accepted friendship`() = runTest {
        val room = FakeBaseRoom(roomId = first, initialRoomInfo = info(first), getDirectRoomMemberResult = { member() },
            leaveRoomLambda = { Result.success(Unit) })
        val client = FakeMatrixClient(getRemoteJoinedRoomIdsResult = { Result.success(setOf(first)) }).apply { givenGetRoomResult(first, room) }
        val action = RemoveFriendAction(client, A_USER_ID_2, FakeFriendshipRemovalProtocol(first))
        action.prepare(first)
        val failure = runCatching { action.execute() }.exceptionOrNull() as io.element.android.security.dataprotection.api.RoomMembershipFailure
        assertThat(failure.diagnostic).isEqualTo("LOCAL_LEFT:STATE_REJECTED")
        assertThat(failure.cause?.message).contains("SDK membership pending")
    }

    @Test
    fun `old confirmation cannot leave a converted group or a new invitation in the same room`() = runTest {
        val changedStates = listOf(
            info(first).copy(joinedMembersCount = 3, activeMembersCount = 3),
            info(first).copy(currentUserMembership = CurrentUserMembership.INVITED),
        )
        for (changed in changedStates) {
            var leaves = 0
            val room = FakeBaseRoom(roomId = first, initialRoomInfo = info(first), getDirectRoomMemberResult = { member() },
                leaveRoomLambda = { leaves++; Result.success(Unit) })
            val client = FakeMatrixClient(getRemoteJoinedRoomIdsResult = { Result.success(setOf(first)) }).apply {
                givenGetRoomResult(first, room)
            }
            val action = RemoveFriendAction(client, A_USER_ID_2, FakeFriendshipRemovalProtocol(first))
            action.prepare(first)
            room.givenRoomInfo(changed)
            assertThat(runCatching { action.execute() }.isFailure).isTrue()
            assertThat(leaves).isEqualTo(0)
        }
    }

    @Test
    fun `newly joined remote room missing from sync prevents partial removal`() = runTest {
        var leaves = 0
        val room = FakeBaseRoom(roomId = first, initialRoomInfo = info(first), getDirectRoomMemberResult = { member() },
            leaveRoomLambda = { leaves++; Result.success(Unit) })
        val client = FakeMatrixClient(getRemoteJoinedRoomIdsResult = { Result.success(linkedSetOf(first, second)) }).apply {
            givenGetRoomResult(first, room)
        }
        val action = RemoveFriendAction(client, A_USER_ID_2, FakeFriendshipRemovalProtocol(first))
        assertThat(runCatching { action.prepare(first) }.isFailure).isTrue()
        assertThat(runCatching { action.execute() }.isFailure).isTrue()
        assertThat(leaves).isEqualTo(0)
    }

    @Test
    fun `removed private chat requires invitation and creates fresh DM only after confirmation`() = runTest {
        val room = FakeBaseRoom(roomId = first, initialRoomInfo = info(first).copy(currentUserMembership = CurrentUserMembership.LEFT))
        val client = FakeMatrixClient().apply {
            givenGetRoomResult(first, room)
            givenFindDmResult(Result.success(first))
            givenCreateDmResult(Result.success(second))
        }
        assertThat(client.startDM(A_USER_ID_2, false)).isEqualTo(StartDMResult.DmDoesNotExist)
        assertThat(client.startDM(A_USER_ID_2, true)).isEqualTo(StartDMResult.Success(second, isNew = true))
    }
}
