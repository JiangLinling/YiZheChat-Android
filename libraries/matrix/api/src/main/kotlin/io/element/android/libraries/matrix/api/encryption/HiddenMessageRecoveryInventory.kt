/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.matrix.api.encryption

import io.element.android.libraries.matrix.api.core.RoomId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Process-memory inventory of encrypted history events that may be recoverable from key backup. */
interface HiddenMessageRecoveryInventory {
    val candidateCount: StateFlow<Int>
    /** Per-room counts only. Event ids stay private to the producer and are never exposed to UI. */
    val candidateCountByRoom: StateFlow<Map<RoomId, Int>>
    /** Aggregate, content-free progress for explaining why eligible history is still hidden. */
    val recoveryStatus: StateFlow<HistoryRecoveryStatus>

    fun replaceRoomCandidates(roomId: RoomId, eventIds: Set<String>)

    fun clearRoom(roomId: RoomId)

    /**
     * Content-free identifiers used only for an explicit local cleanup transaction.
     * Callers must not render, log, or persist this snapshot outside the encrypted
     * message-reference store.
     */
    fun snapshotCandidatesForLocalCleanup(): Map<RoomId, Set<String>> = emptyMap()

    fun updateRecoveryStatus(status: HistoryRecoveryStatus)

    data object Noop : HiddenMessageRecoveryInventory {
        override val candidateCount: StateFlow<Int> = MutableStateFlow(0)
        override val candidateCountByRoom: StateFlow<Map<RoomId, Int>> = MutableStateFlow(emptyMap())
        override val recoveryStatus: StateFlow<HistoryRecoveryStatus> = MutableStateFlow(HistoryRecoveryStatus.IDLE)
        override fun replaceRoomCandidates(roomId: RoomId, eventIds: Set<String>) = Unit
        override fun clearRoom(roomId: RoomId) = Unit
        override fun snapshotCandidatesForLocalCleanup(): Map<RoomId, Set<String>> = emptyMap()
        override fun updateRecoveryStatus(status: HistoryRecoveryStatus) = Unit
    }
}

/**
 * Process-memory status only. It must never carry room names, event ids, message bodies, or key material.
 */
enum class HistoryRecoveryStatus {
    IDLE,
    WAITING_FOR_MATRIX_RECOVERY,
    WAITING_FOR_MATRIX_VERIFICATION,
    WAITING_FOR_DEVICE_TRUST,
    WAITING_FOR_NETWORK,
    AUTHORIZING_DEVICE,
    WAITING_FOR_OLD_DEVICE,
    WAITING_FOR_ADMIN_APPROVAL,
    RETRYING,
    REQUEST_EXPIRED,
    KMS_UNAVAILABLE,
    STANDARD_AUTHORIZED,
    /** The user completed an explicit identity reset; old unverifiable history cannot be authenticated again. */
    IDENTITY_RESET_UNRECOVERABLE,
    FAILED_CLOSED,
}
