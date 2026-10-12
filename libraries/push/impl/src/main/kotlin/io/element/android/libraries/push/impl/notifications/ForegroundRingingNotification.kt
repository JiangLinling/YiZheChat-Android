package io.element.android.libraries.push.impl.notifications

import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.api.notification.NotificationContent
import io.element.android.libraries.matrix.api.notification.NotificationData
import io.element.android.libraries.matrix.api.notification.RtcNotificationType
import io.element.android.libraries.matrix.api.notification.RtcNotificationAuthority
import io.element.android.libraries.matrix.api.room.CurrentUserMembership
import io.element.android.libraries.matrix.api.room.RoomInfo
import io.element.android.libraries.matrix.api.room.RoomNotificationMode
import io.element.android.libraries.matrix.api.timeline.item.event.CallNotifyContent
import io.element.android.libraries.matrix.api.timeline.item.event.EventTimelineItem
import io.element.android.libraries.matrix.api.timeline.item.event.ProfileDetails

/** A visible authenticated RING can race the SDK's read/push-rule filtering.
 * Only recover its exact protocol metadata, while respecting call and room settings.
 * No message preview, new invitation, lifetime or notification authorization is fabricated. */
internal fun recoverForegroundRingingNotification(
    event: EventTimelineItem,
    info: RoomInfo,
    sessionId: SessionId,
    callsEnabled: Boolean,
    mode: RoomNotificationMode?,
    now: Long,
    authority: RtcNotificationAuthority?,
): NotificationData? {
    if (!callsEnabled || mode == null || mode == RoomNotificationMode.MUTE || event.isOwn || !event.isRemote) return null
    if (!(info.isDm || info.isDirect) || info.joinedMembersCount != 2L || info.isSpace ||
        info.currentUserMembership != CurrentUserMembership.JOINED) return null
    val call = event.content as? CallNotifyContent ?: return null
    if (call.lifecycleState != null || sessionId in call.declinedBy) return null
    val id = event.eventId ?: return null
    if (authority == null || authority.eventId != id || authority.content.senderId != event.sender) return null
    val timestamp = authority.timestamp
    if (timestamp != event.timestamp || timestamp > now || now - timestamp >= 90_000) return null
    val content = authority.content
    if (content.type != RtcNotificationType.RING) return null
    val intent = content.callIntent
    if (intent != call.callIntent) return null
    if (content.expirationTimestampMillis <= now || content.expirationTimestampMillis - timestamp !in 1..90_000) return null
    val profile = event.senderProfile as? ProfileDetails.Ready
    return NotificationData(
        sessionId = sessionId, eventId = id, threadId = null, roomId = info.id,
        senderAvatarUrl = profile?.avatarUrl, senderDisplayName = profile?.displayName,
        senderIsNameAmbiguous = profile?.displayNameAmbiguous ?: true,
        roomAvatarUrl = info.avatarUrl, roomDisplayName = info.name,
        isDirect = info.isDirect, isDm = info.isDm, isSpace = info.isSpace,
        isEncrypted = info.isEncrypted == true, isNoisy = false, timestamp = timestamp,
        content = content,
        hasMention = true, roomJoinRule = info.joinRule,
    )
}
