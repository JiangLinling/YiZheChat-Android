/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.push.impl.notifications

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.notification.CallIntent
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.matrix.api.roomlist.RoomList
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.item.event.CallNotifyContent
import io.element.android.libraries.matrix.test.AN_EVENT_ID
import io.element.android.libraries.matrix.test.A_ROOM_ID
import io.element.android.libraries.matrix.test.A_ROOM_ID_2
import io.element.android.libraries.matrix.test.A_SESSION_ID
import io.element.android.libraries.matrix.test.A_USER_ID_2
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.libraries.matrix.test.FakeMatrixClientProvider
import io.element.android.libraries.matrix.test.notification.FakeNotificationService
import io.element.android.libraries.matrix.test.notification.aNotificationData
import io.element.android.libraries.matrix.test.room.FakeBaseRoom
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.libraries.matrix.test.roomlist.FakeDynamicRoomList
import io.element.android.libraries.matrix.test.timeline.FakeTimeline
import io.element.android.libraries.push.impl.notifications.fixtures.aNotifiableCallEvent
import io.element.android.services.appnavstate.api.AppNavigationState
import io.element.android.services.appnavstate.api.NavigationState
import io.element.android.services.appnavstate.impl.DefaultActiveRoomsHolder
import io.element.android.services.appnavstate.test.FakeAppNavigationStateService
import io.element.android.services.toolbox.test.systemclock.FakeSystemClock
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundCallRoomOwnershipTest {
    private class Fixture(scope: CoroutineScope) {
        val nav = FakeAppNavigationStateService(AppNavigationState(
            NavigationState.Room("room", A_ROOM_ID, NavigationState.Session("session", A_SESSION_ID)), true,
        ))
        val holder = DefaultActiveRoomsHolder()
        val visibleItems = MutableStateFlow<List<MatrixTimelineItem>>(emptyList())
        val fallbackItems = MutableStateFlow<List<MatrixTimelineItem>>(emptyList())
        val visible = spyk(FakeJoinedRoom(liveTimeline = FakeTimeline(timelineItems = visibleItems)))
        val fallback = spyk(FakeJoinedRoom(liveTimeline = FakeTimeline(timelineItems = fallbackItems)))
        var currentRoom: JoinedRoom? = visible
        val client = spyk(FakeMatrixClient(sessionCoroutineScope = scope).apply { givenGetRoomResult(A_ROOM_ID, fallback) })
        init {
            (client.roomListService.allRooms as FakeDynamicRoomList).loadingState.value = RoomList.LoadingState.Loaded(1)
            every { client.ownsCurrentRoom(any()) } answers { firstArg<JoinedRoom>() === currentRoom }
        }
        val observer = ForegroundCallInvitationObserver(nav, FakeMatrixClientProvider { Result.success(client) },
            FakeCallNotificationEventResolver(), FakeSystemClock(), holder)
    }

    @Test
    fun `temporary missing SDK room keeps listening for a later current UI owner`() = runTest {
        val f = Fixture(backgroundScope)
        coEvery { f.client.getJoinedRoom(A_ROOM_ID) } returns null
        val job = f.observer.invitations().launchIn(backgroundScope)
        runCurrent()
        assertThat(f.visibleItems.subscriptionCount.value).isEqualTo(0)
        assertThat(f.fallbackItems.subscriptionCount.value).isEqualTo(0)
        f.holder.addRoom(f.visible)
        runCurrent()
        assertThat(f.visibleItems.subscriptionCount.value).isEqualTo(1)
        assertThat(f.client.roomObservationWork.pendingCleanupCount()).isEqualTo(0)
        job.cancel()
        runCurrent()
        verify(exactly = 0) { f.visible.close() }
        verify(exactly = 0) { f.fallback.close() }
    }

    @Test
    fun `current client visible room is observed without constructing or closing a second room`() = runTest {
        val f = Fixture(backgroundScope)
        f.holder.addRoom(f.visible)
        val job = f.observer.invitations().launchIn(backgroundScope)
        runCurrent()
        assertThat(f.client.getJoinedRoomCalledCount).isEqualTo(0)
        assertThat(f.visibleItems.subscriptionCount.value).isEqualTo(1)
        job.cancel()
        runCurrent()
        assertThat(f.visibleItems.subscriptionCount.value).isEqualTo(0)
        verify(exactly = 0) { f.visible.close() }
    }

    @Test
    fun `same account room without current client provenance uses and closes an owned fallback`() = runTest {
        val f = Fixture(backgroundScope)
        f.currentRoom = null
        f.holder.addRoom(f.visible)
        val job = f.observer.invitations().launchIn(backgroundScope)
        runCurrent()
        assertThat(f.client.getJoinedRoomCalledCount).isEqualTo(1)
        assertThat(f.visibleItems.subscriptionCount.value).isEqualTo(0)
        assertThat(f.fallbackItems.subscriptionCount.value).isEqualTo(1)
        job.cancel()
        runCurrent()
        verify(exactly = 1) { f.fallback.close() }
        verify(exactly = 0) { f.visible.close() }
    }

    @Test
    fun `late UI publication retires fallback and unrelated changes do not restart observation`() = runTest {
        val f = Fixture(backgroundScope)
        val job = f.observer.invitations().launchIn(backgroundScope)
        runCurrent()
        assertThat(f.fallbackItems.subscriptionCount.value).isEqualTo(1)
        f.holder.addRoom(f.visible)
        runCurrent()
        assertThat(f.visibleItems.subscriptionCount.value).isEqualTo(1)
        assertThat(f.fallbackItems.subscriptionCount.value).isEqualTo(0)
        verify(exactly = 1) { f.fallback.close() }
        f.holder.addRoom(FakeJoinedRoom(baseRoom = FakeBaseRoom(roomId = A_ROOM_ID_2)))
        runCurrent()
        assertThat(f.client.getJoinedRoomCalledCount).isEqualTo(1)
        job.cancel()
        runCurrent()
        verify(exactly = 0) { f.visible.close() }
    }

    @Test
    fun `restoring a held old-generation room after pop still rejects it and owns only fallback`() = runTest {
        val f = Fixture(backgroundScope)
        f.holder.addRoom(f.visible)
        val currentItems = MutableStateFlow<List<MatrixTimelineItem>>(emptyList())
        val current = spyk(FakeJoinedRoom(liveTimeline = FakeTimeline(timelineItems = currentItems)))
        f.currentRoom = current
        f.holder.addRoom(current)
        val job = f.observer.invitations().launchIn(backgroundScope)
        runCurrent()
        assertThat(currentItems.subscriptionCount.value).isEqualTo(1)
        f.holder.removeRoom(current)
        runCurrent()
        assertThat(f.holder.getActiveRoomMatching(A_SESSION_ID, A_ROOM_ID)).isSameInstanceAs(f.visible)
        assertThat(f.visibleItems.subscriptionCount.value).isEqualTo(0)
        assertThat(f.fallbackItems.subscriptionCount.value).isEqualTo(1)
        job.cancel()
        runCurrent()
        verify(exactly = 0) { current.close() }
        verify(exactly = 0) { f.visible.close() }
        verify(exactly = 1) { f.fallback.close() }
    }

    @Test
    fun `old owner removal cannot remove or close its replacement`() = runTest {
        val f = Fixture(backgroundScope)
        f.holder.addRoom(f.visible)
        val job = f.observer.invitations().launchIn(backgroundScope)
        runCurrent()
        val nextItems = MutableStateFlow<List<MatrixTimelineItem>>(emptyList())
        val next = spyk(FakeJoinedRoom(liveTimeline = FakeTimeline(timelineItems = nextItems)))
        f.currentRoom = next
        f.holder.addRoom(next)
        runCurrent()
        assertThat(f.visibleItems.subscriptionCount.value).isEqualTo(0)
        assertThat(nextItems.subscriptionCount.value).isEqualTo(1)
        f.holder.removeRoom(f.visible)
        runCurrent()
        assertThat(f.holder.getActiveRoomMatching(A_SESSION_ID, A_ROOM_ID)).isSameInstanceAs(next)
        assertThat(f.client.getJoinedRoomCalledCount).isEqualTo(0)
        f.holder.removeRoom(next)
        runCurrent()
        assertThat(nextItems.subscriptionCount.value).isEqualTo(0)
        assertThat(f.fallbackItems.subscriptionCount.value).isEqualTo(1)
        job.cancel()
        runCurrent()
        verify(exactly = 0) { f.visible.close() }
        verify(exactly = 0) { next.close() }
        verify(exactly = 1) { f.fallback.close() }
    }

    @Test
    fun `switching to a late visible owner does not ring the same original invitation again`() = runTest {
        val f = Fixture(backgroundScope)
        val notifications = FakeNotificationService().apply {
            givenGetNotificationsResult(Result.success(mapOf(AN_EVENT_ID to Result.success(aNotificationData()))))
        }
        every { f.client.notificationService } returns notifications
        val callItem = mockk<MatrixTimelineItem.Event> {
            every { event } returns mockk(relaxed = true) {
                every { eventId } returns AN_EVENT_ID
                every { timestamp } returns 99_000L
                every { sender } returns A_USER_ID_2
                every { content } returns CallNotifyContent(CallIntent.AUDIO, emptyList())
            }
        }
        var resolutions = 0
        val resolver = FakeCallNotificationEventResolver { _, _, _ ->
            resolutions++
            Result.success(aNotifiableCallEvent().copy(eventId = AN_EVENT_ID, expirationTimestamp = 120_000))
        }
        val observer = ForegroundCallInvitationObserver(f.nav, FakeMatrixClientProvider { Result.success(f.client) }, resolver,
            FakeSystemClock().apply { epochMillisResult = 100_000 }, f.holder)
        f.fallbackItems.value = listOf(callItem)
        val job = observer.invitations().launchIn(backgroundScope)
        runCurrent()
        assertThat(resolutions).isEqualTo(1)
        f.visibleItems.value = listOf(callItem)
        f.holder.addRoom(f.visible)
        runCurrent()
        assertThat(f.visibleItems.subscriptionCount.value).isEqualTo(1)
        assertThat(resolutions).isEqualTo(1)
        job.cancel()
        runCurrent()
        verify(exactly = 0) { f.visible.close() }
    }

    @Test
    fun `repeated navigation cancellation keeps one session acquisition in flight while UI borrowing continues`() = runTest {
        val f = Fixture(backgroundScope)
        val acquired = CompletableDeferred<Unit>()
        var constructions = 0
        coEvery { f.client.getJoinedRoom(A_ROOM_ID) } coAnswers {
            constructions++
            acquired.await()
            f.fallback
        }
        repeat(3) {
            val job = f.observer.invitations().launchIn(backgroundScope)
            runCurrent()
            assertThat(constructions).isEqualTo(1)
            job.cancel()
            runCurrent()
            assertThat(job.isCompleted).isTrue()
        }
        f.holder.addRoom(f.visible)
        val borrowed = f.observer.invitations().launchIn(backgroundScope)
        runCurrent()
        assertThat(f.visibleItems.subscriptionCount.value).isEqualTo(1)
        assertThat(constructions).isEqualTo(1)
        acquired.complete(Unit)
        runCurrent()
        verify(exactly = 1) { f.fallback.close() }
        borrowed.cancel()
        runCurrent()
        verify(exactly = 0) { f.visible.close() }
    }

    @Test
    fun `failed late-room cleanup keeps session capacity pending across another observation`() = runTest {
        val f = Fixture(backgroundScope)
        val acquired = CompletableDeferred<Unit>()
        var constructions = 0
        coEvery { f.client.getJoinedRoom(A_ROOM_ID) } coAnswers {
            constructions++
            acquired.await()
            f.fallback
        }
        every { f.fallback.close() } throws IllegalStateException("owned close failed")
        val first = f.observer.invitations().launchIn(backgroundScope)
        runCurrent()
        first.cancel()
        runCurrent()
        acquired.complete(Unit)
        runCurrent()
        assertThat(f.client.roomObservationWork.pendingCleanupCount()).isEqualTo(1)
        val next = f.observer.invitations().launchIn(backgroundScope)
        runCurrent()
        assertThat(constructions).isEqualTo(1)
        assertThat(f.client.roomObservationWork.pendingCleanupCount()).isEqualTo(1)
        next.cancel()
        runCurrent()
        assertThat(next.isCompleted).isTrue()
    }

    @Test
    fun `navigation cancellation during local acquisition still closes the returned owned room`() = runTest {
        val f = Fixture(backgroundScope)
        val acquired = CompletableDeferred<Unit>()
        coEvery { f.client.getJoinedRoom(A_ROOM_ID) } coAnswers { acquired.await(); f.fallback }
        val job = f.observer.invitations().launchIn(backgroundScope)
        runCurrent()
        job.cancel()
        runCurrent()
        assertThat(job.isCompleted).isTrue()
        f.holder.addRoom(f.visible)
        val replacement = f.observer.invitations().launchIn(backgroundScope)
        runCurrent()
        assertThat(f.visibleItems.subscriptionCount.value).isEqualTo(1)
        // The original acquisition has still not returned; it must not block the replacement.
        assertThat(acquired.isCompleted).isFalse()
        acquired.complete(Unit)
        runCurrent()
        assertThat(f.fallbackItems.subscriptionCount.value).isEqualTo(0)
        verify(exactly = 1) { f.fallback.close() }
        replacement.cancel()
        runCurrent()
        verify(exactly = 0) { f.visible.close() }
    }
}
