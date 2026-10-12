/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.location.impl.live.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.features.location.api.Location
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.services.toolbox.api.systemclock.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration.Companion.seconds

private val THROTTLE_WINDOW = 3.seconds

@OptIn(ExperimentalAtomicApi::class)
@SingleIn(AppScope::class)
class LiveLocationSharingCoordinator internal constructor(
    private val startService: () -> Unit,
    private val stopService: () -> Unit,
    private val nowMillis: () -> Long,
) {
    @Inject
    constructor(@ApplicationContext context: Context, clock: SystemClock) : this(
        startService = {
            ContextCompat.startForegroundService(context, Intent(context, LiveLocationSharingService::class.java))
        },
        stopService = {
            context.stopService(Intent(context, LiveLocationSharingService::class.java))
        },
        nowMillis = clock::epochMillis
    )

    internal class RuntimeLease {
        val released = CompletableDeferred<Unit>()
        var retiring = false
        private var collections = 0
        private var cleanupFailed = false
        @Synchronized fun collectionStarted() { collections += 1 }
        @Synchronized fun collectionFinished(clean: Boolean) { collections -= 1; cleanupFailed = cleanupFailed || !clean }
        @Synchronized fun verifyReleased() { check(collections == 0 && !cleanupFailed) { "Location provider cleanup is incomplete" } }
    }

    private val receivers = ConcurrentHashMap<SessionId, LiveLocationReceiver>()
    private val lastDispatchMillis = AtomicLong(0L)
    private val lastKnownLocation = AtomicReference<Location?>(null)
    private var runtime: RuntimeLease? = null
    private var nextRuntime: RuntimeLease? = null

    @Synchronized
    fun register(sessionId: SessionId, receiver: LiveLocationReceiver): Location? {
        val wasEmpty = receivers.isEmpty()
        receivers[sessionId] = receiver
        if (wasEmpty) {
            if (runtime?.retiring == true) {
                // Android may deliver onDestroy after a new room starts. The old
                // owner must finish before the replacement service is requested.
                if (nextRuntime == null) nextRuntime = RuntimeLease()
            } else if (runtime == null) {
                runtime = RuntimeLease()
                requestServiceStart()
            }
        }
        return lastKnownLocation.load()
    }

    @Synchronized
    fun unregister(sessionId: SessionId): Deferred<Unit>? {
        receivers.remove(sessionId)
        if (receivers.isNotEmpty()) return null // Other accounts still own the shared provider.
        lastKnownLocation.store(null)
        lastDispatchMillis.store(0L)
        nextRuntime?.released?.complete(Unit) // This lease never requested a producer.
        nextRuntime = null
        val original = runtime ?: return null
        if (!original.retiring) {
            original.retiring = true
            runCatchingExceptions(stopService).onFailure { original.released.completeExceptionally(it) }
        }
        return original.released
    }

    @Synchronized
    internal fun serviceCreated(): RuntimeLease = checkNotNull(runtime) { "Location service has no active owner" }

    @Synchronized
    internal fun serviceReleased(original: RuntimeLease, failure: Throwable? = null) {
        if (failure != null) { original.released.completeExceptionally(failure); return }
        original.verifyReleased()
        original.released.complete(Unit)
        if (runtime !== original) return
        runtime = null
        if (receivers.isEmpty()) lastKnownLocation.store(null)
        else {
            runtime = nextRuntime ?: RuntimeLease()
            nextRuntime = null
            requestServiceStart()
        }
    }

    private fun requestServiceStart() {
        runCatchingExceptions(startService).onFailure { failure ->
            runtime?.released?.completeExceptionally(failure)
            Timber.e(failure, "Failed to start live location service")
        }
    }

    suspend fun dispatchUnrecoverableError() {
        receivers.values.toList().forEach { receiver ->
            runCatchingExceptions { receiver.onUnrecoverableError() }.onFailure {
                Timber.e(it, "Failed to dispatch live location error")
            }
        }
    }

    suspend fun dispatch(location: Location) {
        val targets = synchronized(this) {
            if (receivers.isEmpty() || runtime?.retiring == true) return
            val now = nowMillis()
            if (lastKnownLocation.load() != null && now - lastDispatchMillis.load() < THROTTLE_WINDOW.inWholeMilliseconds) return
            lastKnownLocation.store(location)
            lastDispatchMillis.store(now)
            receivers.values.toList()
        }
        targets.forEach { receiver ->
            runCatchingExceptions { receiver.onLocationUpdate(location) }.onFailure {
                Timber.e(it, "Failed to dispatch live location")
            }
        }
    }
}
