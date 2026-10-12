package io.element.android.libraries.matrix.impl.widget

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.UserId
import kotlinx.serialization.json.*
import org.junit.Test

class RtcRoomCallMembershipTest {
    private val room = "!room:test"
    private fun event(user: String = "@one:test", key: String = "one", at: Long = 1000, leave: Boolean = false) = buildJsonObject {
        put("room_id", room); put("type", NEXT_RTC_MEMBER_EVENT_TYPE); put("sender", user)
        put("event_id", "${'$'}$key$at"); put("origin_server_ts", at)
        putJsonObject("msc4354_sticky") { put("duration_ms", 10000) }
        putJsonObject("content") {
            put("slot_id", "m.call#ROOM"); put("msc4354_sticky_key", key)
            if (!leave) {
                putJsonObject("member") { put("user_id", user); put("device_id", key); put("id", key) }
                putJsonObject("application") { put("type", "m.call"); put("m.call.intent", "video") }
            }
        }
    }

    @Test fun `banner remains until the final participant leaves`() {
        val state = RtcRoomCallMembership(room)
        state.observe(event()); state.observe(event("@two:test", "two"))
        state.observe(event(at = 2000, leave = true))
        assertThat(state.snapshot(2000).participants).containsExactly(UserId("@two:test"))
        state.observe(event("@two:test", "two", 3000, true))
        assertThat(state.snapshot(3000).participants).isEmpty()
        state.observe(event())
        assertThat(state.snapshot(3000).participants).isEmpty()
    }

    @Test fun `server expiry is absolute and duplicate devices count once`() {
        val state = RtcRoomCallMembership(room)
        state.observe(event()); state.observe(event(key = "other-device"))
        assertThat(state.snapshot(5000).participants).containsExactly(UserId("@one:test"))
        assertThat(state.snapshot(11000).participants).isEmpty()
        state.observe(event())
        assertThat(state.snapshot(12000).participants).isEmpty()
    }

    @Test fun `foreign room spoofed sender and missing expiry never create a banner`() {
        val state = RtcRoomCallMembership(room)
        state.observe(JsonObject(event() + ("room_id" to JsonPrimitive("!other:test"))))
        state.observe(JsonObject(event() + ("sender" to JsonPrimitive("@spoof:test"))))
        state.observe(JsonObject(event() - "msc4354_sticky"))
        assertThat(state.snapshot(2000).participants).isEmpty()
    }
}
