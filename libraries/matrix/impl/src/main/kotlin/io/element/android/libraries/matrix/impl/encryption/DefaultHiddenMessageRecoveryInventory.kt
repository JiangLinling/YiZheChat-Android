/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.matrix.impl.encryption

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.encryption.HiddenMessageRecoveryInventory
import io.element.android.libraries.matrix.api.encryption.HistoryRecoveryStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class)
@Inject
class DefaultHiddenMessageRecoveryInventory : HiddenMessageRecoveryInventory {
    private val candidatesByRoom = mutableMapOf<RoomId, Set<String>>()
    private val mutableCandidateCount = MutableStateFlow(0)
    private val mutableCandidateCountByRoom = MutableStateFlow<Map<RoomId, Int>>(emptyMap())
    private val mutableRecoveryStatus = MutableStateFlow(HistoryRecoveryStatus.IDLE)

    override val candidateCount: StateFlow<Int> = mutableCandidateCount.asStateFlow()
    override val candidateCountByRoom: StateFlow<Map<RoomId, Int>> = mutableCandidateCountByRoom.asStateFlow()
    override val recoveryStatus: StateFlow<HistoryRecoveryStatus> = mutableRecoveryStatus.asStateFlow()

    override fun replaceRoomCandidates(roomId: RoomId, eventIds: Set<String>) = synchronized(candidatesByRoom) {
        if (eventIds.isEmpty()) {
            candidatesByRoom.remove(roomId)
        } else {
            candidatesByRoom[roomId] = eventIds
        }
        publishCounts()
    }

    override fun clearRoom(roomId: RoomId) = synchronized(candidatesByRoom) {
        candidatesByRoom.remove(roomId)
        publishCounts()
    }

    override fun snapshotCandidatesForLocalCleanup(): Map<RoomId, Set<String>> = synchronized(candidatesByRoom) {
        candidatesByRoom.mapValues { (_, eventIds) -> eventIds.toSet() }
    }

    override fun updateRecoveryStatus(status: HistoryRecoveryStatus) {
        mutableRecoveryStatus.value = status
    }

    private fun publishCounts() {
        val counts = candidatesByRoom.mapValues { (_, eventIds) -> eventIds.size }
        mutableCandidateCountByRoom.value = counts
        mutableCandidateCount.value = counts.values.sum()
    }
}
