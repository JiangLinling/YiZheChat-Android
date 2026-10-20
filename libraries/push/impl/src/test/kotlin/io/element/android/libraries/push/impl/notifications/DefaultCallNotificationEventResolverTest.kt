/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.impl.notifications

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.notification.CallIntent
import io.element.android.libraries.matrix.api.notification.NotificationContent
import io.element.android.libraries.matrix.api.notification.RtcNotificationType
import io.element.android.libraries.matrix.test.AN_EVENT_ID
import io.element.android.libraries.matrix.test.A_ROOM_ID
import io.element.android.libraries.matrix.test.A_ROOM_NAME
import io.element.android.libraries.matrix.test.A_SESSION_ID
import io.element.android.libraries.matrix.test.A_USER_ID_2
import io.element.android.libraries.matrix.test.A_USER_ID_3
import io.element.android.libraries.matrix.test.A_USER_NAME_2
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.libraries.matrix.test.FakeMatrixClientProvider
import io.element.android.libraries.matrix.test.notification.aNotificationData
import io.element.android.libraries.matrix.test.room.FakeBaseRoom
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.libraries.matrix.test.room.aRoomInfo
import io.element.android.libraries.push.impl.notifications.model.NotifiableMessageEvent
import io.element.android.libraries.push.impl.notifications.model.NotifiableRingingCallEvent
import io.element.android.services.appnavstate.test.FakeAppForegroundStateService
import io.element.android.services.toolbox.test.strings.FakeStringProvider
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DefaultCallNotificationEventResolverTest {
    @Test
    fun `fresh Next OEM invitation avoids redundant projection wait`() {
        assertThat(shouldUseImmediateRinging(true, true, expiration = 1001, now = 1000)).isTrue()
    }

    @Test
    fun `expired undated and non Next invitations keep original resolution`() {
        assertThat(shouldUseImmediateRinging(true, true, expiration = 1000, now = 1000)).isFalse()
        assertThat(shouldUseImmediateRinging(true, true, expiration = 0, now = 1000)).isFalse()
        assertThat(shouldUseImmediateRinging(false, true, expiration = 1001, now = 1000)).isFalse()
        assertThat(shouldUseImmediateRinging(true, false, expiration = 1001, now = 1000)).isFalse()
    }

    @Test
    fun `optimistic ringing accepts fresh RING on a projection-lag-prone manufacturer`() {
        assertThat(
            shouldUseOptimisticRinging(
                isProjectionLagProneManufacturer = true,
                isExpired = false,
                hasFallbackByMembers = false,
            )
        ).isTrue()
    }

    @Test
    fun `optimistic ringing keeps membership requirement for other manufacturers`() {
        assertThat(
            shouldUseOptimisticRinging(
                isProjectionLagProneManufacturer = false,
                isExpired = false,
                hasFallbackByMembers = false,
            )
        ).isFalse()
    }

    @Test
    fun `optimistic ringing never accepts an expired RING`() {
        assertThat(
            shouldUseOptimisticRinging(
                isProjectionLagProneManufacturer = true,
                isExpired = true,
                hasFallbackByMembers = true,
            )
        ).isFalse()
    }

    @Test
    fun `resolution failure never rings without authoritative call membership`() {
        assertThat(
            shouldUseFailureFallbackRinging(
                isExpired = false,
                hasFallbackByMembers = false,
            )
        ).isFalse()
    }

    @Test
    fun `resolution failure accepts a fresh RING only with authoritative call membership`() {
        assertThat(
            shouldUseFailureFallbackRinging(
                isExpired = false,
                hasFallbackByMembers = true,
            )
        ).isTrue()
    }

    @Test
    fun `resolution failure rejects expired RING even with authoritative call membership`() {
        assertThat(
            shouldUseFailureFallbackRinging(
                isExpired = true,
                hasFallbackByMembers = true,
            )
        ).isFalse()
    }

    @Test
    fun `resolve CallNotify - RING when call is still ongoing`() = runTest {
        val room = FakeJoinedRoom(
            baseRoom = FakeBaseRoom(
                sessionId = A_SESSION_ID,
                roomId = A_ROOM_ID,
                // The call is still ongoing
                initialRoomInfo = aRoomInfo(hasRoomCall = true),
            )
        )
        val client = FakeMatrixClient().apply {
            notificationSettingsService.setCallEnabled(true)
            givenGetRoomResult(A_ROOM_ID, room)
        }

        val resolver = createDefaultNotifiableEventResolver(
            clientProvider = FakeMatrixClientProvider(getClient = { Result.success(client) }),
        )
        val expectedResult = NotifiableRingingCallEvent(
            sessionId = A_SESSION_ID,
            roomId = A_ROOM_ID,
            eventId = AN_EVENT_ID,
            senderId = A_USER_ID_2,
            roomName = A_ROOM_NAME,
            editedEventId = null,
            description = "\uD83D\uDCF9 Incoming call",
            timestamp = 567L,
            canBeReplaced = true,
            isRedacted = false,
            isUpdated = false,
            senderDisambiguatedDisplayName = A_USER_NAME_2,
            senderAvatarUrl = null,
            expirationTimestamp = 1567L,
            rtcNotificationType = RtcNotificationType.RING,
            callIntent = CallIntent.VIDEO
        )

        val notificationData = aNotificationData(
            content = NotificationContent.MessageLike.RtcNotification(A_USER_ID_2, RtcNotificationType.RING, CallIntent.VIDEO, 1567)
        )
        val result = resolver.resolveEvent(A_SESSION_ID, notificationData)
        assertThat(result.getOrNull()).isEqualTo(expectedResult)
    }

    @Test
    fun `resolve CallNotify - NOTIFY`() = runTest {
        val room = FakeJoinedRoom(
            baseRoom = FakeBaseRoom(
                sessionId = A_SESSION_ID,
                roomId = A_ROOM_ID,
                // The call already ended
                initialRoomInfo = aRoomInfo(hasRoomCall = true),
            )
        )
        val client = FakeMatrixClient().apply {
            notificationSettingsService.setCallEnabled(true)
            givenGetRoomResult(A_ROOM_ID, room)
        }

        val resolver = createDefaultNotifiableEventResolver(
            clientProvider = FakeMatrixClientProvider(getClient = { Result.success(client) }),
        )
        val expectedResult = NotifiableMessageEvent(
            sessionId = A_SESSION_ID,
            roomId = A_ROOM_ID,
            eventId = AN_EVENT_ID,
            senderId = A_USER_ID_2,
            roomName = A_ROOM_NAME,
            editedEventId = null,
            body = "\uD83D\uDCF9 Incoming call",
            timestamp = 567L,
            canBeReplaced = false,
            isRedacted = false,
            isUpdated = false,
            senderDisambiguatedDisplayName = A_USER_NAME_2,
            noisy = true,
            imageUriString = null,
            imageMimeType = null,
            threadId = null,
            type = "org.matrix.msc4075.rtc.notification",
        )

        val notificationData = aNotificationData(
            content = NotificationContent.MessageLike.RtcNotification(A_USER_ID_2, RtcNotificationType.NOTIFY, CallIntent.AUDIO, 0)
        )
        val result = resolver.resolveEvent(A_SESSION_ID, notificationData)
        assertThat(result.getOrNull()).isEqualTo(expectedResult)
    }

    @Test
    fun `resolve CallNotify - RING falls back with active call participants`() = runTest {
        val futureExpirationTimestamp = System.currentTimeMillis() + 60_000L
        val room = FakeJoinedRoom(
            baseRoom = FakeBaseRoom(
                sessionId = A_SESSION_ID,
                roomId = A_ROOM_ID,
                // The room is not yet marked as active in roomInfo yet still has call members.
                initialRoomInfo = aRoomInfo(
                    hasRoomCall = false,
                    activeRoomCallParticipants = listOf(A_USER_ID_2),
                ),
            )
        )
        val client = FakeMatrixClient().apply {
            notificationSettingsService.setCallEnabled(true)
            givenGetRoomResult(A_ROOM_ID, room)
        }

        val resolver = createDefaultNotifiableEventResolver(
            clientProvider = FakeMatrixClientProvider(getClient = { Result.success(client) }),
        )
        val expectedResult = NotifiableRingingCallEvent(
            sessionId = A_SESSION_ID,
            roomId = A_ROOM_ID,
            eventId = AN_EVENT_ID,
            senderId = A_USER_ID_2,
            roomName = A_ROOM_NAME,
            editedEventId = null,
            description = "\uD83D\uDCF9 Incoming call",
            timestamp = 567L,
            canBeReplaced = true,
            isRedacted = false,
            isUpdated = false,
            senderDisambiguatedDisplayName = A_USER_NAME_2,
            senderAvatarUrl = null,
            expirationTimestamp = futureExpirationTimestamp,
            rtcNotificationType = RtcNotificationType.RING,
            callIntent = CallIntent.VIDEO,
        )

        val notificationData = aNotificationData(
            content = NotificationContent.MessageLike.RtcNotification(
                A_USER_ID_2,
                RtcNotificationType.RING,
                CallIntent.VIDEO,
                futureExpirationTimestamp,
            )
        )
        val result = resolver.resolveEvent(A_SESSION_ID, notificationData)
        assertThat(result.getOrNull()).isEqualTo(expectedResult)
    }

    @Test
    fun `resolve CallNotify - RING requires sender in active call participants`() = runTest {
        val room = FakeJoinedRoom(
            baseRoom = FakeBaseRoom(
                sessionId = A_SESSION_ID,
                roomId = A_ROOM_ID,
                // The room has active participants, but none matches the sender.
                initialRoomInfo = aRoomInfo(
                    hasRoomCall = false,
                    activeRoomCallParticipants = listOf(A_USER_ID_3),
                ),
            )
        )
        val client = FakeMatrixClient().apply {
            notificationSettingsService.setCallEnabled(true)
            givenGetRoomResult(A_ROOM_ID, room)
        }

        val resolver = createDefaultNotifiableEventResolver(
            clientProvider = FakeMatrixClientProvider(getClient = { Result.success(client) }),
        )
        val expectedResult = NotifiableMessageEvent(
            sessionId = A_SESSION_ID,
            roomId = A_ROOM_ID,
            eventId = AN_EVENT_ID,
            senderId = A_USER_ID_2,
            roomName = A_ROOM_NAME,
            editedEventId = null,
            body = "\uD83D\uDCF9 Incoming call",
            timestamp = 567L,
            canBeReplaced = false,
            isRedacted = false,
            isUpdated = false,
            senderDisambiguatedDisplayName = A_USER_NAME_2,
            noisy = true,
            imageUriString = null,
            imageMimeType = null,
            threadId = null,
            type = "org.matrix.msc4075.rtc.notification",
        )

        val notificationData = aNotificationData(
            content = NotificationContent.MessageLike.RtcNotification(A_USER_ID_2, RtcNotificationType.RING, CallIntent.VIDEO, 1567)
        )
        val result = resolver.resolveEvent(A_SESSION_ID, notificationData)
        assertThat(result.getOrNull()).isEqualTo(expectedResult)
    }

    @Test
    fun `resolve CallNotify - RING but timed out displays the same as NOTIFY`() = runTest {
        val room = FakeJoinedRoom(
            baseRoom = FakeBaseRoom(
                sessionId = A_SESSION_ID,
                roomId = A_ROOM_ID,
                // The call already ended
                initialRoomInfo = aRoomInfo(hasRoomCall = false),
            )
        )
        val client = FakeMatrixClient().apply {
            notificationSettingsService.setCallEnabled(true)
            givenGetRoomResult(A_ROOM_ID, room)
        }

        val resolver = createDefaultNotifiableEventResolver(
            clientProvider = FakeMatrixClientProvider(getClient = { Result.success(client) }),
        )
        val expectedResult = NotifiableMessageEvent(
            sessionId = A_SESSION_ID,
            roomId = A_ROOM_ID,
            eventId = AN_EVENT_ID,
            senderId = A_USER_ID_2,
            roomName = A_ROOM_NAME,
            editedEventId = null,
            body = "\uD83D\uDCF9 Incoming call",
            timestamp = 567L,
            canBeReplaced = false,
            isRedacted = false,
            isUpdated = false,
            senderDisambiguatedDisplayName = A_USER_NAME_2,
            noisy = true,
            imageUriString = null,
            imageMimeType = null,
            threadId = null,
            type = "org.matrix.msc4075.rtc.notification",
        )

        val notificationData = aNotificationData(
            content = NotificationContent.MessageLike.RtcNotification(A_USER_ID_2, RtcNotificationType.RING, CallIntent.VIDEO, 0)
        )
        val result = resolver.resolveEvent(A_SESSION_ID, notificationData)
        assertThat(result.getOrNull()).isEqualTo(expectedResult)
    }

    @Test
    fun `explicit RTC mentions still obey disabled calls muted room and ignored sender`() = runTest {
        for (denial in listOf("calls", "mute", "ignored")) {
            val client = FakeMatrixClient(
                ignoredUsersFlow = kotlinx.coroutines.flow.MutableStateFlow(
                    if (denial == "ignored") kotlinx.collections.immutable.persistentListOf(A_USER_ID_2)
                    else kotlinx.collections.immutable.persistentListOf(),
                ),
            )
            client.notificationSettingsService.setCallEnabled(denial != "calls")
            if (denial == "mute") client.notificationSettingsService.muteRoom(A_ROOM_ID)
            val foreground = FakeAppForegroundStateService()
            val resolver = createDefaultNotifiableEventResolver(
                appForegroundStateService = foreground,
                clientProvider = FakeMatrixClientProvider { Result.success(client) },
            )
            val data = aNotificationData(content = NotificationContent.MessageLike.RtcNotification(
                A_USER_ID_2, RtcNotificationType.RING, CallIntent.AUDIO, System.currentTimeMillis() + 60_000,
            ))
            assertThat(resolver.resolveEvent(A_SESSION_ID, data).exceptionOrNull())
                .isEqualTo(io.element.android.libraries.matrix.api.exception.NotificationResolverException.EventFilteredOut)
            assertThat(foreground.hasRingingCall.value).isFalse()
        }
    }

    private fun createDefaultNotifiableEventResolver(
        stringProvider: FakeStringProvider = FakeStringProvider(defaultResult = "\uD83D\uDCF9 Incoming call"),
        appForegroundStateService: FakeAppForegroundStateService = FakeAppForegroundStateService(),
        clientProvider: FakeMatrixClientProvider = FakeMatrixClientProvider(),
    ) = DefaultCallNotificationEventResolver(
        stringProvider = stringProvider,
        appForegroundStateService = appForegroundStateService,
        clientProvider = clientProvider,
    )
}
