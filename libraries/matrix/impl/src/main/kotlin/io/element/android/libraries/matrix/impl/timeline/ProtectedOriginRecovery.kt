/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.timeline

import io.element.android.security.dataprotection.api.LifecycleOperationContext
import kotlinx.coroutines.CompletableDeferred

/** Coalesces legacy identity repair. Stores no completed origin, permission, key or plaintext. */
internal class ProtectedOriginRecovery(private val capacity: Int = 4096, private val now: () -> Long) {
    private class Attempt {
        var active: CompletableDeferred<LifecycleOperationContext?>? = null
        var failures = 0
        var retryAt = 0L
    }
    private val attempts = LinkedHashMap<String, Attempt>()

    suspend fun lookup(key: String, block: suspend () -> LifecycleOperationContext?): LifecycleOperationContext? {
        var producer = false
        val state: Attempt
        val result: CompletableDeferred<LifecycleOperationContext?>
        synchronized(attempts) {
            if (key !in attempts && attempts.size >= capacity) {
                attempts.entries.firstOrNull { it.value.active == null }?.let { attempts.remove(it.key) }
                if (attempts.size >= capacity) return null
            }
            state = attempts.getOrPut(key) { Attempt() }
            result = state.active ?: run {
                if (now() < state.retryAt) return null
                producer = true
                CompletableDeferred<LifecycleOperationContext?>().also { state.active = it }
            }
        }
        if (!producer) return result.await()
        var resolved = false
        try {
            val context = block()
            resolved = context != null
            result.complete(context)
            return context
        } catch (failure: Exception) {
            result.completeExceptionally(failure)
            throw failure
        } finally {
            synchronized(attempts) {
                state.active = null
                if (resolved) {
                    // Every later lookup must consult authority again. Readers still perform KMS/AEAD.
                    attempts.remove(key)
                } else {
                    state.failures = (state.failures + 1).coerceAtMost(5)
                    state.retryAt = now() + (2_000L shl state.failures).coerceAtMost(60_000L)
                }
            }
        }
    }
}
