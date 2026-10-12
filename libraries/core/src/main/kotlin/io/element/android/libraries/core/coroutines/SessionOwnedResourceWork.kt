/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.core.coroutines

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/** Cancel before admission; after submission one bounded session owner accounts for the original action. */
class SessionOwnedResourceWork(private val scope: CoroutineScope) {
    private val background = Semaphore(1)
    private val userMedia = Semaphore(3)
    private class CleanupDebt(val discard: () -> Unit, val verified: () -> Boolean, val release: () -> Unit)
    private val cleanupLock = Any()
    private val cleanupRetry = Mutex()
    private val cleanupDebts = mutableListOf<CleanupDebt>()

    /** Cleanup only, never another registration, authorization or download. Failed handles retain their permit. */
    suspend fun retryFailedDiscards() = cleanupRetry.withLock {
        val debts = synchronized(cleanupLock) { cleanupDebts.toList() }
        debts.forEach { debt ->
            try {
                debt.discard()
                // close() may mark a handle closed before failing; a second no-op is not cleanup evidence.
                if (debt.verified()) {
                    synchronized(cleanupLock) { cleanupDebts.remove(debt) }
                    debt.release()
                }
            } catch (_: Throwable) {
                // Retain failed cleanup and its capacity; only verified cleanup may release it.
            }
        }
    }

    fun pendingCleanupCount(): Int = synchronized(cleanupLock) { cleanupDebts.size }

    suspend fun <T : Any> execute(
        userInitiated: Boolean,
        context: CoroutineContext,
        discard: (T) -> Unit,
        isDiscardVerified: (T) -> Boolean = { false },
        onCleanupFailure: () -> Unit = {},
        action: suspend (retainUntilReceipt: (T) -> Unit) -> T,
    ): T {
        require(context[Job] == null) { "The session download owner must not inherit a UI Job." }
        retryFailedDiscards()
        val permit = if (userInitiated) userMedia else background
        permit.acquire()
        val released = AtomicBoolean(false)
        val lock = Any()
        val ready = CompletableDeferred<Unit>()
        var abandoned = false
        var finished = false
        var resolved = false
        var cleanupPending = false
        var value: T? = null
        fun release() { if (released.compareAndSet(false, true)) permit.release() }
        fun releaseIfDone() { if (synchronized(lock) { finished && resolved && !cleanupPending }) release() }
        fun discardOrRetain(acquired: T) {
            try {
                discard(acquired)
            } catch (_: Throwable) {
                synchronized(lock) { cleanupPending = true }
                synchronized(cleanupLock) {
                    cleanupDebts += CleanupDebt({ discard(acquired) }, { isDiscardVerified(acquired) }, ::release)
                }
                onCleanupFailure()
            }
        }
        try {
            currentCoroutineContext().ensureActive()
            // Context contains scheduling and real lifecycle identity, never the navigation Job.
            scope.launch(context) {
                var partial: T? = null
                try {
                    if (synchronized(lock) { abandoned }) {
                        synchronized(lock) { resolved = true }
                        return@launch
                    }
                    val acquired = action { partial = it }
                    partial = null
                    val retained = synchronized(lock) {
                        if (abandoned) false else { value = acquired; true }
                    }
                    if (!retained) {
                        discardOrRetain(acquired)
                        synchronized(lock) { resolved = true }
                    }
                    ready.complete(Unit)
                } catch (failure: Throwable) {
                    partial?.let(::discardOrRetain)
                    synchronized(lock) { resolved = true }
                    ready.completeExceptionally(failure)
                }
            }.invokeOnCompletion { failure ->
                synchronized(lock) { finished = true }
                if (!ready.isCompleted) {
                    synchronized(lock) { resolved = true }
                    ready.completeExceptionally(failure ?: CancellationException("Media owner ended before delivery."))
                }
                releaseIfDone()
            }
        } catch (failure: Throwable) {
            release()
            throw failure
        }
        try {
            ready.await()
            return synchronized(lock) { checkNotNull(value).also { value = null; resolved = true } }
        } finally {
            val late = synchronized(lock) { abandoned = true; value.also { value = null } }
            late?.let {
                discardOrRetain(it)
                synchronized(lock) { resolved = true }
            }
            releaseIfDone()
        }
    }
}
