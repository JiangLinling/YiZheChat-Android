/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.call.utils

import android.app.Notification
import android.os.PowerManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.element.android.features.call.api.CallData
import io.element.android.features.call.impl.notifications.RingingCallNotificationCreator
import io.element.android.features.call.impl.notifications.aCallNotificationData
import io.element.android.features.call.impl.services.IncomingCallForegroundServiceController
import io.element.android.features.call.impl.utils.ActiveCall
import io.element.android.features.call.impl.utils.CallState
import io.element.android.features.call.impl.utils.DefaultActiveCallManager
import io.element.android.features.call.impl.utils.DefaultCurrentCallService
import io.element.android.features.call.impl.utils.IncomingCallRingtonePlayer
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.matrix.test.AN_EVENT_ID
import io.element.android.libraries.matrix.test.AN_EVENT_ID_2
import io.element.android.libraries.matrix.test.A_ROOM_ID
import io.element.android.libraries.matrix.test.A_ROOM_ID_2
import io.element.android.libraries.matrix.test.A_SESSION_ID
import io.element.android.libraries.matrix.test.A_USER_ID
import io.element.android.libraries.matrix.test.A_USER_ID_2
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.libraries.matrix.test.FakeMatrixClientProvider
import io.element.android.libraries.matrix.test.room.FakeBaseRoom
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.libraries.matrix.test.room.aRoomInfo
import io.element.android.libraries.matrix.test.timeline.FakeTimeline
import io.element.android.libraries.matrix.ui.media.test.FakeImageLoaderHolder
import io.element.android.libraries.push.api.notifications.ForegroundServiceType
import io.element.android.libraries.push.api.notifications.ForegroundNotificationHost
import io.element.android.libraries.push.api.notifications.NotificationIdProvider
import io.element.android.libraries.push.test.notifications.FakeOnMissedCallNotificationHandler
import io.element.android.libraries.push.test.notifications.push.FakeNotificationBitmapLoader
import io.element.android.services.appnavstate.test.FakeAppForegroundStateService
import io.element.android.services.toolbox.test.systemclock.A_FAKE_TIMESTAMP
import io.element.android.services.toolbox.test.systemclock.FakeSystemClock
import io.element.android.tests.testutils.lambda.lambdaRecorder
import io.element.android.tests.testutils.lambda.value
import io.element.android.tests.testutils.plantTestTimber
import io.mockk.every
import kotlinx.coroutines.flow.MutableStateFlow
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.item.event.CallNotifyContent
import io.element.android.libraries.matrix.api.timeline.item.event.CallLifecycleState
import io.element.android.libraries.matrix.api.notification.CallIntent
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class DefaultActiveCallManagerTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `Next foreground answer controls do not wait for notification avatar loading`() = runTest {
        setupShadowPowerManager()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val shadow = shadowOf(context as android.app.Application)
        while (shadow.nextStartedActivity != null) { /* Retire prior test intents. */ }
        val room = FakeJoinedRoom(liveTimeline = FakeTimeline(timelineItems = MutableStateFlow(listOf(invitation()))))
        val client = FakeMatrixClient().apply { givenGetRoomResult(A_ROOM_ID, room) }
        val bitmapLoader = mockk<io.element.android.libraries.push.api.notifications.NotificationBitmapLoader>()
        io.mockk.coEvery { bitmapLoader.getUserIcon(any(), any()) } coAnswers {
            kotlinx.coroutines.delay(10_000)
            null
        }
        val manager = createActiveCallManager(matrixClientProvider = FakeMatrixClientProvider { Result.success(client) },
            notificationBitmapLoader = bitmapLoader,
            systemClock = FakeSystemClock().apply { epochMillisResult = 100 }, nextCalls = true)
        manager.registerIncomingCall(aCallNotificationData(timestamp = 10, expirationTimestamp = 30_000))
        runCurrent()
        assertThat(shadow.nextStartedActivity?.component?.className)
            .isEqualTo("io.element.android.features.call.impl.ui.IncomingCallActivity")
        assertThat(manager.activeCall.value?.callState).isInstanceOf(CallState.Ringing::class.java)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `Next foreground invitation opens native ringing once and background never launches it`() = runTest {
        setupShadowPowerManager()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val shadow = shadowOf(context as android.app.Application)
        while (shadow.nextStartedActivity != null) { /* Retire prior test intents. */ }
        val timeline = FakeTimeline(timelineItems = MutableStateFlow(listOf(invitation())))
        val room = FakeJoinedRoom(liveTimeline = timeline)
        val client = FakeMatrixClient().apply { givenGetRoomResult(A_ROOM_ID, room) }
        val foreground = FakeAppForegroundStateService()
        val service = mockk<IncomingCallForegroundServiceController>(relaxed = true)
        val manager = createActiveCallManager(matrixClientProvider = FakeMatrixClientProvider { Result.success(client) },
            appForegroundStateService = foreground, incomingCallForegroundServiceController = service,
            systemClock = FakeSystemClock().apply { epochMillisResult = 100 }, nextCalls = true)
        val notification = aCallNotificationData(timestamp = 10, expirationTimestamp = 30_000)
        manager.registerIncomingCall(notification)
        runCurrent()
        val activity = requireNotNull(shadow.nextStartedActivity)
        assertThat(activity.component?.className).isEqualTo("io.element.android.features.call.impl.ui.IncomingCallActivity")
        assertThat(activity.data?.scheme).isEqualTo("yizhe-call")
        verify(exactly = 0) { service.start(any()) }
        manager.registerIncomingCall(notification)
        runCurrent()
        assertThat(shadow.nextStartedActivity).isNull()
        foreground.givenIsInForeground(false)
        runCurrent()
        assertThat(shadow.nextStartedActivity).isNull()
        verify { service.start(any()) }
    }

    private val notificationId = NotificationIdProvider.getForegroundServiceNotificationId(ForegroundServiceType.INCOMING_CALL)

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `Next room Join binds original invitation before late push while a different invite remains busy`() = runTest {
        setupShadowPowerManager()
        val first = invitation()
        val items = MutableStateFlow<List<MatrixTimelineItem>>(listOf(first))
        val room = roomEntryFixture(items)
        val client = FakeMatrixClient().apply { givenGetRoomResult(A_ROOM_ID, room) }
        val holder = mockk<io.element.android.services.appnavstate.api.ActiveRoomsHolder> {
            every { getActiveRoomMatching(A_SESSION_ID, A_ROOM_ID) } returns room
        }
        val ringtone = mockk<IncomingCallRingtonePlayer>(relaxed = true)
        val foreground = FakeAppForegroundStateService()
        val service = mockk<IncomingCallForegroundServiceController>(relaxed = true)
        val manager = createActiveCallManager(matrixClientProvider = FakeMatrixClientProvider { Result.success(client) },
            systemClock = FakeSystemClock().apply { epochMillisResult = 100 }, nextCalls = true,
            activeRoomsHolder = holder, incomingCallRingtonePlayer = ringtone,
            appForegroundStateService = foreground, incomingCallForegroundServiceController = service)
        val target = manager.resolveRoomEntry(CallData(A_SESSION_ID, A_ROOM_ID, true))
        assertThat(target.notificationEventId).isEqualTo(AN_EVENT_ID.value)
        assertThat(target.attemptId).isEqualTo(AN_EVENT_ID.value)
        assertThat(target.isAudioCall).isFalse() // The original invitation owns the mode.
        runCurrent()
        foreground.givenIsInForeground(false)
        runCurrent()
        verify(exactly = 0) { service.start(any()) }
        manager.validateIncomingCall(target, room)
        manager.joinedCall(target)
        manager.registerIncomingCall(aCallNotificationData(timestamp = 10, expirationTimestamp = 30_000))
        runCurrent()
        assertThat(manager.activeCall.value?.callData).isEqualTo(target)
        assertThat(manager.activeCall.value?.callState).isEqualTo(CallState.InCall)
        coVerify(exactly = 0) { room.declineCall(AN_EVENT_ID) }
        verify(exactly = 0) { ringtone.start(any()) }

        items.value = listOf(first, invitation(AN_EVENT_ID_2, 20))
        manager.registerIncomingCall(aCallNotificationData(eventId = AN_EVENT_ID_2, timestamp = 20, expirationTimestamp = 30_000))
        runCurrent()
        coVerify(exactly = 1) { room.declineCall(AN_EVENT_ID_2) }
        coVerify(exactly = 0) { room.declineCall(AN_EVENT_ID) }
    }

    @Test fun `Next room Join never revives older invite after latest is finished or authority is mismatched`() = runTest {
        val first = invitation()
        val finished = invitation(AN_EVENT_ID_2, 20, CallLifecycleState.CANCELLED)
        for (events in listOf(listOf(first, finished), listOf(first))) {
            val room = roomEntryFixture(MutableStateFlow(events))
            io.mockk.coEvery { room.loadRtcNotification(AN_EVENT_ID) } returns Result.success(
                io.element.android.libraries.matrix.api.notification.RtcNotificationAuthority(AN_EVENT_ID_2, 10,
                    io.element.android.libraries.matrix.api.notification.NotificationContent.MessageLike.RtcNotification(
                        A_USER_ID_2, io.element.android.libraries.matrix.api.notification.RtcNotificationType.RING, CallIntent.VIDEO, 30_000)))
            val client = FakeMatrixClient().apply { givenGetRoomResult(A_ROOM_ID, room) }
            val holder = mockk<io.element.android.services.appnavstate.api.ActiveRoomsHolder> {
                every { getActiveRoomMatching(A_SESSION_ID, A_ROOM_ID) } returns room
            }
            val manager = createActiveCallManager(matrixClientProvider = FakeMatrixClientProvider { Result.success(client) },
                systemClock = FakeSystemClock().apply { epochMillisResult = 100 }, nextCalls = true, activeRoomsHolder = holder)
            assertThat(runCatching { manager.resolveRoomEntry(CallData(A_SESSION_ID, A_ROOM_ID, true)) }.exceptionOrNull())
                .isInstanceOf(io.element.android.features.call.impl.utils.IncomingCallUnavailableException::class.java)
            assertThat(manager.activeCall.value).isNull()
        }
    }

    @Test fun `Next room start without ongoing call keeps a new outgoing attempt`() = runTest {
        val holder = mockk<io.element.android.services.appnavstate.api.ActiveRoomsHolder> {
            every { getActiveRoomMatching(A_SESSION_ID, A_ROOM_ID) } returns null
        }
        val manager = createActiveCallManager(nextCalls = true, activeRoomsHolder = holder)
        val request = CallData(A_SESSION_ID, A_ROOM_ID, true)
        assertThat(manager.resolveRoomEntry(request)).isEqualTo(request)
        assertThat(manager.activeCall.value).isNull()
    }

    private fun roomEntryFixture(items: MutableStateFlow<out List<MatrixTimelineItem>>): JoinedRoom {
        val room = io.mockk.spyk(FakeJoinedRoom(
            baseRoom = FakeBaseRoom(getUpdatedMemberResult = { Result.success(io.element.android.libraries.matrix.test.room.aRoomMember(it)) }),
            liveTimeline = FakeTimeline(timelineItems = items),
        ).apply { givenRoomInfo(aRoomInfo(isDm = true, hasRoomCall = true, joinedMembersCount = 2)) })
        io.mockk.coEvery { room.loadRtcNotification(AN_EVENT_ID) } returns Result.success(
            io.element.android.libraries.matrix.api.notification.RtcNotificationAuthority(AN_EVENT_ID, 10,
                io.element.android.libraries.matrix.api.notification.NotificationContent.MessageLike.RtcNotification(
                    A_USER_ID_2, io.element.android.libraries.matrix.api.notification.RtcNotificationType.RING, CallIntent.VIDEO, 30_000)))
        return room
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `registerIncomingCall - sets the incoming call as active`() = runTest {
        setupShadowPowerManager()
        val notificationManagerCompat = mockk<NotificationManagerCompat>(relaxed = true)
        val incomingCallRingtonePlayer = mockk<IncomingCallRingtonePlayer>(relaxed = true)
        val incomingCallForegroundServiceController = mockk<IncomingCallForegroundServiceController>(relaxed = true)
        val manager = createActiveCallManager(
            notificationManagerCompat = notificationManagerCompat,
            incomingCallRingtonePlayer = incomingCallRingtonePlayer,
            incomingCallForegroundServiceController = incomingCallForegroundServiceController,
        )

        assertThat(manager.activeWakeLock?.isHeld).isFalse()
        assertThat(manager.activeCall.value).isNull()

        val callNotificationData = aCallNotificationData()
        manager.registerIncomingCall(callNotificationData)

        assertThat(manager.activeCall.value).isEqualTo(
            ActiveCall(
                callData = CallData(
                    sessionId = callNotificationData.sessionId,
                    roomId = callNotificationData.roomId,
                    isAudioCall = false,
                ),
                callState = CallState.Ringing(callNotificationData)
            )
        )

        runCurrent()

        assertThat(manager.activeWakeLock?.isHeld).isTrue()
        verify { incomingCallRingtonePlayer.start(callNotificationData.notificationChannelId) }
        verify { incomingCallForegroundServiceController.start(any()) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `registerIncomingCall - foreground host replaces system call notification`() = runTest {
        setupShadowPowerManager()
        val notificationManagerCompat = mockk<NotificationManagerCompat>(relaxed = true)
        val incomingCallForegroundServiceController = mockk<IncomingCallForegroundServiceController>(relaxed = true)
        val foregroundNotificationHost = TestForegroundNotificationHost(showResult = true)
        val manager = createActiveCallManager(
            notificationManagerCompat = notificationManagerCompat,
            incomingCallForegroundServiceController = incomingCallForegroundServiceController,
            foregroundNotificationHost = foregroundNotificationHost,
        )

        manager.registerIncomingCall(aCallNotificationData())
        runCurrent()

        assertThat(foregroundNotificationHost.shownIds).containsExactly(notificationId)
        verify(exactly = 0) { incomingCallForegroundServiceController.start(any()) }
        verify { notificationManagerCompat.cancel(notificationId) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `foreground ringing call moves to system notification when app is backgrounded`() = runTest {
        setupShadowPowerManager()
        val appForegroundStateService = FakeAppForegroundStateService(initialForegroundValue = true)
        val serviceController = mockk<IncomingCallForegroundServiceController>(relaxed = true)
        val host = TestForegroundNotificationHost(showResult = true)
        val manager = createActiveCallManager(
            appForegroundStateService = appForegroundStateService,
            incomingCallForegroundServiceController = serviceController,
            foregroundNotificationHost = host,
        )
        manager.registerIncomingCall(aCallNotificationData())
        runCurrent()
        verify(exactly = 0) { serviceController.start(any()) }

        host.showResult = false
        appForegroundStateService.givenIsInForeground(false)
        runCurrent()

        verify { serviceController.start(any()) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `registerIncomingCall - sets the incoming audio call as active`() = runTest {
        setupShadowPowerManager()
        val notificationManagerCompat = mockk<NotificationManagerCompat>(relaxed = true)
        val manager = createActiveCallManager(notificationManagerCompat = notificationManagerCompat)

        val callNotificationData = aCallNotificationData(audioOnly = true)
        manager.registerIncomingCall(callNotificationData)

        assertThat(manager.activeCall.value).isEqualTo(
            ActiveCall(
                callData = CallData(
                    sessionId = callNotificationData.sessionId,
                    roomId = callNotificationData.roomId,
                    isAudioCall = true,
                ),
                callState = CallState.Ringing(callNotificationData)
            )
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `registerIncomingCall - when there is an already active call adds missed call notification`() = runTest {
        val addMissedCallNotificationLambda = lambdaRecorder<SessionId, RoomId, EventId, Unit> { _, _, _ -> }
        val onMissedCallNotificationHandler = FakeOnMissedCallNotificationHandler(addMissedCallNotificationLambda = addMissedCallNotificationLambda)
        val manager = createActiveCallManager(
            onMissedCallNotificationHandler = onMissedCallNotificationHandler,
        )

        // Register existing call
        val callNotificationData = aCallNotificationData()
        manager.registerIncomingCall(callNotificationData)
        val activeCall = manager.activeCall.value

        // Now add a new call
        manager.registerIncomingCall(aCallNotificationData(roomId = A_ROOM_ID_2))

        assertThat(manager.activeCall.value).isEqualTo(activeCall)
        assertThat(manager.activeCall.value?.callData?.roomId).isNotEqualTo(A_ROOM_ID_2)

        advanceTimeBy(1)

        addMissedCallNotificationLambda.assertions()
            .isCalledOnce()
            .with(value(A_SESSION_ID), value(A_ROOM_ID_2), value(AN_EVENT_ID))
    }

    @Test
    fun `incomingCallTimedOut - when there isn't an active call does nothing`() = runTest {
        val addMissedCallNotificationLambda = lambdaRecorder<SessionId, RoomId, EventId, Unit> { _, _, _ -> }
        val manager = createActiveCallManager(
            onMissedCallNotificationHandler = FakeOnMissedCallNotificationHandler(addMissedCallNotificationLambda = addMissedCallNotificationLambda)
        )

        manager.incomingCallTimedOut(displayMissedCallNotification = true)

        addMissedCallNotificationLambda.assertions().isNeverCalled()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `incomingCallTimedOut - when there is an active call removes it and adds a missed call notification`() = runTest {
        setupShadowPowerManager()
        val notificationManagerCompat = mockk<NotificationManagerCompat>(relaxed = true)
        val addMissedCallNotificationLambda = lambdaRecorder<SessionId, RoomId, EventId, Unit> { _, _, _ -> }
        val manager = createActiveCallManager(
            onMissedCallNotificationHandler = FakeOnMissedCallNotificationHandler(addMissedCallNotificationLambda = addMissedCallNotificationLambda),
            notificationManagerCompat = notificationManagerCompat,
        )

        manager.registerIncomingCall(aCallNotificationData())
        assertThat(manager.activeCall.value).isNotNull()
        assertThat(manager.activeWakeLock?.isHeld).isTrue()

        manager.incomingCallTimedOut(displayMissedCallNotification = true)
        advanceTimeBy(1)

        assertThat(manager.activeCall.value).isNull()
        assertThat(manager.activeWakeLock?.isHeld).isFalse()
        addMissedCallNotificationLambda.assertions().isCalledOnce()
        verify { notificationManagerCompat.cancel(notificationId) }
    }

    @Test
    fun `hangUpCall - removes existing call if the CallData matches`() = runTest {
        setupShadowPowerManager()
        val notificationManagerCompat = mockk<NotificationManagerCompat>(relaxed = true)
        val manager = createActiveCallManager(notificationManagerCompat = notificationManagerCompat)

        val notificationData = aCallNotificationData()
        manager.registerIncomingCall(notificationData)
        assertThat(manager.activeCall.value).isNotNull()
        assertThat(manager.activeWakeLock?.isHeld).isTrue()

        manager.hangUpCall(CallData(notificationData.sessionId, notificationData.roomId, false))
        assertThat(manager.activeCall.value).isNull()
        assertThat(manager.activeWakeLock?.isHeld).isFalse()

        verify { notificationManagerCompat.cancel(notificationId) }
    }

    @Test
    fun `Decline event - Hangup on a ringing call should send a decline event`() = runTest {
        setupShadowPowerManager()
        val notificationManagerCompat = mockk<NotificationManagerCompat>(relaxed = true)

        val room = mockk<JoinedRoom>(relaxed = true)

        val matrixClient = FakeMatrixClient().apply {
            givenGetRoomResult(A_ROOM_ID, room)
        }
        val clientProvider = FakeMatrixClientProvider({ Result.success(matrixClient) })

        val manager = createActiveCallManager(
            matrixClientProvider = clientProvider,
            notificationManagerCompat = notificationManagerCompat
        )

        val notificationData = aCallNotificationData(roomId = A_ROOM_ID)
        manager.registerIncomingCall(notificationData)

        manager.hangUpCall(CallData(notificationData.sessionId, notificationData.roomId, false))

        coVerify {
            room.declineCall(notificationEventId = notificationData.eventId)
        }
    }

    @Test
    fun `Decline event - Hangup on a unknown call should send a decline event`() = runTest {
        setupShadowPowerManager()
        val notificationManagerCompat = mockk<NotificationManagerCompat>(relaxed = true)

        val room = mockk<JoinedRoom>(relaxed = true)

        val matrixClient = FakeMatrixClient().apply {
            givenGetRoomResult(A_ROOM_ID, room)
        }
        val clientProvider = FakeMatrixClientProvider({ Result.success(matrixClient) })

        val manager = createActiveCallManager(
            matrixClientProvider = clientProvider,
            notificationManagerCompat = notificationManagerCompat
        )

        val notificationData = aCallNotificationData(roomId = A_ROOM_ID)
        // Do not register the incoming call, so the manager doesn't know about it
        manager.hangUpCall(
            callData = CallData(notificationData.sessionId, notificationData.roomId, false),
            notificationData = notificationData,
        )
        coVerify {
            room.declineCall(notificationEventId = notificationData.eventId)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Decline event - Declining from another session should stop ringing`() = runTest {
        setupShadowPowerManager()
        val notificationManagerCompat = mockk<NotificationManagerCompat>(relaxed = true)

        val room = FakeJoinedRoom()

        val matrixClient = FakeMatrixClient().apply {
            givenGetRoomResult(A_ROOM_ID, room)
        }
        val clientProvider = FakeMatrixClientProvider({ Result.success(matrixClient) })

        val manager = createActiveCallManager(
            matrixClientProvider = clientProvider,
            notificationManagerCompat = notificationManagerCompat
        )

        val notificationData = aCallNotificationData(roomId = A_ROOM_ID)
        manager.registerIncomingCall(notificationData)

        runCurrent()

        // Simulate declined from other session
        room.baseRoom.givenDecliner(matrixClient.sessionId, notificationData.eventId)

        runCurrent()

        assertThat(manager.activeCall.value).isNull()
        assertThat(manager.activeWakeLock?.isHeld).isFalse()

        verify { notificationManagerCompat.cancel(notificationId) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Decline event - Should ignore decline for other notification events`() = runTest {
        plantTestTimber()
        setupShadowPowerManager()
        val notificationManagerCompat = mockk<NotificationManagerCompat>(relaxed = true)

        val room = FakeJoinedRoom()

        val matrixClient = FakeMatrixClient().apply {
            givenGetRoomResult(A_ROOM_ID, room)
        }
        val clientProvider = FakeMatrixClientProvider({ Result.success(matrixClient) })

        val manager = createActiveCallManager(
            matrixClientProvider = clientProvider,
            notificationManagerCompat = notificationManagerCompat
        )

        val notificationData = aCallNotificationData(roomId = A_ROOM_ID)
        manager.registerIncomingCall(notificationData)

        runCurrent()

        // Simulate declined for another notification event
        room.baseRoom.givenDecliner(matrixClient.sessionId, AN_EVENT_ID_2)

        runCurrent()

        assertThat(manager.activeCall.value).isNotNull()
        assertThat(manager.activeWakeLock?.isHeld).isTrue()

        verify(exactly = 0) { notificationManagerCompat.cancel(notificationId) }
    }

    @Test
    fun `hangUpCall - does nothing if the CallData doesn't match`() = runTest {
        setupShadowPowerManager()
        val notificationManagerCompat = mockk<NotificationManagerCompat>(relaxed = true)
        val manager = createActiveCallManager(notificationManagerCompat = notificationManagerCompat)

        manager.registerIncomingCall(aCallNotificationData())
        assertThat(manager.activeCall.value).isNotNull()
        assertThat(manager.activeWakeLock?.isHeld).isTrue()

        manager.hangUpCall(
            CallData(
                sessionId = A_SESSION_ID,
                roomId = A_ROOM_ID_2,
                isAudioCall = true,
            )
        )
        assertThat(manager.activeCall.value).isNotNull()
        assertThat(manager.activeWakeLock?.isHeld).isTrue()

        // A stale action must leave the current invitation and notification intact.
        verify(exactly = 0) { notificationManagerCompat.cancel(notificationId) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `joinedCall - register an ongoing call and tries sending the call notify event`() = runTest {
        val notificationManagerCompat = mockk<NotificationManagerCompat>(relaxed = true)
        val manager = createActiveCallManager(notificationManagerCompat = notificationManagerCompat)
        assertThat(manager.activeCall.value).isNull()

        manager.joinedCall(CallData(A_SESSION_ID, A_ROOM_ID, true))
        assertThat(manager.activeCall.value).isEqualTo(
            ActiveCall(
                callData = CallData(
                    sessionId = A_SESSION_ID,
                    roomId = A_ROOM_ID,
                    isAudioCall = true,
                ),
                callState = CallState.InCall,
            )
        )

        runCurrent()

        verify { notificationManagerCompat.cancel(notificationId) }
    }

    @Test
    fun `prepareHangUpCall - Matrix lifecycle failure propagates to prevent a success receipt`() = runTest {
        setupShadowPowerManager()
        val timeline = FakeTimeline().apply {
            sendCallLifecycleLambda = { _, _, _ -> Result.failure(IllegalStateException("lifecycle failed")) }
        }
        val room = FakeJoinedRoom(liveTimeline = timeline).apply {
            givenRoomInfo(aRoomInfo(isDm = true))
        }
        val matrixClient = FakeMatrixClient().apply {
            givenGetRoomResult(A_ROOM_ID, room)
        }
        val manager = createActiveCallManager(
            matrixClientProvider = FakeMatrixClientProvider { Result.success(matrixClient) },
            notificationManagerCompat = mockk(relaxed = true),
        )
        val notificationData = aCallNotificationData(roomId = A_ROOM_ID)
        manager.registerIncomingCall(notificationData)

        val completion = manager.prepareHangUpCall(
            CallData(
                sessionId = notificationData.sessionId,
                roomId = notificationData.roomId,
                isAudioCall = notificationData.audioOnly,
                notificationEventId = notificationData.eventId.value,
            )
        )
        val failure = runCatching { requireNotNull(completion).invoke() }.exceptionOrNull()

        assertThat(manager.activeCall.value).isNull()
        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure).hasMessageThat().contains("Failed to persist protected DM call lifecycle")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `connected DM - remote participant leaving ends the active call within grace period`() = runTest {
        org.junit.Assume.assumeFalse(io.element.android.appconfig.NextDeploymentConfig.isNextDeployment)
        val room = FakeJoinedRoom().apply {
            givenRoomInfo(
                aRoomInfo(
                    isDm = true,
                    activeRoomCallParticipants = listOf(A_USER_ID, A_USER_ID_2),
                )
            )
        }
        val matrixClient = FakeMatrixClient().apply { givenGetRoomResult(A_ROOM_ID, room) }
        val manager = createActiveCallManager(
            matrixClientProvider = FakeMatrixClientProvider { Result.success(matrixClient) },
        )

        manager.joinedCall(CallData(A_SESSION_ID, A_ROOM_ID, true, AN_EVENT_ID.value))
        runCurrent()
        advanceTimeBy(1)
        runCurrent()
        assertThat(manager.activeCall.value).isNotNull()

        room.givenRoomInfo(
            aRoomInfo(
                isDm = true,
                activeRoomCallParticipants = listOf(A_USER_ID),
            )
        )
        runCurrent()
        advanceTimeBy(999)
        assertThat(manager.activeCall.value).isNotNull()

        advanceTimeBy(502)
        runCurrent()
        assertThat(manager.activeCall.value).isNull()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Next signalling members do not establish media readiness or end a reconnecting call`() = runTest {
        org.junit.Assume.assumeTrue(io.element.android.appconfig.NextDeploymentConfig.isNextDeployment)
        val states = mutableListOf<io.element.android.libraries.matrix.api.timeline.item.event.CallLifecycleState>()
        val timeline = FakeTimeline().apply {
            sendCallLifecycleLambda = { _, state, _ -> states.add(state); Result.success(Unit) }
        }
        val room = FakeJoinedRoom(liveTimeline = timeline).apply {
            givenRoomInfo(aRoomInfo(isDm = true, activeRoomCallParticipants = listOf(A_USER_ID, A_USER_ID_2)))
        }
        val client = FakeMatrixClient().apply { givenGetRoomResult(A_ROOM_ID, room) }
        val manager = createActiveCallManager(matrixClientProvider = FakeMatrixClientProvider { Result.success(client) }, nextCalls = true)
        val call = CallData(A_SESSION_ID, A_ROOM_ID, true, attemptId = "outgoing")
        manager.joinedCall(call)
        manager.bindOutgoingInvitation(call, AN_EVENT_ID)
        runCurrent()
        assertThat(states).doesNotContain(io.element.android.libraries.matrix.api.timeline.item.event.CallLifecycleState.CONNECTED)
        manager.mediaConnected(call)
        manager.mediaConnected(call)
        assertThat(states.count { it == io.element.android.libraries.matrix.api.timeline.item.event.CallLifecycleState.CONNECTED }).isEqualTo(1)
        room.givenRoomInfo(aRoomInfo(isDm = true, activeRoomCallParticipants = listOf(A_USER_ID)))
        advanceTimeBy(5_000)
        runCurrent()
        assertThat(manager.activeCall.value).isNotNull()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `group call - another participant leaving does not end the active call`() = runTest {
        val room = FakeJoinedRoom().apply {
            givenRoomInfo(
                aRoomInfo(
                    isDm = false,
                    activeRoomCallParticipants = listOf(A_USER_ID, A_USER_ID_2),
                )
            )
        }
        val matrixClient = FakeMatrixClient().apply { givenGetRoomResult(A_ROOM_ID, room) }
        val manager = createActiveCallManager(
            matrixClientProvider = FakeMatrixClientProvider { Result.success(matrixClient) },
        )

        manager.joinedCall(CallData(A_SESSION_ID, A_ROOM_ID, true))
        runCurrent()
        room.givenRoomInfo(aRoomInfo(isDm = false, activeRoomCallParticipants = listOf(A_USER_ID)))
        advanceTimeBy(1_500)
        runCurrent()

        assertThat(manager.activeCall.value).isNotNull()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `observeRingingCalls - will cancel the active ringing call if the call is cancelled`() = runTest {
        val room = FakeBaseRoom().apply {
            givenRoomInfo(aRoomInfo())
        }
        val client = FakeMatrixClient().apply {
            givenGetRoomResult(A_ROOM_ID, room)
        }
        val matrixClientProvider = FakeMatrixClientProvider(getClient = { Result.success(client) })
        val manager = createActiveCallManager(matrixClientProvider = matrixClientProvider)

        manager.registerIncomingCall(aCallNotificationData())

        // Call is active (the other user join the call)
        room.givenRoomInfo(aRoomInfo(hasRoomCall = true))
        advanceTimeBy(1)
        // Call is cancelled (the other user left the call)
        room.givenRoomInfo(aRoomInfo(hasRoomCall = false))
        advanceTimeBy(1)

        assertThat(manager.activeCall.value).isNull()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `observeRingingCalls - will do nothing if either the session or the room are not found`() = runTest {
        val room = FakeBaseRoom().apply {
            givenRoomInfo(aRoomInfo())
        }
        val client = FakeMatrixClient().apply {
            givenGetRoomResult(A_ROOM_ID, room)
        }
        val matrixClientProvider = FakeMatrixClientProvider(getClient = { Result.failure(IllegalStateException("Matrix client not found")) })
        val manager = createActiveCallManager(matrixClientProvider = matrixClientProvider)

        // No matrix client

        manager.registerIncomingCall(aCallNotificationData())

        room.givenRoomInfo(aRoomInfo(hasRoomCall = true))
        advanceTimeBy(1)
        room.givenRoomInfo(aRoomInfo(hasRoomCall = false))
        advanceTimeBy(1)

        // The call should still be active
        assertThat(manager.activeCall.value).isNotNull()

        // No room
        client.givenGetRoomResult(A_ROOM_ID, null)
        matrixClientProvider.getClient = { Result.success(client) }

        manager.registerIncomingCall(aCallNotificationData())

        room.givenRoomInfo(aRoomInfo(hasRoomCall = true))
        advanceTimeBy(1)
        room.givenRoomInfo(aRoomInfo(hasRoomCall = false))
        advanceTimeBy(1)

        // The call should still be active
        assertThat(manager.activeCall.value).isNotNull()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `IncomingCall - rings no longer than expiration time`() = runTest {
        setupShadowPowerManager()
        val notificationManagerCompat = mockk<NotificationManagerCompat>(relaxed = true)
        val incomingCallForegroundServiceController = mockk<IncomingCallForegroundServiceController>(relaxed = true)
        val clock = FakeSystemClock()
        val manager = createActiveCallManager(
            notificationManagerCompat = notificationManagerCompat,
            systemClock = clock,
            incomingCallForegroundServiceController = incomingCallForegroundServiceController,
        )

        assertThat(manager.activeWakeLock?.isHeld).isFalse()
        assertThat(manager.activeCall.value).isNull()

        val eventTimestamp = A_FAKE_TIMESTAMP
        // The call should not ring more than 30 seconds after the initial event was sent
        val expirationTimestamp = eventTimestamp + 30_000

        val callNotificationData = aCallNotificationData(
            timestamp = eventTimestamp,
            expirationTimestamp = expirationTimestamp,
        )

        // suppose it took 10s to be notified
        clock.epochMillisResult = eventTimestamp + 10_000
        manager.registerIncomingCall(callNotificationData)

        assertThat(manager.activeCall.value).isEqualTo(
            ActiveCall(
                callData = CallData(
                    sessionId = callNotificationData.sessionId,
                    roomId = callNotificationData.roomId,
                    isAudioCall = false,
                ),
                callState = CallState.Ringing(callNotificationData)
            )
        )

        runCurrent()

        assertThat(manager.activeWakeLock?.isHeld).isTrue()
        verify { incomingCallForegroundServiceController.start(any()) }

        // advance by 21s it should have stopped ringing
        advanceTimeBy(21_000)
        runCurrent()

        verify { notificationManagerCompat.cancel(any()) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `IncomingCall - ignore expired ring lifetime`() = runTest {
        setupShadowPowerManager()
        val notificationManagerCompat = mockk<NotificationManagerCompat>(relaxed = true)
        val clock = FakeSystemClock()
        val manager = createActiveCallManager(notificationManagerCompat = notificationManagerCompat, systemClock = clock)

        assertThat(manager.activeWakeLock?.isHeld).isFalse()
        assertThat(manager.activeCall.value).isNull()

        val eventTimestamp = A_FAKE_TIMESTAMP
        // The call should not ring more than 30 seconds after the initial event was sent
        val expirationTimestamp = eventTimestamp + 30_000

        val callNotificationData = aCallNotificationData(
            timestamp = eventTimestamp,
            expirationTimestamp = expirationTimestamp,
        )

        // suppose it took 35s to be notified
        clock.epochMillisResult = eventTimestamp + 35_000
        manager.registerIncomingCall(callNotificationData)

        assertThat(manager.activeCall.value).isNull()

        runCurrent()

        assertThat(manager.activeWakeLock?.isHeld).isFalse()
        verify(exactly = 0) { notificationManagerCompat.notify(notificationId, any()) }
    }


    private fun invitation(id: EventId = AN_EVENT_ID, time: Long = 10L, state: CallLifecycleState? = null,
        declinedBy: List<io.element.android.libraries.matrix.api.core.UserId> = emptyList(),
        intent: CallIntent = CallIntent.VIDEO): MatrixTimelineItem.Event = mockk {
        every { event } returns mockk(relaxed = true) {
            every { isOwn } returns false
            every { isRemote } returns true
            every { eventId } returns id
            every { timestamp } returns time
            every { sender } returns A_USER_ID_2
            every { content } returns CallNotifyContent(intent, declinedBy, lifecycleState = state)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `Next cancellation clears exact invitation and old answer cannot join redial`() = runTest {
        setupShadowPowerManager()
        val items = MutableStateFlow<List<MatrixTimelineItem>>(listOf(invitation()))
        val room = FakeJoinedRoom(liveTimeline = FakeTimeline(timelineItems = items))
        val client = FakeMatrixClient().apply { givenGetRoomResult(A_ROOM_ID, room) }
        val manager = createActiveCallManager(matrixClientProvider = FakeMatrixClientProvider { Result.success(client) },
            systemClock = FakeSystemClock().apply { epochMillisResult = 100 }, nextCalls = true)
        val first = aCallNotificationData(timestamp = 10, expirationTimestamp = 30_000)
        manager.registerIncomingCall(first)
        runCurrent()
        val oldCall = requireNotNull(manager.activeCall.value).callData
        manager.validateIncomingCall(oldCall, room)
        items.value = listOf(invitation(state = CallLifecycleState.CANCELLED))
        runCurrent()
        assertThat(manager.activeCall.value).isNull()
        assertThat(runCatching { manager.validateIncomingCall(oldCall, room) }.isFailure).isTrue()
        items.value = listOf(invitation(state = CallLifecycleState.CANCELLED), invitation(AN_EVENT_ID_2, 20))
        manager.registerIncomingCall(first.copy(eventId = AN_EVENT_ID_2, timestamp = 20))
        runCurrent()
        assertThat(manager.activeCall.value?.callData?.notificationEventId).isEqualTo(AN_EVENT_ID_2.value)
        manager.hangUpCall(oldCall, first)
        assertThat(manager.activeCall.value?.callData?.notificationEventId).isEqualTo(AN_EVENT_ID_2.value)
        assertThat(runCatching { manager.joinedCall(oldCall) }.isFailure).isTrue()
        manager.validateIncomingCall(requireNotNull(manager.activeCall.value).callData)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `Next newer invitation wins before delayed cancel and old push cannot resurrect`() = runTest {
        setupShadowPowerManager()
        val items = MutableStateFlow<List<MatrixTimelineItem>>(listOf(invitation()))
        val room = FakeJoinedRoom(liveTimeline = FakeTimeline(timelineItems = items))
        val client = FakeMatrixClient().apply { givenGetRoomResult(A_ROOM_ID, room) }
        val manager = createActiveCallManager(matrixClientProvider = FakeMatrixClientProvider { Result.success(client) },
            systemClock = FakeSystemClock().apply { epochMillisResult = 100 }, nextCalls = true)
        val first = aCallNotificationData(timestamp = 10, expirationTimestamp = 30_000)
        manager.registerIncomingCall(first)
        runCurrent()
        val oldCall = requireNotNull(manager.activeCall.value).callData
        items.value = listOf(invitation(), invitation(AN_EVENT_ID_2, 20))
        manager.registerIncomingCall(first.copy(eventId = AN_EVENT_ID_2, timestamp = 20))
        runCurrent()
        assertThat(manager.activeCall.value?.callData?.notificationEventId).isEqualTo(AN_EVENT_ID_2.value)
        assertThat(runCatching { manager.validateIncomingCall(oldCall, room) }.isFailure).isTrue()
        manager.registerIncomingCall(first)
        items.value = listOf(invitation(state = CallLifecycleState.CANCELLED), invitation(AN_EVENT_ID_2, 20))
        runCurrent()
        assertThat(manager.activeCall.value?.callData?.notificationEventId).isEqualTo(AN_EVENT_ID_2.value)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `Next cancellation while answering closes attempt but connected does not`() = runTest {
        setupShadowPowerManager()
        val items = MutableStateFlow<List<MatrixTimelineItem>>(listOf(invitation()))
        val room = FakeJoinedRoom(liveTimeline = FakeTimeline(timelineItems = items))
        val client = FakeMatrixClient().apply { givenGetRoomResult(A_ROOM_ID, room) }
        val manager = createActiveCallManager(matrixClientProvider = FakeMatrixClientProvider { Result.success(client) },
            systemClock = FakeSystemClock().apply { epochMillisResult = 100 }, nextCalls = true)
        manager.registerIncomingCall(aCallNotificationData(timestamp = 10, expirationTimestamp = 30_000))
        val data = requireNotNull(manager.activeCall.value).callData
        manager.joinedCall(data)
        runCurrent()
        items.value = listOf(invitation(state = CallLifecycleState.CONNECTED))
        runCurrent()
        assertThat(manager.activeCall.value?.callState).isEqualTo(CallState.InCall)
        items.value = listOf(invitation(state = CallLifecycleState.ENDED))
        runCurrent()
        assertThat(manager.activeCall.value?.remoteEnded).isTrue()
        val completion = manager.prepareHangUpCall(data)
        assertThat(completion).isNotNull()
        requireNotNull(completion).invoke()
        assertThat(manager.activeCall.value).isNull()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `Next unknown expired and duplicate invitations do not create or extend ringing`() = runTest {
        setupShadowPowerManager()
        val items = MutableStateFlow<List<MatrixTimelineItem>>(emptyList())
        val room = FakeJoinedRoom(liveTimeline = FakeTimeline(timelineItems = items))
        val client = FakeMatrixClient().apply { givenGetRoomResult(A_ROOM_ID, room) }
        val ringtone = mockk<IncomingCallRingtonePlayer>(relaxed = true)
        val manager = createActiveCallManager(matrixClientProvider = FakeMatrixClientProvider { Result.success(client) },
            incomingCallRingtonePlayer = ringtone,
            systemClock = FakeSystemClock().apply { epochMillisResult = 100 }, nextCalls = true)
        val data = aCallNotificationData(timestamp = 10, expirationTimestamp = 30_000)
        manager.registerIncomingCall(data)
        assertThat(manager.activeCall.value).isNull()
        items.value = listOf(invitation())
        manager.registerIncomingCall(data.copy(expirationTimestamp = 100))
        assertThat(manager.activeCall.value).isNull()
        manager.registerIncomingCall(data)
        runCurrent()
        manager.registerIncomingCall(data)
        runCurrent()
        verify(exactly = 1) { ringtone.start(any()) }
    }

    @Test fun `Next outgoing cancellation targets acknowledged invite and late old hangup does not end next attempt`() = runTest {
        val writes = mutableListOf<EventId>()
        val timeline = FakeTimeline().apply { sendCallLifecycleLambda = { id, _, _ -> writes += id; Result.success(Unit) } }
        val room = FakeJoinedRoom(liveTimeline = timeline).apply { givenRoomInfo(aRoomInfo(isDm = true)) }
        val client = FakeMatrixClient().apply { givenGetRoomResult(A_ROOM_ID, room) }
        val manager = createActiveCallManager(matrixClientProvider = FakeMatrixClientProvider { Result.success(client) }, nextCalls = true)
        val first = CallData(A_SESSION_ID, A_ROOM_ID, false, attemptId = "a")
        val second = first.copy(attemptId = "b")
        manager.joinedCall(first)
        manager.bindOutgoingInvitation(first, AN_EVENT_ID)
        manager.hangUpCall(first)
        assertThat(writes).containsExactly(AN_EVENT_ID)
        manager.joinedCall(second)
        manager.bindOutgoingInvitation(first, AN_EVENT_ID)
        manager.hangUpCall(first)
        assertThat(manager.activeCall.value?.callData).isEqualTo(second)
        manager.bindOutgoingInvitation(second, AN_EVENT_ID_2)
        manager.hangUpCall(second)
        assertThat(writes).containsExactly(AN_EVENT_ID, AN_EVENT_ID_2).inOrder()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `Next native departure closes only matching ringing generation`() = runTest {
        setupShadowPowerManager()
        val items = MutableStateFlow<List<MatrixTimelineItem>>(listOf(invitation()))
        val a = MutableStateFlow(false)
        val b = MutableStateFlow(false)
        val room = FakeJoinedRoom(liveTimeline = FakeTimeline(timelineItems = items)).apply {
            invitationDepartures[AN_EVENT_ID] = a
            invitationDepartures[AN_EVENT_ID_2] = b
        }
        val client = FakeMatrixClient().apply { givenGetRoomResult(A_ROOM_ID, room) }
        val manager = createActiveCallManager(matrixClientProvider = FakeMatrixClientProvider { Result.success(client) },
            systemClock = FakeSystemClock().apply { epochMillisResult = 100 }, nextCalls = true)
        val first = aCallNotificationData(timestamp = 10, expirationTimestamp = 30_000)
        manager.registerIncomingCall(first)
        runCurrent()
        items.value = listOf(invitation(), invitation(AN_EVENT_ID_2, 20))
        manager.registerIncomingCall(first.copy(eventId = AN_EVENT_ID_2, timestamp = 20))
        runCurrent()
        a.value = true
        runCurrent()
        assertThat(manager.activeCall.value?.callData?.notificationEventId).isEqualTo(AN_EVENT_ID_2.value)
        b.value = true
        runCurrent()
        assertThat(manager.activeCall.value).isNull()
        manager.registerIncomingCall(first.copy(eventId = AN_EVENT_ID_2, timestamp = 20))
        assertThat(manager.activeCall.value).isNull()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `Next outgoing terminal invitation closes current caller without closing redial from old terminal`() = runTest {
        val items = MutableStateFlow<List<MatrixTimelineItem>>(listOf(invitation()))
        val room = FakeJoinedRoom(liveTimeline = FakeTimeline(timelineItems = items)).apply {
            givenRoomInfo(aRoomInfo(isDm = true))
        }
        val client = FakeMatrixClient().apply { givenGetRoomResult(A_ROOM_ID, room) }
        val manager = createActiveCallManager(matrixClientProvider = FakeMatrixClientProvider { Result.success(client) }, nextCalls = true)
        val first = CallData(A_SESSION_ID, A_ROOM_ID, true, attemptId = "root-no-answer-a")
        manager.joinedCall(first)
        manager.bindOutgoingInvitation(first, AN_EVENT_ID)
        runCurrent()
        items.value = listOf(invitation(state = CallLifecycleState.MISSED))
        runCurrent()
        assertThat(manager.activeCall.value?.remoteEnded).isTrue()
        val second = first.copy(attemptId = "root-no-answer-b")
        manager.joinedCall(second)
        manager.bindOutgoingInvitation(second, AN_EVENT_ID_2)
        items.value = listOf(invitation(state = CallLifecycleState.MISSED), invitation(AN_EVENT_ID_2))
        runCurrent()
        assertThat(manager.activeCall.value?.remoteEnded).isFalse()
        items.value = listOf(invitation(state = CallLifecycleState.MISSED), invitation(AN_EVENT_ID_2, state = CallLifecycleState.DECLINED))
        runCurrent()
        assertThat(manager.activeCall.value?.remoteEnded).isTrue()
    }

    private fun setupShadowPowerManager() {
        shadowOf(InstrumentationRegistry.getInstrumentation().targetContext.getSystemService<PowerManager>()).apply {
            setIsWakeLockLevelSupported(PowerManager.PARTIAL_WAKE_LOCK, true)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `Next native decline requires current invitation and exact DM peer and retains completion`() = runTest {
        setupShadowPowerManager()
        val room = FakeJoinedRoom().apply { givenRoomInfo(aRoomInfo(isDm = true,
            heroes = listOf(io.element.android.libraries.matrix.api.user.MatrixUser(A_USER_ID_2)))) }
        val client = FakeMatrixClient().apply { givenGetRoomResult(A_ROOM_ID, room) }
        val manager = createActiveCallManager(matrixClientProvider = FakeMatrixClientProvider { Result.success(client) }, nextCalls = true)
        val a = CallData(A_SESSION_ID, A_ROOM_ID, true, attemptId = "decline-a")
        val b = a.copy(attemptId = "decline-b")
        manager.joinedCall(a)
        manager.bindOutgoingInvitation(a, AN_EVENT_ID)
        runCurrent()
        manager.joinedCall(b)
        manager.bindOutgoingInvitation(b, AN_EVENT_ID_2)
        runCurrent()
        room.baseRoom.givenDecliner(A_USER_ID_2, AN_EVENT_ID)
        room.baseRoom.givenDecliner(A_SESSION_ID, AN_EVENT_ID_2)
        runCurrent()
        assertThat(manager.activeCall.value?.remoteEnded).isFalse()
        room.baseRoom.givenDecliner(A_USER_ID_2, AN_EVENT_ID_2)
        runCurrent()
        assertThat(manager.activeCall.value?.remoteEnded).isTrue()
        val completion = manager.prepareHangUpCall(b)
        assertThat(completion).isNotNull()
        requireNotNull(completion).invoke()
        assertThat(manager.activeCall.value).isNull()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `Next duplicate push while answer connects never declines the same invitation`() = runTest {
        setupShadowPowerManager()
        val room = io.mockk.spyk(FakeJoinedRoom(liveTimeline = FakeTimeline(
            timelineItems = MutableStateFlow(listOf(invitation())),
        )))
        val client = FakeMatrixClient().apply { givenGetRoomResult(A_ROOM_ID, room) }
        val manager = createActiveCallManager(matrixClientProvider = FakeMatrixClientProvider { Result.success(client) },
            systemClock = FakeSystemClock().apply { epochMillisResult = 100 }, nextCalls = true)
        val notification = aCallNotificationData(timestamp = 10, expirationTimestamp = 30_000)
        manager.registerIncomingCall(notification)
        val call = requireNotNull(manager.activeCall.value).callData
        manager.joinedCall(call)
        manager.registerIncomingCall(notification)
        runCurrent()
        assertThat(manager.activeCall.value?.callState).isEqualTo(CallState.InCall)
        coVerify(exactly = 0) { room.declineCall(AN_EVENT_ID) }
    }

    @Test fun `Next decline metadata gets its own lifecycle authorization before protected send`() = runTest {
        setupShadowPowerManager()
        var writeContext: io.element.android.security.dataprotection.api.LifecycleOperationContext? = null
        val timeline = FakeTimeline(timelineItems = MutableStateFlow(listOf(invitation()))).apply {
            sendCallLifecycleLambda = { _, _, _ ->
                writeContext = io.element.android.security.dataprotection.api.currentLifecycleOperationContext()
                Result.success(Unit)
            }
        }
        val room = FakeJoinedRoom(liveTimeline = timeline).apply { givenRoomInfo(aRoomInfo(isDm = true)) }
        val client = FakeMatrixClient().apply { givenGetRoomResult(A_ROOM_ID, room) }
        val manager = createActiveCallManager(matrixClientProvider = FakeMatrixClientProvider { Result.success(client) },
            systemClock = FakeSystemClock().apply { epochMillisResult = 100 }, nextCalls = true)
        val notification = aCallNotificationData(timestamp = 10, expirationTimestamp = 30_000)
        manager.registerIncomingCall(notification)
        val call = requireNotNull(manager.activeCall.value).callData
        manager.hangUpCall(call, notification)
        assertThat(writeContext?.lifecycleId).isEqualTo(io.element.android.security.dataprotection.api.FullAppLifecycleId.LC_15)
        assertThat(writeContext?.operationId).startsWith("call-state-")
        assertThat(manager.activeCall.value).isNull()
    }

    @Test fun `group decline belongs to its recipient and does not cancel other incoming invitations`() = runTest {
        setupShadowPowerManager()
        val items = MutableStateFlow<List<MatrixTimelineItem>>(listOf(invitation(declinedBy = listOf(A_USER_ID_2))))
        val room = FakeJoinedRoom(liveTimeline = FakeTimeline(timelineItems = items)).apply {
            givenRoomInfo(aRoomInfo(isDm = false))
        }
        val client = FakeMatrixClient().apply { givenGetRoomResult(A_ROOM_ID, room) }
        val manager = createActiveCallManager(matrixClientProvider = FakeMatrixClientProvider { Result.success(client) },
            systemClock = FakeSystemClock().apply { epochMillisResult = 100 }, nextCalls = true)
        manager.registerIncomingCall(aCallNotificationData(timestamp = 10, expirationTimestamp = 30_000))
        runCurrent()
        val incoming = requireNotNull(manager.activeCall.value).callData
        assertThat((manager.activeCall.value?.callState as? CallState.Ringing)?.notificationData?.isGroupCall).isTrue()
        manager.validateIncomingCall(incoming)
        items.value = listOf(invitation(declinedBy = listOf(A_SESSION_ID)))
        runCurrent()
        assertThat(manager.activeCall.value).isNull()
    }

    @Test fun `group inviter departure stops ringing but preserves already connected participants`() = runTest {
        setupShadowPowerManager()
        val departed = MutableStateFlow(false)
        val items = MutableStateFlow<List<MatrixTimelineItem>>(listOf(invitation()))
        val room = FakeJoinedRoom(liveTimeline = FakeTimeline(timelineItems = items)).apply {
            givenRoomInfo(aRoomInfo(isDm = false))
            invitationDepartures[AN_EVENT_ID] = departed
        }
        val client = FakeMatrixClient().apply { givenGetRoomResult(A_ROOM_ID, room) }
        val manager = createActiveCallManager(matrixClientProvider = FakeMatrixClientProvider { Result.success(client) },
            systemClock = FakeSystemClock().apply { epochMillisResult = 100 }, nextCalls = true)
        manager.registerIncomingCall(aCallNotificationData(timestamp = 10, expirationTimestamp = 30_000))
        runCurrent()
        val incoming = requireNotNull(manager.activeCall.value).callData
        manager.joinedCall(incoming)
        manager.mediaConnected(incoming)
        departed.value = true
        advanceTimeBy(2_000)
        runCurrent()
        assertThat(manager.activeCall.value?.callState).isInstanceOf(CallState.InCall::class.java)
        assertThat(manager.activeCall.value?.remoteEnded).isFalse()
    }


    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `BUG039 natural timeout persists original MISSED after cancellable LC15 checkpoint`() = runTest {
        setupShadowPowerManager()
        val coordinator = io.element.android.security.dataprotection.test.FakeAppOperationalStateCoordinator()
        val writes = mutableListOf<Pair<EventId, CallLifecycleState>>()
        val timeline = FakeTimeline(timelineItems = MutableStateFlow(listOf(invitation(intent = CallIntent.AUDIO)))).apply {
            sendCallLifecycleLambda = { id, state, _ ->
                // A real send switches dispatchers and suspends; a synchronous fake hid self-cancellation.
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                kotlinx.coroutines.delay(1)
                writes += id to state
                Result.success(Unit)
            }
        }
        val room = FakeJoinedRoom(liveTimeline = timeline).apply { givenRoomInfo(aRoomInfo(isDm = true)) }
        val client = FakeMatrixClient().apply { givenGetRoomResult(A_ROOM_ID, room) }
        val missed = FakeOnMissedCallNotificationHandler()
        val manager = createActiveCallManager(matrixClientProvider = FakeMatrixClientProvider { Result.success(client) },
            onMissedCallNotificationHandler = missed, coordinator = coordinator,
            systemClock = FakeSystemClock().apply { epochMillisResult = 100 }, nextCalls = true)
        manager.registerIncomingCall(aCallNotificationData(timestamp = 10, expirationTimestamp = 1_000, audioOnly = true))
        runCurrent()
        advanceTimeBy(900)
        runCurrent()
        assertThat(manager.activeCall.value).isNull()
        assertThat(manager.activeWakeLock?.isHeld).isFalse()
        advanceTimeBy(1)
        runCurrent()
        assertThat(writes).containsExactly(AN_EVENT_ID to CallLifecycleState.MISSED)
        val expected = "call-state-" + sha256("${A_SESSION_ID.value}|${A_ROOM_ID.value}|${AN_EVENT_ID.value}|MISSED")
        assertThat(coordinator.startedRequests.map { it.operationId }).containsExactly(expected)
        assertThat(coordinator.startedRequests.single().lifecycleId)
            .isEqualTo(io.element.android.security.dataprotection.api.FullAppLifecycleId.LC_15)
        assertThat(coordinator.startedRequests.single().roomIdHash).isEqualTo(sha256(A_ROOM_ID.value))
        assertThat(coordinator.startedRequests.single().eventIdHash).isEqualTo(sha256(AN_EVENT_ID.value))
        assertThat(coordinator.clientReceipts.map { it.operationId }).containsExactly(expected, expected)
        assertThat(coordinator.clientReceipts.map { it.component }).containsExactly(
            io.element.android.security.dataprotection.api.LifecycleComponent.ANDROID_SERVICE,
            io.element.android.security.dataprotection.api.LifecycleComponent.ANDROID_FRONTEND,
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `BUG039 expired original write survives next same room timer cancellation`() = runTest {
        setupShadowPowerManager()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val writes = mutableListOf<Pair<EventId, CallLifecycleState>>()
        val items = MutableStateFlow<List<MatrixTimelineItem>>(listOf(invitation()))
        val timeline = FakeTimeline(timelineItems = items).apply {
            sendCallLifecycleLambda = { id, state, _ ->
                if (id == AN_EVENT_ID && state == CallLifecycleState.MISSED) gate.await()
                writes += id to state
                Result.success(Unit)
            }
        }
        val room = FakeJoinedRoom(liveTimeline = timeline).apply { givenRoomInfo(aRoomInfo(isDm = true)) }
        val client = FakeMatrixClient().apply { givenGetRoomResult(A_ROOM_ID, room) }
        val manager = createActiveCallManager(matrixClientProvider = FakeMatrixClientProvider { Result.success(client) },
            systemClock = FakeSystemClock().apply { epochMillisResult = 100 }, nextCalls = true)
        val first = aCallNotificationData(timestamp = 10, expirationTimestamp = 1_000)
        manager.registerIncomingCall(first)
        runCurrent()
        advanceTimeBy(900)
        runCurrent()
        assertThat(manager.activeCall.value).isNull()
        items.value = listOf(invitation(), invitation(AN_EVENT_ID_2, 20))
        val second = first.copy(eventId = AN_EVENT_ID_2, timestamp = 20, expirationTimestamp = 5_000)
        manager.registerIncomingCall(second)
        runCurrent()
        val secondCall = requireNotNull(manager.activeCall.value).callData
        manager.hangUpCall(secondCall, second)
        gate.complete(Unit)
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertThat(writes).containsExactly(AN_EVENT_ID to CallLifecycleState.MISSED, AN_EVENT_ID_2 to CallLifecycleState.DECLINED)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `BUG039 authoritative terminal before deadline suppresses local MISSED`() = runTest {
        for (terminal in listOf(CallLifecycleState.DECLINED, CallLifecycleState.CANCELLED, CallLifecycleState.MISSED, CallLifecycleState.ENDED)) {
            setupShadowPowerManager()
            val writes = mutableListOf<CallLifecycleState>()
            val items = MutableStateFlow<List<MatrixTimelineItem>>(listOf(invitation()))
            val timeline = FakeTimeline(timelineItems = items).apply {
                sendCallLifecycleLambda = { _, state, _ -> writes += state; Result.success(Unit) }
            }
            val room = FakeJoinedRoom(liveTimeline = timeline).apply { givenRoomInfo(aRoomInfo(isDm = true)) }
            val client = FakeMatrixClient().apply { givenGetRoomResult(A_ROOM_ID, room) }
            val manager = createActiveCallManager(matrixClientProvider = FakeMatrixClientProvider { Result.success(client) },
                systemClock = FakeSystemClock().apply { epochMillisResult = 100 }, nextCalls = true)
            manager.registerIncomingCall(aCallNotificationData(timestamp = 10, expirationTimestamp = 1_000))
            runCurrent()
            items.value = listOf(invitation(state = terminal))
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()
            assertThat(manager.activeCall.value).isNull()
            assertThat(writes).isEmpty()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `BUG039 accepted media and explicit decline never expire as MISSED`() = runTest {
        for (accepted in listOf(false, true)) {
            setupShadowPowerManager()
            val writes = mutableListOf<CallLifecycleState>()
            val timeline = FakeTimeline(timelineItems = MutableStateFlow(listOf(invitation()))).apply {
                sendCallLifecycleLambda = { _, state, _ -> writes += state; Result.success(Unit) }
            }
            val room = FakeJoinedRoom(liveTimeline = timeline).apply { givenRoomInfo(aRoomInfo(isDm = true)) }
            val client = FakeMatrixClient().apply { givenGetRoomResult(A_ROOM_ID, room) }
            val manager = createActiveCallManager(matrixClientProvider = FakeMatrixClientProvider { Result.success(client) },
                systemClock = FakeSystemClock().apply { epochMillisResult = 100 }, nextCalls = true)
            val notification = aCallNotificationData(timestamp = 10, expirationTimestamp = 1_000)
            manager.registerIncomingCall(notification)
            runCurrent()
            val call = requireNotNull(manager.activeCall.value).callData
            if (accepted) {
                manager.joinedCall(call)
                manager.mediaConnected(call)
            } else manager.hangUpCall(call, notification)
            advanceTimeBy(1_000)
            runCurrent()
            if (accepted) {
                assertThat(manager.activeCall.value?.callState).isEqualTo(CallState.InCall)
                manager.hangUpCall(call)
            }
            assertThat(writes).containsExactlyElementsIn(if (accepted) listOf(CallLifecycleState.CONNECTED, CallLifecycleState.ENDED)
                else listOf(CallLifecycleState.DECLINED))
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `BUG039 failed protected timeout write has no applied receipts or optimistic card`() = runTest {
        setupShadowPowerManager()
        val coordinator = io.element.android.security.dataprotection.test.FakeAppOperationalStateCoordinator()
        val items = MutableStateFlow<List<MatrixTimelineItem>>(listOf(invitation()))
        val timeline = FakeTimeline(timelineItems = items).apply {
            sendCallLifecycleLambda = { _, _, _ ->
                kotlinx.coroutines.delay(1)
                Result.failure(IllegalStateException("authority unavailable"))
            }
        }
        val room = FakeJoinedRoom(liveTimeline = timeline).apply { givenRoomInfo(aRoomInfo(isDm = true)) }
        val client = FakeMatrixClient().apply { givenGetRoomResult(A_ROOM_ID, room) }
        val manager = createActiveCallManager(matrixClientProvider = FakeMatrixClientProvider { Result.success(client) },
            coordinator = coordinator, systemClock = FakeSystemClock().apply { epochMillisResult = 100 }, nextCalls = true)
        manager.registerIncomingCall(aCallNotificationData(timestamp = 10, expirationTimestamp = 1_000))
        runCurrent()
        advanceTimeBy(901)
        runCurrent()
        assertThat(manager.activeCall.value).isNull()
        assertThat(coordinator.startedRequests).hasSize(1)
        assertThat(coordinator.clientReceipts).isEmpty()
        assertThat((items.value.single() as MatrixTimelineItem.Event).event.content)
            .isEqualTo(CallNotifyContent(CallIntent.VIDEO, emptyList()))
    }

    private fun sha256(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun TestScope.createActiveCallManager(
        matrixClientProvider: FakeMatrixClientProvider = FakeMatrixClientProvider(),
        onMissedCallNotificationHandler: FakeOnMissedCallNotificationHandler = FakeOnMissedCallNotificationHandler(),
        notificationManagerCompat: NotificationManagerCompat = mockk(relaxed = true),
        systemClock: FakeSystemClock = FakeSystemClock(),
        incomingCallRingtonePlayer: IncomingCallRingtonePlayer = mockk(relaxed = true),
        incomingCallForegroundServiceController: IncomingCallForegroundServiceController = mockk(relaxed = true),
        foregroundNotificationHost: ForegroundNotificationHost = mockk(relaxed = true),
        appForegroundStateService: FakeAppForegroundStateService = FakeAppForegroundStateService(),
        notificationBitmapLoader: io.element.android.libraries.push.api.notifications.NotificationBitmapLoader = FakeNotificationBitmapLoader(),
        nextCalls: Boolean = false,
        activeRoomsHolder: io.element.android.services.appnavstate.api.ActiveRoomsHolder = mockk(relaxed = true),
        coordinator: io.element.android.security.dataprotection.test.FakeAppOperationalStateCoordinator =
            io.element.android.security.dataprotection.test.FakeAppOperationalStateCoordinator(),
    ) = DefaultActiveCallManager(
        context = InstrumentationRegistry.getInstrumentation().targetContext,
        coroutineScope = backgroundScope,
        onMissedCallNotificationHandler = onMissedCallNotificationHandler,
        ringingCallNotificationCreator = RingingCallNotificationCreator(
            context = InstrumentationRegistry.getInstrumentation().targetContext,
            matrixClientProvider = matrixClientProvider,
            imageLoaderHolder = FakeImageLoaderHolder(),
            notificationBitmapLoader = notificationBitmapLoader,
        ),
        notificationManagerCompat = notificationManagerCompat,
        matrixClientProvider = matrixClientProvider,
        defaultCurrentCallService = DefaultCurrentCallService(),
        appForegroundStateService = appForegroundStateService,
        imageLoaderHolder = FakeImageLoaderHolder(),
        systemClock = systemClock,
        incomingCallRingtonePlayer = incomingCallRingtonePlayer,
        incomingCallForegroundServiceController = incomingCallForegroundServiceController,
        foregroundNotificationHost = foregroundNotificationHost,
        dataProtectionPolicyProvider = io.element.android.security.dataprotection.test.FakeDataProtectionPolicyProvider(),
        appOperationalStateCoordinator = coordinator,
        nextCalls = nextCalls,
        activeRoomsHolder = activeRoomsHolder,
        finalizationRecovery = mockk(relaxed = true),
    )
}

private class TestForegroundNotificationHost(
    var showResult: Boolean,
) : ForegroundNotificationHost {
    val shownIds = mutableListOf<Int>()
    override fun setResumed(resumed: Boolean) = Unit
    override fun showIncomingCall(id: Int, notification: Notification): Boolean {
        shownIds += id
        return showResult
    }
    override fun clearIncomingCall(id: Int) = Unit
    @Composable
    override fun Render(modifier: Modifier) = Unit
}
