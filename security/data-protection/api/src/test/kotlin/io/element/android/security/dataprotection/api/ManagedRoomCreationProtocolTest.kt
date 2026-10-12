/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.dataprotection.api

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class ManagedRoomCreationProtocolTest {
    @Test
    fun `managed admission retains the original operation identity and deadline`() {
        val original = lifecycleOperationRequest(FullAppLifecycleId.LC_12, "room_create")
        val managed = original.copy(roomCreationProtocol = "managed-v1")
        assertThat(managed.operationId).isEqualTo(original.operationId)
        assertThat(managed.idempotencyKey).isEqualTo(original.idempotencyKey)
        assertThat(managed.deadlineEpochMillis).isEqualTo(original.deadlineEpochMillis)
        assertThat(original.roomCreationProtocol).isNull()
    }

    @Test
    fun `room creation protocol cannot be applied to message or membership operations`() {
        for ((id, trigger) in listOf(FullAppLifecycleId.LC_13 to "send", FullAppLifecycleId.LC_12 to "member_join")) {
            val original = lifecycleOperationRequest(id, trigger)
            assertThrows(IllegalArgumentException::class.java) { original.copy(roomCreationProtocol = "managed-v1") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            lifecycleOperationRequest(FullAppLifecycleId.LC_12, "room_create").copy(roomCreationProtocol = "unmanaged")
        }
    }
}
