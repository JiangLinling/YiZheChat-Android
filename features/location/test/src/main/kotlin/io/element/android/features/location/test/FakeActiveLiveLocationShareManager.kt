/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.location.test

import io.element.android.features.location.api.live.ActiveLiveLocationShareManager
import io.element.android.features.location.api.live.PausedLiveLocationShare
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.tests.testutils.lambda.lambdaError
import io.element.android.tests.testutils.simulateLongTask
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.room.location.BeaconId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Duration

class FakeActiveLiveLocationShareManager(
    val setupLambda: () -> Unit = { lambdaError() },
    val startShareLambda: (roomId: RoomId, duration: Duration) -> Result<Unit> = { _, _ -> lambdaError() },
    val stopShareLambda: (roomId: RoomId) -> Result<Unit> = { _ -> lambdaError() },
    val stopShareWithTargetLambda: (roomId: RoomId, protectedAggregateId: String?) -> Result<Unit> = { roomId, _ -> stopShareLambda(roomId) },
    val resumeShareLambda: (roomId: RoomId) -> Result<Unit> = { _ -> lambdaError() },
) : ActiveLiveLocationShareManager {
    private val _sharingRoomIds = MutableStateFlow(emptySet<RoomId>())
    override val sharingRoomIds: StateFlow<Set<RoomId>> = _sharingRoomIds
    private val _pausedShares = MutableStateFlow(emptyMap<RoomId, PausedLiveLocationShare>())
    override val pausedShares: StateFlow<Map<RoomId, PausedLiveLocationShare>> = _pausedShares

    private val pausedBeaconIds = mutableMapOf<RoomId, BeaconId>()

    override fun pausedShare(roomId: RoomId, beaconId: BeaconId): Flow<PausedLiveLocationShare?> = pausedShares.map {
        it[roomId].takeIf { pausedBeaconIds[roomId] == beaconId }
    }

    override suspend fun setup() {
        setupLambda()
    }

    override suspend fun startShare(roomId: RoomId, duration: Duration): Result<Unit> = simulateLongTask {
        startShareLambda(roomId, duration).onSuccess {
            _sharingRoomIds.update {
                it + roomId
            }
        }
    }

    override suspend fun resumeShare(roomId: RoomId): Result<Unit> = simulateLongTask {
        resumeShareLambda(roomId).onSuccess {
            _pausedShares.update { it - roomId }
            _sharingRoomIds.update { it + roomId }
        }
    }

    fun givenPausedShare(roomId: RoomId, state: PausedLiveLocationShare, beaconId: BeaconId = EventId("\$yizhe-live-original")) {
        pausedBeaconIds[roomId] = beaconId
        _pausedShares.update { it + (roomId to state) }
    }

    override suspend fun stopShare(roomId: RoomId, protectedAggregateId: String?): Result<Unit> = simulateLongTask {
        stopShareWithTargetLambda(roomId, protectedAggregateId).onSuccess {
            _sharingRoomIds.update {
                it - roomId
            }
            _pausedShares.update { it - roomId }
        }
    }
}
