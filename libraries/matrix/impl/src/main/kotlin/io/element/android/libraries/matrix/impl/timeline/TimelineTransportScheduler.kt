/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.timeline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/** Bounded transport scheduling. Original send mappings run before queued historical lookups. */
internal class TimelineTransportScheduler {
    private class Waiter(val receiveIdentity: String?) {
        val ready = CompletableDeferred<Unit>()
        var granted = false
        var backgroundRead = false
    }

    private val lock = Any()
    private val sends = ArrayDeque<Waiter>()
    private val foregroundReads = ArrayDeque<Waiter>()
    private val backgroundReads = ArrayDeque<Waiter>()
    private val ordinary = ArrayDeque<Waiter>()
    private var active = 0
    private var sendBurst = 0
    private var priorityBurst = 0
    private var recentReadBurst = 0
    private var activeBackgroundReads = 0
    private var preferBackgroundRead = false

    suspend fun <T> withPermit(
        isLiveSend: Boolean = false,
        isForegroundRead: Boolean = false,
        receiveIdentity: String? = null,
        foregroundRequested: (() -> Boolean)? = null,
        block: suspend () -> T,
    ): T {
        val waiter = Waiter(receiveIdentity)
        synchronized(lock) {
            val foreground = isForegroundRead || foregroundRequested?.invoke() == true
            waiter.backgroundRead = receiveIdentity != null && !foreground && !isLiveSend
            when {
                isLiveSend -> sends
                foreground -> foregroundReads
                waiter.backgroundRead -> backgroundReads
                else -> ordinary
            }.addLast(waiter)
            dispatch()
        }
        try {
            waiter.ready.await()
        } catch (cancelled: CancellationException) {
            synchronized(lock) {
                if (waiter.granted) release(waiter) else {
                    sends.remove(waiter)
                    foregroundReads.remove(waiter)
                    backgroundReads.remove(waiter)
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
                release(waiter)
                dispatch()
            }
        }
    }

    fun promoteReceive(receiveIdentity: String) = synchronized(lock) {
        backgroundReads.firstOrNull { it.receiveIdentity == receiveIdentity }?.let { waiter ->
            backgroundReads.remove(waiter)
            waiter.backgroundRead = false
            foregroundReads.addLast(waiter)
            dispatch()
        }
    }

    private fun release(waiter: Waiter) {
        active--
        if (waiter.backgroundRead) activeBackgroundReads--
    }

    private fun dispatch() {
        while (active < 2) {
            val backgroundAvailable = backgroundReads.isNotEmpty() && activeBackgroundReads < 1
            val historyAvailable = ordinary.isNotEmpty() || backgroundAvailable
            if (sends.isEmpty() && foregroundReads.isEmpty() && !historyAvailable) break
            // Fresh visible receives must not wait behind a restored viewport backlog. Every
            // eighth priority admission still serves history, and every eighth receive serves
            // the oldest visible request. Scheduling changes no original identity or authority.
            val waiter = when {
                historyAvailable && (priorityBurst >= 8 || sends.isEmpty() && foregroundReads.isEmpty()) -> {
                    priorityBurst = 0
                    sendBurst = 0
                    if (backgroundAvailable && (preferBackgroundRead || ordinary.isEmpty())) {
                        preferBackgroundRead = false
                        backgroundReads.removeFirst()
                    } else {
                        preferBackgroundRead = true
                        ordinary.removeFirst()
                    }
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
            if (waiter.backgroundRead) activeBackgroundReads++
            waiter.granted = true
            waiter.ready.complete(Unit)
        }
    }
}
