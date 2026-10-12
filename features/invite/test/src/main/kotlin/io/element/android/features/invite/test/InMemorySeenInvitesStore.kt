/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.invite.test

import io.element.android.features.invite.api.InviteNotification
import io.element.android.features.invite.api.SeenInvitesStore
import io.element.android.libraries.matrix.api.core.RoomId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class InMemorySeenInvitesStore(
    initialRoomIds: Set<RoomId> = emptySet(),
) : SeenInvitesStore {
    private val roomIds = MutableStateFlow(initialRoomIds)

    override fun seenRoomIds(): Flow<Set<RoomId>> = roomIds

    override suspend fun markAsSeen(roomId: RoomId) {
        roomIds.value += roomId
    }

    override suspend fun markAsUnSeen(roomId: RoomId) {
        roomIds.value -= roomId
    }

    private val records = MutableStateFlow<List<InviteNotification>>(emptyList())
    override fun notifications(): Flow<List<InviteNotification>> = records
    override suspend fun observeNotifications(notifications: List<InviteNotification>, completeSnapshot: Boolean) {
        records.value = (records.value + notifications).distinctBy { it.roomId }
    }
    override suspend fun recordOutcome(roomId: RoomId, outcome: InviteNotification.Outcome, seen: Boolean, expectedSequence: Long?) {
        if (expectedSequence != null && records.value.firstOrNull { it.roomId == roomId }?.sequence != expectedSequence) return
        records.value = records.value.map { if (it.roomId == roomId) it.copy(outcome = outcome, seen = seen) else it }
        if (seen) roomIds.value += roomId else roomIds.value -= roomId
    }
    override suspend fun markNotificationSeen(roomId: RoomId) {
        records.value = records.value.map { if (it.roomId == roomId) it.copy(seen = true) else it }
        markAsSeen(roomId)
    }
    override suspend fun clear() {
        roomIds.value = emptySet()
        records.value = emptyList()
    }
}
