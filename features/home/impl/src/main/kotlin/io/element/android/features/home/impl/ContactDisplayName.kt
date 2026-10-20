package io.element.android.features.home.impl

import io.element.android.features.home.impl.model.RoomListRoomSummary
import io.element.android.features.home.impl.model.RoomSummaryDisplayType
import io.element.android.features.userprofile.api.FriendPrivateProfile
import io.element.android.features.userprofile.api.privateDirectDisplayName
import io.element.android.libraries.matrix.api.core.UserId

internal fun contactDisplayName(room: RoomListRoomSummary, profiles: Map<UserId, FriendPrivateProfile>): String {
    if (!room.isDm) return room.name ?: room.roomId.value
    // Incoming invite summaries can have an inviter but no heroes until the invite is accepted.
    if (room.displayType == RoomSummaryDisplayType.INVITE) room.inviteSender?.let {
        return profiles.privateDirectDisplayName(it.userId, it.displayName.ifBlank { room.name.orEmpty() })
    }
    val hero = room.heroes.firstOrNull()
    val name = hero?.name?.takeIf { it.isNotBlank() } ?: room.name?.takeUnless { it == room.roomId.value }
    return hero?.id?.let { profiles.privateDirectDisplayName(UserId(it), name) }
        ?: name ?: room.inviteSender?.userId?.value ?: room.roomId.value
}
