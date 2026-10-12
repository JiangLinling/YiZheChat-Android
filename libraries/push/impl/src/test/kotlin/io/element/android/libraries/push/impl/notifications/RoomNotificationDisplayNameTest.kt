/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.impl.notifications

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.RoomId
import org.junit.Test

private val A_NEW_DM_ROOM_ID = RoomId("!CqaYJtuGgzaMKJGlHx:yizhesy.cn")
private const val A_SENDER_DISPLAY_NAME = "理然"
private const val A_GENERIC_DISPLAY_NAME = "新消息"

class RoomNotificationDisplayNameTest {
    @Test
    fun `meaningful room name is preserved`() {
        val result = resolveNotificationRoomDisplayName(
            roomId = A_NEW_DM_ROOM_ID,
            roomName = "金吉莉",
            senderDisplayName = A_SENDER_DISPLAY_NAME,
            isDm = true,
            genericDisplayName = A_GENERIC_DISPLAY_NAME,
        )

        assertThat(result).isEqualTo("金吉莉")
    }

    @Test
    fun `generated room name in a direct message falls back to sender display name`() {
        val result = resolveNotificationRoomDisplayName(
            roomId = A_NEW_DM_ROOM_ID,
            roomName = "Room name (!CqaYJtu…)",
            senderDisplayName = A_SENDER_DISPLAY_NAME,
            isDm = true,
            genericDisplayName = A_GENERIC_DISPLAY_NAME,
        )

        assertThat(result).isEqualTo(A_SENDER_DISPLAY_NAME)
    }

    @Test
    fun `raw room id in a direct message falls back to sender display name`() {
        val result = resolveNotificationRoomDisplayName(
            roomId = A_NEW_DM_ROOM_ID,
            roomName = A_NEW_DM_ROOM_ID.value,
            senderDisplayName = A_SENDER_DISPLAY_NAME,
            isDm = true,
            genericDisplayName = A_GENERIC_DISPLAY_NAME,
        )

        assertThat(result).isEqualTo(A_SENDER_DISPLAY_NAME)
    }

    @Test
    fun `generated room name in a group does not expose room id or sender`() {
        val result = resolveNotificationRoomDisplayName(
            roomId = A_NEW_DM_ROOM_ID,
            roomName = "Room name (!CqaYJtu…)",
            senderDisplayName = A_SENDER_DISPLAY_NAME,
            isDm = false,
            genericDisplayName = A_GENERIC_DISPLAY_NAME,
        )

        assertThat(result).isEqualTo(A_GENERIC_DISPLAY_NAME)
    }

    @Test
    fun `privacy-hidden identity uses generic display name`() {
        val result = resolveNotificationRoomDisplayName(
            roomId = A_NEW_DM_ROOM_ID,
            roomName = null,
            senderDisplayName = null,
            isDm = true,
            genericDisplayName = A_GENERIC_DISPLAY_NAME,
        )

        assertThat(result).isEqualTo(A_GENERIC_DISPLAY_NAME)
    }
}
