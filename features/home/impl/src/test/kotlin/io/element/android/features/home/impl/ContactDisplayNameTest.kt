package io.element.android.features.home.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.features.home.impl.model.RoomSummaryDisplayType
import io.element.android.features.home.impl.model.aRoomListRoomSummary
import io.element.android.features.userprofile.api.FriendPrivateProfile
import io.element.android.libraries.designsystem.components.avatar.AvatarData
import io.element.android.libraries.designsystem.components.avatar.AvatarSize
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.ui.model.InviteSender
import kotlinx.collections.immutable.persistentListOf
import org.junit.Test

class ContactDisplayNameTest {
    @Test fun `incoming friend without heroes uses inviter and private remark`() {
        val peer = UserId("@peer:test")
        val room = aRoomListRoomSummary(isDm = true, displayType = RoomSummaryDisplayType.INVITE).copy(
            name = "Peer", heroes = persistentListOf(),
            inviteSender = InviteSender(peer, "Peer", AvatarData(peer.value, "Peer", size = AvatarSize.InviteSender), null))
        assertThat(contactDisplayName(room, emptyMap())).isEqualTo("Peer")
        assertThat(contactDisplayName(room, mapOf(peer to FriendPrivateProfile(peer, remarkName = "Private name")))).isEqualTo("Private name")
        assertThat(contactDisplayName(room.copy(inviteSender = null), emptyMap())).isEqualTo("Peer")
    }
}
