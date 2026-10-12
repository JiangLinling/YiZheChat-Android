/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.features.call.impl.notifications

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.app.Person
import dev.zacsweers.metro.Inject
import io.element.android.appconfig.ElementCallConfig
import io.element.android.features.call.api.CallData
import io.element.android.features.call.impl.receivers.DeclineCallBroadcastReceiver
import io.element.android.features.call.impl.ui.IncomingCallActivity
import io.element.android.features.call.impl.utils.IntentProvider
import io.element.android.libraries.designsystem.components.avatar.AvatarData
import io.element.android.libraries.designsystem.components.avatar.AvatarSize
import io.element.android.libraries.designsystem.utils.CommonDrawables
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.matrix.api.MatrixClientProvider
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.ui.media.ImageLoaderHolder
import io.element.android.libraries.push.api.notifications.NotificationBitmapLoader
import kotlin.time.Duration.Companion.seconds

/**
 * Creates a notification for a ringing call.
 */
@Inject
class RingingCallNotificationCreator(
    @ApplicationContext private val context: Context,
    private val matrixClientProvider: MatrixClientProvider,
    private val imageLoaderHolder: ImageLoaderHolder,
    private val notificationBitmapLoader: NotificationBitmapLoader,
) {
    companion object {
        /**
         * Request code for the decline action.
         */
        const val DECLINE_REQUEST_CODE = 1

        /**
         * Request code for the full screen intent.
         */
        const val FULL_SCREEN_INTENT_REQUEST_CODE = 2
    }

    suspend fun createNotification(
        sessionId: SessionId,
        roomId: RoomId,
        eventId: EventId,
        senderId: UserId,
        roomName: String?,
        senderDisplayName: String,
        roomAvatarUrl: String?,
        notificationChannelId: String,
        timestamp: Long,
        expirationTimestamp: Long,
        textContent: String?,
        audioOnly: Boolean,
        fullScreenData: CallNotificationData? = null,
    ): Notification? {
        val matrixClient = matrixClientProvider.getOrRestore(sessionId).getOrNull() ?: return null
        val imageLoader = imageLoaderHolder.get(matrixClient)
        val userIcon = notificationBitmapLoader.getUserIcon(
            avatarData = AvatarData(
                id = roomId.value,
                name = roomName,
                url = roomAvatarUrl,
                size = AvatarSize.RoomDetailsHeader,
            ),
            imageLoader = imageLoader,
        )

        val caller = Person.Builder()
            .setName(senderDisplayName)
            .setIcon(userIcon)
            .setImportant(true)
            .build()

        val answerIntent = IntentProvider.getPendingIntent(
            context,
            CallData(
                sessionId = sessionId,
                roomId = roomId,
                isAudioCall = audioOnly,
                notificationEventId = eventId.value,
            ),
        )
        val notificationData = CallNotificationData(
            sessionId = sessionId,
            roomId = roomId,
            eventId = eventId,
            senderId = senderId,
            roomName = roomName,
            senderName = senderDisplayName,
            avatarUrl = roomAvatarUrl,
            notificationChannelId = notificationChannelId,
            timestamp = timestamp,
            textContent = textContent,
            expirationTimestamp = expirationTimestamp,
            audioOnly = audioOnly,
        )

        val declineIntent = PendingIntentCompat.getBroadcast(
            context,
            DECLINE_REQUEST_CODE,
            Intent(context, DeclineCallBroadcastReceiver::class.java).apply {
                data = android.net.Uri.parse("yizhe-call:decline/" + android.net.Uri.encode(eventId.value))
                putExtra(DeclineCallBroadcastReceiver.EXTRA_NOTIFICATION_DATA, notificationData)
            },
            PendingIntent.FLAG_CANCEL_CURRENT,
            false,
        )!!

        val fullScreenIntent = PendingIntent.getActivity(
            context,
            FULL_SCREEN_INTENT_REQUEST_CODE,
            Intent(context, IncomingCallActivity::class.java).apply {
                data = android.net.Uri.parse("yizhe-call:incoming/" + android.net.Uri.encode(eventId.value))
                putExtra(IncomingCallActivity.EXTRA_NOTIFICATION_DATA, fullScreenData ?: notificationData)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            fullScreenActivityOptions(),
        )

        return NotificationCompat.Builder(context, notificationChannelId)
            .setSmallIcon(CommonDrawables.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addPerson(caller)
            .setContentTitle(senderDisplayName)
            .setAutoCancel(true)
            .setWhen(timestamp)
            .setOngoing(true)
            .setShowWhen(false)
            // If textContent is null, the content text is set by the style (will be "Incoming call")
            .setContentText(textContent)
            .setTimeoutAfter(ElementCallConfig.RINGING_CALL_DURATION_SECONDS.seconds.inWholeMilliseconds)
            .setContentIntent(answerIntent)
            .setDeleteIntent(declineIntent)
            .setStyle(
                NotificationCompat.CallStyle
                    .forIncomingCall(caller, declineIntent, answerIntent)
                    .setIsVideo(!audioOnly)
            )
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setFullScreenIntent(fullScreenIntent, true)
            .build()
            .apply {
                flags = flags.or(Notification.FLAG_INSISTENT)
            }
    }

    private fun fullScreenActivityOptions() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        ActivityOptions.makeBasic()
            .setPendingIntentCreatorBackgroundActivityStartMode(backgroundActivityStartMode())
            .toBundle()
    } else {
        null
    }

    @Suppress("DEPRECATION")
    private fun backgroundActivityStartMode(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
    } else {
        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
    }
}
