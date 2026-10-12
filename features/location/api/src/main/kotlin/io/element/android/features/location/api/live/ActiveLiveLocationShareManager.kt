/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.location.api.live

import io.element.android.libraries.core.coroutine.mapState
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.room.location.BeaconId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

interface ActiveLiveLocationShareManager {
    /** All rooms currently sharing live location on this device. */
    val sharingRoomIds: StateFlow<Set<RoomId>>

    /** Shares restored after process death. They stay paused until the user explicitly resumes. */
    val pausedShares: StateFlow<Map<RoomId, PausedLiveLocationShare>>

    /** Local pause projection for this exact original beacon; observing it never resumes capture. */
    fun pausedShare(roomId: RoomId, beaconId: BeaconId): Flow<PausedLiveLocationShare?>

    /**
     * Initializes the manager.
     * This will restart or stop current location sharing and set the listener on the SDK
     * and the session manager.
     */
    suspend fun setup()

    /**
     * Starts live location sharing in the given room.
     * Calls room.startLiveLocationShare() on the SDK, registers the share,
     * and starts the foreground GPS service if not already running.
     */
    suspend fun startShare(roomId: RoomId, duration: Duration): Result<Unit>

    /** Resumes local GPS delivery for the original remote share and original deadline. */
    suspend fun resumeShare(roomId: RoomId): Result<Unit>

    /**
     * Stops live location sharing in the given room.
     * Calls room.stopLiveLocationShare() on the SDK, removes the share,
     * and stops the foreground service if no shares remain.
     */
    suspend fun stopShare(roomId: RoomId, protectedAggregateId: String? = null): Result<Unit>
}

enum class PausedLiveLocationShare {
    CHECKING,
    RECOVERABLE,
    UNRECOVERABLE,
}

fun ActiveLiveLocationShareManager.isCurrentlySharing(roomId: RoomId): StateFlow<Boolean> {
    return sharingRoomIds.mapState { roomId in it }
}
