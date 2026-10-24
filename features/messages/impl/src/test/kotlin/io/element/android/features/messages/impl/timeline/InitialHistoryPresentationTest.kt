/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.messages.impl.timeline

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.UniqueId
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.item.event.ProtectedMessageStatus
import io.element.android.libraries.matrix.api.timeline.item.event.TextMessageType
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.libraries.matrix.test.timeline.FakeTimeline
import io.element.android.libraries.matrix.test.timeline.aMessageContent
import io.element.android.libraries.matrix.test.timeline.anEventTimelineItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class InitialHistoryPresentationTest {
    @Test
    fun `older attachment does not pass a nearer pending text on first live entry`() = runTest {
        val attachment = item("older-file", ProtectedMessageStatus.ATTACHMENT_DECRYPTING)
        val pendingText = item("nearer-text", ProtectedMessageStatus.DECRYPTING)
        val latest = item("latest-qa")
        withController(listOf(attachment, pendingText, latest)) { controller ->
            assertThat(controller.timelinePresentation().first().items).containsExactly(latest)
        }
    }

    @Test
    fun `older readable body does not fill the screen ahead of the newest pending body`() = runTest {
        withController(listOf(item("older-readable"), item("latest-pending", ProtectedMessageStatus.DECRYPTING))) { controller ->
            assertThat(controller.timelinePresentation().first().items).isEmpty()
        }
    }

    @Test
    fun `fully ready cached history stays immediately available`() = runTest {
        val ready = listOf(item("older-ready"), item("latest-ready"))
        withController(ready) { controller ->
            assertThat(controller.timelinePresentation().first().items).containsExactlyElementsIn(ready).inOrder()
        }
    }

    private suspend fun withController(items: List<MatrixTimelineItem>, block: suspend (TimelineController) -> Unit) {
        val timeline = FakeTimeline(timelineItems = flowOf(items))
        val controller = TimelineController(FakeJoinedRoom(liveTimeline = timeline), timeline, requireContentProtection = true)
        try {
            block(controller)
        } finally {
            controller.close()
        }
    }

    private fun item(id: String, status: ProtectedMessageStatus? = null): MatrixTimelineItem.Event {
        val body = if (status == null) id else ""
        return MatrixTimelineItem.Event(UniqueId(id), anEventTimelineItem(
            eventId = EventId("\$$id"), isRemote = true,
            content = aMessageContent().copy(
                body = body, type = TextMessageType(body, formatted = null),
                protectedMessageStatus = status, isContentProtectionAuthorized = status == null,
            ),
        ))
    }
}
