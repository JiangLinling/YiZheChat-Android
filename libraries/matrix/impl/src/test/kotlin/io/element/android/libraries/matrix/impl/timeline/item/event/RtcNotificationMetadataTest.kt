/* SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.libraries.matrix.impl.timeline.item.event

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.notification.RtcNotificationType
import org.junit.Test

class RtcNotificationMetadataTest {
    @Test
    fun `recognizes only explicit RTC ring and notification metadata`() {
        assertThat(rtcNotificationTypeFromSource(event("ring"))).isEqualTo(RtcNotificationType.RING)
        assertThat(rtcNotificationTypeFromSource(event("notification"))).isEqualTo(RtcNotificationType.NOTIFY)
    }

    @Test
    fun `unavailable malformed unknown and oversized metadata preserves legacy behavior`() {
        listOf(null, "", "[]", "{", event("future"), event("notification").replace("notification_type", "other"),
            event("notification").replace("org.matrix.msc4075.rtc.notification", "m.room.message"),
            event("notification") + " ".repeat(16_384)).forEach {
            assertThat(rtcNotificationTypeFromSource(it)).isNull()
        }
    }

    private fun event(type: String) =
        """{"type":"org.matrix.msc4075.rtc.notification","content":{"notification_type":"$type","call_intent":"video"}}"""
}
