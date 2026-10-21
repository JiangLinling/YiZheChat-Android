/* SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.messages.impl.timeline

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.UniqueId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.notification.CallIntent
import io.element.android.libraries.matrix.api.notification.RtcNotificationType
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.item.event.CallLifecycleState
import io.element.android.libraries.matrix.api.timeline.item.event.CallNotifyContent
import io.element.android.libraries.matrix.test.timeline.anEventTimelineItem
import org.junit.Test

class DirectCallSignalsTest {
    @Test
    fun `direct timeline suppresses only the bare widget join announcement`() {
        val signal = item(RtcNotificationType.NOTIFY)
        val ring = item(RtcNotificationType.RING)
        val legacy = item(null)
        val terminal = item(RtcNotificationType.NOTIFY, CallLifecycleState.ENDED)
        val declined = item(RtcNotificationType.NOTIFY, declined = listOf(UserId("@peer:test")))
        val ordinary = MatrixTimelineItem.Event(UniqueId("ordinary"), anEventTimelineItem())
        val input = listOf(signal, ring, legacy, terminal, declined, ordinary, MatrixTimelineItem.Other)
        assertThat(input.withoutDirectCallSignals(true)).containsExactly(ring, legacy, terminal, declined, ordinary, MatrixTimelineItem.Other).inOrder()
        assertThat(input).hasSize(7)
    }

    @Test
    fun `group timeline retains join announcements and all call records`() {
        val input = listOf(item(RtcNotificationType.NOTIFY), item(RtcNotificationType.RING), item(null))
        assertThat(input.withoutDirectCallSignals(false)).isSameInstanceAs(input)
    }

    private fun item(type: RtcNotificationType?, state: CallLifecycleState? = null, declined: List<UserId> = emptyList()) =
        MatrixTimelineItem.Event(UniqueId("$type-$state-$declined"), anEventTimelineItem(content =
            CallNotifyContent(CallIntent.VIDEO, declined, lifecycleState = state, notificationType = type)))
}
