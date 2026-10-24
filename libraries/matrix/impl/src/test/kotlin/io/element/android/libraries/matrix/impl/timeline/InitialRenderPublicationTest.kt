/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.timeline

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.UniqueId
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.test.timeline.anEventTimelineItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Tests completed projections only: no authorization or lifecycle request is issued here. */
@OptIn(ExperimentalCoroutinesApi::class)
class InitialRenderPublicationTest {
    private val raw = MatrixTimelineItem.Event(UniqueId("initial-source"), anEventTimelineItem())
    private val authorized = MatrixTimelineItem.Event(UniqueId("initial-result"), anEventTimelineItem())
    private val work = PendingDecryptWork(
        key = "event:initial-source", item = raw, priority = DecryptPriority.HISTORY,
        rawStateGeneration = 12L, timelineScopeId = "owned-initial-scope", releaseGeneration = 9L,
        bootstrapHistory = true,
    )
    private val initial = DecryptedItem(
        rawItem = raw, decryptedItem = authorized, authorizationRevision = 4L, priority = DecryptPriority.HISTORY,
        rawStateGeneration = work.rawStateGeneration, timelineScopeId = work.timelineScopeId, pendingWork = work,
    )

    @Test
    fun `completed initial render projection publishes without the history coalescing window`() = runTest {
        val input = Channel<DecryptedItem>(Channel.UNLIMITED)
        val batches = mutableListOf<List<DecryptedItem>>()
        val released = mutableListOf<List<DecryptedItem>>()
        backgroundScope.launch { collectDecryptedItemBatches(input, { released += it }) { batches += it } }
        input.send(initial)
        runCurrent()

        assertThat(batches).containsExactly(listOf(initial))
        assertThat(released).containsExactly(listOf(initial))
        val published = batches.single().single()
        assertThat(published).isSameInstanceAs(initial)
        assertThat(published.rawItem).isSameInstanceAs(raw)
        assertThat(published.pendingWork).isSameInstanceAs(work)
        assertThat(published.priority).isEqualTo(DecryptPriority.HISTORY)
        assertThat(published.authorizationRevision).isEqualTo(4L)
        assertThat(published.pendingWork!!.releaseGeneration).isEqualTo(9L)
    }

    @Test
    fun `completed initial render projection flushes an older history batch immediately`() = runTest {
        val input = Channel<DecryptedItem>(Channel.UNLIMITED)
        val batches = mutableListOf<List<DecryptedItem>>()
        backgroundScope.launch { collectDecryptedItemBatches(input) { batches += it } }
        val history = initial.copy(pendingWork = null)
        input.send(history)
        runCurrent()
        advanceTimeBy(100L)
        assertThat(batches).isEmpty()
        input.send(initial)
        runCurrent()

        assertThat(batches).containsExactly(listOf(history, initial))
        assertThat(batches.single().last().pendingWork).isSameInstanceAs(work)
    }

    @Test
    fun `completed initial render projection flushes a visible coalescing batch immediately`() = runTest {
        val input = Channel<DecryptedItem>(Channel.UNLIMITED)
        val batches = mutableListOf<List<DecryptedItem>>()
        backgroundScope.launch { collectDecryptedItemBatches(input) { batches += it } }
        val visible = initial.copy(priority = DecryptPriority.VISIBLE, pendingWork = null)
        input.send(visible)
        runCurrent()
        advanceTimeBy(1L)
        assertThat(batches).isEmpty()
        input.send(initial)
        runCurrent()

        assertThat(batches).containsExactly(listOf(visible, initial))
        assertThat(batches.single().last().priority).isEqualTo(DecryptPriority.HISTORY)
    }
}
