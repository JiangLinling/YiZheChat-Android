/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.widget

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** Restores metadata lost when the encrypted timeline event is delivered as a decrypted widget event. */
internal class RtcWidgetStickyMetadata(
    private val roomId: String,
    private val fetchEvent: suspend (String) -> JsonObject,
) {
    suspend fun restore(message: String): String {
        val envelope = Json.parseToJsonElement(message) as? JsonObject ?: return message
        if (envelope.text("api") != "toWidget" || envelope.text("action") != "send_event" || "response" in envelope) return message
        val data = envelope["data"] as? JsonObject ?: return message
        if (data.text("type") != NEXT_RTC_MEMBER_EVENT_TYPE || data["msc4354_sticky"] != null) return message
        val content = data["content"] as? JsonObject ?: return message
        if (content.text("msc4354_sticky_key") == null) return message
        check(data.text("room_id") == roomId) { "RTC widget room mismatch" }
        val eventId = checkNotNull(data.text("event_id")) { "RTC event identity unavailable" }
        check(eventId.startsWith('$')) { "RTC event is not a server echo" }
        val original = fetchEvent(eventId)
        check(original.text("event_id") == eventId && original.text("sender") == data.text("sender")) {
            "RTC event authority identity mismatch"
        }
        check(original.text("room_id") in setOf(null, roomId)) { "RTC event authority room mismatch" }
        check(original.text("type") in setOf("m.room.encrypted", NEXT_RTC_MEMBER_EVENT_TYPE)) { "RTC event authority type mismatch" }
        val sticky = original["msc4354_sticky"] as? JsonObject ?: return message
        val duration = (sticky["duration_ms"] as? JsonPrimitive)?.longOrNull
        val timestamp = (original["origin_server_ts"] as? JsonPrimitive)?.longOrNull
        check(duration != null && duration in 1..3_600_000 && timestamp != null && timestamp > 0) { "RTC sticky authority metadata invalid" }
        // Use the original absolute time on every delivery, never a new TTL starting at receipt time.
        val restored = data.toMutableMap().apply {
            put("msc4354_sticky", sticky)
            put("origin_server_ts", JsonPrimitive(timestamp))
            (get("unsigned") as? JsonObject)?.let { unsigned ->
                put("unsigned", JsonObject(unsigned - "msc4354_sticky_duration_ttl_ms"))
            }
        }
        return JsonObject(envelope + ("data" to JsonObject(restored))).toString()
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
}
