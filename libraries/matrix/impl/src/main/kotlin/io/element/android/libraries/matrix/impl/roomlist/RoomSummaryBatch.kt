/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.roomlist

import io.element.android.libraries.matrix.api.roomlist.RoomSummary
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.matrix.rustcomponents.sdk.Room

/** Keep SDK order and authorization while overlapping independent room preview reads. */
internal suspend fun buildRoomSummaryBatch(rooms: List<Room>, factory: RoomSummaryFactory, initialSnapshot: Boolean = false): List<RoomSummary> {
    val permits = Semaphore(4)
    try {
        return coroutineScope {
            rooms.map { room -> async { permits.withPermit { if (initialSnapshot) factory.createInitial(room) else factory.create(room) } } }.awaitAll()
        }
    } finally {
        // coroutineScope joins cancelled workers before releasing all owned native handles,
        // including entries which were still waiting for a permit when a sibling failed.
        rooms.forEach { it.close() }
    }
}
