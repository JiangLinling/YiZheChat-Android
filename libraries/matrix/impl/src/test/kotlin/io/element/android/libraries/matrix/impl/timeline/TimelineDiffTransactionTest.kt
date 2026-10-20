/*
 * Copyright (c) 2026 YiZhe Security.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.timeline

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.impl.fixtures.fakes.FakeFfiTimelineItem
import org.junit.Test
import org.matrix.rustcomponents.sdk.TimelineDiff

class TimelineDiffTransactionTest {
    @Test
    fun `ignore list clear followed by incremental call events does not wait forever for Reset`() {
        val coordinator = TimelineStructuralTransactionCoordinator(TimelineCommitScope(1L))
        assertThat(coordinator.accept(listOf(TimelineDiff.Clear))).isNull()
        val append = TimelineDiff.Append(listOf(FakeFfiTimelineItem()))
        val first = checkNotNull(coordinator.accept(listOf(append)))
        assertThat(first.diffs).containsExactly(TimelineDiff.Clear, append).inOrder()
        val push = TimelineDiff.PushBack(FakeFfiTimelineItem())
        val second = checkNotNull(coordinator.accept(listOf(push)))
        assertThat(second.diffs).containsExactly(push)
        assertThat(second.structuralEpoch).isEqualTo(first.structuralEpoch)
        assertThat(second.sequence).isEqualTo(first.sequence + 1)
    }

    @Test
    fun `last Clear and its incremental replacement commit together and discard superseded events`() {
        val coordinator = TimelineStructuralTransactionCoordinator(TimelineCommitScope(1L))
        val push = TimelineDiff.PushBack(FakeFfiTimelineItem())
        val result = checkNotNull(coordinator.accept(listOf(
            TimelineDiff.Clear, TimelineDiff.Reset(listOf(FakeFfiTimelineItem())), TimelineDiff.Clear, push,
        )))
        assertThat(result.diffs).containsExactly(TimelineDiff.Clear, push).inOrder()
        assertThat(result.structuralEpoch).isEqualTo(2L)
    }

    @Test
    fun `each standalone Reset advances the structural epoch`() {
        val coordinator = TimelineStructuralTransactionCoordinator(TimelineCommitScope(1L))

        val first = checkNotNull(coordinator.accept(listOf(TimelineDiff.Reset(emptyList()))))
        val second = checkNotNull(coordinator.accept(listOf(TimelineDiff.Reset(emptyList()))))

        assertThat(first.structuralEpoch).isEqualTo(1L)
        assertThat(second.structuralEpoch).isEqualTo(2L)
    }

    @Test
    fun `Reset completes a pending Clear in the same structural epoch`() {
        val coordinator = TimelineStructuralTransactionCoordinator(TimelineCommitScope(1L))

        assertThat(coordinator.accept(listOf(TimelineDiff.Clear))).isNull()
        val replacement = checkNotNull(coordinator.accept(listOf(TimelineDiff.Reset(emptyList()))))

        assertThat(replacement.structuralEpoch).isEqualTo(1L)
        assertThat(replacement.diffs).containsExactly(TimelineDiff.Clear, TimelineDiff.Reset(emptyList())).inOrder()
    }
}
