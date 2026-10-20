package io.element.android.libraries.matrix.impl.widget

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Test

class RtcGroupRingingRecipientsTest {
    private fun request(kind: String = "ring", room: Boolean = true) =
        """{"api":"fromWidget","action":"send_event","widgetId":"a","requestId":"b","data":{"type":"org.matrix.msc4075.rtc.notification","content":{"notification_type":"$kind","slot_id":"m.call#ROOM","m.mentions":{"room":$room,"user_ids":["@existing:test"]},"m.relates_to":{"event_id":"parent"}}}}"""

    @Test fun `group ring mentions every joined recipient and preserves invitation identity`() = runTest {
        val recipients = (1..12).map { "@member$it:test" }
        val before = Json.parseToJsonElement(request()).jsonObject
        val after = Json.parseToJsonElement(prepareGroupRingingMessage(request()) { recipients + recipients.first() }).jsonObject
        assertThat(after["requestId"]).isEqualTo(before["requestId"])
        val content = after["data"]!!.jsonObject["content"]!!.jsonObject
        assertThat(content["m.mentions"]!!.jsonObject["user_ids"]!!.jsonArray.map { it.jsonPrimitive.content }).containsExactlyElementsIn(recipients)
        assertThat(content["m.mentions"]!!.jsonObject.containsKey("room")).isFalse()
        assertThat(content["m.relates_to"]).isEqualTo(before["data"]!!.jsonObject["content"]!!.jsonObject["m.relates_to"])
    }

    @Test fun `joining announcements and already explicit recipients remain untouched`() = runTest {
        for (message in listOf(request("notification"), request(room = false), "broken")) {
            assertThat(prepareGroupRingingMessage(message) { error("must not fetch") }).isEqualTo(message)
        }
    }

    @Test fun `direct call targets its peer without requiring room mention power`() = runTest {
        val before = Json.parseToJsonElement(request()).jsonObject
        val after = Json.parseToJsonElement(prepareGroupRingingMessage(request()) { listOf("@peer:test") }).jsonObject
        val data = after["data"]!!.jsonObject
        val content = data["content"]!!.jsonObject
        assertThat(content["m.mentions"]).isEqualTo(buildJsonObject {
            putJsonArray("user_ids") { add("@peer:test") }
        })
        assertThat(JsonObject(content - "m.mentions")).isEqualTo(JsonObject(before["data"]!!.jsonObject["content"]!!.jsonObject - "m.mentions"))
        assertThat(JsonObject(after - "data")).isEqualTo(JsonObject(before - "data"))
    }
}
