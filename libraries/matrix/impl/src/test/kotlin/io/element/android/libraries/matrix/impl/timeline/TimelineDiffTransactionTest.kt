/*
 * Copyright (c) 2026 YiZhe Security.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.timeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.matrix.rustcomponents.sdk.TimelineDiff

class TimelineDiffTransactionTest {
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
