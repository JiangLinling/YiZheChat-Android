/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.messages.impl.timeline

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.UniqueId
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.matrix.api.timeline.item.event.ProtectedMessageStatus
import io.element.android.libraries.matrix.api.timeline.item.event.TextMessageType
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.libraries.matrix.test.timeline.FakeTimeline
import io.element.android.libraries.matrix.test.timeline.aMessageContent
import io.element.android.libraries.matrix.test.timeline.anEventTimelineItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class InitialHistoryControllerLifecycleTest {
    private val file = item("older-file", ProtectedMessageStatus.ATTACHMENT_DECRYPTING)
    private val pending = item("nearer-text", ProtectedMessageStatus.DECRYPTING)
    private val latest = item("latest-qa")

    @Test
    fun `full authorized projection remains available to original cleanup while UI holds older rows`() = runTest {
        val timeline = FakeTimeline(timelineItems = flowOf(listOf(file, pending, latest)))
        val sut = TimelineController(FakeJoinedRoom(liveTimeline = timeline), timeline, requireContentProtection = true)
        try {
            val presentation = sut.timelinePresentation().first()
            assertThat(presentation.items).containsExactly(latest)
            assertThat(presentation.completeItems).containsExactly(file, latest).inOrder()
            assertThat(sut.timelineItems().first()).containsExactly(file, latest).inOrder()
        } finally { sut.close() }
    }

    @Test
    fun `same entry expands the tail then releases an explicit failure without permanent loading`() = runTest {
        val source = MutableStateFlow(listOf(file, pending, latest))
        val timeline = FakeTimeline(timelineItems = source)
        val sut = TimelineController(FakeJoinedRoom(liveTimeline = timeline), timeline, requireContentProtection = true)
        try {
            sut.timelinePresentation().test {
                val first = awaitItem()
                assertThat(first.items).containsExactly(latest)
                assertThat(first.initialHistoryLoading).isNotNull()
                val readyText = item("nearer-text")
                source.value = listOf(file, readyText, latest)
                val next = awaitItem()
                assertThat(next.items).containsExactly(readyText, latest).inOrder()
                assertThat(next.initialHistoryLoading).isSameInstanceAs(first.initialHistoryLoading)
                val unavailable = item("older-file", ProtectedMessageStatus.TEMPORARILY_UNAVAILABLE)
                source.value = listOf(unavailable, readyText, latest)
                val done = awaitItem()
                assertThat(done.initialHistoryLoading).isNull()
                assertThat(done.items).containsExactly(unavailable, readyText, latest).inOrder()
                cancelAndIgnoreRemainingEvents()
            }
        } finally { sut.close() }
    }

    @Test
    fun `old collection callback cannot retire a newer collection and genuine browse keeps authorization filtering`() = runTest {
        val timeline = FakeTimeline(timelineItems = flowOf(listOf(file, pending, latest)))
        val sut = TimelineController(FakeJoinedRoom(liveTimeline = timeline), timeline, requireContentProtection = true)
        try {
            val old = checkNotNull(sut.timelinePresentation().first().initialHistoryLoading)
            sut.timelinePresentation().test {
                val current = awaitItem()
                val control = checkNotNull(current.initialHistoryLoading)
                assertThat(control).isNotSameInstanceAs(old)
                old.retire()
                assertThat(control.retired.value).isFalse()
                expectNoEvents()
                control.retire()
                val browsing = awaitItem()
                assertThat(browsing.initialHistoryLoading).isNull()
                assertThat(browsing.items).containsExactly(file, latest).inOrder()
                assertThat(browsing.items).doesNotContain(pending)
                cancelAndIgnoreRemainingEvents()
            }
        } finally { sut.close() }
    }

    @Test
    fun `focused timeline and return to live cannot rearm the first-entry shaping`() = runTest {
        val source = listOf(file, pending, latest)
        val focused = FakeTimeline(mode = Timeline.Mode.FocusedOnEvent(EventId("\$target")), timelineItems = flowOf(source))
        val live = FakeTimeline(timelineItems = flowOf(source))
        val room = FakeJoinedRoom(liveTimeline = live, createTimelineResult = { Result.success(focused) })
        val sut = TimelineController(room, live, requireContentProtection = true)
        try {
            sut.timelinePresentation().test {
                assertThat(awaitItem().initialHistoryLoading).isNotNull()
                assertThat(sut.focusOnEvent(EventId("\$target"), null).isSuccess).isTrue()
                val detached = awaitItem()
                assertThat(detached.initialHistoryLoading).isNull()
                assertThat(detached.items).containsExactly(file, latest).inOrder()
                sut.focusOnLive()
                val returned = awaitItem()
                assertThat(returned.initialHistoryLoading).isNull()
                assertThat(returned.items).containsExactly(file, latest).inOrder()
                cancelAndIgnoreRemainingEvents()
            }
        } finally { sut.close() }
    }

    private fun item(id: String, status: ProtectedMessageStatus? = null): MatrixTimelineItem.Event {
        val body = if (status == null) id else ""
        return MatrixTimelineItem.Event(UniqueId(id), anEventTimelineItem(eventId = EventId("\$$id"), isRemote = true,
            content = aMessageContent().copy(body = body, type = TextMessageType(body, null),
                protectedMessageStatus = status, isContentProtectionAuthorized = status == null)))
    }
}
