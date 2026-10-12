package io.element.android.libraries.push.impl.notifications

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.notification.CallIntent
import io.element.android.libraries.matrix.api.notification.NotificationContent
import io.element.android.libraries.matrix.api.room.RoomNotificationMode
import io.element.android.libraries.matrix.api.notification.RtcNotificationAuthority
import io.element.android.libraries.matrix.api.notification.RtcNotificationType
import io.element.android.libraries.matrix.api.room.CurrentUserMembership
import io.element.android.libraries.matrix.api.timeline.item.event.CallLifecycleState
import io.element.android.libraries.matrix.api.timeline.item.event.CallNotifyContent
import io.element.android.libraries.matrix.test.*
import io.element.android.libraries.matrix.test.room.aRoomInfo
import io.element.android.libraries.matrix.test.timeline.anEventTimelineItem
import org.junit.Test

class ForegroundRingingNotificationTest {
    private val info = aRoomInfo(isDirect = true, isDm = true, isEncrypted = true, joinedMembersCount = 2)
    private fun event() = anEventTimelineItem(
        isRemote = true, sender = A_USER_ID_2, timestamp = 99_000,
        content = CallNotifyContent(CallIntent.VIDEO, emptyList()),
    )
    private fun authority() = RtcNotificationAuthority(AN_EVENT_ID, 99_000,
        NotificationContent.MessageLike.RtcNotification(A_USER_ID_2, RtcNotificationType.RING, CallIntent.VIDEO, 189_000))
    @Test fun `decrypted private ring preserves exact invitation and authority expiration`() {
        val notification = recoverForegroundRingingNotification(event(), info, A_SESSION_ID, true, RoomNotificationMode.ALL_MESSAGES, 100_000, authority())
        assertThat(notification?.eventId).isEqualTo(AN_EVENT_ID)
        assertThat((notification?.content as? NotificationContent.MessageLike.RtcNotification)?.expirationTimestampMillis).isEqualTo(189_000)
    }
    @Test fun `disabled unknown and muted settings stay fail closed`() {
        assertThat(recoverForegroundRingingNotification(event(), info, A_SESSION_ID, false, RoomNotificationMode.ALL_MESSAGES, 100_000, authority())).isNull()
        assertThat(recoverForegroundRingingNotification(event(), info, A_SESSION_ID, true, RoomNotificationMode.MUTE, 100_000, authority())).isNull()
        assertThat(recoverForegroundRingingNotification(event(), info, A_SESSION_ID, true, null, 100_000, authority())).isNull()
    }
    @Test fun `terminal own and nonremote projections cannot ring`() {
        val invalid = listOf(event().copy(content = CallNotifyContent(CallIntent.VIDEO, emptyList(), lifecycleState = CallLifecycleState.ENDED)),
            event().copy(isOwn = true), event().copy(isRemote = false), event().copy(timestamp = 99_001))
        invalid.forEach { assertThat(recoverForegroundRingingNotification(it, info, A_SESSION_ID, true, RoomNotificationMode.ALL_MESSAGES, 100_000, authority())).isNull() }
    }
    @Test fun `notify expired mismatched and absent authorities cannot ring`() {
        val invalid = listOf(null, authority().copy(eventId = AN_EVENT_ID_2), authority().copy(timestamp = 99_001),
            authority().copy(content = authority().content.copy(type = RtcNotificationType.NOTIFY)),
            authority().copy(content = authority().content.copy(expirationTimestampMillis = 100_000)),
            authority().copy(content = authority().content.copy(senderId = A_SESSION_ID)))
        invalid.forEach { assertThat(recoverForegroundRingingNotification(event(), info, A_SESSION_ID, true, RoomNotificationMode.ALL_MESSAGES, 100_000, it)).isNull() }
    }
    @Test fun `group multiparty and left room keep recipient policy`() {
        val invalid = listOf(info.copy(isDirect = false, isDm = false), info.copy(joinedMembersCount = 3),
            info.copy(currentUserMembership = CurrentUserMembership.LEFT))
        invalid.forEach { assertThat(recoverForegroundRingingNotification(event(), it, A_SESSION_ID, true, RoomNotificationMode.ALL_MESSAGES, 100_000, authority())).isNull() }
    }
}
