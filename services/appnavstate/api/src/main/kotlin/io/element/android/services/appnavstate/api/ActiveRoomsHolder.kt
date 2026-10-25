/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.services.appnavstate.api

import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.api.room.JoinedRoom
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Holds the active rooms for a given session so they can be reused instead of instantiating new ones.
 */
interface ActiveRoomsHolder {
    /**
     * Adds a new held room for the given sessionId.
     */
    fun addRoom(room: JoinedRoom)

    /**
     * Returns the last room added for the given [sessionId] or null if no room was added.
     */
    fun getActiveRoom(sessionId: SessionId): JoinedRoom?

    /**
     * Returns an active room associated to the given [sessionId], with the given [roomId], or null if none match.
     */
    fun getActiveRoomMatching(sessionId: SessionId, roomId: RoomId): JoinedRoom?

    /** Only changes of this exact held room are emitted; a room ID is not client provenance. */
    fun observeActiveRoomMatching(sessionId: SessionId, roomId: RoomId): Flow<JoinedRoom?> =
        flowOf(getActiveRoomMatching(sessionId, roomId))

    /**
     * Removes any room matching the provided [sessionId] and [roomId].
     */
    fun removeRoom(sessionId: SessionId, roomId: RoomId)

    /** A retiring owner must not remove a replacement with the same session and room ID. */
    fun removeRoom(room: JoinedRoom) = removeRoom(room.sessionId, room.roomId)

    /**
     * Clears all the rooms for the given sessionId.
     */
    fun clear(sessionId: SessionId)
}
