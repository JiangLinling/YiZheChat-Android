/* Copyright (c) 2026 YiZheChat. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.x.backgroundtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectedForegroundRetryTest {
    private val queued = mutableListOf<Pair<Runnable, Long>>()
    private var allowed = true
    private var succeeds = false
    private var calls = 0
    private val recovery = ConnectedForegroundRetry(
        postDelayed = { task, delay -> queued.add(task to delay) },
        remove = { task -> queued.removeAll { it.first === task } },
        canRetry = { allowed },
        retry = { calls++; succeeds },
    )

    @Test fun `persistent failure exhausts budget without an endless loop`() {
        recovery.start()
        val delays = mutableListOf<Long>()
        while (queued.isNotEmpty()) {
            val (task, delay) = queued.removeAt(0)
            delays.add(delay)
            task.run()
            assertTrue(calls <= 3)
        }
        assertEquals(listOf(1_000L, 3_000L, 10_000L), delays)
        assertEquals(3, calls)
    }

    @Test fun `successful recovery cancels further attempts`() {
        recovery.start()
        succeeds = true
        queued.removeAt(0).first.run()
        assertEquals(1, calls)
        assertTrue(queued.isEmpty())
    }

    @Test fun `permission or connection loss prevents queued retry`() {
        recovery.start()
        allowed = false
        queued.removeAt(0).first.run()
        assertEquals(0, calls)
        assertTrue(queued.isEmpty())
    }

    @Test fun `disabled service cannot create a retry window`() {
        allowed = false
        recovery.start()
        assertTrue(queued.isEmpty())
    }

    @Test fun `disconnect invalidates even an already dequeued callback`() {
        recovery.start()
        val stale = queued.removeAt(0).first
        recovery.cancel()
        stale.run()
        assertEquals(0, calls)
        assertTrue(queued.isEmpty())
    }

    @Test fun `replacement connection never runs the old pending attempt`() {
        recovery.start()
        val stale = queued.first().first
        recovery.start()
        stale.run()
        assertEquals(0, calls)
        assertEquals(1, queued.size)
        succeeds = true
        queued.removeAt(0).first.run()
        assertEquals(1, calls)
        assertTrue(queued.isEmpty())
    }

    @Test fun `real reconnection can recover after the prior budget was exhausted`() {
        recovery.start()
        repeat(3) { queued.removeAt(0).first.run() }
        succeeds = true
        recovery.start()
        queued.removeAt(0).first.run()
        assertEquals(4, calls)
        assertTrue(queued.isEmpty())
    }
}
