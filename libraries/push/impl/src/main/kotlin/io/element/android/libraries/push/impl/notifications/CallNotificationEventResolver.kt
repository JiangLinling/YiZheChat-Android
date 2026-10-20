/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.impl.notifications

import android.os.Build
import io.element.android.appconfig.NextDeploymentConfig
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.matrix.api.MatrixClientProvider
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.api.exception.NotificationResolverException
import io.element.android.libraries.matrix.api.notification.CallIntent
import io.element.android.libraries.matrix.api.notification.NotificationContent
import io.element.android.libraries.matrix.api.notification.NotificationData
import io.element.android.libraries.matrix.api.notification.RtcNotificationType
import io.element.android.libraries.matrix.api.timeline.item.event.EventType
import io.element.android.libraries.push.impl.R
import io.element.android.libraries.push.impl.notifications.model.NotifiableEvent
import io.element.android.libraries.push.impl.notifications.model.NotifiableRingingCallEvent
import io.element.android.services.appnavstate.api.AppForegroundStateService
import io.element.android.services.toolbox.api.strings.StringProvider
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import kotlin.time.Duration.Companion.seconds

/**
 * Helper to resolve a valid [NotifiableEvent] from a [NotificationData].
 */
interface CallNotificationEventResolver {
    /**
     * Resolve a call notification event from a notification data depending on whether it should be a ringing one or not.
     * @param sessionId the current session id
     * @param notificationData the notification data
     * @param forceNotify `true` to force the notification to be non-ringing, `false` to use the default behaviour. Default is `false`.
     * @return a [NotifiableEvent] if the notification data is a call notification, null otherwise
     */
    suspend fun resolveEvent(
        sessionId: SessionId,
        notificationData: NotificationData,
        forceNotify: Boolean = false,
    ): Result<NotifiableEvent>
}

@ContributesBinding(AppScope::class)
class DefaultCallNotificationEventResolver(
    private val stringProvider: StringProvider,
    private val appForegroundStateService: AppForegroundStateService,
    private val clientProvider: MatrixClientProvider,
) : CallNotificationEventResolver {
    override suspend fun resolveEvent(
        sessionId: SessionId,
        notificationData: NotificationData,
        forceNotify: Boolean
    ): Result<NotifiableEvent> = runCatchingExceptions {
        val content = notificationData.content as? NotificationContent.MessageLike.RtcNotification
            ?: throw NotificationResolverException.UnknownError("content is not a call notify")

        // Intentional user mentions make modern RTC invitations eligible for push. They must
        // still obey the user's call switch and room mute policy (the legacy call rule only
        // matches m.call.invite). Check outside the projection fallback so a policy denial
        // can never turn into optimistic ringing.
        if (content.type == RtcNotificationType.RING) {
            val client = clientProvider.getOrRestore(sessionId).getOrNull()
                ?: throw NotificationResolverException.EventFilteredOut
            val settings = client.notificationSettingsService
            val mode = settings.getRoomNotificationSettings(
                notificationData.roomId, notificationData.isEncrypted, notificationData.isDm,
            ).getOrNull()?.mode
            if (settings.isCallEnabled().getOrNull() != true || mode == null ||
                mode == io.element.android.libraries.matrix.api.room.RoomNotificationMode.MUTE ||
                content.senderId in client.ignoredUsersFlow.value
            ) throw NotificationResolverException.EventFilteredOut
        }

        val isProjectionLagProneManufacturer = sequenceOf(Build.MANUFACTURER, Build.BRAND).any { value ->
            val normalizedValue = value.orEmpty().trim()
            normalizedValue.equals("huawei", ignoreCase = true) ||
                normalizedValue.equals("honor", ignoreCase = true) ||
                normalizedValue.equals("xiaomi", ignoreCase = true) ||
                normalizedValue.equals("redmi", ignoreCase = true)
        }
        val previousRingingCallStatus = appForegroundStateService.hasRingingCall.value
        // We need the sync service working to get the updated room info
        val isRoomCallActive = runCatchingExceptions {
            if (content.type == RtcNotificationType.RING) {
                appForegroundStateService.updateHasRingingCall(true)

                val client = clientProvider.getOrRestore(
                    sessionId
                ).getOrNull() ?: throw NotificationResolverException.UnknownError("Session $sessionId not found")
                val room = client.getRoom(
                    notificationData.roomId
                ) ?: throw NotificationResolverException.UnknownError("Room ${notificationData.roomId} not found")
                val currentRoomInfo = room.info()
                val hasCallParticipants = currentRoomInfo.activeRoomCallParticipants.isNotEmpty()
                val senderInCallParticipants = currentRoomInfo.activeRoomCallParticipants.contains(content.senderId)
                // Give a few seconds for the room info flow to catch up with the sync, if needed - this is usually instant.
                // On some Huawei/HarmonyOS cases, room info can be unavailable before timeout.
                // The decrypted fresh RING already authorizes the existing OEM fallback.
                // Do not delay that same decision behind a lagging membership projection.
                // Exact invitation/terminal-event validation remains in ActiveCallManager.
                val immediateRinging = shouldUseImmediateRinging(
                    isNextDeployment = NextDeploymentConfig.isNextDeployment,
                    isProjectionLagProneManufacturer = isProjectionLagProneManufacturer,
                    expiration = content.expirationTimestampMillis,
                    now = System.currentTimeMillis(),
                )
                val isActive = if (immediateRinging) {
                    true
                } else {
                    withTimeoutOrNull(3.seconds) { room.roomInfoFlow.firstOrNull { it.hasRoomCall } }?.hasRoomCall
                }
                val resolvedIsActive = when {
                    isActive == true -> true
                    isActive == false -> false
                    else -> {
                        val now = System.currentTimeMillis()
                        val expiration = content.expirationTimestampMillis
                        val isExpired = expiration > 0 && expiration < now
                        val hasFallbackByMembers = hasCallParticipants && senderInCallParticipants
                        if (isExpired) {
                            Timber.d(
                                "RING call room state timeout for room ${notificationData.roomId}, " +
                                    "push expired at $expiration, current time $now"
                            )
                        } else {
                            Timber.d(
                                "RING call room state timeout for room ${notificationData.roomId}, " +
                                    "fallback to optimistic ringing; hasActiveCallParticipants=$hasCallParticipants, " +
                                    "senderInCallParticipants=$senderInCallParticipants"
                            )
                        }
                        shouldUseOptimisticRinging(
                            isProjectionLagProneManufacturer = isProjectionLagProneManufacturer,
                            isExpired = isExpired,
                            hasFallbackByMembers = hasFallbackByMembers,
                        )
                    }
                }

                // We no longer need the sync service to be active because of a call notification.
                appForegroundStateService.updateHasRingingCall(previousRingingCallStatus)

                resolvedIsActive
            } else {
                // If the call notification is not of ringing type, we don't need to check if the call is active
                false
            }
        }.recoverCatching { exception ->
            // Make sure to reset the hasRingingCall state in case of failure
            Timber.w(exception, "Failed to resolve ringing call state for room ${notificationData.roomId}")
            appForegroundStateService.updateHasRingingCall(previousRingingCallStatus)
            val shouldFallbackToRinging = content.type == RtcNotificationType.RING &&
                (content.expirationTimestampMillis == 0L || content.expirationTimestampMillis > System.currentTimeMillis())
            val fallbackRoom = runCatching {
                clientProvider.getOrRestore(sessionId)
                    .getOrNull()
                    ?.getRoom(notificationData.roomId)
            }.getOrNull()
            val roomInfo = runCatching { fallbackRoom?.info() }.getOrNull()
            val hasActiveCallParticipants = roomInfo?.activeRoomCallParticipants?.isNotEmpty() == true
            val senderInCallParticipants = roomInfo?.activeRoomCallParticipants?.contains(content.senderId) == true
            val now = System.currentTimeMillis()
            Timber.w(
                "RING call state resolution failed for room ${notificationData.roomId}. " +
                    "Falling back only when the sender is in the authoritative room call projection, now=$now, " +
                    "expiration=${content.expirationTimestampMillis}, activeParticipants=$hasActiveCallParticipants, " +
                    "senderInParticipants=$senderInCallParticipants"
            )
            shouldFallbackToRinging && shouldUseFailureFallbackRinging(
                isExpired = false,
                hasFallbackByMembers = hasActiveCallParticipants && senderInCallParticipants,
            )
        }.getOrDefault(false)

        notificationData.run {
            if (content.type == RtcNotificationType.RING && isRoomCallActive && !forceNotify) {
                Timber.d("Ringing call notification intent ${content.callIntent} in room $roomId")
                NotifiableRingingCallEvent(
                    sessionId = sessionId,
                    roomId = roomId,
                    eventId = eventId,
                    roomName = roomDisplayName,
                    editedEventId = null,
                    canBeReplaced = true,
                    timestamp = this.timestamp,
                    isRedacted = false,
                    isUpdated = false,
                    description = if (content.callIntent ==
                        CallIntent.AUDIO) {
                            stringProvider.getString(R.string.notification_incoming_audio_call)
                        } else {
                            stringProvider.getString(
                        R.string.notification_incoming_call
                    )
                        },
                    senderDisambiguatedDisplayName = getDisambiguatedDisplayName(content.senderId),
                    roomAvatarUrl = roomAvatarUrl,
                    rtcNotificationType = content.type,
                    callIntent = content.callIntent,
                    senderId = content.senderId,
                    senderAvatarUrl = senderAvatarUrl,
                    expirationTimestamp = content.expirationTimestampMillis,
                )
            } else {
                Timber.d("Event $eventId is call notify but should not ring: $isRoomCallActive, notify: ${content.type}")
                // Create a simple message notification event
                buildNotifiableMessageEvent(
                    sessionId = sessionId,
                    senderId = content.senderId,
                    roomId = roomId,
                    eventId = eventId,
                    noisy = true,
                    timestamp = this.timestamp,
                    senderDisambiguatedDisplayName = getDisambiguatedDisplayName(content.senderId),
                    body = if (content.callIntent == CallIntent.VIDEO) {
                        stringProvider.getString(R.string.notification_incoming_call)
                    } else {
                        stringProvider.getString(R.string.notification_incoming_audio_call)
                    },
                    roomName = roomDisplayName,
                    roomIsDm = isDm,
                    roomAvatarPath = roomAvatarUrl,
                    senderAvatarPath = senderAvatarUrl,
                    type = EventType.RTC_NOTIFICATION,
                )
            }
        }
    }
}

/**
 * Some OEM background-process implementations can wake the app before the room-call membership
 * projection has caught up. At this point the event has already been fetched, decrypted, and
 * identified as an unexpired Matrix RING notification, so waiting for the redundant membership
 * projection would incorrectly downgrade a real incoming call to an ordinary message notification.
 */
internal fun shouldUseImmediateRinging(
    isNextDeployment: Boolean,
    isProjectionLagProneManufacturer: Boolean,
    expiration: Long,
    now: Long,
): Boolean = isNextDeployment && isProjectionLagProneManufacturer && expiration > now

internal fun shouldUseOptimisticRinging(
    isProjectionLagProneManufacturer: Boolean,
    isExpired: Boolean,
    hasFallbackByMembers: Boolean,
): Boolean = !isExpired && (hasFallbackByMembers || isProjectionLagProneManufacturer)

/**
 * An OEM-specific projection delay is only safe to use after the session and room were resolved.
 * If resolution itself failed, require the sender to be present in the room call projection.
 */
internal fun shouldUseFailureFallbackRinging(
    isExpired: Boolean,
    hasFallbackByMembers: Boolean,
): Boolean = !isExpired && hasFallbackByMembers
