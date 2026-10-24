/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.contentprotection.impl.scheduling

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NextTimelineReleaseSchedulerPrototypeTest {
    @Test
    fun `real visible work precedes bootstrap even when shared rotation points to bootstrap`() {
        val scheduler = scheduler(foregroundWeight = 1, historyWeight = 2)
        scheduler.enqueue(viewportRequest("boot-0", NextTimelineReleaseClass.FOREGROUND_BOOTSTRAP).copy(allowBootstrap = true))
        scheduler.takeBatch(limit = 1, allowedClasses = setOf(NextTimelineReleaseClass.FOREGROUND_BOOTSTRAP)).single().fail()
        scheduler.enqueue(viewportRequest("boot-1", NextTimelineReleaseClass.FOREGROUND_BOOTSTRAP).copy(allowBootstrap = true))
        scheduler.enqueue(viewportRequest("real-visible", NextTimelineReleaseClass.FOREGROUND_VISIBLE))
        val next = scheduler.takeBatch(limit = 1,
            allowedClasses = setOf(NextTimelineReleaseClass.FOREGROUND_VISIBLE, NextTimelineReleaseClass.FOREGROUND_BOOTSTRAP)).single()
        assertThat(next.request.requestId).isEqualTo("real-visible")
    }

    @Test
    fun `initial render hint keeps visible priority and retires when the owner leaves its initial tail`() {
        val scheduler = scheduler()
        val ordinary = viewportRequest("ordinary", NextTimelineReleaseClass.HISTORY_PREFETCH)
        val initial = viewportRequest("initial", NextTimelineReleaseClass.HISTORY_PREFETCH).copy(allowBootstrap = true)
        val preview = viewportRequest("preview", NextTimelineReleaseClass.HISTORY_PREFETCH)
        scheduler.enqueue(ordinary)
        scheduler.enqueue(initial)
        scheduler.enqueue(preview)
        val scope = checkNotNull(initial.viewportIdentity).scopeId
        scheduler.reprioritizeViewport(scope, 2, listOf("ordinary"), listOf("initial", "preview"))
        assertThat(scheduler.pendingCount(NextTimelineReleaseClass.FOREGROUND_BOOTSTRAP)).isEqualTo(1)
        val current = scheduler.takeBatch(limit = 1,
            allowedClasses = setOf(NextTimelineReleaseClass.FOREGROUND_VISIBLE, NextTimelineReleaseClass.FOREGROUND_BOOTSTRAP)).single()
        assertThat(current.request.requestId).isEqualTo("ordinary")
        current.fail()
        scheduler.reprioritizeViewport(scope, 3, emptyList(), emptyList())
        assertThat(scheduler.pendingCount(NextTimelineReleaseClass.FOREGROUND_BOOTSTRAP)).isEqualTo(0)
        assertThat(scheduler.pendingCount(NextTimelineReleaseClass.HISTORY_PREFETCH)).isEqualTo(2)
    }

    @Test
    fun `bootstrap cannot spend foreground pending reserve and is cancelled with its viewport owner`() {
        val scheduler = scheduler(maximumPending = 4, maximumBatchSize = 4, foregroundReservedPending = 1)
        repeat(3) { index ->
            scheduler.enqueue(viewportRequest("boot-$index", NextTimelineReleaseClass.FOREGROUND_BOOTSTRAP).copy(allowBootstrap = true))
        }
        assertThat(scheduler.enqueue(viewportRequest("overflow", NextTimelineReleaseClass.FOREGROUND_BOOTSTRAP)))
            .isEqualTo(NextTimelineReleaseAdmission.SkippedBackpressure("overflow"))
        assertThat(scheduler.enqueue(viewportRequest("visible", NextTimelineReleaseClass.FOREGROUND_VISIBLE)))
            .isEqualTo(NextTimelineReleaseAdmission.Enqueued)
        val scope = checkNotNull(viewportRequest("x", NextTimelineReleaseClass.HISTORY_PREFETCH).viewportIdentity).scopeId
        val cancellation = scheduler.cancelViewportScope(scope)
        assertThat(cancellation.removedPending).hasSize(4)
        assertThat(scheduler.pendingCount()).isEqualTo(0)
    }

    @Test
    fun `foreground receives first service opportunity while history stays queued`() {
        val scheduler = scheduler(foregroundWeight = 2, historyWeight = 1, reliableWeight = 1)
        scheduler.enqueue(viewportRequest("history", NextTimelineReleaseClass.HISTORY_PREFETCH))
        scheduler.enqueue(viewportRequest("visible", NextTimelineReleaseClass.FOREGROUND_VISIBLE))

        val first = scheduler.takeBatch(limit = 1).single()

        assertThat(first.request.requestId).isEqualTo("visible")
        assertThat(scheduler.pendingCount(NextTimelineReleaseClass.HISTORY_PREFETCH)).isEqualTo(1)
    }

    @Test
    fun `weighted rotation eventually services history under continuous foreground demand`() {
        val scheduler = scheduler(foregroundWeight = 2, historyWeight = 1, reliableWeight = 1)
        scheduler.enqueue(viewportRequest("history", NextTimelineReleaseClass.HISTORY_PREFETCH))
        repeat(6) { index -> scheduler.enqueue(viewportRequest("visible-$index", NextTimelineReleaseClass.FOREGROUND_VISIBLE)) }

        val dispatchedIds = List(3) { scheduler.takeBatch(limit = 1).single().request.requestId }

        assertThat(dispatchedIds).containsExactly("visible-0", "visible-1", "history").inOrder()
    }

    @Test
    fun `bounded queue returns explicit outcomes and leaves reliable operation with caller`() {
        val scheduler = scheduler(maximumPending = 2, maximumBatchSize = 2)
        assertThat(scheduler.enqueue(viewportRequest("history-1", NextTimelineReleaseClass.HISTORY_PREFETCH)))
            .isEqualTo(NextTimelineReleaseAdmission.Enqueued)
        assertThat(scheduler.enqueue(viewportRequest("history-2", NextTimelineReleaseClass.HISTORY_PREFETCH)))
            .isEqualTo(NextTimelineReleaseAdmission.Enqueued)

        assertThat(scheduler.enqueue(viewportRequest("history-3", NextTimelineReleaseClass.HISTORY_PREFETCH)))
            .isEqualTo(NextTimelineReleaseAdmission.SkippedBackpressure("history-3"))
        assertThat(scheduler.enqueue(reliableRequest("fence", "operation-13")))
            .isEqualTo(NextTimelineReleaseAdmission.RetryRequired("fence", "operation-13"))
        assertThat(scheduler.pendingCount()).isEqualTo(2)
    }

    @Test
    fun `generation advance removes pending viewport work but leaves reliable work`() {
        val scheduler = scheduler()
        scheduler.enqueue(viewportRequest("old-visible", NextTimelineReleaseClass.FOREGROUND_VISIBLE, generation = 4))
        scheduler.enqueue(viewportRequest("old-history", NextTimelineReleaseClass.HISTORY_PREFETCH, generation = 4))
        scheduler.enqueue(reliableRequest("audit", "operation-audit"))

        val cancellation = scheduler.advanceViewportGeneration(SCOPE, currentGeneration = 5)

        assertThat(cancellation.removedPending.map { it.requestId }).containsExactly("old-visible", "old-history")
        assertThat(cancellation.cancelledInFlightCount).isEqualTo(0)
        assertThat(scheduler.takeBatch().single().request.requestId).isEqualTo("audit")
    }

    @Test
    fun `late result after in flight generation cancellation is cleared exactly once`() {
        val scheduler = scheduler()
        scheduler.enqueue(viewportRequest("old-visible", NextTimelineReleaseClass.FOREGROUND_VISIBLE, generation = 4))
        val ticket = scheduler.takeBatch(limit = 1).single()
        val clearCounts = mutableListOf<Int>()
        val bytes = byteArrayOf(7, 8, 9)

        val cancellation = scheduler.advanceViewportGeneration(SCOPE, currentGeneration = 5)
        val result = NextTimelineSensitiveResult(bytes) { value ->
            clearCounts += 1
            value.fill(0)
        }
        val firstCompletion = ticket.complete(result, viewportIdentity(generation = 5))
        val clearedAgain = result.clearExactlyOnce()

        assertThat(cancellation.cancelledInFlightCount).isEqualTo(1)
        assertThat(firstCompletion).isEqualTo(NextTimelineResultDisposition.ClearedStale)
        assertThat(clearedAgain).isFalse()
        assertThat(clearCounts).containsExactly(1)
        assertThat(bytes).isEqualTo(byteArrayOf(0, 0, 0))
        assertThat(scheduler.inFlightCount()).isEqualTo(0)
    }

    @Test
    fun `identity mismatch clears result even when generation matches`() {
        val scheduler = scheduler()
        scheduler.enqueue(viewportRequest("visible", NextTimelineReleaseClass.FOREGROUND_VISIBLE, generation = 5))
        val ticket = scheduler.takeBatch(limit = 1).single()
        val bytes = byteArrayOf(1, 2)

        val completion = ticket.complete(
            result = NextTimelineSensitiveResult(bytes) { it.fill(0) },
            currentViewportIdentity = viewportIdentity(generation = 5).copy(contentVersion = "content-v2"),
        )

        assertThat(completion).isEqualTo(NextTimelineResultDisposition.ClearedStale)
        assertThat(bytes).isEqualTo(byteArrayOf(0, 0))
    }

    @Test
    fun `successful delivery transfers cleanup ownership and later cancellation cannot clear it`() {
        val scheduler = scheduler()
        scheduler.enqueue(viewportRequest("visible", NextTimelineReleaseClass.FOREGROUND_VISIBLE, generation = 5))
        val ticket = scheduler.takeBatch(limit = 1).single()
        var clearCount = 0
        val bytes = byteArrayOf(3, 4)
        val result = NextTimelineSensitiveResult(bytes) { value ->
            clearCount += 1
            value.fill(0)
        }

        val completion = ticket.complete(result, viewportIdentity(generation = 5))
        val cancellation = scheduler.advanceViewportGeneration(SCOPE, currentGeneration = 6)
        val schedulerTriedToClear = result.clearExactlyOnce()

        assertThat(completion).isInstanceOf(NextTimelineResultDisposition.Delivered::class.java)
        assertThat((completion as NextTimelineResultDisposition.Delivered<*>).value).isSameInstanceAs(bytes)
        assertThat(cancellation.cancelledInFlightCount).isEqualTo(0)
        assertThat(schedulerTriedToClear).isFalse()
        assertThat(clearCount).isEqualTo(0)
        assertThat(bytes).isEqualTo(byteArrayOf(3, 4))
    }

    @Test
    fun `duplicate completion clears only the duplicate sensitive result`() {
        val scheduler = scheduler()
        scheduler.enqueue(viewportRequest("visible", NextTimelineReleaseClass.FOREGROUND_VISIBLE, generation = 5))
        val ticket = scheduler.takeBatch(limit = 1).single()
        var deliveredClearCount = 0
        var duplicateClearCount = 0
        val deliveredBytes = byteArrayOf(1)
        val duplicateBytes = byteArrayOf(2)

        val first = ticket.complete(
            NextTimelineSensitiveResult(deliveredBytes) { deliveredClearCount += 1 },
            viewportIdentity(generation = 5),
        )
        val duplicate = ticket.complete(
            NextTimelineSensitiveResult(duplicateBytes) { value ->
                duplicateClearCount += 1
                value.fill(0)
            },
            viewportIdentity(generation = 5),
        )

        assertThat(first).isInstanceOf(NextTimelineResultDisposition.Delivered::class.java)
        assertThat(duplicate).isEqualTo(NextTimelineResultDisposition.ClearedDuplicate)
        assertThat(deliveredClearCount).isEqualTo(0)
        assertThat(duplicateClearCount).isEqualTo(1)
        assertThat(deliveredBytes).isEqualTo(byteArrayOf(1))
        assertThat(duplicateBytes).isEqualTo(byteArrayOf(0))
    }

    @Test
    fun `repeated cancellation followed by duplicate completion has one terminal state`() {
        val scheduler = scheduler()
        scheduler.enqueue(viewportRequest("visible", NextTimelineReleaseClass.FOREGROUND_VISIBLE, generation = 5))
        val ticket = scheduler.takeBatch(limit = 1).single()
        var firstClearCount = 0
        var duplicateClearCount = 0
        val firstBytes = byteArrayOf(8)
        val duplicateBytes = byteArrayOf(9)

        val firstCancellation = scheduler.advanceViewportGeneration(SCOPE, currentGeneration = 6)
        val repeatedCancellation = scheduler.advanceViewportGeneration(SCOPE, currentGeneration = 7)
        val firstCompletion = ticket.complete(
            NextTimelineSensitiveResult(firstBytes) { value ->
                firstClearCount += 1
                value.fill(0)
            },
            viewportIdentity(generation = 6),
        )
        val duplicateCompletion = ticket.complete(
            NextTimelineSensitiveResult(duplicateBytes) { value ->
                duplicateClearCount += 1
                value.fill(0)
            },
            viewportIdentity(generation = 7),
        )

        assertThat(firstCancellation.cancelledInFlightCount).isEqualTo(1)
        assertThat(repeatedCancellation.cancelledInFlightCount).isEqualTo(0)
        assertThat(firstCompletion).isEqualTo(NextTimelineResultDisposition.ClearedStale)
        assertThat(duplicateCompletion).isEqualTo(NextTimelineResultDisposition.ClearedDuplicate)
        assertThat(firstClearCount).isEqualTo(1)
        assertThat(duplicateClearCount).isEqualTo(1)
        assertThat(firstBytes).isEqualTo(byteArrayOf(0))
        assertThat(duplicateBytes).isEqualTo(byteArrayOf(0))
        assertThat(scheduler.inFlightCount()).isEqualTo(0)
    }

    @Test
    fun `reliable dispatch ignores viewport generation cancellation and preserves operation identity`() {
        val scheduler = scheduler()
        scheduler.enqueue(reliableRequest("revoke", "operation-revoke"))
        val ticket = scheduler.takeBatch(limit = 1).single()
        val bytes = byteArrayOf(5)

        val cancellation = scheduler.advanceViewportGeneration(SCOPE, currentGeneration = 99)
        val completion = ticket.complete(NextTimelineSensitiveResult(bytes) { it.fill(0) }, currentViewportIdentity = null)

        assertThat(cancellation.cancelledInFlightCount).isEqualTo(0)
        assertThat(ticket.request.reliableOperationId).isEqualTo("operation-revoke")
        assertThat(completion).isInstanceOf(NextTimelineResultDisposition.Delivered::class.java)
        assertThat(bytes).isEqualTo(byteArrayOf(5))
    }

    @Test
    fun `batch and capacity parameters reject invalid values`() {
        val failure = runCatching {
            NextTimelineReleaseSchedulerConfig(
                maximumPending = 2,
                maximumBatchSize = 3,
                foregroundWeight = 1,
                historyWeight = 1,
                reliableWeight = 1,
            )
        }

        assertThat(failure.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `transport failure releases in flight slot and exact identity can be admitted again`() {
        val scheduler = scheduler()
        val request = viewportRequest("visible", NextTimelineReleaseClass.FOREGROUND_VISIBLE)
        scheduler.enqueue(request)
        val ticket = scheduler.takeBatch(limit = 1).single()

        assertThat(ticket.fail()).isTrue()
        assertThat(ticket.fail()).isFalse()
        assertThat(scheduler.inFlightCount()).isEqualTo(0)
        assertThat(scheduler.enqueue(request)).isEqualTo(NextTimelineReleaseAdmission.Enqueued)
    }

    @Test
    fun `duplicate admission binds request content and authorization identity`() {
        val scheduler = scheduler()
        val original = viewportRequest("visible", NextTimelineReleaseClass.FOREGROUND_VISIBLE)
        assertThat(scheduler.enqueue(original)).isEqualTo(NextTimelineReleaseAdmission.Enqueued)

        assertThat(scheduler.enqueue(original.copy(payload = "other-payload")))
            .isEqualTo(NextTimelineReleaseAdmission.SkippedDuplicate("visible"))
        assertThat(
            scheduler.enqueue(
                original.copy(
                    deduplicationKey = original.deduplicationKey.copy(contentIdentity = "content-visible-v2"),
                )
            )
        ).isEqualTo(NextTimelineReleaseAdmission.Enqueued)
        assertThat(
            scheduler.enqueue(
                original.copy(
                    deduplicationKey = original.deduplicationKey.copy(authorizationIdentity = "authorization-v2"),
                )
            )
        ).isEqualTo(NextTimelineReleaseAdmission.Enqueued)
    }

    @Test
    fun `cancel and late completion release slot before clearing result exactly once`() {
        val scheduler = scheduler()
        scheduler.enqueue(viewportRequest("visible", NextTimelineReleaseClass.FOREGROUND_VISIBLE, generation = 5))
        val ticket = scheduler.takeBatch(limit = 1).single()
        val bytes = byteArrayOf(4, 5, 6)
        var clearCount = 0

        val cancellation = scheduler.cancelViewportRequests(SCOPE, setOf("visible"))
        val completion = ticket.complete(
            NextTimelineSensitiveResult(bytes) { value ->
                clearCount += 1
                value.fill(0)
            },
            viewportIdentity(generation = 5),
        )

        assertThat(cancellation.cancelledInFlight).containsExactly(ticket)
        assertThat(scheduler.inFlightCount()).isEqualTo(0)
        assertThat(completion).isEqualTo(NextTimelineResultDisposition.ClearedStale)
        assertThat(clearCount).isEqualTo(1)
        assertThat(bytes).isEqualTo(byteArrayOf(0, 0, 0))
    }

    @Test
    fun `foreground reserve rejects excess history but admits foreground`() {
        val scheduler = scheduler(maximumPending = 4, maximumBatchSize = 4, foregroundReservedPending = 1)
        repeat(3) { index ->
            assertThat(scheduler.enqueue(viewportRequest("history-$index", NextTimelineReleaseClass.HISTORY_PREFETCH)))
                .isEqualTo(NextTimelineReleaseAdmission.Enqueued)
        }

        assertThat(scheduler.enqueue(viewportRequest("history-overflow", NextTimelineReleaseClass.HISTORY_PREFETCH)))
            .isEqualTo(NextTimelineReleaseAdmission.SkippedBackpressure("history-overflow"))
        assertThat(scheduler.enqueue(viewportRequest("visible", NextTimelineReleaseClass.FOREGROUND_VISIBLE)))
            .isEqualTo(NextTimelineReleaseAdmission.Enqueued)
        assertThat(scheduler.takeBatch(limit = 1).single().request.requestId).isEqualTo("visible")
    }

    @Test
    fun `viewport reprioritizes only pending work and updates its generation`() {
        val scheduler = scheduler()
        scheduler.enqueue(viewportRequest("old-visible", NextTimelineReleaseClass.FOREGROUND_VISIBLE, generation = 1))
        scheduler.enqueue(viewportRequest("new-visible", NextTimelineReleaseClass.HISTORY_PREFETCH, generation = 1))

        val result = scheduler.reprioritizeViewport(SCOPE, currentGeneration = 2, visibleRequestIds = listOf("new-visible"))
        val promoted = scheduler.takeBatch(limit = 1).single().request
        val demoted = scheduler.takeBatch(limit = 1).single().request

        assertThat(result.updatedCount).isEqualTo(2)
        assertThat(result.removedHistory).isEmpty()
        assertThat(promoted.requestId).isEqualTo("new-visible")
        assertThat(promoted.releaseClass).isEqualTo(NextTimelineReleaseClass.FOREGROUND_VISIBLE)
        assertThat(promoted.viewportIdentity?.generation).isEqualTo(2)
        assertThat(demoted.requestId).isEqualTo("old-visible")
        assertThat(demoted.releaseClass).isEqualTo(NextTimelineReleaseClass.HISTORY_PREFETCH)
        assertThat(demoted.viewportIdentity?.generation).isEqualTo(2)
    }

    @Test
    fun `viewport demotion removes history tail to restore foreground reserve`() {
        val scheduler = scheduler(maximumPending = 4, maximumBatchSize = 4, foregroundReservedPending = 1)
        repeat(4) { index ->
            scheduler.enqueue(viewportRequest("visible-$index", NextTimelineReleaseClass.FOREGROUND_VISIBLE))
        }

        val result = scheduler.reprioritizeViewport(SCOPE, currentGeneration = 2, visibleRequestIds = emptyList())

        assertThat(result.removedHistory.map { it.requestId }).containsExactly("visible-3")
        assertThat(scheduler.pendingCount()).isEqualTo(3)
        val evicted = result.removedHistory.single()
        val viewportRetry = evicted.copy(
            releaseClass = NextTimelineReleaseClass.FOREGROUND_VISIBLE,
            viewportIdentity = requireNotNull(evicted.viewportIdentity).copy(generation = 3),
        )
        assertThat(scheduler.enqueue(viewportRetry))
            .isEqualTo(NextTimelineReleaseAdmission.Enqueued)
        assertThat(scheduler.takeBatch(limit = 1).single().request.requestId).isEqualTo("visible-3")
    }

    @Test
    fun `viewport reprioritization never changes in flight authorization`() {
        val scheduler = scheduler()
        scheduler.enqueue(viewportRequest("visible", NextTimelineReleaseClass.FOREGROUND_VISIBLE, generation = 1))
        val ticket = scheduler.takeBatch(limit = 1).single()

        val result = scheduler.reprioritizeViewport(SCOPE, currentGeneration = 2, visibleRequestIds = emptyList())

        assertThat(result.updatedCount).isEqualTo(0)
        assertThat(ticket.request.releaseClass).isEqualTo(NextTimelineReleaseClass.FOREGROUND_VISIBLE)
        assertThat(ticket.request.viewportIdentity?.generation).isEqualTo(1)
        assertThat(scheduler.inFlightCount()).isEqualTo(1)
    }

    private fun scheduler(
        maximumPending: Int = 8,
        maximumBatchSize: Int = 4,
        foregroundWeight: Int = 2,
        historyWeight: Int = 1,
        reliableWeight: Int = 1,
        foregroundReservedPending: Int = 0,
    ) = NextTimelineReleaseSchedulerPrototype<String>(
        NextTimelineReleaseSchedulerConfig(
            maximumPending = maximumPending,
            maximumBatchSize = maximumBatchSize,
            foregroundWeight = foregroundWeight,
            historyWeight = historyWeight,
            reliableWeight = reliableWeight,
            foregroundReservedPending = foregroundReservedPending,
        ),
    )

    private fun viewportRequest(
        requestId: String,
        releaseClass: NextTimelineReleaseClass,
        generation: Long = 1,
    ) = NextTimelineReleaseRequest(
        requestId = requestId,
        deduplicationKey = NextTimelineReleaseDeduplicationKey(
            requestIdentity = requestId,
            contentIdentity = "content-$requestId",
            authorizationIdentity = "authorization-v1",
        ),
        releaseClass = releaseClass,
        payload = requestId,
        viewportIdentity = viewportIdentity(generation),
    )

    private fun reliableRequest(requestId: String, operationId: String) = NextTimelineReleaseRequest(
        requestId = requestId,
        deduplicationKey = NextTimelineReleaseDeduplicationKey(
            requestIdentity = requestId,
            contentIdentity = "content-$requestId",
            authorizationIdentity = operationId,
        ),
        releaseClass = NextTimelineReleaseClass.RELIABLE_SECURITY,
        payload = requestId,
        reliableOperationId = operationId,
    )

    private fun viewportIdentity(generation: Long) = NextTimelineViewportIdentity(
        scopeId = SCOPE,
        roomId = "!room:yizhe.test",
        eventId = "\$event",
        contentVersion = "content-v1",
        generation = generation,
    )

    private companion object {
        const val SCOPE = "timeline-scope"
    }
}
