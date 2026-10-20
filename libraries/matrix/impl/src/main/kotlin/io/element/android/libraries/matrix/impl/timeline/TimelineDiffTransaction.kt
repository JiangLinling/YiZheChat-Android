/*
 * Copyright (c) 2026 YiZhe Security.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.timeline

import org.matrix.rustcomponents.sdk.TimelineDiff

/** Identifies one live subscription to one [MatrixTimelineDiffProcessor] instance. */
@JvmInline
internal value class TimelineCommitScope(val value: Long)

/**
 * One ordered commit presented to the processor. A structural transaction always contains the
 * final Clear and its replacement in the same immutable diff list.
 */
internal data class TimelineDiffTransaction(
    val scope: TimelineCommitScope,
    val sequence: Long,
    val structuralEpoch: Long,
    val diffs: List<TimelineDiff>,
)

/**
 * Converts the ordered Rust diff stream into atomic structural transactions.
 *
 * A bare Clear never leaves this coordinator. A later Clear supersedes every uncommitted
 * replacement before it, and only the tail following the last Clear is eligible to commit.
 * The SDK also repopulates an ignored-user-list clear with ordinary Append/Push/Insert diffs;
 * a Reset is not guaranteed. Preserve that ordered stream against the new empty base.
 */
internal class TimelineStructuralTransactionCoordinator(
    private val scope: TimelineCommitScope,
) {
    private var nextSequence = 0L
    private var structuralEpoch = 0L
    private var awaitingStructuralReplacement = false

    fun accept(diffs: List<TimelineDiff>): TimelineDiffTransaction? {
        if (diffs.isEmpty()) return null

        val lastClearIndex = diffs.indexOfLast { it == TimelineDiff.Clear }
        val lastResetIndex = diffs.indexOfLast { it is TimelineDiff.Reset }
        val commitDiffs = when {
            lastClearIndex >= 0 -> {
                structuralEpoch += diffs.count { it == TimelineDiff.Clear }
                awaitingStructuralReplacement = true
                val replacement = diffs.drop(lastClearIndex + 1)
                if (replacement.isEmpty()) return null
                awaitingStructuralReplacement = false
                listOf(TimelineDiff.Clear) + replacement
            }
            awaitingStructuralReplacement -> {
                awaitingStructuralReplacement = false
                listOf(TimelineDiff.Clear) + if (lastResetIndex >= 0) diffs.drop(lastResetIndex) else diffs
            }
            lastResetIndex >= 0 -> {
                structuralEpoch += diffs.count { it is TimelineDiff.Reset }
                diffs.drop(lastResetIndex)
            }
            else -> diffs
        }

        return TimelineDiffTransaction(
            scope = scope,
            sequence = ++nextSequence,
            structuralEpoch = structuralEpoch,
            diffs = commitDiffs,
        )
    }
}
