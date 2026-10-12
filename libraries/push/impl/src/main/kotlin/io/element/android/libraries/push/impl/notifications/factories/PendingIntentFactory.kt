/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.impl.notifications.factories

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import dev.zacsweers.metro.Inject
import io.element.android.libraries.androidutils.uri.createIgnoredUri
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.api.core.ThreadId
import io.element.android.libraries.push.impl.intent.IntentProvider
import io.element.android.libraries.push.impl.notifications.NotificationActionIds
import io.element.android.libraries.push.impl.notifications.NotificationBroadcastReceiver
import io.element.android.libraries.push.impl.notifications.TestNotificationReceiver
import io.element.android.services.toolbox.api.systemclock.SystemClock
import io.element.android.services.appnavstate.api.ROOM_OPENED_FROM_NOTIFICATION

@Inject
class PendingIntentFactory(
    @ApplicationContext private val context: Context,
    private val intentProvider: IntentProvider,
    private val clock: SystemClock,
    private val actionIds: NotificationActionIds,
) {
    fun createOpenSessionPendingIntent(sessionId: SessionId, extras: Bundle? = null): PendingIntent? {
        return createRoomPendingIntent(sessionId = sessionId, roomId = null, eventId = null, threadId = null, extras = extras)
    }

    fun createOpenRoomPendingIntent(sessionId: SessionId, roomId: RoomId, eventId: EventId?, extras: Bundle? = null): PendingIntent? {
        return createRoomPendingIntent(sessionId = sessionId, roomId = roomId, eventId = eventId, threadId = null, extras = extras)
    }

    fun createOpenThreadPendingIntent(sessionId: SessionId, roomId: RoomId, eventId: EventId?, threadId: ThreadId, extras: Bundle? = null): PendingIntent? {
        return createRoomPendingIntent(sessionId = sessionId, roomId = roomId, eventId = eventId, threadId = threadId, extras = extras)
    }

    private fun createRoomPendingIntent(sessionId: SessionId, roomId: RoomId?, eventId: EventId?, threadId: ThreadId?, extras: Bundle? = null): PendingIntent? {
        val intent = intentProvider.getViewRoomIntent(sessionId = sessionId, roomId = roomId, eventId = eventId, threadId = threadId, extras = extras)
        if (roomId != null && extras?.getBoolean(ROOM_OPENED_FROM_NOTIFICATION) == true) {
            intent.setClassName(context, NOTIFICATION_CHAT_ACTIVITY)
        }
        return PendingIntent.getActivity(
            context,
            clock.epochMillis().toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun createFloatingChatPendingIntent(sessionId: SessionId, roomId: RoomId, threadId: ThreadId?): PendingIntent {
        val intent = intentProvider.getViewRoomIntent(
            sessionId = sessionId, roomId = roomId, eventId = null, threadId = threadId,
            extras = Bundle().apply { putBoolean(ROOM_OPENED_FROM_NOTIFICATION, true) },
        ).setClassName(context, "io.element.android.x.FloatingNotificationChatActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = ActivityOptions.makeBasic()
        if (Build.VERSION.SDK_INT >= 36) {
            options.setPendingIntentCreatorBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE)
        } else if (Build.VERSION.SDK_INT >= 35) {
            options.setPendingIntentCreatorBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
        }
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE, options.toBundle())
    }

    fun createConversationBubblePendingIntent(sessionId: SessionId, roomId: RoomId): PendingIntent {
        val intent = intentProvider.getViewRoomIntent(
            sessionId = sessionId, roomId = roomId, eventId = null, threadId = null,
            extras = Bundle().apply { putBoolean(ROOM_OPENED_FROM_NOTIFICATION, true) },
        ).setClassName(context, NOTIFICATION_CHAT_ACTIVITY)
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0,
        )
    }

    fun createDismissSummaryPendingIntent(sessionId: SessionId): PendingIntent {
        val intent = Intent(context, NotificationBroadcastReceiver::class.java)
        intent.action = actionIds.dismissSummary
        intent.data = createIgnoredUri("deleteSummary/$sessionId")
        intent.putExtra(NotificationBroadcastReceiver.KEY_SESSION_ID, sessionId.value)
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun createDismissRoomPendingIntent(sessionId: SessionId, roomId: RoomId): PendingIntent {
        val intent = Intent(context, NotificationBroadcastReceiver::class.java)
        intent.action = actionIds.dismissRoom
        intent.data = createIgnoredUri("deleteRoom/$sessionId/$roomId")
        intent.putExtra(NotificationBroadcastReceiver.KEY_SESSION_ID, sessionId.value)
        intent.putExtra(NotificationBroadcastReceiver.KEY_ROOM_ID, roomId.value)
        return PendingIntent.getBroadcast(
            context,
            clock.epochMillis().toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun createDismissInvitePendingIntent(sessionId: SessionId, roomId: RoomId): PendingIntent {
        val intent = Intent(context, NotificationBroadcastReceiver::class.java)
        intent.action = actionIds.dismissInvite
        intent.data = createIgnoredUri("deleteInvite/$sessionId/$roomId")
        intent.putExtra(NotificationBroadcastReceiver.KEY_SESSION_ID, sessionId.value)
        intent.putExtra(NotificationBroadcastReceiver.KEY_ROOM_ID, roomId.value)
        return PendingIntent.getBroadcast(
            context,
            clock.epochMillis().toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun createDismissEventPendingIntent(sessionId: SessionId, roomId: RoomId, eventId: EventId): PendingIntent {
        val intent = Intent(context, NotificationBroadcastReceiver::class.java)
        intent.action = actionIds.dismissEvent
        intent.data = createIgnoredUri("deleteEvent/$sessionId/$roomId/$eventId")
        intent.putExtra(NotificationBroadcastReceiver.KEY_SESSION_ID, sessionId.value)
        intent.putExtra(NotificationBroadcastReceiver.KEY_ROOM_ID, roomId.value)
        intent.putExtra(NotificationBroadcastReceiver.KEY_EVENT_ID, eventId.value)
        return PendingIntent.getBroadcast(
            context,
            clock.epochMillis().toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun createTestPendingIntent(): PendingIntent? {
        val testActionIntent = Intent(context, TestNotificationReceiver::class.java)
        testActionIntent.action = actionIds.diagnostic
        return PendingIntent.getBroadcast(
            context,
            0,
            testActionIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private companion object {
        const val NOTIFICATION_CHAT_ACTIVITY = "io.element.android.x.NotificationChatActivity"
    }
}
