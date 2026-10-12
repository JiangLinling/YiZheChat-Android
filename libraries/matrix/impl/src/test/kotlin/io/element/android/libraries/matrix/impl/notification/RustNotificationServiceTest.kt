/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.notification

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.DeviceId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.api.exception.NotificationResolverException
import io.element.android.libraries.matrix.api.notification.NotificationContent
import io.element.android.libraries.matrix.api.timeline.item.event.MessageContent
import io.element.android.libraries.matrix.api.timeline.item.event.TextMessageType
import io.element.android.libraries.matrix.impl.fixtures.factories.aRustBatchNotificationResultOk
import io.element.android.libraries.matrix.impl.fixtures.factories.aRustNotificationEventTimeline
import io.element.android.libraries.matrix.impl.fixtures.factories.aRustNotificationItem
import io.element.android.libraries.matrix.impl.fixtures.fakes.FakeFfiNotificationClient
import io.element.android.libraries.matrix.impl.fixtures.fakes.FakeFfiTimelineEvent
import io.element.android.libraries.matrix.impl.timeline.DisabledMatrixProtectedContentAdapter
import io.element.android.libraries.matrix.impl.timeline.MatrixProtectedContentAdapter
import io.element.android.libraries.matrix.impl.timeline.MatrixProtectedContentAdapterFactory
import io.element.android.libraries.matrix.impl.timeline.ProtectedReadDiagnosticOrigin
import io.element.android.libraries.matrix.test.AN_EVENT_ID
import io.element.android.libraries.matrix.test.AN_EVENT_ID_2
import io.element.android.libraries.matrix.test.A_DEVICE_ID
import io.element.android.libraries.matrix.test.A_MESSAGE
import io.element.android.libraries.matrix.test.A_ROOM_ID
import io.element.android.libraries.matrix.test.A_SESSION_ID
import io.element.android.libraries.matrix.test.A_USER_ID_2
import io.element.android.services.toolbox.api.systemclock.SystemClock
import io.element.android.services.toolbox.test.systemclock.FakeSystemClock
import io.element.android.tests.testutils.lambda.lambdaRecorder
import io.element.android.tests.testutils.testCoroutineDispatchers
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.matrix.rustcomponents.sdk.NotificationClient
import org.matrix.rustcomponents.sdk.NotificationStatus
import org.matrix.rustcomponents.sdk.TimelineEventContent

class RustNotificationServiceTest {
    @Test
    fun test() = runTest {
        val notificationClient = FakeFfiNotificationClient(
            notificationItemResult = mapOf(AN_EVENT_ID.value to aRustBatchNotificationResultOk()),
        )
        val sut = createRustNotificationService(
            notificationClient = notificationClient,
        )
        val result = sut.getNotifications(mapOf(A_ROOM_ID to listOf(AN_EVENT_ID))).getOrThrow()[AN_EVENT_ID]!!.getOrThrow()
        assertThat(result.isEncrypted).isTrue()
        assertThat(result.content).isEqualTo(
            NotificationContent.MessageLike.RoomMessage(
                senderId = A_USER_ID_2,
                messageType = TextMessageType(
                    body = A_MESSAGE,
                    formatted = null,
                )
            )
        )
    }

    @Test
    fun `test mapping invalid item only drops that item`() = runTest {
        val error = IllegalStateException("This event content is not supported")
        val faultyEvent = object : FakeFfiTimelineEvent() {
            override fun content(): TimelineEventContent {
                throw error
            }
        }
        val notificationClient = FakeFfiNotificationClient(
            notificationItemResult = mapOf(
                AN_EVENT_ID.value to aRustBatchNotificationResultOk(
                    notificationStatus = NotificationStatus.Event(aRustNotificationItem(aRustNotificationEventTimeline(faultyEvent)))
                ),
                AN_EVENT_ID_2.value to aRustBatchNotificationResultOk()
            ),
        )
        val sut = createRustNotificationService(
            notificationClient = notificationClient,
        )
        val result = sut.getNotifications(mapOf(A_ROOM_ID to listOf(AN_EVENT_ID, AN_EVENT_ID_2))).getOrThrow()
        val exception = result[AN_EVENT_ID]!!.exceptionOrNull()
        assertThat(exception).isEqualTo(error)

        val successfulResult = result[AN_EVENT_ID_2]
        assertThat(successfulResult?.isSuccess).isTrue()
    }

    @Test
    fun `authenticated burn after read lifecycle is preserved in notification projection`() = runTest {
        val notificationClient = FakeFfiNotificationClient(
            notificationItemResult = mapOf(AN_EVENT_ID.value to aRustBatchNotificationResultOk()),
        )
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun decryptMessageContent(eventIdentity: String, message: MessageContent): MessageContent =
                message.copy(
                    body = "one time secret",
                    type = TextMessageType("one time secret", formatted = null),
                    isContentProtectionAuthorized = true,
                    isBurnAfterRead = true,
                )

            override suspend fun decryptMessageContent(
                eventIdentity: String,
                message: MessageContent,
                diagnosticOrigin: ProtectedReadDiagnosticOrigin,
            ): MessageContent = decryptMessageContent(eventIdentity, message)

            override suspend fun decryptMessageContent(
                eventIdentity: String,
                message: MessageContent,
                diagnosticOrigin: ProtectedReadDiagnosticOrigin,
                senderAccountIdHash: String,
            ): MessageContent {
                assertThat(senderAccountIdHash).isEqualTo(
                    io.element.android.security.securestorage.api.accountIdHash(A_USER_ID_2.value)
                )
                return decryptMessageContent(eventIdentity, message, diagnosticOrigin)
            }
        }
        val sut = createRustNotificationService(
            notificationClient = notificationClient,
            protectedContentAdapterFactory = FixedProtectedContentAdapterFactory(adapter),
        )

        val result = sut.getNotifications(mapOf(A_ROOM_ID to listOf(AN_EVENT_ID))).getOrThrow()[AN_EVENT_ID]!!.getOrThrow()

        val content = result.content as NotificationContent.MessageLike.RoomMessage
        assertThat(content.isContentProtectionAuthorized).isTrue()
        assertThat(content.isBurnAfterRead).isTrue()
        assertThat((content.messageType as TextMessageType).body).isEqualTo("one time secret")
    }

    @Test
    fun `test unable to resolve event`() = runTest {
        val notificationClient = FakeFfiNotificationClient(
            notificationItemResult = emptyMap(),
        )
        val sut = createRustNotificationService(
            notificationClient = notificationClient,
        )
        val exception = sut.getNotifications(mapOf(A_ROOM_ID to listOf(AN_EVENT_ID))).getOrThrow()[AN_EVENT_ID]!!.exceptionOrNull()
        assertThat(exception).isInstanceOf(NotificationResolverException::class.java)
    }

    @Test
    fun `close should invoke the close method of the service`() = runTest {
        val closeResult = lambdaRecorder<Unit> { }
        val notificationClient = FakeFfiNotificationClient(
            closeResult = closeResult,
        )
        val sut = createRustNotificationService(
            notificationClient = notificationClient,
        )
        sut.close()
        closeResult.assertions().isCalledOnce()
    }

    @Test
    fun notificationDiagnosticOriginPreservesUnapprovedContentAndEventIdentity() = runTest {
        val origins = mutableListOf<ProtectedReadDiagnosticOrigin>()
        val eventIds = mutableListOf<String>()
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun decryptMessageContent(
                eventIdentity: String,
                message: MessageContent,
                diagnosticOrigin: ProtectedReadDiagnosticOrigin,
            ): MessageContent {
                origins += diagnosticOrigin
                eventIds += eventIdentity
                return message.copy(isContentProtectionAuthorized = false, isBurnAfterRead = false)
            }

            override suspend fun decryptMessageContent(
                eventIdentity: String,
                message: MessageContent,
                diagnosticOrigin: ProtectedReadDiagnosticOrigin,
                senderAccountIdHash: String,
            ): MessageContent {
                assertThat(senderAccountIdHash).isEqualTo(
                    io.element.android.security.securestorage.api.accountIdHash(A_USER_ID_2.value)
                )
                return decryptMessageContent(eventIdentity, message, diagnosticOrigin)
            }
        }
        val sut = createRustNotificationService(
            notificationClient = FakeFfiNotificationClient(
                notificationItemResult = mapOf(AN_EVENT_ID.value to aRustBatchNotificationResultOk()),
            ),
            protectedContentAdapterFactory = FixedProtectedContentAdapterFactory(adapter),
        )
        val result = sut.getNotifications(mapOf(A_ROOM_ID to listOf(AN_EVENT_ID)))
            .getOrThrow()[AN_EVENT_ID]!!.getOrThrow()
        val content = result.content as NotificationContent.MessageLike.RoomMessage
        assertThat(origins).containsExactly(ProtectedReadDiagnosticOrigin.NOTIFICATION_PREVIEW)
        assertThat(eventIds).containsExactly(AN_EVENT_ID.value)
        assertThat(content.isContentProtectionAuthorized).isFalse()
        assertThat(content.isBurnAfterRead).isFalse()
        assertThat((content.messageType as TextMessageType).body).isEqualTo(A_MESSAGE)
    }

    @Test
    fun defaultDiagnosticOverloadPreservesLegacyDisabledProjection() = runTest {
        val message = io.element.android.libraries.matrix.test.timeline.aMessageContent(A_MESSAGE)
            .copy(isContentProtectionAuthorized = false, isBurnAfterRead = true)
        val adapter: MatrixProtectedContentAdapter = DisabledMatrixProtectedContentAdapter
        val legacy = adapter.decryptMessageContent(AN_EVENT_ID.value, message)
        for (origin in ProtectedReadDiagnosticOrigin.values()) {
            val observed = adapter.decryptMessageContent(AN_EVENT_ID.value, message, origin)
            assertThat(observed).isSameInstanceAs(message)
            assertThat(observed).isEqualTo(legacy)
            assertThat(observed.isContentProtectionAuthorized).isFalse()
            assertThat(observed.isBurnAfterRead).isTrue()
        }
    }

    private fun TestScope.createRustNotificationService(
        notificationClient: NotificationClient = FakeFfiNotificationClient(),
        clock: SystemClock = FakeSystemClock(),
        protectedContentAdapterFactory: MatrixProtectedContentAdapterFactory =
            io.element.android.libraries.matrix.impl.timeline.DisabledMatrixProtectedContentAdapterFactory,
    ) =
        RustNotificationService(
            sessionId = A_SESSION_ID,
            deviceId = A_DEVICE_ID,
            notificationClient = notificationClient,
            dispatchers = testCoroutineDispatchers(),
            clock = clock,
            protectedContentAdapterFactory = protectedContentAdapterFactory,
        )
}

private class FixedProtectedContentAdapterFactory(
    private val adapter: MatrixProtectedContentAdapter,
) : MatrixProtectedContentAdapterFactory {
    override fun create(
        sessionId: SessionId,
        deviceId: DeviceId,
        roomId: RoomId,
        activeMemberAccountIdHashes: suspend () -> Set<String>,
        isRoomEncrypted: suspend () -> Boolean,
    ): MatrixProtectedContentAdapter = adapter
}
