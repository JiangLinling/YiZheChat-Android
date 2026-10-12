/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.roomlist

import io.element.android.libraries.matrix.api.roomlist.RoomSummary
import io.element.android.services.analytics.api.AnalyticsService
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.RoomListEntriesUpdate
import org.matrix.rustcomponents.sdk.RoomListServiceInterface
import org.matrix.rustcomponents.sdk.use
import timber.log.Timber
import kotlin.collections.groupingBy
import kotlin.coroutines.CoroutineContext

class RoomSummaryListProcessor(
    private val roomSummaries: MutableSharedFlow<List<RoomSummary>>,
    private val roomListService: RoomListServiceInterface,
    private val coroutineContext: CoroutineContext,
    private val roomSummaryFactory: RoomSummaryFactory,
    private val analyticsService: AnalyticsService,
) {
    private val mutex = Mutex()

    suspend fun postUpdate(updates: List<RoomListEntriesUpdate>) {
        updateRoomSummaries(updates) { initialSnapshot ->
            Timber.v("Update rooms from postUpdates (with ${updates.size} items) on ${Thread.currentThread()}")
            updates.forEach { update ->
                applyUpdate(update, initialSnapshot)
            }
        }
    }

    suspend fun rebuildRoomSummaries() {
        updateRoomSummaries(emptyList()) { _ ->
            forEachIndexed { i, summary ->
                val result = buildRoomSummaryForIdentifier(summary.roomId.value)
                if (result != null) {
                    this[i] = result
                }
            }
        }
    }

    private suspend fun MutableList<RoomSummary>.applyUpdate(update: RoomListEntriesUpdate, initialSnapshot: Boolean) {
        // Remove this comment to debug changes in the room list
        // Timber.d("Apply room list update: ${update.describe()}")
        when (update) {
            is RoomListEntriesUpdate.Append -> {
                val roomSummaries = buildRoomSummaryBatch(update.values, roomSummaryFactory, initialSnapshot)
                addAll(roomSummaries)
            }
            is RoomListEntriesUpdate.PushBack -> {
                val roomSummary = buildSummaryForRoomListEntry(update.value, initialSnapshot)
                add(roomSummary)
            }
            is RoomListEntriesUpdate.PushFront -> {
                val roomSummary = buildSummaryForRoomListEntry(update.value, initialSnapshot)
                add(0, roomSummary)
            }
            is RoomListEntriesUpdate.Set -> {
                val roomSummary = buildSummaryForRoomListEntry(update.value, initialSnapshot)
                this[update.index.toInt()] = roomSummary
            }
            is RoomListEntriesUpdate.Insert -> {
                val roomSummary = buildSummaryForRoomListEntry(update.value, initialSnapshot)
                add(update.index.toInt(), roomSummary)
            }
            is RoomListEntriesUpdate.Remove -> {
                removeAt(update.index.toInt())
            }
            is RoomListEntriesUpdate.Reset -> {
                clear()
                addAll(buildRoomSummaryBatch(update.values, roomSummaryFactory, initialSnapshot))
            }
            RoomListEntriesUpdate.PopBack -> {
                removeLastOrNull()
            }
            RoomListEntriesUpdate.PopFront -> {
                removeFirstOrNull()
            }
            RoomListEntriesUpdate.Clear -> {
                clear()
            }
            is RoomListEntriesUpdate.Truncate -> {
                subList(update.length.toInt(), size).clear()
            }
        }
    }

    private suspend fun buildSummaryForRoomListEntry(entry: Room, initialSnapshot: Boolean = false): RoomSummary {
        return entry.use { if (initialSnapshot) roomSummaryFactory.createInitial(it) else roomSummaryFactory.create(room = it) }
    }

    private suspend fun buildRoomSummaryForIdentifier(identifier: String): RoomSummary? {
        return roomListService.roomOrNull(identifier)?.let { room ->
            buildSummaryForRoomListEntry(room)
        }
    }

    private suspend fun updateRoomSummaries(updates: List<RoomListEntriesUpdate>, block: suspend MutableList<RoomSummary>.(Boolean) -> Unit) = withContext(
        coroutineContext
    ) {
        // Capture the description before applying updates: applyUpdate consumes each Room via
        // `entry.use { ... }` which destroys it, and the duplicate-detection branch below reads
        // id() through `describe()`. Without this capture the trackError call crashes before it
        // can be reported.
        val updatesDescription = updates.description()
        mutex.withLock {
            val current = roomSummaries.replayCache.lastOrNull()
            val mutableRoomSummaries = current.orEmpty().toMutableList()
            block(mutableRoomSummaries, current.isNullOrEmpty())

            // TODO remove once https://github.com/element-hq/element-x-android/issues/5031 has been confirmed as fixed
            val uniqueRooms = mutableRoomSummaries.distinctBy { it.roomId }

            if (uniqueRooms.size != mutableRoomSummaries.size) {
                val duplicates = mutableRoomSummaries.groupingBy { it.roomId }.eachCount().filter { it.value > 1 }
                if (duplicates.isNotEmpty()) {
                    analyticsService.trackError(
                        IllegalStateException(
                            "Found duplicates in room summaries after a list update from the SDK: $duplicates. " +
                                "Updates: $updatesDescription"
                        )
                    )
                }
            }

            roomSummaries.emit(uniqueRooms)
        }
    }
}

private fun List<RoomListEntriesUpdate>.description(): String = joinToString { it.describe() }
