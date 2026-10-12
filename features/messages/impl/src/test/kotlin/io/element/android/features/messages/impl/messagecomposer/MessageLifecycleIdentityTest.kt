/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.messages.impl.messagecomposer

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.DeviceId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.securestorage.api.accountIdHash
import org.junit.Test

class MessageLifecycleIdentityTest {
    @Test
    fun `a restored transaction retains its lifecycle identity only for the same session device room and action`() {
        val room = FakeJoinedRoom()
        val client = FakeMatrixClient(sessionId = room.sessionId)
        val original = ordinaryMessageLifecycleRequest(room, client, "send", "captured-transaction")
        assertThat(original.operationId.matches(Regex("[A-Za-z0-9_-]{16,256}"))).isTrue()
        val restored = ordinaryMessageLifecycleRequest(room, client, "send", "captured-transaction")
        assertThat(restored.operationId).isEqualTo(original.operationId)
        assertThat(restored.idempotencyKey).isEqualTo(original.idempotencyKey)
        assertThat(ordinaryMessageLifecycleRequest(room, client, "send", "another-transaction").operationId)
            .isNotEqualTo(original.operationId)
        assertThat(ordinaryMessageLifecycleRequest(room, client, "reply", "captured-transaction").operationId)
            .isNotEqualTo(original.operationId)
        val replacement = FakeMatrixClient(sessionId = room.sessionId, deviceId = DeviceId("replacement-device"))
        assertThat(ordinaryMessageLifecycleRequest(room, replacement, "send", "captured-transaction").operationId)
            .isNotEqualTo(original.operationId)
    }

    @Test
    fun `send reply and edit carry exact account device and room without changing lifecycle`() {
        val room = FakeJoinedRoom()
        val client = FakeMatrixClient(sessionId = room.sessionId)
        listOf("send", "reply", "edit").forEach { trigger ->
            val request = ordinaryMessageLifecycleRequest(room, client, trigger)
            assertThat(request.lifecycleId).isEqualTo(FullAppLifecycleId.LC_13)
            assertThat(request.trigger).isEqualTo(trigger)
            assertThat(request.accountIdHash).isEqualTo(accountIdHash(client.sessionId.value))
            assertThat(request.matrixDeviceIdHash).isEqualTo(accountIdHash(client.deviceId.value))
            assertThat(request.roomIdHash).isEqualTo(accountIdHash(room.roomId.value))
            assertThat(request.operationId).isEqualTo(request.idempotencyKey)
        }
    }

    @Test
    fun `another room session cannot borrow the current client's identity`() {
        val room = FakeJoinedRoom()
        val client = FakeMatrixClient(sessionId = SessionId("@other:test.invalid"))
        assertThat(runCatching { ordinaryMessageLifecycleRequest(room, client, "send") }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `device replacement creates a different device subject and not a shared authorization`() {
        val room = FakeJoinedRoom()
        val oldClient = FakeMatrixClient(sessionId = room.sessionId, deviceId = DeviceId("old-device"))
        val newClient = FakeMatrixClient(sessionId = room.sessionId, deviceId = DeviceId("new-device"))
        val old = ordinaryMessageLifecycleRequest(room, oldClient, "send")
        val new = ordinaryMessageLifecycleRequest(room, newClient, "send")
        assertThat(old.accountIdHash).isEqualTo(new.accountIdHash)
        assertThat(old.matrixDeviceIdHash).isNotEqualTo(new.matrixDeviceIdHash)
        assertThat(old.operationId).isNotEqualTo(new.operationId)
    }
}
