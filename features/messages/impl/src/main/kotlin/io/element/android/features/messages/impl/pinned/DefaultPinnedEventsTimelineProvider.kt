/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.pinned

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.features.messages.api.pinned.PinnedEventsTimelineProvider
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.core.coroutine.mapState
import io.element.android.libraries.di.RoomScope
import io.element.android.libraries.matrix.api.room.CreateTimelineParams
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.matrix.api.sync.SyncService
import io.element.android.libraries.matrix.api.timeline.Timeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@SingleIn(RoomScope::class)
@ContributesBinding(RoomScope::class)
class DefaultPinnedEventsTimelineProvider(
    private val room: JoinedRoom,
    private val syncService: SyncService,
    private val dispatchers: CoroutineDispatchers,
) : PinnedEventsTimelineProvider {
    private val _timelineStateFlow: MutableStateFlow<AsyncData<Timeline>> =
        MutableStateFlow(AsyncData.Uninitialized)
    private val timelineMutationMutex = Mutex()

    override fun activeTimelineFlow(): StateFlow<Timeline?> {
        return _timelineStateFlow
            .mapState { value ->
                value.dataOrNull()
            }
    }

    val timelineStateFlow = _timelineStateFlow

    fun launchIn(scope: CoroutineScope) {
        _timelineStateFlow.subscriptionCount
            .map { count -> count > 0 }
            .distinctUntilChanged()
            .onEach { isActive ->
                if (isActive) {
                    onActive()
                } else {
                    onInactive()
                }
            }
            .launchIn(scope)
            .invokeOnCompletion { timelineStateFlow.value.dataOrNull()?.close() }
    }

    private suspend fun onActive() = coroutineScope {
        syncService.syncState.onEach {
            // Do not use the sync value here: cached data is sufficient. A new emission only
            // provides a retry opportunity when creating the timeline previously failed.
            loadTimelineIfNeeded()
        }.launchIn(this)

        // A pinned-only timeline is created from the room state that exists at creation time. The
        // SDK normally updates it afterwards, but pin state can race that listener and leave the
        // timeline exposing old events. Recreate it whenever the authoritative pinned ids change.
        room.roomInfoFlow
            .map { it.pinnedEventIds }
            .distinctUntilChanged()
            .drop(1)
            .onEach {
                refreshTimeline()
            }
            .launchIn(this)
    }

    private suspend fun onInactive() {
        timelineMutationMutex.withLock {
            resetTimelineLocked()
        }
    }

    private suspend fun resetTimelineLocked() {
        timelineStateFlow.value.dataOrNull()?.close()
        _timelineStateFlow.emit(AsyncData.Uninitialized)
    }

    suspend fun <T> invokeOnTimeline(action: suspend Timeline.() -> T): T? = timelineMutationMutex.withLock {
        when (val asyncTimeline = timelineStateFlow.value) {
            is AsyncData.Success -> action(asyncTimeline.data)
            else -> null
        }
    }

    private suspend fun refreshTimeline() = timelineMutationMutex.withLock {
        resetTimelineLocked()
        loadTimelineIfNeededLocked()
    }

    private suspend fun loadTimelineIfNeeded() = timelineMutationMutex.withLock {
        loadTimelineIfNeededLocked()
    }

    private suspend fun loadTimelineIfNeededLocked() {
        when (timelineStateFlow.value) {
            is AsyncData.Uninitialized, is AsyncData.Failure -> {
                timelineStateFlow.emit(AsyncData.Loading())
                withContext(dispatchers.io) {
                    room.createTimeline(CreateTimelineParams.PinnedOnly)
                }
                    .fold(
                        { timelineStateFlow.emit(AsyncData.Success(it)) },
                        { timelineStateFlow.emit(AsyncData.Failure(it)) }
                    )
            }
            else -> Unit
        }
    }
}
