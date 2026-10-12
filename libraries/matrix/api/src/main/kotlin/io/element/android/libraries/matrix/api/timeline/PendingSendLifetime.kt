/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.api.timeline

/**
 * Retains send dependencies for work already owned by the session scope.
 * Screen disposal must not destroy Room/Timeline handles across a suspended protection or send.
 * This does not detach cancellation or authorize any new operation.
 */
class PendingSendLifetime(private val closeNativeOwner: () -> Unit) {
    private val lock = Any()
    private var activeSends = 0
    private var closing = false
    private var closed = false

    /** Transfers an already admitted send's dependency ownership beyond its caller's wait. */
    fun retain(): AutoCloseable {
        synchronized(lock) {
            check(!closing) { "Native send owner is closed to new sends." }
            activeSends++
        }
        val released = java.util.concurrent.atomic.AtomicBoolean(false)
        return AutoCloseable {
            if (released.compareAndSet(false, true)) {
                val release = synchronized(lock) { activeSends--; takeCloseLocked() }
                if (release) closeNativeOwner()
            }
        }
    }

    suspend fun <T> withSend(block: suspend () -> T): T {
        val retention = retain()
        try { return block() } finally { retention.close() }
    }

    fun close() {
        val release = synchronized(lock) {
            closing = true
            takeCloseLocked()
        }
        if (release) closeNativeOwner()
    }

    private fun takeCloseLocked(): Boolean {
        if (!closing || activeSends != 0 || closed) return false
        closed = true
        return true
    }
}
