/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.timeline

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.item.event.MessageContent
import io.element.android.libraries.matrix.impl.fixtures.factories.aRustEventTimelineItem
import io.element.android.libraries.matrix.impl.fixtures.factories.aRustTimelineItemContentMsgLike
import io.element.android.libraries.matrix.impl.fixtures.fakes.FakeFfiTimelineItem
import io.element.android.libraries.matrix.test.timeline.aMessageContent
import io.element.android.security.contentprotection.api.TimelineContentReleaseIntent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.matrix.rustcomponents.sdk.EventOrTransactionId
import org.matrix.rustcomponents.sdk.TimelineDiff

@OptIn(ExperimentalCoroutinesApi::class)
class TimelineFirstFrameTest {
    @Test
    fun `valid cached viewport is shown without waiting for an uncached neighbour`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val items = MutableStateFlow<List<MatrixTimelineItem>>(emptyList())
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun timelineItemsShell(items: List<MatrixTimelineItem>) = items.map { it.project("", false) }
            override suspend fun hasTransientDecryptingShell(item: MatrixTimelineItem) = true
            override suspend fun cachedProjectionForCurrentAuthorization(item: MatrixTimelineItem) =
                item.takeIf { (it as? MatrixTimelineItem.Event)?.uniqueId?.value == "cached" }?.project("cached cleartext", true)
            override suspend fun decryptItem(item: MatrixTimelineItem): MatrixTimelineItem {
                gate.await()
                return item.project("slow cleartext", true)
            }
        }
        val processor = createMatrixTimelineDiffProcessor(items, protectedContentAdapter = adapter)
        val scope = TimelineCommitScope(201L)
        processor.activateCommitScope(scope)
        processor.commitDiffs(TimelineDiffTransaction(scope, 1L, 1L,
            listOf(TimelineDiff.Clear, TimelineDiff.Reset(listOf(carrier("cached"), carrier("slow"))))))
        assertThat(items.value.map { it.body() }).containsExactly("cached cleartext", "").inOrder()
        gate.complete(Unit)
    }

    @Test
    fun `authenticated recent arrival is displayed while initial old history is still waiting`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val items = MutableStateFlow<List<MatrixTimelineItem>>(emptyList())
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun timelineItemsShell(items: List<MatrixTimelineItem>) = items.map { it.project("", false) }
            override suspend fun hasTransientDecryptingShell(item: MatrixTimelineItem) = true
            override suspend fun decryptItem(item: MatrixTimelineItem): MatrixTimelineItem {
                if ((item as? MatrixTimelineItem.Event)?.uniqueId?.value == "old") gate.await()
                return item.project("readable recent", true)
            }
            override suspend fun decryptItem(item: MatrixTimelineItem, timelineReleaseIntent: TimelineContentReleaseIntent) = decryptItem(item)
        }
        val processor = createMatrixTimelineDiffProcessor(items, protectedContentAdapter = adapter)
        val scope = TimelineCommitScope(202L)
        processor.activateCommitScope(scope)
        processor.commitDiffs(TimelineDiffTransaction(scope, 1L, 1L, listOf(TimelineDiff.Clear, TimelineDiff.Reset(listOf(carrier("old"))))))
        runCurrent()
        assertThat(items.value).isEmpty()
        processor.commitDiffs(TimelineDiffTransaction(scope, 2L, 1L, listOf(TimelineDiff.PushBack(carrier("recent")))))
        runCurrent()
        assertThat(items.value.map { it.body() }).contains("readable recent")
        assertThat(gate.isCompleted).isFalse()
        gate.complete(Unit)
    }

    private fun carrier(id: String) = FakeFfiTimelineItem(
        uniqueId = id,
        asEventResult = aRustEventTimelineItem(isOwn = false,
            eventOrTransactionId = EventOrTransactionId.EventId("\$" + id), content = aRustTimelineItemContentMsgLike("encrypted carrier")),
    )

    private fun MatrixTimelineItem.project(body: String, authorized: Boolean): MatrixTimelineItem {
        val event = this as? MatrixTimelineItem.Event ?: return this
        return event.copy(event = event.event.copy(content = aMessageContent(body).copy(isContentProtectionAuthorized = authorized)))
    }

    private fun MatrixTimelineItem.body() = ((this as? MatrixTimelineItem.Event)?.event?.content as? MessageContent)?.body
}
