/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.roomlist

import io.element.android.libraries.matrix.api.core.RoomId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import org.matrix.rustcomponents.sdk.Timeline
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.TimelineConfiguration
import org.matrix.rustcomponents.sdk.TimelineFocus
import org.matrix.rustcomponents.sdk.TimelineFilter
import org.matrix.rustcomponents.sdk.DateDividerMode
import uniffi.matrix_sdk_ui.TimelineReadReceiptTracking

/** Bounded SDK carrier metadata owners, never cached authorization or decrypted protected content. */
class RoomPreviewTimelineCache(
    private val scope: CoroutineScope,
    private val capacity: Int = 2,
    private val load: suspend (RoomId) -> Timeline?,
) {
    private class Entry {
        lateinit var loading: Deferred<Result<Timeline?>>
        var timeline: Timeline? = null
        var readers = 0
        var retired = false
    }

    private val lock = Any()
    private val entries = LinkedHashMap<RoomId, Entry>()
    private var closed = false

    init {
        require(capacity > 0)
        requireNotNull(scope.coroutineContext[Job]) { "Preview timelines need a session owner." }
            .invokeOnCompletion { close() }
    }

    suspend fun <T> withTimeline(roomId: RoomId, block: suspend (Timeline) -> T): T? {
        val entry = synchronized(lock) {
            if (closed || scope.coroutineContext[Job]?.isActive != true) return null
            val existing = entries.remove(roomId)
            val selected = existing ?: run {
                if (entries.size >= capacity) {
                    val victim = entries.entries.firstOrNull { it.value.readers == 0 && it.value.loading.isCompleted }
                        ?: return null
                    entries.remove(victim.key)
                    retire(victim.value)
                }
                Entry().also { created ->
                    // A 300 ms list-preview timeout cancels only the waiter. The one tracked
                    // construction retains its own Room handle until the SDK returns.
                    created.loading = scope.async(start = CoroutineStart.LAZY) {
                        try {
                            val timeline = load(roomId)
                            synchronized(lock) {
                                created.timeline = timeline
                                if (created.retired && created.readers == 0) releaseTimeline(created)
                            }
                            Result.success(timeline)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            Result.failure(failure)
                        }
                    }
                }
            }
            entries[roomId] = selected
            selected.readers++
            selected.loading.start()
            selected
        }
        try {
            val timeline = entry.loading.await().getOrThrow() ?: return null
            return block(timeline)
        } finally {
            synchronized(lock) {
                entry.readers--
                if (entry.retired && entry.readers == 0) releaseTimeline(entry)
                if (entry.readers == 0 && entry.loading.isCompleted && entry.timeline == null) {
                    entries.remove(roomId, entry)
                }
            }
        }
    }

    fun close() = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        entries.values.forEach(::retire)
        entries.clear()
    }

    private fun retire(entry: Entry) {
        entry.retired = true
        entry.loading.cancel()
        // An active native read owns its handle until its finally block runs.
        if (entry.readers == 0) releaseTimeline(entry)
    }

    private fun releaseTimeline(entry: Entry) {
        entry.timeline?.destroy()
        entry.timeline = null
    }
}

internal suspend fun Room.createPreviewIdentityTimeline(): Timeline = timelineWithConfiguration(TimelineConfiguration(
    focus = TimelineFocus.Live(hideThreadedEvents = false),
    filter = TimelineFilter.All,
    internalIdPrefix = "room_preview_identity", dateDividerMode = DateDividerMode.DAILY,
    trackReadReceipts = TimelineReadReceiptTracking.DISABLED, reportUtds = false,
))
