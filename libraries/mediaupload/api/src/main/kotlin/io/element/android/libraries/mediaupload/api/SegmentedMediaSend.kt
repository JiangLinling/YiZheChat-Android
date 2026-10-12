/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.mediaupload.api

import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.TransactionId

/**
 * A complete Contract B root event whose resources are already uploaded and whose root is PREPARED.
 *
 * Authority is deliberately absent from this request. The sender must obtain and verify Contract A
 * ALLOW and SEND_BOUND envelopes for this exact operation, request, transaction and canonical event
 * before it invokes Matrix. [canonicalEventContentJson] is the decrypted five-member
 * `m.room.message` content from Contract B v3; it is never a legacy video attachment.
 */
data class PreparedSegmentedMediaSend(
    val operationId: String,
    val requestId: String,
    val transactionId: TransactionId,
    val canonicalEventContentJson: String,
)

sealed interface SegmentedMediaSendOutcome {
    val transactionId: TransactionId

    /** Matrix accepted the exact queued event; authoritative B observation/ACTIVE is still pending. */
    data class SentPendingObservation(
        override val transactionId: TransactionId,
        val eventId: EventId,
    ) : SegmentedMediaSendOutcome

    /** Dispatch may have happened. Recovery must reconcile this same transaction; no new send is allowed. */
    data class Uncertain(
        override val transactionId: TransactionId,
    ) : SegmentedMediaSendOutcome
}
