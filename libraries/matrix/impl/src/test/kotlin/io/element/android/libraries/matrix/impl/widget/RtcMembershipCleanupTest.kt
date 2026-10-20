package io.element.android.libraries.matrix.impl.widget

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.*
import org.junit.Test

class RtcMembershipCleanupTest {
    private val id = "${'$'}join"
    private val owner = "@owner:test"
    private fun event(content: JsonObject = Json.parseToJsonElement(rtcMembershipRedactionContent(id)).jsonObject): JsonObject = buildJsonObject {
        put("event_id", id); put("sender", owner); put("type", "m.room.encrypted"); putJsonObject("content") {}
        putJsonObject("unsigned") {
            putJsonObject("redacted_because") {
                put("event_id", "${'$'}redaction"); put("sender", owner); put("type", "m.room.redaction")
                put("content", JsonObject(content + ("redacts" to JsonPrimitive(id))))
            }
        }
    }
    @Test fun `only accepted exact own RTC cleanup is omitted from timeline`() {
        assertThat(isRtcMembershipCleanup(event().toString(), id, owner)).isTrue()
        assertThat(isRtcMembershipCleanup(event(JsonObject(emptyMap())).toString(), id, owner)).isFalse()
        for ((before, after) in listOf(
            "org.matrix.msc4143.rtc.member" to "m.room.message",
            "\"sender\":\"@owner:test\",\"type\":\"m.room.redaction\"" to "\"sender\":\"@other:test\",\"type\":\"m.room.redaction\"",
            "\"redacts\":\"${'$'}join\"" to "\"redacts\":\"${'$'}other\"",
            "\"content\":{}" to "\"content\":{\"body\":\"real message\"}",
            "m.room.encrypted" to "m.room.message",
        )) {
            val raw = event().toString()
            check(before in raw)
            assertThat(isRtcMembershipCleanup(raw.replace(before, after), id, owner)).isFalse()
        }
        assertThat(isRtcMembershipCleanup(event().toString(), "${'$'}other", owner)).isFalse()
        assertThat(isRtcMembershipCleanup(null, id, owner)).isFalse()
        assertThat(isRtcMembershipCleanup("invalid", id, owner)).isFalse()
    }
}
