/* SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.libraries.matrix.impl.timeline.item.event

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.notification.CallIntent
import io.element.android.libraries.matrix.api.notification.RtcNotificationType
import io.element.android.libraries.matrix.api.timeline.item.event.CallNotifyContent
import io.element.android.libraries.matrix.impl.fixtures.factories.aRustEventTimelineItem
import io.element.android.libraries.matrix.impl.fixtures.factories.anEventTimelineItemDebugInfo
import io.element.android.libraries.matrix.impl.fixtures.fakes.FakeFfiLazyTimelineItemProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Test

class RtcNotificationTimelineMappingTest {
    @Test
    fun `mapped RTC content keeps the same event identity and receives decrypted protocol type`() {
        val mapper = mockk<TimelineEventContentMapper>()
        every { mapper.map(any()) } returns CallNotifyContent(CallIntent.VIDEO, emptyList())
        val event = aRustEventTimelineItem(lazyProvider = FakeFfiLazyTimelineItemProvider(debugInfo = anEventTimelineItemDebugInfo(
            originalJson = """{"type":"org.matrix.msc4075.rtc.notification","content":{"notification_type":"notification"}}""",
        )))
        val result = EventTimelineItemMapper(mapper).map(event)
        assertThat((result.content as CallNotifyContent).notificationType).isEqualTo(RtcNotificationType.NOTIFY)
        assertThat(result.sender.value).isEqualTo(event.sender)
        assertThat((result.content as CallNotifyContent).callIntent).isEqualTo(CallIntent.VIDEO)
    }
}
