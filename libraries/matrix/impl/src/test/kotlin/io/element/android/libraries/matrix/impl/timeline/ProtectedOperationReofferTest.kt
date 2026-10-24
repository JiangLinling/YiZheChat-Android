/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.timeline

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.UniqueId
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.item.event.ProtectedOperationContent
import io.element.android.libraries.matrix.impl.fixtures.factories.aRustEventTimelineItem
import io.element.android.libraries.matrix.impl.fixtures.factories.aRustTimelineItemContentMsgLike
import io.element.android.libraries.matrix.impl.fixtures.fakes.FakeFfiTimelineItem
import io.element.android.security.contentprotection.api.TimelineContentReleaseIntent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.matrix.rustcomponents.sdk.TimelineDiff

@OptIn(ExperimentalCoroutinesApi::class)
class ProtectedOperationReofferTest {
    @Test
    fun `authenticated nonrendered operation is not reoffered on stable raw viewports`() = verifyReoffer(true, 1)

    @Test
    fun `unchanged hidden sentinel is not cached as a successful authorization`() = verifyReoffer(false, 2)

    private fun verifyReoffer(authenticatedOperation: Boolean, expectedCalls: Int) = runTest {
        var calls = 0
        val adapter = object : MatrixProtectedContentAdapter by DisabledMatrixProtectedContentAdapter {
            override suspend fun timelineItemsShell(items: List<MatrixTimelineItem>) = items.map {
                hiddenProtectedOperationItem(it as MatrixTimelineItem.Event)
            }
            override suspend fun hasTransientDecryptingShell(item: MatrixTimelineItem) = true
            override suspend fun decryptItem(item: MatrixTimelineItem, timelineReleaseIntent: TimelineContentReleaseIntent): MatrixTimelineItem {
                calls++
                val event = item as MatrixTimelineItem.Event
                return if (authenticatedOperation) event.copy(event = event.event.copy(content = ProtectedOperationContent(
                    protocolVersion = 1, operationId = "authenticated-call-operation", operation = ProtectedOperationContent.Operation.CALL_STATE,
                    targetEventId = "missing-call-target", aggregateId = "call-aggregate",
                ))) else hiddenProtectedOperationItem(event)
            }
        }
        val items = MutableStateFlow<List<MatrixTimelineItem>>(emptyList())
        val processor = createMatrixTimelineDiffProcessor(items, protectedContentAdapter = adapter)
        val scope = TimelineCommitScope(120L)
        processor.activateCommitScope(scope)
        processor.updateViewport(scope, 1L, emptyList())
        processor.commitDiffs(TimelineDiffTransaction(scope, 1L, 1L, listOf(TimelineDiff.Reset(listOf(
            FakeFfiTimelineItem(asEventResult = aRustEventTimelineItem(content = aRustTimelineItemContentMsgLike("encrypted operation")),
                uniqueId = "stable-operation"),
        )))))
        runCurrent()
        advanceTimeBy(MatrixTimelineDiffProcessor.HISTORY_DECRYPT_BATCH_WINDOW_MILLIS + 1L)
        runCurrent()
        assertThat(calls).isEqualTo(1)
        // There is deliberately no visible operation row. Only the same off-screen source remains.
        assertThat(items.value.filterIsInstance<MatrixTimelineItem.Event>()).isEmpty()
        processor.updateViewport(scope, 2L, emptyList<UniqueId>())
        runCurrent()
        assertThat(calls).isEqualTo(expectedCalls)
    }
}
