/*
 * Copyright (c) 2026 Element Creations Ltd.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.messages.impl.timeline.location

import com.google.common.truth.Truth.assertThat
import io.element.android.features.location.api.live.PausedLiveLocationShare
import io.element.android.features.location.test.FakeActiveLiveLocationShareManager
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemLocationContent
import io.element.android.features.messages.impl.timeline.model.event.aLiveLocationMode
import io.element.android.features.messages.impl.timeline.model.event.aStaticLocationMode
import io.element.android.features.messages.impl.timeline.model.event.aTimelineItemLocationContent
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.test.A_ROOM_ID
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.tests.testutils.WarmUpRule
import io.element.android.tests.testutils.test
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class LiveLocationItemPresenterTest {
    @get:Rule val warmUpRule = WarmUpRule()
    private val room = FakeJoinedRoom()
    private val deadline = System.currentTimeMillis() + 60_000L

    @Test fun `only own original active aggregate gets local pause without changing deadline`() = runTest {
        val manager = FakeActiveLiveLocationShareManager()
        manager.givenPausedShare(A_ROOM_ID, PausedLiveLocationShare.RECOVERABLE, EventId("\$yizhe-live-original"))
        val original = aTimelineItemLocationContent(senderId = room.sessionId,
            mode = aLiveLocationMode(true, endTimestamp = deadline).copy(protectedAggregateId = "yizhe-live-original"))
        LiveLocationItemPresenter(original, room, manager).test {
            var state = awaitItem()
            if ((state.mode as TimelineItemLocationContent.Mode.Live).pausedState == null) state = awaitItem()
            val mode = state.mode as TimelineItemLocationContent.Mode.Live
            assertThat(mode.pausedState).isEqualTo(PausedLiveLocationShare.RECOVERABLE)
            assertThat(mode.isActive).isTrue()
            assertThat(mode.endTimestamp).isEqualTo(deadline)
            assertThat(mode.protectedAggregateId).isEqualTo("yizhe-live-original")
            assertThat(mode.canStopSharing).isTrue()
            assertThat(manager.sharingRoomIds.value).isEmpty()
        }
    }

    @Test fun `other beacon in same room does not inherit pause`() = runTest {
        val manager = FakeActiveLiveLocationShareManager()
        manager.givenPausedShare(A_ROOM_ID, PausedLiveLocationShare.RECOVERABLE, EventId("\$yizhe-live-original"))
        val item = aTimelineItemLocationContent(senderId = room.sessionId,
            mode = aLiveLocationMode(true, endTimestamp = deadline).copy(protectedAggregateId = "yizhe-live-other"))
        LiveLocationItemPresenter(item, room, manager).test {
            assertThat((awaitItem().mode as TimelineItemLocationContent.Mode.Live).pausedState).isNull()
        }
    }

    @Test fun `same user in another room cannot inherit own paused projection`() = runTest {
        val manager = FakeActiveLiveLocationShareManager()
        manager.givenPausedShare(RoomId("!other:matrix.org"), PausedLiveLocationShare.RECOVERABLE, EventId("\$yizhe-live-original"))
        val item = aTimelineItemLocationContent(senderId = room.sessionId,
            mode = aLiveLocationMode(true, endTimestamp = deadline).copy(protectedAggregateId = "yizhe-live-original"))
        LiveLocationItemPresenter(item, room, manager).test {
            assertThat((awaitItem().mode as TimelineItemLocationContent.Mode.Live).pausedState).isNull()
        }
    }

    @Test fun `other user in the same room cannot inherit own paused projection`() = runTest {
        val manager = FakeActiveLiveLocationShareManager()
        manager.givenPausedShare(A_ROOM_ID, PausedLiveLocationShare.RECOVERABLE, EventId("\$yizhe-live-original"))
        val item = aTimelineItemLocationContent(senderId = UserId("@other:matrix.org"),
            mode = aLiveLocationMode(true, isOwnUser = false, endTimestamp = deadline).copy(protectedAggregateId = "yizhe-live-original"))
        LiveLocationItemPresenter(item, room, manager).test {
            assertThat((awaitItem().mode as TimelineItemLocationContent.Mode.Live).pausedState).isNull()
        }
    }

    @Test fun `incorrect own user flag does not override sender identity`() = runTest {
        val manager = FakeActiveLiveLocationShareManager()
        manager.givenPausedShare(A_ROOM_ID, PausedLiveLocationShare.RECOVERABLE, EventId("\$yizhe-live-original"))
        val item = aTimelineItemLocationContent(senderId = UserId("@other:matrix.org"),
            mode = aLiveLocationMode(true, endTimestamp = deadline).copy(protectedAggregateId = "yizhe-live-original"))
        LiveLocationItemPresenter(item, room, manager).test {
            assertThat((awaitItem().mode as TimelineItemLocationContent.Mode.Live).pausedState).isNull()
        }
    }

    @Test fun `original deadline expiry wins over paused projection`() = runTest {
        val manager = FakeActiveLiveLocationShareManager()
        manager.givenPausedShare(A_ROOM_ID, PausedLiveLocationShare.RECOVERABLE, EventId("\$yizhe-live-original"))
        val item = aTimelineItemLocationContent(senderId = room.sessionId,
            mode = aLiveLocationMode(true, endTimestamp = 1L).copy(protectedAggregateId = "yizhe-live-original"))
        LiveLocationItemPresenter(item, room, manager).test {
            val mode = awaitItem().mode as TimelineItemLocationContent.Mode.Live
            assertThat(mode.isActive).isFalse()
            assertThat(mode.pausedState).isNull()
            assertThat(mode.endTimestamp).isEqualTo(1L)
            assertThat(mode.canStopSharing).isFalse()
        }
    }

    @Test fun `ended and static locations retain their authoritative state`() = runTest {
        val manager = FakeActiveLiveLocationShareManager()
        manager.givenPausedShare(A_ROOM_ID, PausedLiveLocationShare.RECOVERABLE, EventId("\$yizhe-live-original"))
        val ended = aTimelineItemLocationContent(senderId = room.sessionId,
            mode = aLiveLocationMode(false, endTimestamp = deadline).copy(protectedAggregateId = "yizhe-live-original"))
        LiveLocationItemPresenter(ended, room, manager).test {
            assertThat((awaitItem().mode as TimelineItemLocationContent.Mode.Live).pausedState).isNull()
        }
        val static = aTimelineItemLocationContent(mode = aStaticLocationMode())
        LiveLocationItemPresenter(static, room, manager).test { assertThat(awaitItem()).isEqualTo(static) }
    }
}
