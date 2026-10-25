/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.room

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.matrix.test.A_DEVICE_ID
import io.element.android.libraries.matrix.test.A_ROOM_ID
import io.element.android.libraries.matrix.test.A_SESSION_ID
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.matrix.rustcomponents.sdk.Client

@OptIn(ExperimentalCoroutinesApi::class)
class RustRoomFactoryOwnershipTest {
    @Test
    fun `destroyed factory rejects even its previously valid room and does not create another`() = runTest {
        val dispatcher = Dispatchers.Default
        val nativeClient = mockk<Client>(relaxed = true)
        val factory = RustRoomFactory(
            sessionId = A_SESSION_ID, deviceId = A_DEVICE_ID, notificationSettingsService = mockk(),
            sessionCoroutineScope = backgroundScope,
            dispatchers = CoroutineDispatchers(io = dispatcher, computation = dispatcher, main = dispatcher),
            systemClock = mockk(), roomContentForwarder = mockk(), roomListService = mockk(), innerClient = nativeClient,
            roomSyncSubscriber = mockk(), timelineEventFilterFactory = FakeTimelineEventFilterFactory(), featureFlagService = mockk(),
            roomMembershipObserver = mockk(), roomInfoMapper = mockk(), analyticsService = mockk(),
        )
        val room = mockk<JoinedRustRoom> {
            every { sessionId } returns A_SESSION_ID
            every { roomId } returns A_ROOM_ID
            every { belongsToClient(any(), A_SESSION_ID, A_ROOM_ID) } returns true
        }
        assertThat(factory.ownsCurrentRoom(room)).isTrue()
        factory.destroy()
        assertThat(factory.ownsCurrentRoom(room)).isFalse()
        assertThat(factory.getJoinedRoomOrPreview(A_ROOM_ID, emptyList())).isNull()
        verify(exactly = 0) { nativeClient.getRoom(any()) }
    }
}
