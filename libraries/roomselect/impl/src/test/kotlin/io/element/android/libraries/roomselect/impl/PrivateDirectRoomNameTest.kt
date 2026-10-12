/*
 * Copyright (c) 2026 YiZhe.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.roomselect.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.features.userprofile.api.FriendPrivateProfile
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.test.A_ROOM_NAME
import io.element.android.libraries.matrix.test.room.aRoomSummary
import io.element.android.libraries.matrix.ui.model.toSelectRoomInfo
import org.junit.Test

class PrivateDirectRoomNameTest {
    @Test
    fun `direct room uses private remark in room selector`() {
        val contactId = UserId("@contact:example.org")
        val roomInfo = aRoomSummary().toSelectRoomInfo()

        val result = roomInfo.withPrivateDirectRoomName(
            isDirectRoom = true,
            heroUserIds = listOf(contactId),
            privateProfiles = mapOf(contactId to FriendPrivateProfile(contactId, remarkName = "李博")),
        )

        assertThat(result.name).isEqualTo("李博")
        assertThat(result.getAvatarData(io.element.android.libraries.designsystem.components.avatar.AvatarSize.RoomSelectRoomListItem).name)
            .isEqualTo("李博")
    }

    @Test
    fun `group room never exposes a private contact remark as its room name`() {
        val contactId = UserId("@contact:example.org")
        val roomInfo = aRoomSummary().toSelectRoomInfo()

        val result = roomInfo.withPrivateDirectRoomName(
            isDirectRoom = false,
            heroUserIds = listOf(contactId),
            privateProfiles = mapOf(contactId to FriendPrivateProfile(contactId, remarkName = "李博")),
        )

        assertThat(result.name).isEqualTo(A_ROOM_NAME)
    }

    @Test
    fun `bootstrap onboarding alias falls back to the Matrix user id`() {
        val contactId = UserId("@contact:example.org")
        val bootstrapAlias = "onboarding-${"a".repeat(32)}"
        val roomInfo = aRoomSummary().toSelectRoomInfo().copy(name = bootstrapAlias)

        val result = roomInfo.withPrivateDirectRoomName(
            isDirectRoom = true,
            heroUserIds = listOf(contactId),
            heroDisplayNames = listOf(bootstrapAlias),
            privateProfiles = emptyMap(),
        )

        assertThat(result.name).isEqualTo(contactId.value)
    }
}
