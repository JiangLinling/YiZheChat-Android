/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.roomlist

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.test.room.aRoomSummary
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.matrix.rustcomponents.sdk.Room

class RoomSummaryBatchTest {
    @Test
    fun `batch overlaps at most four authorized reads and retains SDK order`() = runTest {
        val closed = mutableListOf<Int>()
        val rooms = rooms(12, closed)
        val factory = mockk<RoomSummaryFactory>()
        var active = 0
        var maximum = 0
        coEvery { factory.create(any()) } coAnswers {
            val index = rooms.indexOf(firstArg<Room>())
            active++
            maximum = maxOf(maximum, active)
            try {
                delay((12-index).toLong())
                aRoomSummary(roomId = RoomId("!batch$index:example.org"))
            } finally { active-- }
        }
        val result = buildRoomSummaryBatch(rooms, factory)
        assertThat(result.map { it.roomId.value }).containsExactlyElementsIn(
            (0 until 12).map { "!batch$it:example.org" }
        ).inOrder()
        assertThat(maximum).isEqualTo(4)
        assertThat(active).isEqualTo(0)
        assertThat(closed).containsExactlyElementsIn(0 until 12)
    }

    @Test
    fun `one failed read cancels siblings and closes waiting native entries`() = runTest {
        val closed = mutableListOf<Int>()
        val rooms = rooms(12, closed)
        val factory = mockk<RoomSummaryFactory>()
        var active = 0
        coEvery { factory.create(any()) } coAnswers {
            active++
            try {
                if (firstArg<Room>() === rooms[0]) {
                    delay(10)
                    error("read rejected")
                }
                awaitCancellation()
            } finally { active-- }
        }
        val result = runCatching { buildRoomSummaryBatch(rooms, factory) }
        assertThat(result.exceptionOrNull()?.message).isEqualTo("read rejected")
        assertThat(active).isEqualTo(0)
        assertThat(closed).containsExactlyElementsIn(0 until 12)
    }

    @Test
    fun `list cancellation joins all workers before closing every native entry`() = runTest {
        val closed = mutableListOf<Int>()
        val rooms = rooms(12, closed)
        val factory = mockk<RoomSummaryFactory>()
        var active = 0
        coEvery { factory.create(any()) } coAnswers {
            active++
            try { awaitCancellation() } finally { active-- }
        }
        val job = async { buildRoomSummaryBatch(rooms, factory) }
        runCurrent()
        assertThat(active).isEqualTo(4)
        job.cancelAndJoin()
        assertThat(active).isEqualTo(0)
        assertThat(closed).containsExactlyElementsIn(0 until 12)
    }

    private fun rooms(size: Int, closed: MutableList<Int>): List<Room> = (0 until size).map { index ->
        mockk<Room>(relaxed = true) { every { close() } answers { closed.add(index); Unit } }
    }
}
