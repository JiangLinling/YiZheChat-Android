/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.widget

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class RtcWidgetStickyMetadataTest {
    private val eventId = "\$rtc"
    private val roomId = "!room:example.org"
    private val original = Json.parseToJsonElement(
        """{"event_id":"$eventId","sender":"@alice:example.org","type":"m.room.encrypted",
            "origin_server_ts":1000,"msc4354_sticky":{"duration_ms":110000}}"""
    ).jsonObject
    private val event = Json.parseToJsonElement(
        """{"event_id":"$eventId","room_id":"$roomId","sender":"@alice:example.org",
            "type":"org.matrix.msc4143.rtc.member","origin_server_ts":9999,
            "unsigned":{"msc4354_sticky_duration_ttl_ms":3600000},
            "content":{"msc4354_sticky_key":"device:member"}}"""
    ).jsonObject

    private fun message(data: JsonObject = event) = JsonObject(mapOf(
        "api" to JsonPrimitive("toWidget"), "action" to JsonPrimitive("send_event"), "data" to data,
    )).toString()

    @Test
    fun `server lifetime and original timestamp survive every replay`() = runTest {
        val restorer = RtcWidgetStickyMetadata(roomId) { assertThat(it).isEqualTo(eventId); original }
        val first = restorer.restore(message())
        val data = Json.parseToJsonElement(first).jsonObject.getValue("data").jsonObject
        assertThat(data["msc4354_sticky"]).isEqualTo(original["msc4354_sticky"])
        assertThat(data["origin_server_ts"]).isEqualTo(JsonPrimitive(1000))
        assertThat(data.getValue("unsigned").jsonObject).doesNotContainKey("msc4354_sticky_duration_ttl_ms")
        assertThat(restorer.restore(message())).isEqualTo(first)
        assertThat(restorer.restore(first)).isEqualTo(first)
    }

    @Test
    fun `ordinary timeline event without server sticky metadata never acquires a lifetime`() = runTest {
        val restorer = RtcWidgetStickyMetadata(roomId) { JsonObject(original - "msc4354_sticky") }
        assertThat(restorer.restore(message())).isEqualTo(message())
    }

    @Test
    fun `leave event with no member payload still preserves its authoritative sticky tombstone`() = runTest {
        val leave = JsonObject(event + ("content" to Json.parseToJsonElement("""{"msc4354_sticky_key":"device:member"}""")))
        val restorer = RtcWidgetStickyMetadata(roomId) { original }
        assertThat(Json.parseToJsonElement(restorer.restore(message(leave))).jsonObject["data"]?.jsonObject?.get("msc4354_sticky"))
            .isEqualTo(original["msc4354_sticky"])
    }

    @Test
    fun `wrong sender event and room fail closed`() = runTest {
        for ((key, value) in listOf("sender" to "@other:example.org", "event_id" to "\$other", "room_id" to "!other:example.org")) {
            val restorer = RtcWidgetStickyMetadata(roomId) { JsonObject(original + (key to JsonPrimitive(value))) }
            assertThat(runCatching { restorer.restore(message()) }.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        }
    }

    @Test
    fun `missing negative or oversized duration fails closed`() = runTest {
        for (duration in listOf("null", "-1", "3600001")) {
            val restorer = RtcWidgetStickyMetadata(roomId) {
                JsonObject(original + ("msc4354_sticky" to Json.parseToJsonElement("""{"duration_ms":$duration}""")))
            }
            assertThat(runCatching { restorer.restore(message()) }.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        }
    }

    @Test
    fun `failed lookup never synthesizes a lifetime`() = runTest {
        val restorer = RtcWidgetStickyMetadata(roomId) { error("authority unavailable") }
        assertThat(runCatching { restorer.restore(message()) }.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
    }
}
