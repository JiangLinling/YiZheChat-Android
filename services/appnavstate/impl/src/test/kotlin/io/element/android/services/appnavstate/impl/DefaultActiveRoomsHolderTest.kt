/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.services.appnavstate.impl

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.test.A_ROOM_ID
import io.element.android.libraries.matrix.test.A_ROOM_ID_2
import io.element.android.libraries.matrix.test.A_SESSION_ID
import io.element.android.libraries.matrix.test.A_SESSION_ID_2
import io.element.android.libraries.matrix.test.room.FakeBaseRoom
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DefaultActiveRoomsHolderTest {
    @Test
    fun `session clear destroys every held instance and preserves another session`() = runTest {
        val holder = DefaultActiveRoomsHolder()
        val previous = spyk(FakeJoinedRoom(baseRoom = FakeBaseRoom(roomId = A_ROOM_ID)))
        val next = spyk(FakeJoinedRoom(baseRoom = FakeBaseRoom(roomId = A_ROOM_ID)))
        val other = spyk(FakeJoinedRoom(baseRoom = FakeBaseRoom(sessionId = A_SESSION_ID_2)))
        holder.addRoom(previous)
        holder.addRoom(next)
        holder.addRoom(other)
        holder.observeActiveRoomMatching(A_SESSION_ID, A_ROOM_ID).test {
            assertThat(awaitItem()).isSameInstanceAs(next)
            holder.clear(A_SESSION_ID)
            assertThat(awaitItem()).isNull()
            assertThat(holder.getActiveRoom(A_SESSION_ID_2)).isSameInstanceAs(other)
            verify(exactly = 1) { previous.destroy() }
            verify(exactly = 1) { next.destroy() }
            verify(exactly = 0) { other.destroy() }
        }
    }

    @Test
    fun `popping the newest room restores the still-held previous owner`() = runTest {
        val holder = DefaultActiveRoomsHolder()
        val previous = FakeJoinedRoom(baseRoom = FakeBaseRoom(roomId = A_ROOM_ID))
        val next = FakeJoinedRoom(baseRoom = FakeBaseRoom(roomId = A_ROOM_ID))
        holder.addRoom(previous)
        holder.addRoom(next)
        holder.observeActiveRoomMatching(A_SESSION_ID, A_ROOM_ID).test {
            assertThat(awaitItem()).isSameInstanceAs(next)
            holder.removeRoom(next)
            assertThat(awaitItem()).isSameInstanceAs(previous)
        }
    }

    @Test
    fun `late room publication and replacement emit but unrelated rooms do not`() = runTest {
        val holder = DefaultActiveRoomsHolder()
        val first = FakeJoinedRoom(baseRoom = FakeBaseRoom(roomId = A_ROOM_ID))
        val next = FakeJoinedRoom(baseRoom = FakeBaseRoom(roomId = A_ROOM_ID))
        holder.observeActiveRoomMatching(A_SESSION_ID, A_ROOM_ID).test {
            assertThat(awaitItem()).isNull()
            holder.addRoom(first)
            assertThat(awaitItem()).isSameInstanceAs(first)
            holder.addRoom(FakeJoinedRoom(baseRoom = FakeBaseRoom(roomId = A_ROOM_ID_2)))
            expectNoEvents()
            holder.addRoom(next)
            assertThat(awaitItem()).isSameInstanceAs(next)
            holder.removeRoom(first)
            assertThat(holder.getActiveRoomMatching(A_SESSION_ID, A_ROOM_ID)).isSameInstanceAs(next)
            expectNoEvents()
            holder.removeRoom(next)
            assertThat(awaitItem()).isNull()
        }
    }
}
