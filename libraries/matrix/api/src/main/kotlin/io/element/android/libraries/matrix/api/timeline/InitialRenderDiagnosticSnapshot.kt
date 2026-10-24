/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.api.timeline

import java.util.Collections

enum class InitialRenderDiagnosticState { UNARMED, ACTIVE, RETIRED }
enum class InitialRenderDiagnosticCause {
    NOT_OPTED_IN, NO_DISPLAYABLE_ANCHOR, EMPTY_CANDIDATES, TAIL_BLOCKED, RAW_IDENTITY_CHANGED, NO_PENDING_CANDIDATE, SELECTED, RETIRED,
}
enum class InitialRenderUnavailable { NEVER_MEASURED, HIDDEN, OWNER_CLOSED, HARD_INVALIDATION, TRANSITION, PROTECTED_FOREGROUND, INVALID_SELECTOR }
enum class InitialRenderMatrixLane { VISIBLE, BOOTSTRAP, HISTORY }
enum class InitialRenderTailBlock { NONE, NO_ANCHOR, UNCAPTURED_EVENT, RAW_IDENTITY_CHANGED, NOT_EMPTY_TEXT, MISSING_SLOT }

/** Scalars only: no source object, identifier, payload, exception or free-form string is accepted. */
data class InitialRenderDiagnosticSnapshot(
    val state: InitialRenderDiagnosticState = InitialRenderDiagnosticState.UNARMED,
    val cause: InitialRenderDiagnosticCause = InitialRenderDiagnosticCause.EMPTY_CANDIDATES,
    val tailBlock: InitialRenderTailBlock = InitialRenderTailBlock.NO_ANCHOR,
    val allowInitialRender: Boolean = false,
    val measured: Boolean = false,
    val anchorDisplayable: Boolean = false,
    val tailOnScreen: Boolean = false,
    val rawCount: Int = 0,
    val displayCount: Int = 0,
    val visibleItemCount: Int = 0,
    val matchingVisibleRawSlots: Int = 0,
    val shellCount: Int = 0,
    val initialWindowSize: Int = 0,
    val lastCaptureEmptyShells: Int = 0,
    val lastCaptureMetadataEligible: Int = 0,
    val candidateCount: Int = 0,
    val matchingRawCandidates: Int = 0,
    val selectedBootstrap: Int = 0,
    val rawRevisionChanges: Int = 0,
) {
    internal fun bounded() = copy(
        rawCount = rawCount.coerceIn(0, 4096), shellCount = shellCount.coerceIn(0, 4096),
        displayCount = displayCount.coerceIn(0, 4096), visibleItemCount = visibleItemCount.coerceIn(0, 4096),
        matchingVisibleRawSlots = matchingVisibleRawSlots.coerceIn(0, 64),
        initialWindowSize = initialWindowSize.coerceIn(0, 30), lastCaptureEmptyShells = lastCaptureEmptyShells.coerceIn(0, 30),
        lastCaptureMetadataEligible = lastCaptureMetadataEligible.coerceIn(0, 30), candidateCount = candidateCount.coerceIn(0, 30),
        matchingRawCandidates = matchingRawCandidates.coerceIn(0, 30), selectedBootstrap = selectedBootstrap.coerceIn(0, 30),
        rawRevisionChanges = rawRevisionChanges.coerceIn(0, 65535),
    )
}

/** Immutable publication. Matrix dispatch counts are not evidence of physical HTTP dispatch. */
class InitialRenderDiagnosticReport internal constructor(
    val unavailable: InitialRenderUnavailable? = InitialRenderUnavailable.NEVER_MEASURED,
    samples: List<InitialRenderDiagnosticSnapshot> = emptyList(),
    val resetsSeen: Int = 0,
    val captureAttempts: Int = 0,
    val emptyCaptures: Int = 0,
    val matrixVisible: Int = 0,
    val matrixBootstrap: Int = 0,
    val matrixHistory: Int = 0,
    val unmeasuredHistory: Int = 0,
) {
    val samples: List<InitialRenderDiagnosticSnapshot> = Collections.unmodifiableList(ArrayList(samples))

    internal fun sample(value: InitialRenderDiagnosticSnapshot): InitialRenderDiagnosticReport {
        val bounded = value.bounded()
        if (samples.lastOrNull() == bounded) return this
        val next = samples + bounded
        return changed(unavailable = null, samples = if (next.size <= 16) next else next.take(4) + next.takeLast(12))
    }

    internal fun changed(
        unavailable: InitialRenderUnavailable? = this.unavailable,
        samples: List<InitialRenderDiagnosticSnapshot> = this.samples,
        resetsSeen: Int = this.resetsSeen,
        captureAttempts: Int = this.captureAttempts,
        emptyCaptures: Int = this.emptyCaptures,
        matrixVisible: Int = this.matrixVisible,
        matrixBootstrap: Int = this.matrixBootstrap,
        matrixHistory: Int = this.matrixHistory,
        unmeasuredHistory: Int = this.unmeasuredHistory,
    ) = InitialRenderDiagnosticReport(unavailable, samples, resetsSeen, captureAttempts, emptyCaptures,
        matrixVisible, matrixBootstrap, matrixHistory, unmeasuredHistory)
}
