/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.messages.impl.timeline.components

import com.google.common.truth.Truth.assertThat
import io.element.android.features.messages.impl.R
import io.element.android.features.messages.impl.timeline.factories.event.toRtcNotificationState
import io.element.android.features.messages.impl.timeline.model.event.RtcNotificationState
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.notification.CallIntent
import io.element.android.libraries.matrix.api.timeline.item.event.CallLifecycleState
import io.element.android.libraries.matrix.api.timeline.item.event.CallNotifyContent
import org.junit.Test

class RtcNotificationActorTest {
    private val caller = UserId("@caller:example.test")
    private val receiver = UserId("@receiver:example.test")

    @Test fun `outgoing protected refusal retains receiver and never names invitation sender`() {
        val state = notify(CallLifecycleState.DECLINED, receiver).toRtcNotificationState(caller) as RtcNotificationState.Declined
        assertThat(state.actors).containsExactly(receiver)
        assertThat(state.byMe).isFalse()
        assertThat(state.terminalDetailResource(true)).isEqualTo(R.string.screen_room_call_detail_declined)
    }

    @Test fun `incoming protected self refusal shows you`() {
        val state = notify(CallLifecycleState.DECLINED, receiver).toRtcNotificationState(receiver) as RtcNotificationState.Declined
        assertThat(state.actors).containsExactly(receiver)
        assertThat(state.byMe).isTrue()
        assertThat(state.terminalDetailResource(true)).isEqualTo(R.string.screen_room_call_detail_declined_by_you)
    }

    @Test fun `legacy refusal retains distinct actors on both clients`() {
        val content = CallNotifyContent(CallIntent.AUDIO, listOf(receiver, receiver))
        val outgoing = content.toRtcNotificationState(caller) as RtcNotificationState.Declined
        val incoming = content.toRtcNotificationState(receiver) as RtcNotificationState.Declined
        assertThat(outgoing.actors).containsExactly(receiver)
        assertThat(outgoing.byMe).isFalse()
        assertThat(incoming.byMe).isTrue()
    }

    @Test fun `legacy multiple actors do not collapse to invitation sender`() {
        val other = UserId("@other:example.test")
        val state = CallNotifyContent(CallIntent.VIDEO, listOf(receiver, other)).toRtcNotificationState(caller) as RtcNotificationState.Declined
        assertThat(state.actors).containsExactly(receiver, other).inOrder()
        assertThat(state.terminalDetailResource(true)).isEqualTo(R.string.screen_room_call_detail_declined)
        assertThat(state.terminalDetailResource(false)).isNull()
    }

    @Test fun `missing protected actor remains unknown even with stale legacy self refusal`() {
        val state = notify(CallLifecycleState.DECLINED, null).copy(declinedBy = listOf(caller))
            .toRtcNotificationState(caller) as RtcNotificationState.Declined
        assertThat(state.actors).isEmpty()
        assertThat(state.byMe).isFalse()
        assertThat(state.terminalDetailResource(true)).isEqualTo(R.string.screen_room_call_detail_declined)
    }

    @Test fun `cancelled actor and cancelled semantics remain independent of decline`() {
        val content = notify(CallLifecycleState.CANCELLED, caller).copy(declinedBy = listOf(receiver))
        val outgoing = content.toRtcNotificationState(caller) as RtcNotificationState.Cancelled
        val incoming = content.toRtcNotificationState(receiver) as RtcNotificationState.Cancelled
        assertThat(outgoing.actor).isEqualTo(caller)
        assertThat(outgoing.byMe).isTrue()
        assertThat(outgoing.terminalDetailResource(true)).isEqualTo(R.string.screen_room_call_detail_cancelled_by_you)
        assertThat(incoming.terminalDetailResource(true)).isEqualTo(R.string.screen_room_call_detail_cancelled)
    }

    @Test fun `missed actor is retained without attributing a refusal`() {
        val state = notify(CallLifecycleState.MISSED, receiver).toRtcNotificationState(caller) as RtcNotificationState.Missed
        assertThat(state.actor).isEqualTo(receiver)
        assertThat(state.byMe).isFalse()
        assertThat(state.terminalDetailResource(true)).isEqualTo(R.string.screen_room_call_detail_missed)
    }

    @Test fun `unknown cancellation and missed actor remain neutral`() {
        for (status in listOf(CallLifecycleState.CANCELLED, CallLifecycleState.MISSED)) {
            val state = notify(status, null).toRtcNotificationState(caller)
            assertThat(state.terminalDetailResource(true)).isIn(listOf(
                R.string.screen_room_call_detail_cancelled, R.string.screen_room_call_detail_missed,
            ))
            assertThat(state.terminalDetailResource(false)).isNull()
        }
    }

    @Test fun `group terminal states have no individual actor detail`() {
        for (status in listOf(CallLifecycleState.DECLINED, CallLifecycleState.CANCELLED, CallLifecycleState.MISSED)) {
            assertThat(notify(status, receiver).toRtcNotificationState(receiver).terminalDetailResource(false)).isNull()
        }
    }

    @Test fun `started connected ended and duration retain existing semantics`() {
        assertThat(CallNotifyContent(CallIntent.AUDIO, emptyList()).toRtcNotificationState(caller)).isEqualTo(RtcNotificationState.Started)
        assertThat(notify(CallLifecycleState.CONNECTED, receiver).toRtcNotificationState(caller)).isEqualTo(RtcNotificationState.Connected)
        assertThat(notify(CallLifecycleState.ENDED, receiver).copy(durationMillis = 65000).toRtcNotificationState(caller))
            .isEqualTo(RtcNotificationState.Ended(65000))
    }

    private fun notify(state: CallLifecycleState, actor: UserId?) = CallNotifyContent(
        callIntent = CallIntent.AUDIO, declinedBy = emptyList(), lifecycleState = state, lifecycleActor = actor,
    )
}
