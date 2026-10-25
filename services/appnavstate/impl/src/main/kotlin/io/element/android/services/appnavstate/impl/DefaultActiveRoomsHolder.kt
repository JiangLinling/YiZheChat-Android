/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.services.appnavstate.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.services.appnavstate.api.ActiveRoomsHolder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DefaultActiveRoomsHolder : ActiveRoomsHolder {
    private val rooms = MutableStateFlow<List<JoinedRoom>>(emptyList())

    override fun addRoom(room: JoinedRoom) {
        rooms.update { held ->
            if (held.any { it === room }) held else held + room
        }
    }

    override fun getActiveRoom(sessionId: SessionId): JoinedRoom? {
        return rooms.value.lastOrNull { it.sessionId == sessionId }
    }

    override fun getActiveRoomMatching(sessionId: SessionId, roomId: RoomId): JoinedRoom? {
        return rooms.value.lastOrNull { it.sessionId == sessionId && it.roomId == roomId }
    }

    override fun observeActiveRoomMatching(sessionId: SessionId, roomId: RoomId): Flow<JoinedRoom?> =
        rooms.map { held -> held.lastOrNull { it.sessionId == sessionId && it.roomId == roomId } }
            .distinctUntilChanged { previous, current -> previous === current }

    override fun removeRoom(sessionId: SessionId, roomId: RoomId) {
        rooms.update { held -> held.filterNot { it.sessionId == sessionId && it.roomId == roomId } }
    }

    override fun removeRoom(room: JoinedRoom) {
        rooms.update { held -> held.filterNot { it === room } }
    }

    override fun clear(sessionId: SessionId) {
        val activeRooms = rooms.getAndUpdate { held -> held.filterNot { it.sessionId == sessionId } }
            .filter { it.sessionId == sessionId }
        for (room in activeRooms) {
            // Destroy the room to reset the live timelines
            room.destroy()
        }
    }
}
