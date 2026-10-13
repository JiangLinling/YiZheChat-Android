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
    private val foregroundReads = ArrayDeque<Waiter>()
    private val ordinary = ArrayDeque<Waiter>()
    private var active = 0
    private var sendBurst = 0
    private var priorityBurst = 0
    private var recentReadBurst = 0

    suspend fun <T> withPermit(isLiveSend: Boolean = false, isForegroundRead: Boolean = false, block: suspend () -> T): T {
        val waiter = Waiter()
        synchronized(lock) {
            when {
                isLiveSend -> sends
                isForegroundRead -> foregroundReads
                else -> ordinary
            }.addLast(waiter)
            dispatch()
        }
        try {
            waiter.ready.await()
        } catch (cancelled: CancellationException) {
            synchronized(lock) {
                if (waiter.granted) active-- else {
                    sends.remove(waiter)
                    foregroundReads.remove(waiter)
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
        while (active < 2 && (sends.isNotEmpty() || foregroundReads.isNotEmpty() || ordinary.isNotEmpty())) {
            // Fresh visible receives must not wait behind a restored viewport backlog. Every
            // eighth priority admission still serves history, and every eighth receive serves
            // the oldest visible request. Scheduling changes no original identity or authority.
            val waiter = when {
                ordinary.isNotEmpty() && (priorityBurst >= 8 || sends.isEmpty() && foregroundReads.isEmpty()) -> {
                    priorityBurst = 0
                    sendBurst = 0
                    ordinary.removeFirst()
                }
                sends.isNotEmpty() && (sendBurst < 8 || foregroundReads.isEmpty()) -> {
                    priorityBurst++
                    sendBurst++
                    sends.removeFirst()
                }
                foregroundReads.isNotEmpty() -> {
                    priorityBurst++
                    sendBurst = 0
                    if (recentReadBurst >= 7) {
                        recentReadBurst = 0
                        foregroundReads.removeFirst()
                    } else {
                        recentReadBurst++
                        foregroundReads.removeLast()
                    }
                }
                else -> {
                    priorityBurst++
                    sendBurst++
                    sends.removeFirst()
                }
            }
            active++
            waiter.granted = true
            waiter.ready.complete(Unit)
        }
    }
}
