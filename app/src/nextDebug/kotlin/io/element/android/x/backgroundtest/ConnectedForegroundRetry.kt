/* Copyright (c) 2026 YiZheChat. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.x.backgroundtest

/** Main-thread, finite recovery of an already connected service. Never grants permission or starts a service. */
internal class ConnectedForegroundRetry(
    private val postDelayed: (Runnable, Long) -> Unit,
    private val remove: (Runnable) -> Unit,
    private val canRetry: () -> Boolean,
    private val retry: () -> Boolean,
) {
    private var pending: Runnable? = null
    private var attempt = 0

    /** A real connection/page event may start a new recovery window; repeated events cannot stack jobs. */
    fun start() {
        cancel()
        attempt = 0
        scheduleNext()
    }

    fun cancel() {
        pending?.let(remove)
        pending = null
        attempt = DELAYS.size
    }

    private fun scheduleNext() {
        if (attempt >= DELAYS.size || !canRetry()) return
        val task = object : Runnable {
            override fun run() {
                // A queued callback from an old connection must not act on the replacement.
                if (pending !== this) return
                pending = null
                if (canRetry() && !retry()) scheduleNext()
            }
        }
        pending = task
        postDelayed(task, DELAYS[attempt++])
    }

    private companion object {
        val DELAYS = longArrayOf(1_000L, 3_000L, 10_000L)
    }
}
