/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.timeline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/** Bounded transport scheduling. Original send mappings run before queued historical lookups. */
internal class TimelineTransportScheduler {
    private class Waiter {
        val ready = CompletableDeferred<Unit>()
        var granted = false
    }

    private val lock = Any()
    private val sends = ArrayDeque<Waiter>()
    private val ordinary = ArrayDeque<Waiter>()
    private var active = 0
    private var sendBurst = 0

    suspend fun <T> withPermit(isLiveSend: Boolean = false, block: suspend () -> T): T {
        val waiter = Waiter()
        synchronized(lock) {
            (if (isLiveSend) sends else ordinary).addLast(waiter)
            dispatch()
        }
        try {
            waiter.ready.await()
        } catch (cancelled: CancellationException) {
            synchronized(lock) {
                if (waiter.granted) active-- else {
                    sends.remove(waiter)
                    ordinary.remove(waiter)
                }
                dispatch()
            }
            throw cancelled
        }
        try {
            return block()
        } finally {
            synchronized(lock) {
                active--
                dispatch()
            }
        }
    }

    private fun dispatch() {
        while (active < 2 && (sends.isNotEmpty() || ordinary.isNotEmpty())) {
            // A sustained send burst must still let existing reads make progress.
            val waiter = if (sends.isNotEmpty() && (sendBurst < 8 || ordinary.isEmpty())) {
                sendBurst++
                sends.removeFirst()
            } else {
                sendBurst = 0
                ordinary.removeFirst()
            }
            active++
            waiter.granted = true
            waiter.ready.complete(Unit)
        }
    }
}
