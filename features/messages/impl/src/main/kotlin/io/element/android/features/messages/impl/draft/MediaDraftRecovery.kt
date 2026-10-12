/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.messages.impl.draft

import io.element.android.libraries.matrix.api.media.MediaSubmissionFact
import io.element.android.libraries.matrix.api.media.MediaSubmissionPhase

internal fun mergeMediaSubmissionFacts(old: Map<Int, MediaSubmissionFact>, incoming: Map<Int, MediaSubmissionFact>) =
    old.toMutableMap().apply {
        incoming.forEach { (index, fact) ->
            val previous = get(index)
            if (previous != null) {
                require(previous.transportName == null || fact.transportName == null || previous.transportName == fact.transportName)
                require(previous.eventId == null || fact.eventId == null || previous.eventId == fact.eventId)
                require(previous.transactionId == null || fact.transactionId == null || previous.transactionId == fact.transactionId)
                require(previous.uploadOperationId == null || fact.uploadOperationId == null || previous.uploadOperationId == fact.uploadOperationId)
            }
            val retained = if (previous != null && previous.phase.ordinal > fact.phase.ordinal) previous else fact
            put(index, retained.copy(
                transportName = previous?.transportName ?: fact.transportName,
                transactionId = previous?.transactionId ?: fact.transactionId,
                contentIds = previous?.contentIds.orEmpty() + fact.contentIds,
                localCleanupAtEpochMillis = previous?.localCleanupAtEpochMillis ?: fact.localCleanupAtEpochMillis,
                clientAppliedAtEpochMillis = previous?.clientAppliedAtEpochMillis ?: fact.clientAppliedAtEpochMillis,
                uploadOperationId = previous?.uploadOperationId ?: fact.uploadOperationId,
            ))
        }
    }.toMap()

internal fun MediaSelectionDraft.recoverMediaSubmissions(): MediaSelectionDraft {
    val accepted = submissions.filterValues { it.phase == MediaSubmissionPhase.ACCEPTED }.keys.filterNot { it in cancellationRequested || it in discarded }
    val retryable = submissions.filterValues { it.phase == MediaSubmissionPhase.PRE_SDK }.keys.filterNot { it in cancellationRequested || it in discarded }
    val retainedCommitted = (committed + accepted).distinct()
    return copy(committed = retainedCommitted, inFlight = inFlight.filterNot { it in accepted || it in retryable },
        selected = (selected + retryable).distinct().filterNot { it in retainedCommitted || it in cancellationRequested || it in discarded })
}

/** Old committed UI markers do not contain an authoritative Matrix acceptance fact. */
internal fun MediaSelectionDraft.hasUnknownMediaSubmission(): Boolean =
    inFlight.any { submissions[it]?.phase !in setOf(MediaSubmissionPhase.PRE_SDK, MediaSubmissionPhase.ACCEPTED) } ||
        committed.any { submissions[it]?.phase != MediaSubmissionPhase.ACCEPTED }
