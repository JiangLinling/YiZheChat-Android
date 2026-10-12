/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.features.preferences.impl.chatsecurity

import com.google.common.truth.Truth.assertThat
import io.element.android.features.messages.api.MessageReference
import io.element.android.features.messages.api.MessageReferenceState
import io.element.android.features.messages.api.MessageReferenceStore
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.encryption.HiddenMessageRecoveryInventory
import io.element.android.libraries.matrix.api.encryption.HistoryRecoveryStatus
import io.element.android.libraries.matrix.api.encryption.RecoveryState
import io.element.android.security.contentprotection.api.ContentProtectionException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ChatSecurityRecoveryStateTest {
    @Test
    fun `signed identity reset is terminal even while backup metadata is unavailable`() {
        assertThat(
            historyRecoveryState(
                deviceSecurityState = PublicDeviceSecurityState.PROCESSING,
                hasServerBackup = null,
                recoveryState = RecoveryState.DISABLED,
                candidateCount = 4,
                recoveryStatus = HistoryRecoveryStatus.IDENTITY_RESET_UNRECOVERABLE,
            )
        ).isEqualTo(PublicHistoryRecoveryState.IDENTITY_RESET_UNRECOVERABLE(4))
    }

    @Test
    fun `hidden history without a server backup is explained separately`() {
        assertThat(
            historyRecoveryState(
                deviceSecurityState = PublicDeviceSecurityState.PASSED,
                hasServerBackup = false,
                recoveryState = RecoveryState.DISABLED,
                candidateCount = 4,
            )
        ).isEqualTo(PublicHistoryRecoveryState.NO_MATRIX_BACKUP)
    }

    @Test
    fun `hidden history waits for device verification before offering restore`() {
        assertThat(
            historyRecoveryState(
                deviceSecurityState = PublicDeviceSecurityState.ACTION_REQUIRED,
                hasServerBackup = true,
                recoveryState = RecoveryState.DISABLED,
                candidateCount = 4,
            )
        ).isEqualTo(PublicHistoryRecoveryState.DEVICE_VERIFICATION_REQUIRED)
    }

    @Test
    fun `enabled Matrix recovery with remaining candidates stays in recovery instead of claiming none`() {
        assertThat(
            historyRecoveryState(
                deviceSecurityState = PublicDeviceSecurityState.PASSED,
                hasServerBackup = true,
                recoveryState = RecoveryState.ENABLED,
                candidateCount = 3,
                recoveryStatus = HistoryRecoveryStatus.AUTHORIZING_DEVICE,
            )
        ).isEqualTo(PublicHistoryRecoveryState.RECOVERING(3))
    }

    @Test
    fun `old device and administrator waits remain explicit lifecycle states`() {
        assertThat(
            historyRecoveryState(
                deviceSecurityState = PublicDeviceSecurityState.PASSED,
                hasServerBackup = true,
                recoveryState = RecoveryState.ENABLED,
                candidateCount = 2,
                recoveryStatus = HistoryRecoveryStatus.WAITING_FOR_OLD_DEVICE,
            )
        ).isEqualTo(PublicHistoryRecoveryState.WAITING_FOR_OLD_DEVICE(2))
        assertThat(
            historyRecoveryState(
                deviceSecurityState = PublicDeviceSecurityState.PASSED,
                hasServerBackup = true,
                recoveryState = RecoveryState.ENABLED,
                candidateCount = 2,
                recoveryStatus = HistoryRecoveryStatus.WAITING_FOR_ADMIN_APPROVAL,
            )
        ).isEqualTo(PublicHistoryRecoveryState.WAITING_FOR_ADMIN_APPROVAL(2))
    }

    @Test
    fun `zero candidates closes the lifecycle regardless of stale progress`() {
        assertThat(
            historyRecoveryState(
                deviceSecurityState = PublicDeviceSecurityState.PASSED,
                hasServerBackup = true,
                recoveryState = RecoveryState.ENABLED,
                candidateCount = 0,
                recoveryStatus = HistoryRecoveryStatus.AUTHORIZING_DEVICE,
            )
        ).isEqualTo(PublicHistoryRecoveryState.NONE)
    }

    @Test
    fun `local cleanup commits encrypted references before closing inventory`() = runTest {
        val roomId = RoomId("!room:example.org")
        val inventory = RecordingRecoveryInventory(mapOf(roomId to setOf("\$event-a", "\$event-b")))
        val store = RecordingMessageReferenceStore()
        inventory.onClear = { clearedRoomId -> store.operations += "clear:$clearedRoomId" }

        val result = clearUnavailableHistoryLocally(inventory, store)

        assertThat(result.getOrThrow()).isEqualTo(2)
        assertThat(store.deleted).containsExactly(
            MessageReference(roomId, EventId("\$event-a")),
            MessageReference(roomId, EventId("\$event-b")),
        )
        assertThat(inventory.clearedRooms).containsExactly(roomId)
        assertThat(store.operations).containsExactly("persist", "clear:$roomId").inOrder()
        assertThat(store.unavailableHistoryCutoffTimestamp).isGreaterThan(0L)
    }

    @Test
    fun `failed local cleanup keeps recovery inventory open`() = runTest {
        val roomId = RoomId("!room:example.org")
        val inventory = RecordingRecoveryInventory(mapOf(roomId to setOf("\$event-a")))
        val store = RecordingMessageReferenceStore(failDelete = true)

        val result = clearUnavailableHistoryLocally(inventory, store)

        assertThat(result.isFailure).isTrue()
        assertThat(inventory.clearedRooms).isEmpty()
    }

    @Test
    fun `recovery failures remain specific without exposing server details`() {
        assertThat(ContentProtectionException.KmsUnavailable().toPublicRecoveryActionFailure())
            .isEqualTo(PublicRecoveryActionFailure.SERVICE_UNAVAILABLE)
        assertThat(ContentProtectionException.Expired().toPublicRecoveryActionFailure())
            .isEqualTo(PublicRecoveryActionFailure.REQUEST_EXPIRED)
        assertThat(ContentProtectionException.AccessDenied().toPublicRecoveryActionFailure())
            .isEqualTo(PublicRecoveryActionFailure.DEVICE_NOT_ELIGIBLE)
        assertThat(ContentProtectionException.AuthenticationFailed().toPublicRecoveryActionFailure())
            .isEqualTo(PublicRecoveryActionFailure.SECURITY_REJECTED)
    }
}

private class RecordingRecoveryInventory(
    private val candidates: Map<RoomId, Set<String>>,
) : HiddenMessageRecoveryInventory {
    override val candidateCount: StateFlow<Int> = MutableStateFlow(candidates.values.sumOf { it.size })
    override val candidateCountByRoom: StateFlow<Map<RoomId, Int>> =
        MutableStateFlow(candidates.mapValues { (_, ids) -> ids.size })
    override val recoveryStatus: StateFlow<HistoryRecoveryStatus> = MutableStateFlow(HistoryRecoveryStatus.IDLE)
    val clearedRooms = mutableListOf<RoomId>()
    var onClear: ((RoomId) -> Unit)? = null

    override fun replaceRoomCandidates(roomId: RoomId, eventIds: Set<String>) = Unit
    override fun clearRoom(roomId: RoomId) {
        clearedRooms += roomId
        onClear?.invoke(roomId)
    }
    override fun snapshotCandidatesForLocalCleanup(): Map<RoomId, Set<String>> = candidates
    override fun updateRecoveryStatus(status: HistoryRecoveryStatus) = Unit
}

private class RecordingMessageReferenceStore(
    private val failDelete: Boolean = false,
) : MessageReferenceStore {
    override val state: StateFlow<MessageReferenceState> = MutableStateFlow(MessageReferenceState())
    val deleted = mutableListOf<MessageReference>()
    val operations = mutableListOf<String>()
    var unavailableHistoryCutoffTimestamp: Long? = null

    override suspend fun load() = Unit
    override suspend fun toggleFavourite(roomId: RoomId, eventId: EventId): Boolean = false
    override suspend fun addFavourites(roomId: RoomId, eventIds: Collection<EventId>) = Unit
    override suspend fun removeFavourite(roomId: RoomId, eventId: EventId) = Unit
    override suspend fun deleteLocally(roomId: RoomId, eventIds: Collection<EventId>) = Unit
    override suspend fun deleteLocally(references: Collection<MessageReference>) {
        if (failDelete) error("encrypted local persistence failed")
        deleted += references
        operations += "persist"
    }
    override suspend fun deleteUnavailableHistoryLocally(
        references: Collection<MessageReference>,
        cutoffTimestamp: Long,
    ) {
        if (failDelete) error("encrypted local persistence failed")
        deleted += references
        unavailableHistoryCutoffTimestamp = cutoffTimestamp
        operations += "persist"
    }
    override suspend fun deleteConversation(roomId: RoomId, cutoffTimestamp: Long) = Unit
}
