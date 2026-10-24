/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.messages.impl.timeline

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.UniqueId
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.item.event.ProtectedMessageStatus
import io.element.android.libraries.matrix.api.timeline.item.event.ProtectedOperationContent
import io.element.android.libraries.matrix.api.timeline.item.event.TextMessageType
import io.element.android.libraries.matrix.api.timeline.item.virtual.VirtualTimelineItem
import io.element.android.libraries.matrix.test.timeline.aMessageContent
import io.element.android.libraries.matrix.test.timeline.anEventTimelineItem
import org.junit.Test

class InitialHistoryTailTest {
    private val owner = Any()
    private val sut = InitialHistoryPresentation()
    private val file = item("older-file", ProtectedMessageStatus.ATTACHMENT_DECRYPTING)
    private val text = item("nearer-text", ProtectedMessageStatus.DECRYPTING)
    private val latest = item("latest-qa")

    @Test
    fun `an already ready first cache snapshot takes the fast path and late history cannot rearm it`() {
        assertThat(project(listOf(latest)).loading).isFalse()
        val late = listOf(file, text, latest)
        assertThat(project(late).items).isSameInstanceAs(late)
        assertThat(project(late).loading).isFalse()
    }

    @Test
    fun `ready recent rows expand upward without exposing an older pending attachment`() {
        val initial = project(listOf(file, text, latest))
        assertThat(initial.items).containsExactly(latest)
        assertThat(initial.loading).isTrue()
        val readyText = item("nearer-text")
        val next = project(listOf(file, readyText, latest))
        assertThat(next.items).containsExactly(readyText, latest).inOrder()
        assertThat(next.items.last()).isSameInstanceAs(latest)
        assertThat(next.loading).isTrue()
        val ready = listOf(item("older-file"), readyText, latest)
        val completed = project(ready)
        assertThat(completed.items).isSameInstanceAs(ready)
        assertThat(completed.loading).isFalse()
    }

    @Test
    fun `all newest bodies pending produces a loading region without a fabricated message`() {
        val result = project(listOf(item("older-ready"), text))
        assertThat(result.items).isEmpty()
        assertThat(result.loading).isTrue()
    }

    @Test
    fun `empty first source does not consume the first meaningful capture`() {
        val empty = project(emptyList())
        assertThat(empty.loading).isTrue()
        assertThat(project(listOf(file, text, latest)).items).containsExactly(latest)
    }

    @Test
    fun `explicit failure completes the gap without inventing a successful body`() {
        project(listOf(text, latest))
        val failure = item("nearer-text", ProtectedMessageStatus.TEMPORARILY_UNAVAILABLE)
        val result = project(listOf(failure, latest))
        assertThat(result.loading).isFalse()
        assertThat(result.items).containsExactly(failure, latest).inOrder()
        assertThat((failure.event.content as io.element.android.libraries.matrix.api.timeline.item.event.MessageContent)
            .isContentProtectionAuthorized).isFalse()
    }

    @Test
    fun `older pagination outside the thirty identity window does not extend pending readiness`() {
        val source = (0 until 100).map { item("row-$it", if (it == 99) ProtectedMessageStatus.DECRYPTING else null) }
        assertThat(project(source).items).isEmpty()
        val paginated = listOf(item("older-page", ProtectedMessageStatus.DECRYPTING)) + source.drop(1)
        assertThat(project(paginated).loading).isTrue()
        val completeInitial = paginated.dropLast(1) + item("row-99")
        val result = project(completeInitial)
        assertThat(result.loading).isFalse()
        assertThat(result.items).isSameInstanceAs(completeInitial)
    }

    @Test
    fun `hidden operations and virtual metadata cannot block or refill the frozen window`() {
        project(listOf(file, text, latest))
        val hidden = MatrixTimelineItem.Event(UniqueId("hidden"), anEventTimelineItem(
            eventId = EventId("\$hidden"), isRemote = true,
            content = ProtectedOperationContent(1, "hidden-op", ProtectedOperationContent.Operation.HIDDEN, "", null),
        ))
        val divider = MatrixTimelineItem.Virtual(UniqueId("divider"), VirtualTimelineItem.DayDivider(0L))
        val result = project(listOf(file, hidden, divider, text, latest))
        assertThat(result.loading).isTrue()
        assertThat(result.items).containsExactly(latest)
    }

    @Test
    fun `a late meaningful insertion retires the shaping once instead of growing or recapturing`() {
        project(listOf(file, text, latest))
        val changed = listOf(file, item("late-initial", ProtectedMessageStatus.DECRYPTING), text, latest)
        assertThat(project(changed).loading).isFalse()
        val originalAgain = listOf(file, text, latest)
        assertThat(project(originalAgain).items).isSameInstanceAs(originalAgain)
        assertThat(project(originalAgain).loading).isFalse()
    }

    @Test
    fun `revalidation uses the current projection rather than retaining a readable prior body`() {
        project(listOf(file, text, latest))
        val current = listOf(file, text, item("latest-qa", ProtectedMessageStatus.DECRYPTING))
        val result = project(current)
        assertThat(result.loading).isFalse()
        assertThat(result.items).isSameInstanceAs(current)
        assertThat(result.items).doesNotContain(latest)
    }

    @Test
    fun `new stream arrivals and current local echoes bypass the initial gap even after display reordering`() {
        project(listOf(file, text, latest))
        val incoming = item("new-live")
        val local = item("local-echo")
        val source = listOf(file, text, latest, incoming)
        val result = sut.project(owner, true, source, listOf(incoming, local, file, text, latest), false,
            immediateIds = setOf(local.uniqueId))
        assertThat(result.loading).isTrue()
        assertThat(result.items).containsExactly(incoming, local, latest).inOrder()
    }

    private fun project(items: List<MatrixTimelineItem>): InitialHistoryProjection =
        sut.project(owner, eligible = true, sourceItems = items, orderedItems = items, historyStartReached = false)

    private fun item(id: String, status: ProtectedMessageStatus? = null): MatrixTimelineItem.Event {
        val body = if (status == null) id else ""
        return MatrixTimelineItem.Event(UniqueId(id), anEventTimelineItem(
            eventId = EventId("\$$id"), isRemote = true,
            content = aMessageContent().copy(body = body, type = TextMessageType(body, null),
                protectedMessageStatus = status, isContentProtectionAuthorized = status == null),
        ))
    }
}
