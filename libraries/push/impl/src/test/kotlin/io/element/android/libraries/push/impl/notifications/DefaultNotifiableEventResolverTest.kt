/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.impl.notifications

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.featureflag.test.FakeFeatureFlagService
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.exception.NotificationResolverException
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.libraries.matrix.api.notification.CallIntent
import io.element.android.libraries.matrix.api.notification.NotificationContent
import io.element.android.libraries.matrix.api.notification.NotificationData
import io.element.android.libraries.matrix.api.notification.RtcNotificationType
import io.element.android.libraries.matrix.api.room.RoomMembershipState
import io.element.android.libraries.matrix.api.timeline.item.event.AudioMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.EmoteMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.EventType
import io.element.android.libraries.matrix.api.timeline.item.event.FileMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.FormattedBody
import io.element.android.libraries.matrix.api.timeline.item.event.ImageMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.LocationMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.MessageFormat
import io.element.android.libraries.matrix.api.timeline.item.event.NoticeMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.StickerMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.TextMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.VideoMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.VoiceMessageType
import io.element.android.libraries.matrix.test.AN_EVENT_ID
import io.element.android.libraries.matrix.test.AN_EVENT_ID_2
import io.element.android.libraries.matrix.test.AN_EXCEPTION
import io.element.android.libraries.matrix.test.A_REDACTION_REASON
import io.element.android.libraries.matrix.test.A_ROOM_ID
import io.element.android.libraries.matrix.test.A_ROOM_NAME
import io.element.android.libraries.matrix.test.A_SESSION_ID
import io.element.android.libraries.matrix.test.A_SPACE_NAME
import io.element.android.libraries.matrix.test.A_TIMESTAMP
import io.element.android.libraries.matrix.test.A_USER_ID_2
import io.element.android.libraries.matrix.test.A_USER_NAME_2
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.libraries.matrix.test.FakeMatrixClientProvider
import io.element.android.libraries.matrix.test.notification.FakeNotificationService
import io.element.android.libraries.matrix.test.notification.aNotificationData
import io.element.android.libraries.matrix.test.permalink.FakePermalinkParser
import io.element.android.libraries.push.impl.R
import io.element.android.libraries.push.impl.db.PushRequest
import io.element.android.libraries.push.impl.notifications.fake.FakeNotificationMediaRepo
import io.element.android.libraries.push.impl.notifications.fixtures.aNotifiableMessageEvent
import io.element.android.libraries.push.impl.notifications.fixtures.aPushRequest
import io.element.android.libraries.push.impl.notifications.model.InviteNotifiableEvent
import io.element.android.libraries.push.impl.notifications.model.NotifiableMessageEvent
import io.element.android.libraries.push.impl.notifications.model.ResolvedPushEvent
import io.element.android.libraries.push.impl.notifications.model.SimpleNotifiableEvent
import io.element.android.security.contentprotection.api.RoomLifecycleProfileKind
import io.element.android.security.contentprotection.api.ContentProtectionService
import io.element.android.security.contentprotection.api.ResolvedRoomLifecycle
import io.element.android.security.contentprotection.test.FakeContentProtectionService
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleOperationContext
import io.element.android.security.dataprotection.api.currentLifecycleOperationContext
import io.element.android.security.dataprotection.test.FakeAppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.NotificationExposure
import io.element.android.security.dataprotection.test.FakeDataProtectionPolicyProvider
import io.element.android.security.dataprotection.test.FakeProtectedPreviewAuthorizationGate
import io.element.android.services.toolbox.impl.strings.AndroidStringProvider
import io.element.android.services.toolbox.test.systemclock.FakeSystemClock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@Suppress("LargeClass")
@RunWith(RobolectricTestRunner::class)
class DefaultNotifiableEventResolverTest {
    @Test
    fun `signed room notification lookup carries the original receive operation`() = runTest {
        val coordinator = FakeAppOperationalStateCoordinator()
        val seen = mutableListOf<LifecycleOperationContext>()
        val delegate = FakeContentProtectionService()
        val protection = object : ContentProtectionService by delegate {
            override suspend fun resolveRoomLifecycle(roomId: String): ResolvedRoomLifecycle {
                seen += checkNotNull(currentLifecycleOperationContext())
                return delegate.resolveRoomLifecycle(roomId)
            }
        }
        val sut = createDefaultNotifiableEventResolver(
            notificationResult = Result.success(mapOf(AN_EVENT_ID to Result.success(aNotificationData(
                content = NotificationContent.MessageLike.RoomMessage(
                    senderId = A_USER_ID_2,
                    messageType = TextMessageType("authorized notification", formatted = null),
                    isContentProtectionAuthorized = true,
                ),
            )))),
            requireContentEnvelope = true,
            lifecycleCoordinator = coordinator,
            contentProtectionService = protection,
        )
        val request = aPushRequest(A_SESSION_ID, A_ROOM_ID, AN_EVENT_ID, "UnifiedPush - built-in ntfy")
        repeat(2) { index ->
            val result = if (index == 0) sut.resolveEvents(A_SESSION_ID, listOf(request)) else {
                withContext(LifecycleOperationContext("unrelated-event-receive", FullAppLifecycleId.LC_13)) {
                    sut.resolveEvents(A_SESSION_ID, listOf(request))
                }
            }
            val event = result.getEvent(request)!!.getOrThrow() as ResolvedPushEvent.Event
            assertThat((event.notifiableEvent as NotifiableMessageEvent).body).contains("authorized notification")
        }
        assertThat(seen).hasSize(2)
        assertThat(seen.map { it.operationId }.distinct()).hasSize(1)
        assertThat(seen.none { it.operationId == "unrelated-event-receive" }).isTrue()
        assertThat(seen.all { it.lifecycleId == FullAppLifecycleId.LC_13 }).isTrue()
        assertThat(coordinator.startedRequests.map { it.trigger }.distinct()).containsExactly("receive")
        assertThat(coordinator.startedRequests.all { it.accountIdHash != null && it.matrixDeviceIdHash != null &&
            it.roomIdHash != null && it.eventIdHash != null }).isTrue()
        assertThat(coordinator.clientReceipts).isEmpty()
        assertThat(currentLifecycleOperationContext()).isNull()
    }

    @Test
    fun `notification admission failure never queries room rules or exposes authorized text`() = runTest {
        val coordinator = FakeAppOperationalStateCoordinator(startFailure = { IllegalStateException("Admission rejected") })
        var lookedUp = false
        val delegate = FakeContentProtectionService()
        val protection = object : ContentProtectionService by delegate {
            override suspend fun resolveRoomLifecycle(roomId: String): ResolvedRoomLifecycle {
                lookedUp = true
                return delegate.resolveRoomLifecycle(roomId)
            }
        }
        val sut = createDefaultNotifiableEventResolver(
            notificationResult = Result.success(mapOf(AN_EVENT_ID to Result.success(aNotificationData(
                content = NotificationContent.MessageLike.RoomMessage(
                    senderId = A_USER_ID_2,
                    messageType = TextMessageType("authorized notification", formatted = null),
                    isContentProtectionAuthorized = true,
                ),
            )))),
            requireContentEnvelope = true,
            lifecycleCoordinator = coordinator,
            contentProtectionService = protection,
        )
        val request = aPushRequest(A_SESSION_ID, A_ROOM_ID, AN_EVENT_ID, "firebase")
        val event = sut.resolveEvents(A_SESSION_ID, listOf(request)).getEvent(request)!!.getOrThrow() as ResolvedPushEvent.Event
        assertThat((event.notifiableEvent as NotifiableMessageEvent).body).doesNotContain("authorized notification")
        assertThat(lookedUp).isFalse()
        assertThat(coordinator.clientReceipts).isEmpty()
    }

    @Test
    fun `mismatched admitted event never authorizes notification room lookup`() = runTest {
        val delegate = FakeAppOperationalStateCoordinator()
        val coordinator = object : AppOperationalStateCoordinator by delegate {
            override suspend fun start(request: io.element.android.security.dataprotection.api.LifecycleOperationRequest):
                io.element.android.security.dataprotection.api.LifecycleOperation {
                val operation = delegate.start(request)
                return operation.copy(subjects = operation.subjects.copy(eventIdHash = "f".repeat(64)))
            }
        }
        var lookedUp = false
        val protectionDelegate = FakeContentProtectionService()
        val protection = object : ContentProtectionService by protectionDelegate {
            override suspend fun resolveRoomLifecycle(roomId: String): ResolvedRoomLifecycle {
                lookedUp = true
                return protectionDelegate.resolveRoomLifecycle(roomId)
            }
        }
        val sut = createDefaultNotifiableEventResolver(
            notificationResult = Result.success(mapOf(AN_EVENT_ID to Result.success(aNotificationData(
                content = NotificationContent.MessageLike.RoomMessage(
                    senderId = A_USER_ID_2,
                    messageType = TextMessageType("authorized notification", formatted = null),
                    isContentProtectionAuthorized = true,
                ),
            )))),
            requireContentEnvelope = true,
            lifecycleCoordinator = coordinator,
            contentProtectionService = protection,
        )
        val request = aPushRequest(A_SESSION_ID, A_ROOM_ID, AN_EVENT_ID, "firebase")
        val event = sut.resolveEvents(A_SESSION_ID, listOf(request)).getEvent(request)!!.getOrThrow() as ResolvedPushEvent.Event
        assertThat((event.notifiableEvent as NotifiableMessageEvent).body).doesNotContain("authorized notification")
        assertThat(lookedUp).isFalse()
        assertThat(delegate.clientReceipts).isEmpty()
    }

    @Test
    fun `hidden notification skips additional room authorization and never exposes text`() = runTest {
        val coordinator = FakeAppOperationalStateCoordinator(startFailure = { error("Hidden notification must not admit another lookup") })
        val sut = createDefaultNotifiableEventResolver(
            notificationResult = Result.success(mapOf(AN_EVENT_ID to Result.success(aNotificationData(
                content = NotificationContent.MessageLike.RoomMessage(
                    senderId = A_USER_ID_2,
                    messageType = TextMessageType("authorized notification", formatted = null),
                    isContentProtectionAuthorized = true,
                ),
            )))),
            requireContentEnvelope = true,
            lifecycleCoordinator = coordinator,
            notificationExposure = NotificationExposure.HIDDEN,
        )
        val request = aPushRequest(A_SESSION_ID, A_ROOM_ID, AN_EVENT_ID, "firebase")
        val event = sut.resolveEvents(A_SESSION_ID, listOf(request)).getEvent(request)!!.getOrThrow() as ResolvedPushEvent.Event
        val notification = event.notifiableEvent as NotifiableMessageEvent
        assertThat(notification.body).doesNotContain("authorized notification")
        assertThat(notification.senderDisambiguatedDisplayName).isNull()
        assertThat(coordinator.startedRequests).isEmpty()
        assertThat(coordinator.clientReceipts).isEmpty()
    }

    @Test
    fun `only UnifiedPush deliveries receive foreground ntfy marker`() = runTest {
        val sut = createDefaultNotifiableEventResolver(
            notificationResult = Result.success(mapOf(AN_EVENT_ID to Result.success(aNotificationData(
                content = NotificationContent.MessageLike.RoomMessage(
                    senderId = A_USER_ID_2,
                    messageType = TextMessageType(body = "Hello", formatted = null),
                ),
            )))),
        )
        for (provider in listOf("UnifiedPush - built-in ntfy", "UnifiedPush - external distributor", "Huawei", "firebase")) {
            val request = aPushRequest(A_SESSION_ID, A_ROOM_ID, AN_EVENT_ID, provider)
            val event = sut.resolveEvents(A_SESSION_ID, listOf(request)).getEvent(request)!!.getOrThrow() as ResolvedPushEvent.Event
            assertThat((event.notifiableEvent as NotifiableMessageEvent).isNtfyDelivery).isEqualTo(provider.startsWith("UnifiedPush - "))
        }
    }

    @Test
    fun `resolve event no session`() = runTest {
        val sut = createDefaultNotifiableEventResolver(notificationService = null)
        val result = sut.resolveEvents(A_SESSION_ID, listOf(aPushRequest(A_SESSION_ID, A_ROOM_ID, AN_EVENT_ID, "firebase")))
        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `resolve fetching failure`() = runTest {
        val sut = createDefaultNotifiableEventResolver(
            notificationResult = Result.failure(AN_EXCEPTION)
        )
        val request = aPushRequest(A_SESSION_ID, A_ROOM_ID, AN_EVENT_ID, "firebase")
        val result = sut.resolveEvents(A_SESSION_ID, listOf(request))
        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `resolve event failure`() = runTest {
        val sut = createDefaultNotifiableEventResolver(
            notificationResult = Result.success(mapOf(AN_EVENT_ID to Result.failure(AN_EXCEPTION)))
        )
        val request = aPushRequest(A_SESSION_ID, A_ROOM_ID, AN_EVENT_ID, "firebase")
        val result = sut.resolveEvents(A_SESSION_ID, listOf(request))
        assertThat(result.getEvent(request)?.isFailure).isTrue()
    }

    @Test
    fun `resolve event message text`() = runTest {
        val sut = createDefaultNotifiableEventResolver(
            notificationResult = Result.success(
                mapOf(
                    AN_EVENT_ID to Result.success(aNotificationData(
                        content = NotificationContent.MessageLike.RoomMessage(
                            senderId = A_USER_ID_2,
                            messageType = TextMessageType(body = "Hello world", formatted = null)
                        ),
                    ))
                )
            )
        )
        val request = aPushRequest(A_SESSION_ID, A_ROOM_ID, AN_EVENT_ID, "firebase")
        val result = sut.resolveEvents(A_SESSION_ID, listOf(request))
        val expectedResult = ResolvedPushEvent.Event(
            aNotifiableMessageEvent(body = "Hello world")
        )
        assertThat(result.getEvent(request)).isEqualTo(Result.success(expectedResult))
    }

    @Test
    @Config(qualifiers = "en")
    fun `resolve event message with mention`() = runTest {
        val sut = createDefaultNotifiableEventResolver(
            notificationResult = Result.success(
                mapOf(
                    AN_EVENT_ID to Result.success(aNotificationData(
                        content = NotificationContent.MessageLike.RoomMessage(
                            senderId = A_USER_ID_2,
                            messageType = TextMessageType(body = "Hello world", formatted = null)
                        ),
                        hasMention = true,
                    ))
                )
            )
        )
        val request = aPushRequest(A_SESSION_ID, A_ROOM_ID, AN_EVENT_ID, "firebase")
        val result = sut.resolveEvents(A_SESSION_ID, listOf(request))
        val expectedResult = ResolvedPushEvent.Event(
            aNotifiableMessageEvent(body = "Hello world", hasMentionOrReply = true)
        )
        assertThat(result.getEvent(request)).isEqualTo(Result.success(expectedResult))
    }

    @Test
    fun `resolve HTML formatted event message text takes plain text version`() = runTest {
        val sut = createDefaultNotifiableEventResolver(
            notificationResult = Result.success(
                mapOf(
                    AN_EVENT_ID to Result.success(aNotificationData(
                        content = NotificationContent.MessageLike.RoomMessage(
                            senderId = A_USER_ID_2,
                            messageType = TextMessageType(
                                body = "Hello world!",
                                formatted = FormattedBody(
                                    body = "<b>Hello world</b>",
                                    format = MessageFormat.HTML,
                                )
                            )
                        ),
                    ))
                )
            )
        )
        val request = aPushRequest(A_SESSION_ID, A_ROOM_ID, AN_EVENT_ID, "firebase")
        val result = sut.resolveEvents(A_SESSION_ID, listOf(request))
        val expectedResult = ResolvedPushEvent.Event(
            aNotifiableMessageEvent(body = "Hello world")
        )
        assertThat(result.getEvent(request)).isEqualTo(Result.success(expectedResult))
    }

    @Test
    fun `resolve incorrectly formatted event message text uses fallback`() = runTest {
        val sut = createDefaultNotifiableEventResolver(
            notificationResult = Result.success(
                mapOf(
                    AN_EVENT_ID to Result.success(aNotificationData(
                        content = NotificationContent.MessageLike.RoomMessage(
                            senderId = A_USER_ID_2,
                            messageType = TextMessageType(
                                body = "Hello world",
                                formatted = FormattedBody(
                                    body = "???Hello world!???",
                                    format = MessageFormat.UNKNOWN,
                                )
                            )
                        ),
                    ))
                )
            )
        )
        val request = aPushRequest(A_SESSION_ID, A_ROOM_ID, AN_EVENT_ID, "firebase")
        val result = sut.resolveEvents(A_SESSION_ID, listOf(request))
        val expectedResult = ResolvedPushEvent.Event(
            aNotifiableMessageEvent(body = "Hello world")
        )
        assertThat(result.getEvent(request)).isEqualTo(Result.success(expectedResult))
    }

    @Test
    fun `resolve event message audio`() = runTest {
        val sut = createDefaultNotifiableEventResolver(
            notificationResult = Result.success(
                mapOf(
                    AN_EVENT_ID to Result.success(aNotificationData(
                        content = NotificationContent.MessageLike.RoomMessage(
                            senderId = A_USER_ID_2,
                            messageType = AudioMessageType("Audio", null, null, MediaSource("url"), null)
                        ),
                    ))
                )
            )
        )
        val request = aPushRequest(A_SESSION_ID, A_ROOM_ID, AN_EVENT_ID, "firebase")
        val result = sut.resolveEvents(A_SESSION_ID, listOf(request))
        val expectedResult = ResolvedPushEvent.Event(
            aNotifiableMessageEvent(body = "Audio")
        )
        assertThat(result.getEvent(request)).isEqualTo(Result.success(expectedResult))
    }

    @Test
    fun `resolve event message video`() = runTest {
        val sut = createDefaultNotifiableEventResolver(
            notificationResult = Result.success(
                mapOf(
                    AN_EVENT_ID to Result.success(aNotificationData(
                        content = NotificationContent.MessageLike.RoomMessage(
                            senderId = A_USER_ID_2,
                            messageType = VideoMessageType("Video", null, null, MediaSource("url"), null)
                        ),
                    ))
                )
            )
        )
        val request = aPushRequest(A_SESSION_ID, A_ROOM_ID, AN_EVENT_ID, "firebase")
        val result = sut.resolveEvents(A_SESSION_ID, listOf(request))
        val expectedResult = ResolvedPushEvent.Event(
            aNotifiableMessageEvent(body = "Video")
        )
        assertThat(result.getEvent(request)).isEqualTo(Result.success(expectedResult))
    }

    @Test
    fun `resolve event message voice`() = runTest {
        val sut = createDefaultNotifiableEventResolver(
            notificationResult = Result.success(
                mapOf(
                    AN_EVENT_ID to Result.success(aNotificationData(
                        content = NotificationContent.MessageLike.RoomMessage(
                            senderId = A_USER_ID_2,
                            messageType = VoiceMessageType("Voice", null, null, MediaSource("url"), null, null)
                        ),
                    ))
                )
            )
        )
        val request = aPushRequest(A_SESSION_ID, A_ROOM_ID, AN_EVENT_ID, "firebase")
        val result = sut.resolveEvents(A_SESSION_ID, listOf(request))
        val expectedResult = ResolvedPushEvent.Event(
            aNotifiableMessageEvent(body = "Voice message")
        )
        assertThat(result.getEvent(request)).isEqualTo(Result.success(expectedResult))
    }

    @Test
    fun `resolve event message image`() = runTest {
        val sut = createDefaultNotifiableEventResolver(
            notificationResult = Result.success(
                mapOf(
                    AN_EVENT_ID to Result.success(aNotificationData(
                        content = NotificationContent.MessageLike.RoomMessage(
                            senderId = A_USER_ID_2,
                            messageType = ImageMessageType("Image", null, null, MediaSource("url"), null),
                        ),
                    ))
                )
            )
        )
        val request = aPushRequest(A_SESSION_ID, A_ROOM_ID, AN_EVENT_ID, "firebase")
        val result = sut.resolveEvents(A_SESSION_ID, listOf(request))
        val expectedResult = ResolvedPushEvent.Event(
            aNotifiableMessageEvent(body = "Image")
        )
        assertThat(result.getEvent(request)).isEqualTo(Result.success(expectedResult))
    }

    @Test
    fun `resolve event message sticker`() = runTest {
        val sut = createDefaultNotifiableEventResolver(
            notificationResult = Result.success(
                    mapOf(
                        AN_EVENT_ID to Result.success(aNotificationData(
                        content = NotificationContent.MessageLike.RoomMessage(
                            senderId = A_USER_ID_2,
                            messageType = StickerMessageType("Sticker", null, null, MediaSource("url"), null),
                        ),
                    ))
                )
            )
        )
        val request = aPushRequest(A_SESSION_ID, A_ROOM_ID, AN_EVENT_ID, "firebase")
        val result = sut.resolveEvents(A_SESSION_ID, listOf(request))
        val expectedResult = ResolvedPushEvent.Event(
            aNotifiableMessageEvent(body = "Sticker")
        )
        assertThat(result.getEvent(request)).isEqualTo(Result.success(expectedResult))
    }

    @Test
    fun `resolve event message file`() = runTest {
        val sut = createDefaultNotifiableEventResolver(
            notificationResult = Result.success(
                mapOf(
                    AN_EVENT_ID to Result.success(aNotificationData(
                        content = NotificationContent.MessageLike.RoomMessage(
                            senderId = A_USER_ID_2,
                            messageType = FileMessageType("File", null, null, MediaSource("url"), null),
                        ),
                    ))
                )
            )
        )
        val request = aPushRequest(A_SESSION_ID, A_ROOM_ID, AN_EVENT_ID, "firebase")
        val result = sut.resolveEvents(A_SESSION_ID, listOf(request))
        val expectedResult = ResolvedPushEvent.Event(
            aNotifiableMessageEvent(body = "File")
        )
        assertThat(result.getEvent(request)).isEqualTo(Result.success(expectedResult))
    }

    @Test
    fun `resolve event message location`() = runTest {
        val sut = createDefaultNotifiableEventResolver(
            notificationResult = Result.success(
                mapOf(
                    AN_EVENT_ID to Result.success(aNotificationData(
                        content = NotificationContent.MessageLike.RoomMessage(
                            senderId = A_USER_ID_2,
                            messageType = LocationMessageType("Location", "geo:1,2", null, null),
                        ),
                    ))
                )
            )
        )
        val request = aPushRequest(A_SESSION_ID, A_ROOM_ID, AN_EVENT_ID, "firebase")
        val result = sut.resolveEvents(A_SESSION_ID, listOf(request))
        val expectedResult = ResolvedPushEvent.Event(
            aNotifiableMessageEvent(body = "Location")
        )
        assertThat(result.getEvent(request)).isEqualTo(Result.success(expectedResult))
    }

    @Test
    fun `resolve event message notice`() = runTest {
        val sut = createDefaultNotifiableEventResolver(
            notificationResult = Result.success(
                mapOf(
                    AN_EVENT_ID to Result.success(aNotificationData(
                        content = NotificationContent.MessageLike.RoomMessage(
                            senderId = A_USER_ID_2,
                            messageType = NoticeMessageType("Notice", null),
                        ),
                    ))
                )
            )
        )
        val request = aPushRequest(A_SESSION_ID, A_ROOM_ID, AN_EVENT_ID, "firebase")
        val result = sut.resolveEvents(A_SESSION_ID, listOf(request))
        val expectedResult = ResolvedPushEvent.Event(
            aNotifiableMessageEvent(body = "Notice")
        )
        assertThat(result.getEvent(request)).isEqualTo(Result.success(expectedResult))
    }

    @Test
    fun `resolve event message emote`() = runTest {
        val sut = createDefaultNotifiableEventResolver(
            notificationResult = Result.success(
                mapOf(
                    AN_EVENT_ID to Result.success(aNotificationData(
                        content = NotificationContent.MessageLike.RoomMessage(
                            senderId = A_USER_ID_2,
                            messageType = EmoteMessageType("is happy", null),
                        ),
                    ))
                )
            )
        )
        val request = aPushRequest(A_SESSION_ID, A_ROOM_ID, AN_EVENT_ID, "firebase")
        val result = sut.resolveEvents(A_SESSION_ID, listOf(request))
        val expectedResult = ResolvedPushEvent.Event(
            aNotifiableMessageEvent(body = "* Bob is happy")
        )
        assertThat(result.getEvent(request)).isEqualTo(Result.success(expectedResult))
    }

    @Test
    fun `resolve poll`() = runTest {
        val sut = createDefaultNotifiableEventResolver(
            notificationResult = Result.success(
                mapOf(
                    AN_EVENT_ID to Result.success(aNotificationData(
                        content = NotificationContent.MessageLike.Poll(
                            senderId = A_USER_ID_2,
                            question = "A question"
                        ),
                    ))
                )
            )
        )
        val request = aPushRequest(A_SESSION_ID, A_ROOM_ID, AN_EVENT_ID, "firebase")
        val result = sut.resolveEvents(A_SESSION_ID, listOf(request))
        val expectedResult = ResolvedPushEvent.Event(
            aNotifiableMessageEvent(body = "Poll: A question")
        )
        assertThat(result.getEvent(request)).isEqualTo(Result.success(expectedResult))
    }

    @Test
    fun `resolve RoomMemberContent invite room`() = runTest {
        val sut = createDefaultNotifiableEventResolver(
            notificationResult = Result.success(
                mapOf(
                    AN_EVENT_ID to Result.success(aNotificationData(
                        content = NotificationContent.StateEvent.RoomMemberContent(
                            userId = A_USER_ID_2,
                            membershipState = RoomMembershipState.INVITE
                        ),
                        isDirect = false,
                    ))
                )
            )
        )
        val request = aPushRequest(A_SESSION_ID, A_ROOM_ID, AN_EVENT_ID, "firebase")
        val result = sut.resolveEvents(A_SESSION_ID, listOf(request))
        assertThat(result.getEvent(request)?.getOrNull()).isNull()
    }

    @Test
    fun `resolve invite room`() = runTest {
        val sut = createDefaultNotifiableEventResolver(
            notificationResult = Result.success(
                mapOf(
                    AN_EVENT_ID to Result.success(aNotificationData(
                        content = NotificationContent.Invite(
                            senderId = A_USER_ID_2,
                        ),
                        isDirect = false,
                    ))
                )
            )
        )
        val request = aPushRequest(A_SESSION_ID, A_ROOM_ID, AN_EVENT_ID, "firebase")
        val result = sut.resolveEvents(A_SESSION_ID, listOf(request))
        val expectedResult = ResolvedPushEvent.Event(
            InviteNotifiableEvent(
                sessionId = A_SESSION_ID,
                roomId = A_ROOM_ID,
                eventId = AN_EVENT_ID,
                editedEventId = null,
                canBeReplaced = true,
                roomName = A_ROOM_NAME,
                noisy = false,
                title = null,
                description = "Bob invited you to join the room",
                type = null,
                timestamp = A_TIMESTAMP,
                soundName = null,
                isRedacted = false,
                isUpdated = false,
            )
        )
        assertThat(result.getEvent(request)).isEqualTo(Result.success(expectedResult))
    }

    @Test
    fun `resolve invite space`() = runTest {
        val sut = createDefaultNotifiableEventResolver(
            notificationResult = Result.success(
                mapOf(
                    AN_EVENT_ID to Result.success(aNotificationData(
                        content = NotificationContent.Invite(
                            senderId = A_USER_ID_2,
                        ),
                        roomDisplayName = A_SPACE_NAME,
                        isDirect = false,
                        isSpace = true,
                    ))
                )
            )
        )
        val request = aPushRequest(A_SESSION_ID, A_ROOM_ID, AN_EVENT_ID, "firebase")
        val result = sut.resolveEvents(A_SESSION_ID, listOf(request))
        val expectedResult = ResolvedPushEvent.Event(
            InviteNotifiableEvent(
                sessionId = A_SESSION_ID,
                roomId = A_ROOM_ID,
                eventId = AN_EVENT_ID,
                editedEventId = null,
                canBeReplaced = true,
                roomName = A_SPACE_NAME,
                noisy = false,
                title = null,
                description = "Bob invited you to join the space",
                type = null,
                timestamp = A_TIMESTAMP,
                soundName = null,
                isRedacted = false,
                isUpdated = false,
            )
        )
        assertThat(result.getEvent(request)).isEqualTo(Result.success(expectedResult))
    }

    @Test
    fun `resolve invite direct`() = runTest {
        val sut = createDefaultNotifiableEventResolver(
            notificationResult = Result.success(
                mapOf(
                    AN_EVENT_ID to Result.success(aNotificationData(
                        content = NotificationContent.Invite(
                            senderId = A_USER_ID_2,
                        ),
                        isDirect = true,
                    ))
                )
            )
        )
        val request = aPushRequest(A_SESSION_ID, A_ROOM_ID, AN_EVENT_ID, "firebase")
        val result = sut.resolveEvents(A_SESSION_ID, listOf(request))
        val expectedResult = ResolvedPushEvent.Event(
            InviteNotifiableEvent(
                sessionId = A_SESSION_ID,
                roomId = A_ROOM_ID,
                eventId = AN_EVENT_ID,
                editedEventId = null,
                canBeReplaced = true,
                roomName = A_ROOM_NAME,
                noisy = false,
                title = null,
                description = "Bob invited you to chat",
                type = null,
                timestamp = A_TIMESTAMP,
                soundName = null,
                isRedacted = false,
                isUpdated = false,
            )
        )
        assertThat(result.getEvent(request)).isEqualTo(Result.success(expectedResult))
    }

    @Test
    fun `resolve invite direct, no display name`() = runTest {
        val sut = createDefaultNotifiableEventResolver(
            notificationResult = Result.success(
                mapOf(
                    AN_EVENT_ID to Result.success(aNotificationData(
                        content = NotificationContent.Invite(
                            senderId = A_USER_ID_2,
                        ),
                        isDirect = true,
                        senderDisplayName = null,
                    ))
                )
            )
        )
        val request = aPushRequest(A_SESSION_ID, A_ROOM_ID, AN_EVENT_ID, "firebase")
        val result = sut.resolveEvents(A_SESSION_ID, listOf(request))
        val expectedResult = ResolvedPushEvent.Event(
            InviteNotifiableEvent(
                sessionId = A_SESSION_ID,
                roomId = A_ROOM_ID,
                eventId = AN_EVENT_ID,
                editedEventId = null,
                canBeReplaced = true,
                roomName = A_ROOM_NAME,
                noisy = false,
                title = null,
                description = "@bob:server.org invited you to chat",
                type = null,
                timestamp = A_TIMESTAMP,
                soundName = null,
                isRedacted = false,
                isUpdated = false,
            )
        )
        assertThat(result.getEvent(request)).isEqualTo(Result.success(expectedResult))
    }

    @Test
    fun `resolve invite direct, ambiguous display name`() = runTest {
        val sut = createDefaultNotifiableEventResolver(
            notificationResult = Result.success(
                mapOf(
                    AN_EVENT_ID to Result.success(
                        aNotificationData(
                        content = NotificationContent.Invite(
                            senderId = A_USER_ID_2,
                        ),
                        isDirect = false,
                        senderIsNameAmbiguous = true,
                    )
                )
                )
            )
        )
        val request = aPushRequest(A_SESSION_ID, A_ROOM_ID, AN_EVENT_ID, "firebase")
        val result = sut.resolveEvents(A_SESSION_ID, listOf(request))
        val expectedResult = ResolvedPushEvent.Event(
            InviteNotifiableEvent(
                sessionId = A_SESSION_ID,
                roomId = A_ROOM_ID,
                eventId = AN_EVENT_ID,
                editedEventId = null,
                canBeReplaced = true,
                roomName = A_ROOM_NAME,
                noisy = false,
                title = null,
                description = "Bob (@bob:server.org) invited you to join the room",
                type = null,
                timestamp = A_TIMESTAMP,
                soundName = null,
                isRedacted = false,
                isUpdated = false,
            )
        )
        assertThat(result.getEvent(request)).isEqualTo(Result.success(expectedResult))
    }

    @Test
    fun `resolve RoomMemberContent other`() = runTest {
        val sut = createDefaultNotifiableEventResolver(
            notificationResult = Result.success(
                mapOf(
                    AN_EVENT_ID to Result.success(aNotificationData(
                        content = NotificationContent.StateEvent.RoomMemberContent(
                            userId = A_USER_ID_2,
                            membershipState = RoomMembershipState.JOIN
                        )
                    ))
                )
            )
        )
        val request = aPushRequest(A_SESSION_ID, A_ROOM_ID, AN_EVENT_ID, "firebase")
        val result = sut.resolveEvents(A_SESSION_ID, listOf(request))
        assertThat(result.getEvent(request)?.getOrNull()).isNull()
    }

    @Test
    fun `resolve RoomEncrypted`() = runTest {
        val sut = createDefaultNotifiableEventResolver(
            notificationResult = Result.success(
                mapOf(AN_EVENT_ID to Result.success(aNotificationData(content = NotificationContent.MessageLike.RoomEncrypted)))
            )
        )
        val request = aPushRequest(A_SESSION_ID, A_ROOM_ID, AN_EVENT_ID, "firebase")
        val result = sut.resolveEvents(A_SESSION_ID, listOf(request))
        val event = result.getEvent(request)?.getOrNull() as ResolvedPushEvent.Event
        val notification = event.notifiableEvent as SimpleNotifiableEvent
        assertThat(notification.title).isEqualTo(genericNotificationBody())
        assertThat(notification.description).isEqualTo(genericNotificationBody())
    }

    @Test
    fun `resolve protected text transport as generic notification`() = runTest {
        val sut = createDefaultNotifiableEventResolver(
            notificationResult = Result.success(
                mapOf(
                    AN_EVENT_ID to Result.success(
                        aNotificationData(
                            content = NotificationContent.MessageLike.RoomMessage(
                                senderId = A_USER_ID_2,
                                messageType = TextMessageType("yizhe-secure-v1:ciphertext", formatted = null),
                            )
                        )
                    )
                )
            )
        )
        val request = aPushRequest(A_SESSION_ID, A_ROOM_ID, AN_EVENT_ID, "firebase")

        val result = sut.resolveEvents(A_SESSION_ID, listOf(request))

        assertGenericMessageNotification(result.getEvent(request)?.getOrNull())
    }

    @Test
    fun `resolve protected media transport as generic notification`() = runTest {
        val sut = createDefaultNotifiableEventResolver(
            notificationResult = Result.success(
                mapOf(
                    AN_EVENT_ID to Result.success(
                        aNotificationData(
                            content = NotificationContent.MessageLike.RoomMessage(
                                senderId = A_USER_ID_2,
                                messageType = FileMessageType(
                                    ".yizhe-1234567890.yzm",
                                    null,
                                    null,
                                    MediaSource("mxc://server/id"),
                                    null,
                                ),
                            )
                        )
                    )
                )
            )
        )
        val request = aPushRequest(A_SESSION_ID, A_ROOM_ID, AN_EVENT_ID, "firebase")

        val result = sut.resolveEvents(A_SESSION_ID, listOf(request))

        assertGenericMessageNotification(result.getEvent(request)?.getOrNull())
    }

    @Test
    fun `strict content policy shows a generic notification without exposing message data`() = runTest {
        val sut = createDefaultNotifiableEventResolver(
            notificationResult = Result.success(
                mapOf(
                    AN_EVENT_ID to Result.success(
                        aNotificationData(
                            content = NotificationContent.MessageLike.RoomMessage(
                                senderId = A_USER_ID_2,
                                messageType = TextMessageType("plaintext", formatted = null),
                            )
                        )
                    )
                )
            ),
            requireContentEnvelope = true,
        )
        val request = aPushRequest(A_SESSION_ID, A_ROOM_ID, AN_EVENT_ID, "firebase")

        val result = sut.resolveEvents(A_SESSION_ID, listOf(request))

        assertGenericMessageNotification(result.getEvent(request)?.getOrNull())
    }

    @Test
    fun `strict content policy shows generic message notification while preview gate is locked`() = runTest {
        val sut = createDefaultNotifiableEventResolver(
            notificationResult = Result.success(
                mapOf(
                    AN_EVENT_ID to Result.success(
                        aNotificationData(
                            content = NotificationContent.MessageLike.RoomMessage(
                                senderId = A_USER_ID_2,
                                messageType = TextMessageType("plaintext", formatted = null),
                                isContentProtectionAuthorized = true,
                            )
                        )
                    )
                )
            ),
            requireContentEnvelope = true,
            protectedPreviewAllowed = false,
        )
        val request = aPushRequest(A_SESSION_ID, A_ROOM_ID, AN_EVENT_ID, "firebase")

        val result = sut.resolveEvents(A_SESSION_ID, listOf(request))

        val event = result.getEvent(request)?.getOrNull() as ResolvedPushEvent.Event
        val notification = event.notifiableEvent as NotifiableMessageEvent
        assertThat(notification.body).isEqualTo(contextString(R.string.notification_confidential_content))
        assertThat(notification.body).doesNotContain("plaintext")
        assertThat(notification.senderDisambiguatedDisplayName).isNull()
        assertThat(notification.roomName).isNull()
    }

    @Test
    fun `confidential room never exposes authorized notification content`() = runTest {
        val sut = createDefaultNotifiableEventResolver(
            notificationResult = Result.success(
                mapOf(
                    AN_EVENT_ID to Result.success(
                        aNotificationData(
                            content = NotificationContent.MessageLike.RoomMessage(
                                senderId = A_USER_ID_2,
                                messageType = TextMessageType("confidential plaintext", formatted = null),
                                isContentProtectionAuthorized = true,
                            )
                        )
                    )
                )
            ),
            requireContentEnvelope = true,
            roomLifecycleProfileKind = RoomLifecycleProfileKind.CONFIDENTIAL,
        )
        val request = aPushRequest(A_SESSION_ID, A_ROOM_ID, AN_EVENT_ID, "firebase")

        val result = sut.resolveEvents(A_SESSION_ID, listOf(request))

        val event = result.getEvent(request)?.getOrNull() as ResolvedPushEvent.Event
        val notification = event.notifiableEvent as NotifiableMessageEvent
        assertThat(notification.body).isEqualTo(contextString(R.string.notification_confidential_content))
        assertThat(notification.body).doesNotContain("confidential plaintext")
        assertThat(notification.senderDisambiguatedDisplayName).isNull()
        assertThat(notification.roomName).isNull()
    }

    @Test
    fun `burn after read notification never exposes authorized content`() = runTest {
        val sut = createDefaultNotifiableEventResolver(
            notificationResult = Result.success(
                mapOf(
                    AN_EVENT_ID to Result.success(
                        aNotificationData(
                            content = NotificationContent.MessageLike.RoomMessage(
                                senderId = A_USER_ID_2,
                                messageType = TextMessageType("one time plaintext", formatted = null),
                                isContentProtectionAuthorized = true,
                                isBurnAfterRead = true,
                            )
                        )
                    )
                )
            ),
            requireContentEnvelope = true,
        )
        val request = aPushRequest(A_SESSION_ID, A_ROOM_ID, AN_EVENT_ID, "firebase")

        val result = sut.resolveEvents(A_SESSION_ID, listOf(request))

        val event = result.getEvent(request)?.getOrNull() as ResolvedPushEvent.Event
        val notification = event.notifiableEvent as NotifiableMessageEvent
        assertThat(notification.body).isEqualTo(contextString(R.string.notification_burn_after_read_content))
        assertThat(notification.body).doesNotContain("one time plaintext")
        assertThat(notification.senderDisambiguatedDisplayName).isNull()
        assertThat(notification.roomName).isNull()
    }

    @Test
    fun `strict content policy displays only an authorized preview while gate is unlocked`() = runTest {
        val sut = createDefaultNotifiableEventResolver(
            notificationResult = Result.success(
                mapOf(
                    AN_EVENT_ID to Result.success(
                        aNotificationData(
                            content = NotificationContent.MessageLike.RoomMessage(
                                senderId = A_USER_ID_2,
                                messageType = TextMessageType("authorized plaintext", formatted = null),
                                isContentProtectionAuthorized = true,
                            )
                        )
                    )
                )
            ),
            requireContentEnvelope = true,
        )
        val request = aPushRequest(A_SESSION_ID, A_ROOM_ID, AN_EVENT_ID, "firebase")

        val result = sut.resolveEvents(A_SESSION_ID, listOf(request))
        val event = result.getEvent(request)?.getOrNull() as ResolvedPushEvent.Event
        val notification = event.notifiableEvent as NotifiableMessageEvent

        assertThat(notification.body).contains("authorized plaintext")
        assertThat(notification.senderDisambiguatedDisplayName).isEqualTo(A_USER_NAME_2)
    }

    @Test
    fun `resolve UnableToResolve`() = runTest {
        val sut = createDefaultNotifiableEventResolver(
            notificationResult = Result.success(
                mapOf(AN_EVENT_ID to Result.failure(NotificationResolverException.EventNotFound))
            )
        )
        val request = aPushRequest(A_SESSION_ID, A_ROOM_ID, AN_EVENT_ID, "firebase")
        val result = sut.resolveEvents(A_SESSION_ID, listOf(request))
        assertThat(result.getEvent(request)).isEqualTo(Result.failure<ResolvedPushEvent?>(NotificationResolverException.EventNotFound))
    }

    @Test
    fun `resolve CallInvite`() = runTest {
        val sut = createDefaultNotifiableEventResolver(
            notificationResult = Result.success(
                mapOf(
                    AN_EVENT_ID to Result.success(
                        aNotificationData(
                        content = NotificationContent.MessageLike.CallInvite(A_USER_ID_2),
                    )
                )
                )
            )
        )
        val request = aPushRequest(A_SESSION_ID, A_ROOM_ID, AN_EVENT_ID, "firebase")
        val result = sut.resolveEvents(A_SESSION_ID, listOf(request))
        val expectedResult = ResolvedPushEvent.Event(
            NotifiableMessageEvent(
                sessionId = A_SESSION_ID,
                roomId = A_ROOM_ID,
                eventId = AN_EVENT_ID,
                editedEventId = null,
                canBeReplaced = false,
                senderId = A_USER_ID_2,
                noisy = false,
                timestamp = A_TIMESTAMP,
                senderDisambiguatedDisplayName = A_USER_NAME_2,
                body = "Unsupported call",
                imageUriString = null,
                imageMimeType = null,
                threadId = null,
                roomName = A_ROOM_NAME,
                roomAvatarPath = null,
                senderAvatarPath = null,
                soundName = null,
                outGoingMessage = false,
                outGoingMessageFailed = false,
                isRedacted = false,
                isUpdated = false
            )
        )
        assertThat(result.getEvent(request)).isEqualTo(Result.success(expectedResult))
    }

    @Test
    fun `resolve CallNotify - goes through CallNotificationEventResolver`() = runTest {
        val callNotificationEventResolver = FakeCallNotificationEventResolver()
        val sut = createDefaultNotifiableEventResolver(
            notificationResult = Result.success(
                mapOf(
                    AN_EVENT_ID to Result.success(aNotificationData(
                        content = NotificationContent.MessageLike.RtcNotification(
                            A_USER_ID_2,
                            RtcNotificationType.NOTIFY,
                            CallIntent.VIDEO,
                            0
                        ),
                    ))
                )
            ),
            callNotificationEventResolver = callNotificationEventResolver,
        )
        val expectedResult = ResolvedPushEvent.Event(
            NotifiableMessageEvent(
                sessionId = A_SESSION_ID,
                eventId = AN_EVENT_ID,
                editedEventId = null,
                noisy = true,
                timestamp = A_TIMESTAMP,
                senderDisambiguatedDisplayName = A_USER_NAME_2,
                senderId = A_USER_ID_2,
                body = "📹 Incoming call",
                roomId = A_ROOM_ID,
                threadId = null,
                roomName = A_ROOM_NAME,
                canBeReplaced = false,
                isRedacted = false,
                imageUriString = null,
                imageMimeType = null,
                type = EventType.RTC_NOTIFICATION,
            )
        )
        callNotificationEventResolver.resolveEventLambda = { _, _, _ -> Result.success(expectedResult.notifiableEvent) }
        val request = aPushRequest(A_SESSION_ID, A_ROOM_ID, AN_EVENT_ID, "firebase")
        val result = sut.resolveEvents(A_SESSION_ID, listOf(request))
        assertThat(result.getEvent(request)).isEqualTo(Result.success(expectedResult))
    }

    @Test
    fun `resolve RoomRedaction`() = runTest {
        val sut = createDefaultNotifiableEventResolver(
            notificationResult = Result.success(
                mapOf(
                    AN_EVENT_ID to Result.success(aNotificationData(
                        content = NotificationContent.MessageLike.RoomRedaction(
                            AN_EVENT_ID_2,
                            A_REDACTION_REASON,
                        )
                    ))
                )
            )
        )
        val expectedResult = ResolvedPushEvent.Redaction(
            sessionId = A_SESSION_ID,
            roomId = A_ROOM_ID,
            redactedEventId = AN_EVENT_ID_2,
            reason = A_REDACTION_REASON,
        )
        val request = aPushRequest(A_SESSION_ID, A_ROOM_ID, AN_EVENT_ID, "firebase")
        val result = sut.resolveEvents(A_SESSION_ID, listOf(request))
        assertThat(result.getEvent(request)).isEqualTo(Result.success(expectedResult))
    }

    @Test
    fun `resolve RoomRedaction with null redactedEventId should return null`() = runTest {
        val sut = createDefaultNotifiableEventResolver(
            notificationResult = Result.success(
                mapOf(
                    AN_EVENT_ID to Result.success(aNotificationData(
                        content = NotificationContent.MessageLike.RoomRedaction(
                            null,
                            A_REDACTION_REASON,
                        )
                    ))
                )
            )
        )
        val request = aPushRequest(A_SESSION_ID, A_ROOM_ID, AN_EVENT_ID, "firebase")
        val result = sut.resolveEvents(A_SESSION_ID, listOf(request))
        assertThat(result.getEvent(request)?.getOrNull()).isNull()
    }

    @Test
    fun `resolve null cases`() {
        testNoResults(NotificationContent.MessageLike.CallAnswer)
        testNoResults(NotificationContent.MessageLike.CallHangup)
        testNoResults(NotificationContent.MessageLike.CallCandidates)
        testNoResults(NotificationContent.MessageLike.KeyVerificationReady)
        testNoResults(NotificationContent.MessageLike.KeyVerificationStart)
        testNoResults(NotificationContent.MessageLike.KeyVerificationCancel)
        testNoResults(NotificationContent.MessageLike.KeyVerificationAccept)
        testNoResults(NotificationContent.MessageLike.KeyVerificationKey)
        testNoResults(NotificationContent.MessageLike.KeyVerificationMac)
        testNoResults(NotificationContent.MessageLike.KeyVerificationDone)
        testNoResults(NotificationContent.MessageLike.ReactionContent(relatedEventId = AN_EVENT_ID_2.value))
        testNoResults(NotificationContent.MessageLike.Sticker)
        testNoResults(NotificationContent.StateEvent.PolicyRuleRoom)
        testNoResults(NotificationContent.StateEvent.PolicyRuleServer)
        testNoResults(NotificationContent.StateEvent.PolicyRuleUser)
        testNoResults(NotificationContent.StateEvent.RoomAvatar)
        testNoResults(NotificationContent.StateEvent.RoomCanonicalAlias)
        testNoResults(NotificationContent.StateEvent.RoomCreate)
        testNoResults(NotificationContent.StateEvent.RoomEncryption)
        testNoResults(NotificationContent.StateEvent.RoomGuestAccess)
        testNoResults(NotificationContent.StateEvent.RoomHistoryVisibility)
        testNoResults(NotificationContent.StateEvent.RoomJoinRules)
        testNoResults(NotificationContent.StateEvent.RoomName)
        testNoResults(NotificationContent.StateEvent.RoomPinnedEvents)
        testNoResults(NotificationContent.StateEvent.RoomPowerLevels)
        testNoResults(NotificationContent.StateEvent.RoomServerAcl)
        testNoResults(NotificationContent.StateEvent.RoomThirdPartyInvite)
        testNoResults(NotificationContent.StateEvent.RoomTombstone)
        testNoResults(NotificationContent.StateEvent.RoomTopic(""))
        testNoResults(NotificationContent.StateEvent.SpaceChild)
        testNoResults(NotificationContent.StateEvent.SpaceParent)
    }

    private fun testNoResults(content: NotificationContent) = runTest {
        val sut = createDefaultNotifiableEventResolver(
            notificationResult = Result.success(
                mapOf(AN_EVENT_ID to Result.success(aNotificationData(content = content)))
            )
        )
        val request = aPushRequest(A_SESSION_ID, A_ROOM_ID, AN_EVENT_ID, "firebase")
        val result = sut.resolveEvents(A_SESSION_ID, listOf(request))
        assertThat(result.getEvent(request)?.getOrNull()).isNull()
    }

    private fun Result<Map<PushRequest, Result<ResolvedPushEvent>>>.getEvent(
        request: PushRequest
    ): Result<ResolvedPushEvent>? {
        return getOrNull()?.get(request)
    }

    private fun assertGenericMessageNotification(resolvedPushEvent: ResolvedPushEvent?) {
        val event = resolvedPushEvent as ResolvedPushEvent.Event
        val notification = event.notifiableEvent as NotifiableMessageEvent
        assertThat(notification.body).isEqualTo(genericNotificationBody())
        assertThat(notification.senderDisambiguatedDisplayName).isNull()
        assertThat(notification.roomName).isNull()
        assertThat(notification.imageUri).isNull()
        assertThat(notification.roomAvatarPath).isNull()
        assertThat(notification.senderAvatarPath).isNull()
    }

    private fun genericNotificationBody(): String =
        (RuntimeEnvironment.getApplication() as Context).getString(R.string.notification_fallback_content)

    private fun contextString(resourceId: Int): String =
        (RuntimeEnvironment.getApplication() as Context).getString(resourceId)

    private fun createDefaultNotifiableEventResolver(
        notificationService: FakeNotificationService? = FakeNotificationService(),
        notificationResult: Result<Map<EventId, Result<NotificationData>>> = Result.success(emptyMap()),
        callNotificationEventResolver: FakeCallNotificationEventResolver = FakeCallNotificationEventResolver(),
        requireContentEnvelope: Boolean = false,
        protectedPreviewAllowed: Boolean = true,
        roomLifecycleProfileKind: RoomLifecycleProfileKind = RoomLifecycleProfileKind.STANDARD,
        lifecycleCoordinator: AppOperationalStateCoordinator = FakeAppOperationalStateCoordinator(),
        contentProtectionService: ContentProtectionService = FakeContentProtectionService(roomLifecycleProfileKind),
        notificationExposure: NotificationExposure = NotificationExposure.CONTENT_ALLOWED,
    ): DefaultNotifiableEventResolver {
        val context = RuntimeEnvironment.getApplication() as Context
        notificationService?.givenGetNotificationsResult(notificationResult)
        val matrixClientProvider = FakeMatrixClientProvider(getClient = {
            if (notificationService == null) {
                Result.failure(IllegalStateException("Client not found"))
            } else {
                Result.success(FakeMatrixClient(notificationService = notificationService))
            }
        })
        val notificationMediaRepoFactory = NotificationMediaRepo.Factory {
            FakeNotificationMediaRepo()
        }
        return DefaultNotifiableEventResolver(
            stringProvider = AndroidStringProvider(context.resources),
            matrixClientProvider = matrixClientProvider,
            notificationMediaRepoFactory = notificationMediaRepoFactory,
            context = context,
            permalinkParser = FakePermalinkParser(),
            callNotificationEventResolver = callNotificationEventResolver,
            fallbackNotificationFactory = FallbackNotificationFactory(
                clock = FakeSystemClock(),
            ),
            featureFlagService = FakeFeatureFlagService(),
            notificationPrivacySanitizer = NotificationPrivacySanitizer(
                policyProvider = FakeDataProtectionPolicyProvider(
                    notificationExposure = notificationExposure,
                    requireContentEnvelope = requireContentEnvelope,
                ),
                stringProvider = AndroidStringProvider(context.resources),
            ),
            protectedPreviewAuthorizationGate = FakeProtectedPreviewAuthorizationGate(protectedPreviewAllowed),
            contentProtectionService = contentProtectionService,
            appOperationalStateCoordinator = lifecycleCoordinator,
        )
    }
}
