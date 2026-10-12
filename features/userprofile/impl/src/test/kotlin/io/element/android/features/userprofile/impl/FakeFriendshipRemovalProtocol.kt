package io.element.android.features.userprofile.impl

import io.element.android.features.userprofile.impl.root.FriendshipRemovalProtocol
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.test.A_ROOM_ID
import io.element.android.libraries.matrix.test.A_USER_ID_2
import io.element.android.security.dataprotection.api.*

internal class FakeFriendshipRemovalProtocol(room: RoomId = A_ROOM_ID) : FriendshipRemovalProtocol {
    var snapshot: FriendshipRemovalSnapshot? = FriendshipRemovalSnapshot("a".repeat(64), "b".repeat(64), "test-installation",
        A_USER_ID_2.value, room.value, "c".repeat(64), FriendshipRemovalStatus.ACCEPTED)
    var begins = 0
    var completes = 0
    var completed = false
    var failComplete = false
    override suspend fun preview(peer: UserId) = snapshot
    override suspend fun begin(snapshot: FriendshipRemovalSnapshot, rooms: Set<String>): FriendshipRemovalIntent {
        begins++
        return FriendshipRemovalIntent("friend-" + "d".repeat(64), snapshot, rooms, completed)
    }
    override suspend fun complete(intent: FriendshipRemovalIntent) {
        completes++
        check(!failComplete) { "Authority pending" }
        completed = true
    }
}
