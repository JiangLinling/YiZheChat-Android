package io.element.android.features.call.utils

import com.google.common.truth.Truth.assertThat
import io.element.android.features.call.api.CallData
import io.element.android.features.call.impl.utils.IntentProvider
import io.element.android.libraries.matrix.test.*
import org.junit.Test

class InvitationIntentTest {
    @Test fun `old notification cannot replace a new call screen`() {
        val a = CallData(A_SESSION_ID, A_ROOM_ID, false, AN_EVENT_ID.value)
        val b = a.copy(notificationEventId = AN_EVENT_ID_2.value, attemptId = AN_EVENT_ID_2.value)
        assertThat(IntentProvider.canOpenInvitation(a, a)).isTrue()
        assertThat(IntentProvider.canOpenInvitation(a, b)).isFalse()
        assertThat(IntentProvider.canOpenInvitation(a, null)).isFalse()
        assertThat(IntentProvider.canOpenInvitation(a, a.copy(roomId = A_ROOM_ID_2))).isFalse()
        assertThat(IntentProvider.canOpenInvitation(a.copy(notificationEventId = null), null)).isTrue()
    }
}
