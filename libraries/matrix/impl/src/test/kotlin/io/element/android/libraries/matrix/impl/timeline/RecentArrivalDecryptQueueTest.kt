/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.timeline

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.UniqueId
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.TransactionId
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.item.event.MessageContent
import io.element.android.libraries.matrix.api.timeline.item.event.TextMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.LocalEventSendState
import io.element.android.libraries.matrix.impl.fixtures.factories.aRustEventTimelineItem
import io.element.android.libraries.matrix.impl.fixtures.factories.aRustTimelineItemContentMsgLike
import io.element.android.libraries.matrix.impl.fixtures.fakes.FakeFfiTimelineItem
import io.element.android.libraries.matrix.test.timeline.anEventTimelineItem
import io.element.android.security.contentprotection.api.TimelineContentReleaseClass
import io.element.android.security.contentprotection.api.TimelineContentReleaseIntent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.matrix.rustcomponents.sdk.TimelineDiff
import org.matrix.rustcomponents.sdk.EventOrTransactionId as RustEventOrTransactionId

@OptIn(ExperimentalCoroutinesApi::class)
class RecentArrivalDecryptQueueTest {
    private fun item(id: String) = MatrixTimelineItem.Event(UniqueId(id), anEventTimelineItem())
    private fun queue(maximum: Int = 100, burst: Int = 3) = PendingDecryptQueue(maximum, burst)

    @Test
    fun `new own transaction enters recent scheduling without a visible claim`() {
        val pending = item("own").let { it.copy(event = it.event.copy(
            eventId = null, transactionId = TransactionId("transaction"), isOwn = true,
            localSendState = LocalEventSendState.Sending.Event,
        )) }
        assertThat(pending.isRecentTimelineWork(null)).isTrue()
        val queue = queue()
        queue.offer(emptyList(), listOf(item("old")), recentHistory = listOf(pending))
        assertThat(queue.pollVisible()).isNull()
        val work = checkNotNull(queue.pollVisibleOrHistory())
        assertThat(work.key).isEqualTo("event:own")
        assertThat(work.priority).isEqualTo(DecryptPriority.HISTORY)
    }

    @Test
    fun `own Matrix confirmation with unchanged unique key is recent work`() {
        val pending = item("own").let { it.copy(event = it.event.copy(
            eventId = null, transactionId = TransactionId("transaction"), isOwn = true,
            localSendState = LocalEventSendState.Sending.Event,
        )) }
        val sentId = EventId("\$confirmed")
        val confirmed = pending.copy(event = pending.event.copy(eventId = sentId, localSendState = LocalEventSendState.Sent(sentId)))
        assertThat(confirmed.uniqueId).isEqualTo(pending.uniqueId)
        assertThat(confirmed.isRecentTimelineWork(pending)).isTrue()
        assertThat(confirmed.isRecentTimelineWork(confirmed)).isFalse()
    }

    @Test
    fun `history metadata changes do not promote old remote or unchanged own events`() {
        val remote = item("remote")
        assertThat(remote.copy(event = remote.event.copy(timestamp = 42)).isRecentTimelineWork(remote)).isFalse()
        val own = remote.copy(event = remote.event.copy(isOwn = true))
        assertThat(own.copy(event = own.event.copy(timestamp = 42)).isRecentTimelineWork(own)).isFalse()
        assertThat(own.isRecentTimelineWork(null)).isFalse()
    }

    @Test
    fun `recent arrival precedes backlog without claiming visible priority`() {
        val queue = queue()
        queue.offer(emptyList(), listOf(item("old")))
        queue.offer(emptyList(), emptyList(), recentHistory = listOf(item("new")), releaseGeneration = 17L)
        assertThat(queue.pollVisible()).isNull()
        val recent = checkNotNull(queue.pollVisibleOrHistory())
        assertThat(recent.key).isEqualTo("event:new")
        assertThat(recent.priority).isEqualTo(DecryptPriority.HISTORY)
        assertThat(recent.releaseGeneration).isEqualTo(17L)
        queue.complete(recent)
        assertThat(queue.pollVisibleOrHistory()?.key).isEqualTo("event:old")
    }

    @Test
    fun `newer metadata while recent work is in flight keeps recent history scheduling`() {
        val queue = queue()
        val initial = item("new")
        val replacement = initial.copy(event = initial.event.copy(timestamp = 43))
        queue.offer(emptyList(), listOf(item("old")), recentHistory = listOf(initial),
            timelineScopeId = "current", rawStateGeneration = 1L)
        val inFlight = checkNotNull(queue.pollVisibleOrHistory())
        queue.offer(emptyList(), listOf(replacement), timelineScopeId = "current", rawStateGeneration = 2L)
        queue.complete(inFlight)
        val next = checkNotNull(queue.pollVisibleOrHistory())
        assertThat(next.key).isEqualTo("event:new")
        assertThat(next.item).isEqualTo(replacement)
        assertThat(next.recentHistory).isTrue()
        assertThat(next.priority).isEqualTo(DecryptPriority.HISTORY)
    }

    @Test
    fun `a new scope cannot inherit an old in flight recent scheduling hint`() {
        val queue = queue()
        val initial = item("new")
        queue.offer(emptyList(), listOf(item("old")), recentHistory = listOf(initial),
            timelineScopeId = "old-scope", rawStateGeneration = 1L)
        val inFlight = checkNotNull(queue.pollVisibleOrHistory())
        queue.offer(emptyList(), listOf(initial), timelineScopeId = "new-scope", rawStateGeneration = 2L)
        queue.complete(inFlight)
        val old = checkNotNull(queue.pollVisibleOrHistory())
        assertThat(old.key).isEqualTo("event:old")
        queue.complete(old)
        val next = checkNotNull(queue.pollVisibleOrHistory())
        assertThat(next.key).isEqualTo("event:new")
        assertThat(next.timelineScopeId).isEqualTo("new-scope")
        assertThat(next.recentHistory).isFalse()
    }

    @Test
    fun `later live batch precedes incremental snapshot restore batches too`() {
        val queue = queue()
        queue.offer(emptyList(), emptyList(), recentHistory = (0..39).map { item("restore-$it") }, rawStateGeneration = 1L)
        queue.offer(emptyList(), emptyList(), recentHistory = listOf(item("incoming")), rawStateGeneration = 2L)
        val work = checkNotNull(queue.pollVisibleOrHistory())
        assertThat(work.key).isEqualTo("event:incoming")
        assertThat(work.priority).isEqualTo(DecryptPriority.HISTORY)
        queue.complete(work)
        assertThat(queue.pendingSize()).isEqualTo(40)
    }

    @Test
    fun `stale recent offer cannot move an older value ahead of current arrivals`() {
        val queue = queue()
        queue.offer(emptyList(), emptyList(), recentHistory = listOf(item("first")), rawStateGeneration = 2L)
        queue.offer(emptyList(), emptyList(), recentHistory = listOf(item("latest")), rawStateGeneration = 3L)
        queue.offer(emptyList(), emptyList(), recentHistory = listOf(item("first")), rawStateGeneration = 1L)
        assertThat(queue.pollVisibleOrHistory()?.key).isEqualTo("event:latest")
    }

    @Test
    fun `recent burst retains bounded backlog fairness`() {
        val queue = queue(burst = 3)
        queue.offer(emptyList(), listOf(item("old")), recentHistory = (0..3).map { item("new-$it") })
        val keys = (0..4).map {
            val work = checkNotNull(queue.pollVisibleOrHistory())
            queue.complete(work)
            work.key
        }
        assertThat(keys).containsExactly("event:new-0", "event:new-1", "event:new-2", "event:old", "event:new-3").inOrder()
    }

    @Test
    fun `all pending categories share one capacity and visible precedes recent`() {
        val queue = queue(maximum = 2)
        queue.offer(listOf(item("visible")), listOf(item("old")), recentHistory = listOf(item("new")))
        assertThat(queue.pendingSize()).isEqualTo(2)
        val visible = checkNotNull(queue.pollVisibleOrHistory())
        assertThat(visible.key).isEqualTo("event:visible")
        queue.complete(visible)
        val recent = checkNotNull(queue.pollVisibleOrHistory())
        assertThat(recent.key).isEqualTo("event:new")
        queue.complete(recent)
        assertThat(queue.pollVisibleOrHistory()).isNull()
    }

    @Test
    fun `removal and scope close cannot revive a recent arrival`() {
        val queue = queue()
        queue.offer(emptyList(), emptyList(), recentHistory = listOf(item("new")), timelineScopeId = "closed", rawStateGeneration = 1L)
        val work = checkNotNull(queue.pollVisibleOrHistory())
        queue.invalidate(setOf(work.key), 2L)
        queue.complete(work, retry = true)
        assertThat(queue.pollVisibleOrHistory()).isNull()
        queue.offer(emptyList(), emptyList(), recentHistory = listOf(item("another")), timelineScopeId = "closed")
        val another = checkNotNull(queue.pollVisibleOrHistory())
        queue.cancelScope("closed")
        queue.complete(another, retry = true)
        assertThat(queue.pollVisibleOrHistory()).isNull()
    }

    @Test
    fun `measured promotion moves recent work to exact visible generation`() {
        val queue = queue()
        val incoming = item("new")
        queue.offer(emptyList(), emptyList(), recentHistory = listOf(incoming), timelineScopeId = "scope", rawStateGeneration = 1L)
        queue.reprioritize(listOf("event:new"), listOf(incoming), 1L, "scope", 2L)
        val promoted = checkNotNull(queue.pollVisible())
        assertThat(promoted.priority).isEqualTo(DecryptPriority.VISIBLE)
        assertThat(promoted.releaseGeneration).isEqualTo(2L)
        queue.complete(promoted)
        assertThat(queue.pollVisibleOrHistory()).isNull()
    }

    @Test
    fun `incremental arrival waits for at most existing workers not the entire history backlog`() = runTest {
        val historyPermits = Channel<Unit>(Channel.UNLIMITED)
        val observed = mutableListOf<TimelineContentReleaseIntent>()
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun timelineItemsShell(items: List<MatrixTimelineItem>) = items.map { it.safeTestProjection("") }
            override suspend fun hasTransientDecryptingShell(item: MatrixTimelineItem) = true
            override suspend fun decryptItem(item: MatrixTimelineItem, timelineReleaseIntent: TimelineContentReleaseIntent): MatrixTimelineItem {
                observed += timelineReleaseIntent
                if (timelineReleaseIntent.requestId.startsWith("event:history-")) historyPermits.receive()
                return item.safeTestProjection("authorized projection")
            }
        }
        val items = MutableStateFlow<List<MatrixTimelineItem>>(emptyList())
        val processor = createMatrixTimelineDiffProcessor(items, protectedContentAdapter = adapter)
        val scope = TimelineCommitScope(903L)
        processor.activateCommitScope(scope)
        processor.updateViewport(scope, 1L, listOf(UniqueId("live-bottom")))
        processor.commitDiffs(TimelineDiffTransaction(scope, 1L, 1L,
            listOf(TimelineDiff.Reset((0..39).map { raw("history-$it") } + raw("live-bottom")))))
        runCurrent()
        processor.commitDiffs(TimelineDiffTransaction(scope, 2L, 1L,
            listOf(TimelineDiff.PushBack(raw("live-arrival")))))
        runCurrent()
        assertThat(observed.any { it.requestId == "event:live-arrival" }).isFalse()

        // Free just one already-running history worker, not all forty history operations.
        historyPermits.trySend(Unit)
        runCurrent()
        advanceTimeBy(MatrixTimelineDiffProcessor.HISTORY_DECRYPT_BATCH_WINDOW_MILLIS + 1)
        runCurrent()
        val arrival = observed.single { it.requestId == "event:live-arrival" }
        assertThat(arrival.releaseClass).isEqualTo(TimelineContentReleaseClass.HISTORY_PREFETCH)
        assertThat(arrival.generation).isEqualTo(1L)
        assertThat(observed.count { it.requestId.startsWith("event:history-") }).isLessThan(40)
        val projectedArrival = items.value.filterIsInstance<MatrixTimelineItem.Event>()
            .single { it.uniqueId.value == "live-arrival" }.event.content as MessageContent
        assertThat(projectedArrival.body).isEqualTo("authorized projection")
    }

    @Test
    fun `stale recent result batch retries latest source before the full history backlog`() = runTest {
        val historyPermits = Channel<Unit>(Channel.UNLIMITED)
        val observed = mutableListOf<TimelineContentReleaseIntent>()
        val firstArrivalResult = CompletableDeferred<Unit>()
        val replacementEntered = CompletableDeferred<Unit>()
        val allowReplacement = CompletableDeferred<Unit>()
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun timelineItemsShell(items: List<MatrixTimelineItem>): List<MatrixTimelineItem> {
                if (items.any { ((it as? MatrixTimelineItem.Event)?.event?.content as? MessageContent)
                        ?.body == "new encrypted test carrier" }) {
                    replacementEntered.complete(Unit)
                    allowReplacement.await()
                }
                return items.map { it.safeTestProjection("") }
            }
            override suspend fun hasTransientDecryptingShell(item: MatrixTimelineItem) = true
            override suspend fun decryptItem(item: MatrixTimelineItem, timelineReleaseIntent: TimelineContentReleaseIntent): MatrixTimelineItem {
                observed += timelineReleaseIntent
                if (timelineReleaseIntent.requestId.startsWith("event:history-")) historyPermits.receive()
                if (timelineReleaseIntent.requestId == "event:live-arrival" &&
                    observed.count { it.requestId == "event:live-arrival" } == 1
                ) firstArrivalResult.await()
                return item.safeTestProjection("authorized latest projection")
            }
        }
        val items = MutableStateFlow<List<MatrixTimelineItem>>(emptyList())
        val processor = createMatrixTimelineDiffProcessor(items, protectedContentAdapter = adapter)
        val scope = TimelineCommitScope(904L)
        processor.activateCommitScope(scope)
        processor.updateViewport(scope, 1L, listOf(UniqueId("live-bottom")))
        processor.commitDiffs(TimelineDiffTransaction(scope, 1L, 1L,
            listOf(TimelineDiff.Reset((0..39).map { raw("history-$it") } + raw("live-bottom")))))
        runCurrent()
        processor.commitDiffs(TimelineDiffTransaction(scope, 2L, 1L,
            listOf(TimelineDiff.PushBack(raw("live-arrival")))))
        historyPermits.trySend(Unit)
        runCurrent()
        assertThat(observed.count { it.requestId == "event:live-arrival" }).isEqualTo(1)
        // Hold the raw replacement inside the existing commit mutex. Even with immediate recent
        // publication, a decrypt result can become buffered waiting for that mutex. It must still
        // discard its old source and re-authorize the newest source ahead of forty older items.
        val replacement = backgroundScope.launch {
            processor.commitDiffs(TimelineDiffTransaction(scope, 3L, 1L,
                listOf(TimelineDiff.Set(41u, raw("live-arrival", "new encrypted test carrier")))))
        }
        runCurrent()
        assertThat(replacementEntered.isCompleted).isTrue()
        firstArrivalResult.complete(Unit)
        runCurrent()
        allowReplacement.complete(Unit)
        runCurrent()
        replacement.join()
        historyPermits.trySend(Unit)
        runCurrent()
        advanceTimeBy(MatrixTimelineDiffProcessor.HISTORY_DECRYPT_BATCH_WINDOW_MILLIS + 1)
        runCurrent()
        val arrivals = observed.filter { it.requestId == "event:live-arrival" }
        assertThat(arrivals).hasSize(2)
        assertThat(arrivals.all { it.releaseClass == TimelineContentReleaseClass.HISTORY_PREFETCH }).isTrue()
        assertThat(observed.count { it.requestId.startsWith("event:history-") }).isLessThan(40)
        val projected = items.value.filterIsInstance<MatrixTimelineItem.Event>()
            .single { it.uniqueId.value == "live-arrival" }.event.content as MessageContent
        assertThat(projected.body).isEqualTo("authorized latest projection")
    }

    private fun raw(id: String, carrier: String = "encrypted test carrier") = FakeFfiTimelineItem(
        asEventResult = aRustEventTimelineItem(eventOrTransactionId = RustEventOrTransactionId.EventId("\$$id"),
            content = aRustTimelineItemContentMsgLike(carrier)), uniqueId = id,
    )

    @Test
    fun `visible live projection hint survives replacement and retry without changing its priority`() {
        val queue = queue()
        val incoming = item("incoming")
        queue.offer(listOf(incoming), emptyList(), rawStateGeneration = 1L, timelineScopeId = "current",
            recentProjectionKeys = setOf("event:incoming"))
        val first = checkNotNull(queue.pollVisible())
        assertThat(first.recentProjection).isTrue()
        assertThat(first.recentHistory).isFalse()
        val replacement = incoming.copy(event = incoming.event.copy(timestamp = 42))
        queue.offer(listOf(replacement), emptyList(), rawStateGeneration = 2L, timelineScopeId = "current")
        queue.complete(first)
        val latest = checkNotNull(queue.pollVisible())
        assertThat(latest.item).isEqualTo(replacement)
        assertThat(latest.recentProjection).isTrue()
        queue.complete(latest, retry = true)
        val retry = checkNotNull(queue.pollVisible())
        assertThat(retry.recentProjection).isTrue()
        assertThat(retry.priority).isEqualTo(DecryptPriority.VISIBLE)
    }

    @Test
    fun `new scope cannot inherit a visible live publication hint`() {
        val queue = queue()
        val incoming = item("incoming")
        queue.offer(listOf(incoming), emptyList(), rawStateGeneration = 1L, timelineScopeId = "old",
            recentProjectionKeys = setOf("event:incoming"))
        val first = checkNotNull(queue.pollVisible())
        queue.offer(listOf(incoming), emptyList(), rawStateGeneration = 2L, timelineScopeId = "new")
        queue.complete(first, retry = true)
        val latest = checkNotNull(queue.pollVisible())
        assertThat(latest.timelineScopeId).isEqualTo("new")
        assertThat(latest.recentProjection).isFalse()
    }

    @Test
    fun `measured viewport promotion retains recent publication but changes only the existing release class`() {
        val queue = queue()
        val incoming = item("incoming")
        queue.offer(emptyList(), emptyList(), recentHistory = listOf(incoming), timelineScopeId = "scope", rawStateGeneration = 1L)
        queue.reprioritize(listOf("event:incoming"), listOf(incoming), 1L, "scope", 2L)
        val promoted = checkNotNull(queue.pollVisible())
        assertThat(promoted.recentProjection).isTrue()
        assertThat(promoted.recentHistory).isFalse()
        assertThat(promoted.releaseGeneration).isEqualTo(2L)
        assertThat(promoted.priority).isEqualTo(DecryptPriority.VISIBLE)
    }

    @Test
    fun `incremental visible arrival publishes immediately while Reset retains batching`() = runTest {
        val observed = mutableListOf<TimelineContentReleaseIntent>()
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun timelineItemsShell(items: List<MatrixTimelineItem>) = items.map { it.safeTestProjection("") }
            override suspend fun hasTransientDecryptingShell(item: MatrixTimelineItem) = true
            override suspend fun decryptItem(item: MatrixTimelineItem, timelineReleaseIntent: TimelineContentReleaseIntent): MatrixTimelineItem {
                observed += timelineReleaseIntent
                return item.safeTestProjection("authorized projection")
            }
        }
        val items = MutableStateFlow<List<MatrixTimelineItem>>(emptyList())
        val processor = createMatrixTimelineDiffProcessor(items, protectedContentAdapter = adapter)
        val scope = TimelineCommitScope(905L)
        processor.activateCommitScope(scope)
        processor.updateViewport(scope, 1L, listOf(UniqueId("initial"), UniqueId("incoming")))
        processor.commitDiffs(TimelineDiffTransaction(scope, 1L, 1L, listOf(TimelineDiff.Reset(listOf(raw("initial"))))))
        runCurrent()
        assertThat(items.value.filterIsInstance<MatrixTimelineItem.Event>().any {
            (it.event.content as? MessageContent)?.body == "authorized projection"
        }).isFalse()
        advanceTimeBy(MatrixTimelineDiffProcessor.VISIBLE_DECRYPT_BATCH_WINDOW_MILLIS + 1L)
        runCurrent()
        processor.commitDiffs(TimelineDiffTransaction(scope, 2L, 1L, listOf(TimelineDiff.PushBack(raw("incoming")))))
        runCurrent()
        val arrival = observed.single { it.requestId == "event:incoming" }
        assertThat(arrival.releaseClass).isEqualTo(TimelineContentReleaseClass.FOREGROUND_VISIBLE)
        assertThat(arrival.generation).isEqualTo(1L)
        val projected = items.value.filterIsInstance<MatrixTimelineItem.Event>().single { it.uniqueId.value == "incoming" }
        assertThat((projected.event.content as MessageContent).body).isEqualTo("authorized projection")
    }
}

private fun MatrixTimelineItem.safeTestProjection(body: String): MatrixTimelineItem {
    val item = this as? MatrixTimelineItem.Event ?: return this
    val message = item.event.content as? MessageContent ?: return this
    return item.copy(event = item.event.copy(content = message.copy(body = body, type = TextMessageType(body, null))))
}
