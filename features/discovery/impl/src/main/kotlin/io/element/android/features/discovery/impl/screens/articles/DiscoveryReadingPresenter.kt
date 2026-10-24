/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.screens.articles

import dev.zacsweers.metro.Inject
import io.element.android.features.discovery.impl.actions.DiscoveryReadingCapacityException
import io.element.android.features.discovery.impl.actions.DiscoveryReadingHandle
import io.element.android.features.discovery.impl.actions.DiscoveryReadings
import io.element.android.features.discovery.impl.session.AndroidDiscoveryClock
import io.element.android.libraries.di.annotations.SessionCoroutineScope
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

@Inject
class DiscoveryReadingPresenter internal constructor(
    private val readings: DiscoveryReadings,
    private val clock: AndroidDiscoveryClock,
    @SessionCoroutineScope private val sessionScope: CoroutineScope,
) {
    val revision = readings.revision
    internal fun create(articleId: Long) = DiscoveryReadingSession(articleId, readings, clock, sessionScope)
    suspend fun recover() {
        try { readings.synchronize() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { /* Keep durable responsibility. */ }
    }
}

/** Owned by one Appyx node, not its disposable off-screen composition. */
internal class DiscoveryReadingSession(
    private val articleId: Long,
    private val readings: DiscoveryReadings,
    private val clock: AndroidDiscoveryClock,
    parentScope: CoroutineScope,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + Dispatchers.Main.immediate)
    private val visitId = UUID.randomUUID().toString()
    private val finishId = UUID.randomUUID().toString()
    private var handle: DiscoveryReadingHandle? = null
    private var started: Long? = null
    private var elapsed = 0L
    private var closed = false
    private var work: Job? = null
    private val retry = Channel<Unit>(Channel.CONFLATED)
    private val mutablePending = MutableStateFlow(false)
    val pending = mutablePending.asStateFlow()

    private fun duration(): Long = elapsed + (started?.let { (clock.elapsedMillis() - it).coerceAtLeast(0) } ?: 0L)

    fun visible(value: Boolean) {
        if (closed || value == (started != null)) return
        if (value) {
            started = clock.elapsedMillis()
            val previous = work
            work = scope.launch {
                previous?.join()
                var nextSync = 0L
                while (isActive && !closed && started != null) {
                    try {
                        if (handle == null) handle = readings.begin(articleId, visitId, finishId)
                        readings.checkpoint(requireNotNull(handle), duration())
                        if (clock.elapsedMillis() >= nextSync) {
                            mutablePending.value = !readings.synchronize()
                            nextSync = clock.elapsedMillis() + 30000
                        }
                    } catch (cancelled: CancellationException) { throw cancelled
                    } catch (_: DiscoveryReadingCapacityException) {
                        mutablePending.value = true
                        try { readings.synchronize() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
                        nextSync = 0
                    } catch (_: Exception) {
                        mutablePending.value = true
                        nextSync = clock.elapsedMillis() + 30000
                    }
                    if (withTimeoutOrNull(5000) { retry.receive(); true } == true) nextSync = 0
                }
            }
        } else {
            elapsed = duration()
            started = null
            val previous = work
            previous?.cancel()
            work = scope.launch {
                previous?.join()
                handle?.let { owned ->
                    try { readings.checkpoint(owned, elapsed) } catch (cancelled: CancellationException) { throw cancelled
                    } catch (_: Exception) { mutablePending.value = true }
                }
            }
        }
    }

    fun retry() { retry.trySend(Unit) }

    fun close() {
        if (closed) return
        elapsed = duration()
        started = null
        closed = true
        val previous = work
        previous?.cancel()
        work = scope.launch {
            previous?.join()
            handle?.let { owned ->
                try { readings.checkpoint(owned, elapsed, ended = true) } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) { mutablePending.value = true }
            }
            try { readings.synchronize() } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { mutablePending.value = true }
        }
    }
}
