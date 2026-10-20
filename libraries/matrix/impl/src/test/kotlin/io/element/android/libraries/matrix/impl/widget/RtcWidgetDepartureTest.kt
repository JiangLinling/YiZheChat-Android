/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.widget

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RtcWidgetDepartureTest {
    @Test fun `redaction targets only correlated acknowledged live membership events`() {
        val departure = RtcWidgetDeparture("w")
        val join = request.replace("\"delay\":20000,", "")
            .replace("\"content\":{", "\"content\":{\"member\":{\"id\":\"own\"},")
        val accepted = registered.replace("\"delay_id\":\"own-delay\"", "\"event_id\":\"${'$'}own\"")
        departure.received(accepted)
        assertThat(departure.membershipEvents()).isEmpty()
        departure.sent(join.replace("\"widgetId\":\"w\"", "\"widgetId\":\"foreign\""))
        departure.received(accepted)
        assertThat(departure.membershipEvents()).isEmpty()
        departure.sent(join); departure.received(accepted)
        assertThat(departure.membershipEvents()).containsExactly("${'$'}own")
        departure.received(accepted.replace("${'$'}own", "${'$'}injected"))
        assertThat(departure.membershipEvents()).containsExactly("${'$'}own")
    }
    private val request = """{"api":"fromWidget","widgetId":"w","requestId":"r","action":"send_event","data":{
        "type":"org.matrix.msc4143.rtc.member","delay":20000,"sticky_duration_ms":3600000,
        "content":{"slot_id":"m.call#ROOM","msc4354_sticky_key":"member"}}}"""
    private val registered = """{"api":"fromWidget","widgetId":"w","requestId":"r","action":"send_event",
        "response":{"delay_id":"own-delay"}}"""

    private fun reply(message: String, error: Boolean = false): String {
        val envelope = Json.parseToJsonElement(message).jsonObject
        return JsonObject(envelope + ("response" to if (error) JsonObject(mapOf("error" to JsonPrimitive("not found"))) else JsonObject(emptyMap())))
            .toString()
    }

    @Test
    fun `sends only correlated registered empty membership and waits for actual acknowledgement`() = runTest {
        val departure = RtcWidgetDeparture("w")
        departure.sent(request)
        departure.received(registered)
        val sent = mutableListOf<String>()
        val result = async { departure.depart { sent.add(it); true } }
        runCurrent()
        assertThat(result.isCompleted).isFalse()
        assertThat(sent).hasSize(1)
        val data = Json.parseToJsonElement(sent.single()).jsonObject["data"]!!.jsonObject
        assertThat(data["action"]).isEqualTo(JsonPrimitive("send"))
        assertThat(data["delay_id"]).isEqualTo(JsonPrimitive("own-delay"))
        assertThat(departure.received(reply(sent.single()).replace("\"widgetId\":\"w\"", "\"widgetId\":\"other\""))).isFalse()
        assertThat(result.isCompleted).isFalse()
        assertThat(departure.received(reply(sent.single()))).isTrue()
        assertThat(result.await()).isTrue()
    }

    @Test
    fun `rejects uncorrelated foreign live membership and non-sticky registrations`() = runTest {
        for (invalid in listOf(
            request.replace("\"widgetId\":\"w\"", "\"widgetId\":\"other\""),
            request.replace("\"slot_id\"", "\"member\":{},\"slot_id\""),
            request.replace("\"sticky_duration_ms\":3600000", "\"sticky_duration_ms\":0"),
            request.replace("\"delay\":20000", "\"delay\":0"),
            request.replace("org.matrix.msc4143.rtc.member", "m.room.message"),
            request.replace("\"content\":", "\"state_key\":\"\",\"content\":"),
        )) {
            val departure = RtcWidgetDeparture("w")
            departure.sent(invalid)
            departure.received(registered)
            assertThat(departure.depart { error("must not send an unowned departure") }).isFalse()
        }
        val departure = RtcWidgetDeparture("w")
        departure.received(registered)
        assertThat(departure.depart { error("unsolicited registration") }).isFalse()
    }

    @Test
    fun `expired registration does not prevent another registered departure`() = runTest {
        val departure = RtcWidgetDeparture("w")
        departure.sent(request)
        departure.received(registered)
        departure.sent(request.replace("\"requestId\":\"r\"", "\"requestId\":\"r2\""))
        departure.received(registered.replace("\"requestId\":\"r\"", "\"requestId\":\"r2\"").replace("own-delay", "new-delay"))
        val sent = mutableListOf<String>()
        val result = async { departure.depart { sent.add(it); true } }
        runCurrent()
        assertThat(sent).hasSize(2)
        departure.received(reply(sent[1]))
        departure.received(reply(sent[0], error = true))
        assertThat(result.await()).isTrue()
    }

    @Test
    fun `rejected transport and error reply never establish departure`() = runTest {
        val departure = RtcWidgetDeparture("w")
        departure.sent(request); departure.received(registered)
        assertThat(departure.depart { false }).isFalse()
        departure.sent(request); departure.received(registered)
        assertThat(departure.depart { departure.received(reply(it, error = true)); true }).isFalse()
    }

    @Test
    fun `missing response is bounded and does not retry or retain pending writes`() = runTest {
        val departure = RtcWidgetDeparture("w")
        departure.sent(request); departure.received(registered)
        var sends = 0
        assertThat(withTimeoutOrNull(900) { departure.depart { sends++; true } }).isNull()
        assertThat(testScheduler.currentTime).isEqualTo(900)
        assertThat(departure.depart { sends++; true }).isFalse()
        assertThat(sends).isEqualTo(1)
    }
}
