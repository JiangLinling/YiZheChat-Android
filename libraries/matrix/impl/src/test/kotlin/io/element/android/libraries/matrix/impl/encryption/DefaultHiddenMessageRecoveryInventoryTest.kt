/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.matrix.impl.encryption

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.encryption.HistoryRecoveryStatus
import org.junit.Test

class DefaultHiddenMessageRecoveryInventoryTest {
    @Test
    fun `publishes aggregate and room counts without exposing event ids`() {
        val inventory = DefaultHiddenMessageRecoveryInventory()
        val firstRoom = RoomId("!first:example.org")
        val secondRoom = RoomId("!second:example.org")

        inventory.replaceRoomCandidates(firstRoom, setOf("event-a", "event-b"))
        inventory.replaceRoomCandidates(secondRoom, setOf("event-c"))

        assertThat(inventory.candidateCount.value).isEqualTo(3)
        assertThat(inventory.candidateCountByRoom.value).containsExactly(firstRoom, 2, secondRoom, 1)
        assertThat(inventory.candidateCountByRoom.value.toString()).doesNotContain("event-a")

        inventory.clearRoom(firstRoom)

        assertThat(inventory.candidateCount.value).isEqualTo(1)
        assertThat(inventory.candidateCountByRoom.value).containsExactly(secondRoom, 1)
    }

    @Test
    fun `recovery status contains no content and is updated independently from counts`() {
        val inventory = DefaultHiddenMessageRecoveryInventory()

        inventory.updateRecoveryStatus(HistoryRecoveryStatus.WAITING_FOR_DEVICE_TRUST)

        assertThat(inventory.recoveryStatus.value).isEqualTo(HistoryRecoveryStatus.WAITING_FOR_DEVICE_TRUST)
        assertThat(inventory.candidateCount.value).isEqualTo(0)
    }

    @Test
    fun `local cleanup snapshot is copied and does not mutate the live inventory`() {
        val inventory = DefaultHiddenMessageRecoveryInventory()
        val roomId = RoomId("!room:example.org")
        inventory.replaceRoomCandidates(roomId, setOf("event-a", "event-b"))

        val snapshot = inventory.snapshotCandidatesForLocalCleanup()
        inventory.replaceRoomCandidates(roomId, setOf("event-c"))

        assertThat(snapshot).containsExactly(roomId, setOf("event-a", "event-b"))
        assertThat(inventory.candidateCount.value).isEqualTo(1)
    }
}
