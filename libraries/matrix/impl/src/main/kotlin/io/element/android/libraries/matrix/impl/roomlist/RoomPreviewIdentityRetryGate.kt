/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.roomlist

import io.element.android.libraries.matrix.api.core.RoomId

/** Bounds optional identity lookup work; it never caches a read permission or plaintext. */
internal class RoomPreviewIdentityRetryGate<T : Any>(
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val capacity: Int = 512,
) {
    internal class Attempt<T>(val roomId: RoomId, val signature: T) {
        var running = true
        var failures = 0
        var retryAt = 0L
    }

    private val attempts = LinkedHashMap<RoomId, Attempt<T>>()

    init { require(capacity > 0) }

    @Synchronized
    fun begin(roomId: RoomId, signature: T): Attempt<T>? {
        val previous = attempts[roomId]
        if (previous?.signature == signature) {
            if (previous.running || now() < previous.retryAt) return null
            previous.running = true
            return previous
        }
        // A changed latest message is new work and must not inherit an old preview's backoff.
        if (previous == null && attempts.size >= capacity) {
            val victim = attempts.entries.firstOrNull { !it.value.running } ?: return null
            attempts.remove(victim.key)
        }
        return Attempt(roomId, signature).also { attempts[roomId] = it }
    }

    @Synchronized
    fun complete(attempt: Attempt<T>, success: Boolean) {
        // An obsolete snapshot cannot delay or remove a newer message's lookup.
        if (attempts[attempt.roomId] !== attempt) return
        attempt.running = false
        if (success) {
            attempts.remove(attempt.roomId)
        } else {
            val delay = when (attempt.failures) { 0 -> 1_000L; 1 -> 5_000L; else -> 30_000L }
            attempt.failures = (attempt.failures + 1).coerceAtMost(3)
            attempt.retryAt = now() + delay
        }
    }
}
