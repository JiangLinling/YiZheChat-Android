/*
 * Copyright 2026 YiZheChat.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.room.powerlevels

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal interface PowerLevelEventTransport {
    /** Fresh authority content, never the SDK's typed/cached projection. */
    suspend fun read(): JsonObject
    suspend fun send(content: JsonObject): String
}

/** Edits raw content without round-tripping through the SDK's lossy power-level model. */
internal class RoomPowerLevelUpdater(
    private val transport: PowerLevelEventTransport,
    private val mutex: Mutex,
) {
    suspend fun updateUsers(changes: Map<String, Long>) {
        if (changes.isEmpty()) return
        update { current ->
            val users = when (val value = current["users"]) {
                null -> emptyMap()
                is JsonObject -> value
                else -> error("Invalid Matrix power-level users content")
            }.toMutableMap()
            changes.forEach { (userId, level) -> users[userId] = JsonPrimitive(level) }
            JsonObject(current + ("users" to JsonObject(users)))
        }
    }

    private suspend fun update(transform: (JsonObject) -> JsonObject) = mutex.withLock {
        val original = transport.read()
        val updated = transform(original)
        if (updated == original) return@withLock
        // Matrix state PUT has no compare-and-swap. Reject any conflict we can observe,
        // serialize our local writers, and never add application retries or auto-repair afterward.
        check(transport.read() == original) { "Matrix power levels changed before role update" }
        check(transport.send(updated).isNotBlank()) { "Matrix role update returned no event identity" }
        check(transport.read() == updated) { "Matrix role update authority content did not converge" }
    }
}
