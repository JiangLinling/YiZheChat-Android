/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.impl.notifications

import android.content.Intent
import dev.zacsweers.metro.Inject
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.core.log.logger.LoggerTag
import io.element.android.libraries.di.annotations.AppCoroutineScope
import io.element.android.libraries.matrix.api.MatrixClientProvider
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.api.core.ThreadId
import io.element.android.libraries.matrix.api.room.CreateTimelineParams
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.matrix.api.room.directRoomRelationState
import io.element.android.libraries.matrix.api.timeline.ReceiptType
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.preferences.api.store.SessionPreferencesStoreFactory
import io.element.android.libraries.push.api.notifications.NotificationCleaner
import io.element.android.libraries.push.impl.R
import io.element.android.libraries.push.impl.notifications.model.NotifiableMessageEvent
import io.element.android.libraries.push.impl.push.OnNotifiableEventReceived
import io.element.android.services.appnavstate.api.ActiveRoomsHolder
import io.element.android.services.toolbox.api.strings.StringProvider
import io.element.android.services.toolbox.api.systemclock.SystemClock
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.lifecycleOperationRequest
import io.element.android.security.dataprotection.api.perform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import java.security.MessageDigest
import java.util.UUID

private val loggerTag = LoggerTag("NotificationBroadcastReceiverHandler", LoggerTag.NotificationLoggerTag)

@Inject
class NotificationBroadcastReceiverHandler(
    @AppCoroutineScope
    private val appCoroutineScope: CoroutineScope,
    private val matrixClientProvider: MatrixClientProvider,
    private val sessionPreferencesStore: SessionPreferencesStoreFactory,
    private val notificationCleaner: NotificationCleaner,
    private val actionIds: NotificationActionIds,
    private val systemClock: SystemClock,
    private val onNotifiableEventReceived: OnNotifiableEventReceived,
    private val stringProvider: StringProvider,
    private val replyMessageExtractor: ReplyMessageExtractor,
    private val activeRoomsHolder: ActiveRoomsHolder,
    private val appOperationalStateCoordinator: AppOperationalStateCoordinator,
) {
    fun onReceive(intent: Intent): Job? {
        val sessionId = intent.getStringExtra(NotificationBroadcastReceiver.KEY_SESSION_ID)?.let(::SessionId) ?: return null
        val roomId = intent.getStringExtra(NotificationBroadcastReceiver.KEY_ROOM_ID)?.let(::RoomId)
        val threadId = intent.getStringExtra(NotificationBroadcastReceiver.KEY_THREAD_ID)?.let(::ThreadId)
        val eventId = intent.getStringExtra(NotificationBroadcastReceiver.KEY_EVENT_ID)?.let(::EventId)

        Timber.tag(loggerTag.value).d("onReceive: ${intent.action} ${intent.data} for: ${roomId?.value}/${eventId?.value}")
        return when (intent.action) {
            actionIds.smartReply -> if (roomId != null) {
                handleSmartReply(sessionId, roomId, eventId, threadId, intent)
            } else null
            actionIds.dismissRoom -> if (roomId != null) {
                notificationCleaner.clearMessagesForRoom(sessionId, roomId)
                null
            } else null
            actionIds.dismissSummary -> {
                notificationCleaner.clearAllMessagesEvents(sessionId)
                null
            }
            actionIds.dismissInvite -> if (roomId != null) {
                notificationCleaner.clearMembershipNotificationForRoom(sessionId, roomId)
                null
            } else null
            actionIds.dismissEvent -> if (eventId != null) {
                notificationCleaner.clearEvent(sessionId, eventId)
                null
            } else null
            actionIds.markRoomRead -> if (roomId != null) {
                if (threadId == null) {
                    notificationCleaner.clearMessagesForRoom(sessionId, roomId)
                } else {
                    notificationCleaner.clearMessagesForThread(sessionId, roomId, threadId)
                }
                handleMarkAsRead(sessionId, roomId, threadId)
            } else null
            actionIds.join -> if (roomId != null) {
                notificationCleaner.clearMembershipNotificationForRoom(sessionId, roomId)
                handleJoinRoom(sessionId, roomId)
            } else null
            actionIds.reject -> if (roomId != null) {
                notificationCleaner.clearMembershipNotificationForRoom(sessionId, roomId)
                handleRejectRoom(sessionId, roomId)
            } else null
            else -> null
        }
    }

    private fun handleJoinRoom(sessionId: SessionId, roomId: RoomId) = appCoroutineScope.launch {
        val client = matrixClientProvider.getOrRestore(sessionId).getOrNull() ?: return@launch
        client.joinRoom(roomId)
    }

    private fun handleRejectRoom(sessionId: SessionId, roomId: RoomId) = appCoroutineScope.launch {
        val client = matrixClientProvider.getOrRestore(sessionId).getOrNull() ?: return@launch
        client.getRoom(roomId)?.leave()
    }

    @Suppress("unused")
    private fun handleMarkAsRead(sessionId: SessionId, roomId: RoomId, threadId: ThreadId?) = appCoroutineScope.launch {
        val client = matrixClientProvider.getOrRestore(sessionId).getOrNull() ?: return@launch
        val isSendPublicReadReceiptsEnabled = sessionPreferencesStore.get(sessionId, this).isSendPublicReadReceiptsEnabled().first()
        val receiptType = if (isSendPublicReadReceiptsEnabled) {
            ReceiptType.READ
        } else {
            ReceiptType.READ_PRIVATE
        }
        val room = client.getJoinedRoom(roomId) ?: return@launch
        val timeline = if (threadId != null) {
            room.createTimeline(CreateTimelineParams.Threaded(threadId)).getOrNull()
        } else {
            room.liveTimeline
        }
        timeline?.markAsRead(receiptType)
            ?.onSuccess {
                if (threadId != null) {
                    Timber.d("Marked thread $threadId in room $roomId as read with receipt type $receiptType")
                } else {
                    Timber.d("Marked room $roomId as read with receipt type $receiptType")
                }
            }
            ?.onFailure {
                Timber.e(it, "Fails to mark as read with receipt type $receiptType")
            }
        if (timeline?.mode != Timeline.Mode.Live) {
            timeline?.close()
        }
    }

    private fun handleSmartReply(
        sessionId: SessionId,
        roomId: RoomId,
        replyToEventId: EventId?,
        threadId: ThreadId?,
        intent: Intent,
    ) = appCoroutineScope.launch {
        val message = replyMessageExtractor.getReplyMessage(intent)
        if (message.isNullOrBlank()) {
            // ignore this event
            // Can this happen? should we update notification?
            return@launch
        }
        val client = matrixClientProvider.getOrRestore(sessionId).getOrNull() ?: return@launch
        val room = activeRoomsHolder.getActiveRoomMatching(sessionId, roomId) ?: client.getJoinedRoom(roomId)

        room?.let { joinedRoom ->
            runCatchingExceptions {
                appOperationalStateCoordinator.perform(
                    lifecycleOperationRequest(
                        lifecycleId = FullAppLifecycleId.LC_13,
                        trigger = "reply",
                        roomIdHash = roomId.value.sha256Hex(),
                        eventIdHash = replyToEventId?.value?.sha256Hex(),
                    )
                ) {
                    sendMatrixEvent(
                        sessionId = sessionId,
                        roomId = roomId,
                        replyToEventId = replyToEventId,
                        threadId = threadId,
                        room = joinedRoom,
                        message = message,
                    )
                }
            }.onFailure { failure ->
                Timber.tag(loggerTag.value).e(failure, "Smart reply lifecycle failed")
            }
        }
    }

    private suspend fun sendMatrixEvent(
        sessionId: SessionId,
        roomId: RoomId,
        threadId: ThreadId?,
        replyToEventId: EventId?,
        room: JoinedRoom,
        message: String,
    ) {
        if (!room.info().directRoomRelationState().canInteract) {
            Timber.tag(loggerTag.value).w("Smart reply blocked until the direct-room invitation is accepted for $roomId")
            return
        }
        // Create a new event to be displayed in the notification drawer, right now
        val notifiableMessageEvent = NotifiableMessageEvent(
            sessionId = sessionId,
            roomId = roomId,
            // Generate a Fake event id
            eventId = EventId("\$" + UUID.randomUUID().toString()),
            editedEventId = null,
            canBeReplaced = false,
            senderId = sessionId,
            noisy = false,
            timestamp = systemClock.epochMillis(),
            senderDisambiguatedDisplayName = room.getUpdatedMember(sessionId).getOrNull()
                ?.disambiguatedDisplayName
                ?: stringProvider.getString(R.string.notification_sender_me),
            body = message,
            imageUriString = null,
            imageMimeType = null,
            threadId = threadId,
            roomName = room.info().name,
            roomIsDm = room.isDm(),
            outGoingMessage = true,
        )
        onNotifiableEventReceived.onNotifiableEventsReceived(listOf(notifiableMessageEvent))

        val sendResult = if (threadId != null && replyToEventId != null) {
            room.liveTimeline.replyMessage(
                body = message,
                htmlBody = null,
                intentionalMentions = emptyList(),
                fromNotification = true,
                repliedToEventId = replyToEventId,
            )
        } else {
            room.liveTimeline.sendMessage(
                body = message,
                htmlBody = null,
                intentionalMentions = emptyList()
            )
        }
        sendResult.onFailure {
            Timber.e(it, "Failed to send smart reply message")
            onNotifiableEventReceived.onNotifiableEventsReceived(
                listOf(
                    notifiableMessageEvent.copy(
                        outGoingMessageFailed = true
                    )
                )
            )
        }
        sendResult.getOrThrow()
    }

    private fun String.sha256Hex(): String = MessageDigest.getInstance("SHA-256")
        .digest(encodeToByteArray())
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
