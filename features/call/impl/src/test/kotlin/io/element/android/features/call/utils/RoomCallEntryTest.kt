package io.element.android.features.call.utils

import com.google.common.truth.Truth.assertThat
import io.element.android.features.call.api.CallData
import io.element.android.features.call.impl.utils.IntentProvider
import io.element.android.libraries.matrix.test.A_ROOM_ID
import io.element.android.libraries.matrix.test.A_SESSION_ID
import org.junit.Test

class RoomCallEntryTest {
    private val requested = CallData(A_SESSION_ID, A_ROOM_ID, isAudioCall = false)
    private val invitation = requested.copy(notificationEventId = "incoming", attemptId = "incoming", isAudioCall = true)

    @Test
    fun `room entry keeps active invitation identity and media intent`() {
        assertThat(IntentProvider.forRoomEntry(requested, invitation)).isEqualTo(invitation)
    }

    @Test
    fun `explicit older invitation is never rebound to current invitation`() {
        val old = invitation.copy(notificationEventId = "old", attemptId = "old")
        assertThat(IntentProvider.forRoomEntry(old, invitation)).isEqualTo(old)
    }

    @Test
    fun `unrelated room cannot acquire another room invitation`() {
        val other = invitation.copy(roomId = io.element.android.libraries.matrix.api.core.RoomId("!other:example.org"))
        assertThat(IntentProvider.forRoomEntry(requested, other)).isEqualTo(requested)
        assertThat(IntentProvider.forRoomEntry(requested, null)).isEqualTo(requested)
    }
}
