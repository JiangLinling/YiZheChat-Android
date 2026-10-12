package io.element.android.libraries.matrix.impl.widget

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RtcWidgetInvitationTest {
    private fun request(widget: String = "call-a", request: String = "r1", type: String = "org.matrix.msc4075.rtc.notification") =
        """{"widgetId":"$widget","api":"fromWidget","action":"send_event","requestId":"$request","data":{"type":"$type","content":{"notification_type":"ring"}}}"""
    private fun response(widget: String = "call-a", request: String = "r1", event: String = "\$invite-a") =
        """{"widgetId":"$widget","api":"fromWidget","action":"send_event","requestId":"$request","response":{"event_id":"$event"}}"""

    @Test fun `binds only actual server acknowledgement for this invitation`() {
        val tracker = RtcWidgetInvitation("call-a")
        tracker.received(response())
        assertThat(tracker.invitation.value).isNull()
        tracker.sent(request())
        tracker.received(response(widget = "call-b"))
        tracker.received(response(request = "unrelated"))
        assertThat(tracker.invitation.value).isNull()
        tracker.received(response())
        assertThat(tracker.invitation.value).isEqualTo("\$invite-a")
        tracker.received(response(event = "\$invite-b"))
        assertThat(tracker.invitation.value).isEqualTo("\$invite-a")
    }

    @Test fun `foreign malformed non invitation and error messages never bind a call`() {
        val tracker = RtcWidgetInvitation("call-a")
        tracker.sent("broken")
        tracker.received("[]")
        tracker.sent(request(widget = "call-b"))
        tracker.sent(request(type = "m.room.message"))
        tracker.received(response())
        assertThat(tracker.invitation.value).isNull()
        tracker.sent(request())
        tracker.received("""{"widgetId":"call-a","api":"fromWidget","action":"send_event","requestId":"r1","response":{"error":"denied","event_id":"${'$'}fake"}}""")
        tracker.received(response())
        assertThat(tracker.invitation.value).isNull()
    }
}
