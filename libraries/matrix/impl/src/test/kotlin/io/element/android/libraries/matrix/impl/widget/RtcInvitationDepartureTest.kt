package io.element.android.libraries.matrix.impl.widget

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.*
import org.junit.Test

class RtcInvitationDepartureTest {
    private fun event(id: String, type: String, time: Long, body: JsonObject, sender: String = "caller", room: String = "room") = buildJsonObject {
        put("room_id", room); put("event_id", "$$id"); put("type", type); put("sender", sender)
        put("origin_server_ts", time); put("content", body)
    }
    private fun member(key: String = "device-attempt-a", joined: Boolean = true) = buildJsonObject {
        put("slot_id", "slot"); put("msc4354_sticky_key", key)
        if (joined) put("device_id", "device")
    }
    private fun notification(parent: String = "join") = buildJsonObject {
        put("slot_id", "slot"); put("notification_type", "ring")
        putJsonObject("m.relates_to") { put("rel_type", "m.reference"); put("event_id", "$$parent") }
    }
    @Test fun `only exact parent member leave ends invitation in either snapshot order`() {
        val join = event("join", NEXT_RTC_MEMBER_EVENT_TYPE, 10, member())
        val invite = event("invite", "org.matrix.msc4075.rtc.notification", 20, notification())
        val leave = event("leave", NEXT_RTC_MEMBER_EVENT_TYPE, 30, member(joined = false))
        for (order in listOf(listOf(join,invite,leave),listOf(leave,invite,join))) {
            val tracker = RtcInvitationDeparture("room", "\$invite")
            assertThat(tracker.observe(order[0])).isFalse()
            assertThat(tracker.observe(order[1])).isFalse()
            assertThat(tracker.observe(order[2])).isTrue()
        }
    }
    @Test fun `foreign sender room previous attempt old leave and missing parent cannot cancel`() {
        val tracker = RtcInvitationDeparture("room", "\$invite")
        tracker.observe(event("invite", "org.matrix.msc4075.rtc.notification", 20, notification()))
        assertThat(tracker.observe(event("leave", NEXT_RTC_MEMBER_EVENT_TYPE, 30, member(joined = false)))).isFalse()
        // Each counterexample starts with a fully bound invitation and no matching departure.
        for (bad in listOf(
            event("bad", NEXT_RTC_MEMBER_EVENT_TYPE, 30, member(joined = false), sender = "other"),
            event("bad", NEXT_RTC_MEMBER_EVENT_TYPE, 30, member(joined = false), room = "other"),
            event("bad", NEXT_RTC_MEMBER_EVENT_TYPE, 30, member(key = "old-attempt", joined = false)),
            event("bad", NEXT_RTC_MEMBER_EVENT_TYPE, 15, member(joined = false)),
            event("bad", NEXT_RTC_MEMBER_EVENT_TYPE, 30, member()),
        )) {
            val check = RtcInvitationDeparture("room", "\$invite")
            check.observe(event("join", NEXT_RTC_MEMBER_EVENT_TYPE, 10, member()))
            check.observe(event("invite", "org.matrix.msc4075.rtc.notification", 20, notification()))
            assertThat(check.observe(bad)).isFalse()
        }
    }
}
