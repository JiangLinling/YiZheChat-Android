package io.element.android.libraries.push.impl.notifications

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.notification.CallIntent
import io.element.android.libraries.matrix.api.roomlist.RoomList
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.item.event.CallLifecycleState
import io.element.android.libraries.matrix.api.timeline.item.event.CallNotifyContent
import io.element.android.libraries.matrix.test.*
import io.element.android.libraries.matrix.test.notification.FakeNotificationService
import io.element.android.libraries.matrix.test.notification.aNotificationData
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.libraries.matrix.test.roomlist.FakeDynamicRoomList
import io.element.android.libraries.matrix.test.timeline.FakeTimeline
import io.element.android.libraries.push.impl.notifications.fixtures.aNotifiableCallEvent
import io.element.android.libraries.push.impl.notifications.model.NotifiableRingingCallEvent
import io.element.android.services.appnavstate.api.*
import io.element.android.services.appnavstate.test.FakeActiveRoomsHolder
import io.element.android.services.appnavstate.test.FakeAppNavigationStateService
import io.element.android.services.toolbox.test.systemclock.FakeSystemClock
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundCallInvitationObserverTest {
    private fun invitation(id: EventId, at: Long = 100, state: CallLifecycleState? = null, own: Boolean = false): MatrixTimelineItem.Event = mockk {
        every { event } returns mockk(relaxed = true) {
            every { eventId } returns id
            every { timestamp } returns at
            every { sender } returns if (own) A_SESSION_ID else A_USER_ID_2
            every { content } returns CallNotifyContent(CallIntent.AUDIO, emptyList(), lifecycleState = state)
        }
    }

    @Test fun `foreground redial delivers new invitation once and skips terminal own stale and superseded events`() = runTest {
        val clock = FakeSystemClock().apply { epochMillisResult = 100_000 }
        val nav = FakeAppNavigationStateService(AppNavigationState(
            NavigationState.Room("room", A_ROOM_ID, NavigationState.Session("session", A_SESSION_ID)), true,
        ))
        val items = MutableStateFlow<List<MatrixTimelineItem>>(emptyList())
        val room = FakeJoinedRoom(liveTimeline = FakeTimeline(timelineItems = items))
        val service = FakeNotificationService().apply {
            givenGetNotificationsResult(Result.success(listOf(AN_EVENT_ID, AN_EVENT_ID_2).associateWith {
                Result.success(aNotificationData().copy(eventId = it))
            }))
        }
        val client = FakeMatrixClient(notificationService = service, sessionCoroutineScope = backgroundScope).apply { givenGetRoomResult(A_ROOM_ID, room) }
        val resolved = mutableListOf<EventId>()
        val resolver = FakeCallNotificationEventResolver { _, data, _ ->
            resolved += data.eventId
            Result.success(aNotifiableCallEvent().copy(eventId = data.eventId, expirationTimestamp = 120_000))
        }
        (client.roomListService.allRooms as FakeDynamicRoomList).loadingState.value =
            RoomList.LoadingState.Loaded(1)
        val observer = ForegroundCallInvitationObserver(nav, FakeMatrixClientProvider { Result.success(client) }, resolver, clock, FakeActiveRoomsHolder())
        val seen = mutableListOf<NotifiableRingingCallEvent>()
        observer.invitations().onEach { seen += it }.launchIn(backgroundScope)
        runCurrent()
        items.value = listOf(invitation(AN_EVENT_ID, at = 100), invitation(AN_EVENT_ID_2, at = 99_000, own = true))
        runCurrent()
        assertThat(seen).isEmpty()
        items.value = listOf(invitation(AN_EVENT_ID, at = 99_000))
        runCurrent()
        items.value = listOf(invitation(AN_EVENT_ID, at = 99_000), invitation(AN_EVENT_ID_2, at = 99_500, state = CallLifecycleState.CANCELLED))
        runCurrent()
        assertThat(seen.map { it.eventId }).containsExactly(AN_EVENT_ID)
        items.value = listOf(invitation(AN_EVENT_ID, at = 99_000, state = CallLifecycleState.CANCELLED), invitation(AN_EVENT_ID_2, at = 99_800))
        runCurrent()
        items.value = items.value.reversed()
        runCurrent()
        assertThat(seen.map { it.eventId }).containsExactly(AN_EVENT_ID, AN_EVENT_ID_2).inOrder()
        assertThat(resolved).containsExactly(AN_EVENT_ID, AN_EVENT_ID_2).inOrder()
    }

    @Test fun `leaving foreground room releases its independently acquired room and timeline subscription`() = runTest {
        val nav = FakeAppNavigationStateService(AppNavigationState(
            NavigationState.Room("room", A_ROOM_ID, NavigationState.Session("session", A_SESSION_ID)), true,
        ))
        val items = MutableStateFlow<List<MatrixTimelineItem>>(emptyList())
        val room = spyk(FakeJoinedRoom(liveTimeline = FakeTimeline(timelineItems = items)))
        val client = FakeMatrixClient(sessionCoroutineScope = backgroundScope).apply { givenGetRoomResult(A_ROOM_ID, room) }
        (client.roomListService.allRooms as FakeDynamicRoomList).loadingState.value =
            RoomList.LoadingState.Loaded(1)
        val observer = ForegroundCallInvitationObserver(
            nav, FakeMatrixClientProvider { Result.success(client) }, FakeCallNotificationEventResolver(), FakeSystemClock(), FakeActiveRoomsHolder(),
        )
        observer.invitations().launchIn(backgroundScope)
        runCurrent()
        assertThat(items.subscriptionCount.value).isEqualTo(1)
        verify(exactly = 0) { room.close() }
        nav.emitNavigationState(AppNavigationState(NavigationState.Root, true))
        runCurrent()
        verify(exactly = 1) { room.close() }
        assertThat(items.subscriptionCount.value).isEqualTo(0)
    }

    @Test fun `cancelling the observer releases its room while leaving navigation unchanged`() = runTest {
        val nav = FakeAppNavigationStateService(AppNavigationState(
            NavigationState.Room("room", A_ROOM_ID, NavigationState.Session("session", A_SESSION_ID)), true,
        ))
        val items = MutableStateFlow<List<MatrixTimelineItem>>(emptyList())
        val room = spyk(FakeJoinedRoom(liveTimeline = FakeTimeline(timelineItems = items)))
        val client = FakeMatrixClient(sessionCoroutineScope = backgroundScope).apply { givenGetRoomResult(A_ROOM_ID, room) }
        (client.roomListService.allRooms as FakeDynamicRoomList).loadingState.value =
            RoomList.LoadingState.Loaded(1)
        val observer = ForegroundCallInvitationObserver(
            nav, FakeMatrixClientProvider { Result.success(client) }, FakeCallNotificationEventResolver(), FakeSystemClock(), FakeActiveRoomsHolder(),
        )
        val job = observer.invitations().launchIn(backgroundScope)
        runCurrent()
        assertThat(items.subscriptionCount.value).isEqualTo(1)
        job.cancel()
        runCurrent()
        verify(exactly = 1) { room.close() }
        assertThat(items.subscriptionCount.value).isEqualTo(0)
    }
}
