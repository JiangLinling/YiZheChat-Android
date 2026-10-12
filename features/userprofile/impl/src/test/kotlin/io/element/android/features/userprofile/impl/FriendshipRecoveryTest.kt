package io.element.android.features.userprofile.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.features.userprofile.impl.root.RemoveFriendAction
import io.element.android.libraries.matrix.api.room.CurrentUserMembership
import io.element.android.libraries.matrix.test.*
import io.element.android.libraries.matrix.test.room.FakeBaseRoom
import io.element.android.libraries.matrix.test.room.aRoomInfo
import kotlinx.coroutines.test.runTest
import org.junit.Test

class FriendshipRecoveryTest {
    private val joined = aRoomInfo(isDm = true, joinedMembersCount = 2, invitedMembersCount = 0)
    private val left = joined.copy(currentUserMembership = CurrentUserMembership.LEFT, isDm = false, joinedMembersCount = 1)

    @Test fun `stale joined wrapper cannot hide actual SDK left and already left never dispatches`() = runTest {
        var leaves = 0
        val wrapper = FakeBaseRoom(initialRoomInfo = joined, leaveRoomLambda = { leaves++; Result.success(Unit) })
        val client = FakeMatrixClient(getRemoteJoinedRoomIdsResult = { Result.success(emptySet()) }).apply {
            givenGetRoomResult(A_ROOM_ID, wrapper)
            getCurrentRoomInfoLambda = { Result.success(left) }
        }
        val authority = FakeFriendshipRemovalProtocol()
        val action = RemoveFriendAction(client, A_USER_ID_2, authority)
        action.prepare(); action.execute()
        assertThat(leaves).isEqualTo(0)
        assertThat(wrapper.info().currentUserMembership).isEqualTo(CurrentUserMembership.JOINED)
        assertThat(authority.begins).isEqualTo(1)
        assertThat(authority.completes).isEqualTo(1)
    }

    @Test fun `LC11 response failure retries original intent without leaving again`() = runTest {
        val client = FakeMatrixClient(getRemoteJoinedRoomIdsResult = { Result.success(emptySet()) }).apply {
            getCurrentRoomInfoLambda = { Result.success(left) }
        }
        val authority = FakeFriendshipRemovalProtocol().apply { failComplete = true }
        val action = RemoveFriendAction(client, A_USER_ID_2, authority)
        action.prepare()
        assertThat(runCatching { action.execute() }.isFailure).isTrue()
        authority.failComplete = false
        action.execute()
        assertThat(authority.begins).isEqualTo(1)
        assertThat(authority.completes).isEqualTo(2)
    }

    @Test fun `completed intent cannot leave newly rejoined room`() = runTest {
        val client = FakeMatrixClient(getRemoteJoinedRoomIdsResult = { Result.success(emptySet()) }).apply {
            getCurrentRoomInfoLambda = { Result.success(joined) }
        }
        val authority = FakeFriendshipRemovalProtocol().apply { completed = true }
        val action = RemoveFriendAction(client, A_USER_ID_2, authority)
        action.prepare()
        assertThat(runCatching { action.execute() }.isFailure).isTrue()
        assertThat(authority.completes).isEqualTo(0)
    }

    @Test fun `missing friend authority fails before creating operation`() = runTest {
        val authority = FakeFriendshipRemovalProtocol().apply { snapshot = null }
        val action = RemoveFriendAction(FakeMatrixClient(), A_USER_ID_2, authority)
        assertThat(runCatching { action.prepare() }.isFailure).isTrue()
        assertThat(authority.begins).isEqualTo(0)
    }
}
