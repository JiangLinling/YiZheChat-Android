/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.appnav.di

import androidx.annotation.VisibleForTesting
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.element.android.features.networkmonitor.api.NetworkMonitor
import io.element.android.features.networkmonitor.api.NetworkStatus
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.core.coroutine.childScope
import io.element.android.libraries.matrix.api.sync.SyncService
import io.element.android.libraries.matrix.api.sync.SyncState
import io.element.android.services.analytics.api.AnalyticsService
import io.element.android.services.analytics.api.recordTransaction
import io.element.android.services.analyticsproviders.api.AnalyticsUserData
import io.element.android.services.appnavstate.api.AppForegroundStateService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@AssistedInject
class SyncOrchestrator(
    @Assisted private val syncService: SyncService,
    @Assisted sessionCoroutineScope: CoroutineScope,
    private val appForegroundStateService: AppForegroundStateService,
    private val networkMonitor: NetworkMonitor,
    dispatchers: CoroutineDispatchers,
    private val analyticsService: AnalyticsService,
) {
    @AssistedFactory
    interface Factory {
        fun create(
            syncService: SyncService,
            sessionCoroutineScope: CoroutineScope,
        ): SyncOrchestrator
    }

    private val tag = "SyncOrchestrator"

    private val coroutineScope = sessionCoroutineScope.childScope(dispatchers.io, tag)

    private val started = AtomicBoolean(false)
    private val syncActions = Mutex()

    /**
     * Starting observing the app state and network state to start/stop the sync service.
     *
     * Before observing the state, a first attempt at starting the sync service will happen if it's not already running.
     */
    fun start() {
        if (!started.compareAndSet(false, true)) {
            // An Activity retry reuses this session-scoped orchestrator. Observing once must
            // not mean that a failed, still-idle initial sync can never be started again.
            coroutineScope.launch { restartIdleSyncIfEligible() }
            return
        }

        coroutineScope.launch {
            // Perform an initial sync if the sync service is not running, to check whether the homeserver is accessible
            // Otherwise, if the device is offline the sync service will never start and the SyncState will be Idle, not Offline
            Timber.tag(tag).d("performing initial sync attempt")
            try {
                analyticsService.recordTransaction("First sync", "syncService.startSync()") { transaction ->
                    val firstState = syncActions.withLock { startSyncAndAwaitState() }
                    firstState?.let { transaction.putIndexableData(AnalyticsUserData.FIRST_SYNC_STATE, it.name) }
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                Timber.tag(tag).w("initial sync attempt failed type=%s", failure.javaClass.simpleName)
            }
            // A failed Result or a successful start that never emits a state must not strand
            // foreground/network recovery behind an unbounded first { non-Idle } wait.
            observeStates()
        }
    }

    private suspend fun startSyncAndAwaitState(): SyncState? {
        val result = try {
            syncService.startSync()
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            Result.failure(failure)
        }
        if (result.isFailure) {
            Timber.tag(tag).w("sync start failed type=%s", result.exceptionOrNull()?.javaClass?.simpleName)
            return null
        }
        return withTimeoutOrNull(5.seconds) {
            syncService.syncState.first { it != SyncState.Idle }
        }.also { state ->
            if (state == null) Timber.tag(tag).w("sync start did not publish a state within the bounded wait")
        }
    }

    private fun isAppActive(): Boolean = appForegroundStateService.run {
        isInForeground.value || isInCall.value || isSyncingNotificationEvent.value ||
            hasRingingCall.value || isSharingLiveLocation.value
    }

    private suspend fun restartIdleSyncIfEligible() = syncActions.withLock {
        if (syncService.syncState.value == SyncState.Idle &&
            networkMonitor.connectivity.value == NetworkStatus.Connected && isAppActive()
        ) {
            startSyncAndAwaitState()
        }
    }

    @OptIn(FlowPreview::class)
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal fun observeStates() = coroutineScope.launch {
        Timber.tag(tag).d("start observing the app and network state")
        val isAppActiveFlows = listOf(
            appForegroundStateService.isInForeground,
            appForegroundStateService.isInCall,
            appForegroundStateService.isSyncingNotificationEvent,
            appForegroundStateService.hasRingingCall,
            appForegroundStateService.isSharingLiveLocation
        )
        val isAppActiveFlow = combine(isAppActiveFlows) { actives -> actives.any { it } }
        combine(
            // small debounce to avoid spamming startSync when the state is changing quickly in case of error.
            syncService.syncState.debounce(100.milliseconds),
            networkMonitor.connectivity,
            isAppActiveFlow,
        ) { syncState, networkState, isAppActive ->
            val isNetworkAvailable = networkState == NetworkStatus.Connected

            Timber.tag(tag).d("isAppActive=$isAppActive, isNetworkAvailable=$isNetworkAvailable")
            if (syncState == SyncState.Running && !isAppActive) {
                SyncStateAction.StopSync
            } else if (syncState == SyncState.Idle && isAppActive && isNetworkAvailable) {
                SyncStateAction.StartSync
            } else {
                SyncStateAction.NoOp
            }
        }
            .distinctUntilChanged()
            .debounce { action ->
                // Don't stop the sync immediately, wait a bit to avoid starting/stopping the sync too often
                if (action == SyncStateAction.StopSync) 3.seconds else 0.seconds
            }
            .onCompletion {
                Timber.tag(tag).d("has been stopped")
            }
            .collect { action ->
                when (action) {
                    SyncStateAction.StartSync -> {
                        restartIdleSyncIfEligible()
                    }
                    SyncStateAction.StopSync -> {
                        syncActions.withLock { syncService.stopSync() }
                    }
                    SyncStateAction.NoOp -> Unit
                }
            }
    }
}

private enum class SyncStateAction {
    StartSync,
    StopSync,
    NoOp,
}
