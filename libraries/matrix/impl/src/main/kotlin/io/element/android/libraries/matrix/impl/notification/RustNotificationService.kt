/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.notification

import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.matrix.api.core.DeviceId
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.api.exception.NotificationResolverException
import io.element.android.libraries.matrix.api.notification.GetNotificationDataResult
import io.element.android.libraries.matrix.api.notification.NotificationContent
import io.element.android.libraries.matrix.api.notification.NotificationData
import io.element.android.libraries.matrix.api.notification.NotificationService
import io.element.android.libraries.matrix.api.timeline.item.event.MessageContent
import io.element.android.libraries.matrix.api.timeline.item.event.MessageType
import io.element.android.libraries.matrix.api.timeline.item.event.MessageTypeWithAttachment
import io.element.android.libraries.matrix.api.timeline.item.event.OtherMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.TextMessageType
import io.element.android.libraries.matrix.impl.timeline.DisabledMatrixProtectedContentAdapterFactory
import io.element.android.libraries.matrix.impl.timeline.MatrixProtectedContentAdapter
import io.element.android.libraries.matrix.impl.timeline.MatrixProtectedContentAdapterFactory
import io.element.android.libraries.matrix.impl.timeline.ProtectedReadDiagnosticOrigin
import io.element.android.services.toolbox.api.systemclock.SystemClock
import kotlinx.coroutines.withContext
import org.matrix.rustcomponents.sdk.BatchNotificationResult
import org.matrix.rustcomponents.sdk.NotificationClient
import org.matrix.rustcomponents.sdk.NotificationItemsRequest
import org.matrix.rustcomponents.sdk.NotificationStatus
import org.matrix.rustcomponents.sdk.use
import timber.log.Timber

class RustNotificationService(
    private val sessionId: SessionId,
    private val deviceId: DeviceId,
    private val notificationClient: NotificationClient,
    private val dispatchers: CoroutineDispatchers,
    clock: SystemClock,
    private val protectedContentAdapterFactory: MatrixProtectedContentAdapterFactory = DisabledMatrixProtectedContentAdapterFactory,
) : NotificationService {
    private val notificationMapper: NotificationMapper = NotificationMapper(clock)
    private val previewAdapters = java.util.concurrent.ConcurrentHashMap<RoomId, MatrixProtectedContentAdapter>()

    override suspend fun getNotifications(
        ids: Map<RoomId, List<EventId>>
    ): GetNotificationDataResult = withContext(dispatchers.io) {
        runCatchingExceptions {
            val requests = ids.map { (roomId, eventIds) ->
                NotificationItemsRequest(
                    roomId = roomId.value,
                    eventIds = eventIds.map { it.value }
                )
            }
            val items = notificationClient.getNotifications(requests)
            buildMap {
                val eventIds = requests.flatMap { it.eventIds }.distinct()
                for (rawEventId in eventIds) {
                    val roomId = RoomId(requests.find { it.eventIds.contains(rawEventId) }?.roomId!!)
                    val eventId = EventId(rawEventId)
                    items[rawEventId].use { result ->
                        when (result) {
                            is BatchNotificationResult.Ok -> {
                                when (val status = result.status) {
                                    is NotificationStatus.Event -> {
                                        val result = notificationMapper.map(sessionId, eventId, roomId, status.item)
                                            .authorizeProtectedPreview(eventId, roomId)
                                        result.onFailure { Timber.e(it, "Could not map notification event $eventId") }
                                        put(eventId, result)
                                    }
                                    is NotificationStatus.EventNotFound -> {
                                        Timber.e("Could not retrieve event for notification with $eventId - event not found")
                                        put(eventId, Result.failure(NotificationResolverException.EventNotFound))
                                    }
                                    is NotificationStatus.EventFilteredOut -> {
                                        Timber.d("Could not retrieve event for notification with $eventId - event filtered out")
                                        put(eventId, Result.failure(NotificationResolverException.EventFilteredOut))
                                    }
                                    NotificationStatus.EventRedacted -> {
                                        Timber.d("Could not retrieve event for notification with $eventId - event redacted")
                                        put(eventId, Result.failure(NotificationResolverException.EventRedacted))
                                    }
                                }
                            }
                            is BatchNotificationResult.Error -> {
                                Timber.e("Error while retrieving notification with $rawEventId - ${result.message}")
                                put(
                                    eventId,
                                    Result.failure(NotificationResolverException.UnknownError(result.message))
                                )
                            }
                            null -> {
                                Timber.e("The notification data for $rawEventId was not in the retrieved results. This is unexpected.")
                                put(
                                    eventId,
                                    Result.failure(NotificationResolverException.UnknownError("Notification data not found"))
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    fun close() {
        notificationClient.close()
    }

    private suspend fun Result<NotificationData>.authorizeProtectedPreview(
        eventId: EventId,
        roomId: RoomId,
    ): Result<NotificationData> {
        val notificationData = getOrElse { return this }
        val roomMessage = notificationData.content as? NotificationContent.MessageLike.RoomMessage
            ?: return this
        val outerMessage = MessageContent(
            body = roomMessage.messageType.previewTransportBody(),
            inReplyTo = null,
            isEdited = false,
            threadInfo = null,
            type = roomMessage.messageType,
        )
        val adapter = previewAdapters.getOrPut(roomId) {
            protectedContentAdapterFactory.create(
                sessionId = sessionId,
                deviceId = deviceId,
                roomId = roomId,
                activeMemberAccountIdHashes = { emptySet() },
            )
        }
        val authorized = adapter.decryptMessageContent(
            eventId.value, outerMessage, diagnosticOrigin = ProtectedReadDiagnosticOrigin.NOTIFICATION_PREVIEW,
            senderAccountIdHash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(roomMessage.senderId.value.encodeToByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) },
        )
        return Result.success(
            notificationData.copy(
                content = roomMessage.copy(
                    messageType = authorized.type,
                    isContentProtectionAuthorized = authorized.isContentProtectionAuthorized,
                    isBurnAfterRead = authorized.isBurnAfterRead,
                )
            )
        )
    }
}

private fun MessageType.previewTransportBody(): String = when (this) {
    is TextMessageType -> body
    is OtherMessageType -> body
    is MessageTypeWithAttachment -> bestDescription
    else -> ""
}
