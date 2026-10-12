/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.matrix.api.timeline

import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.TransactionId

/**
 * Matrix production boundary required by WP08 Contract B v3.
 *
 * An implementation must enqueue the exact custom `m.room.message` content through the normal E2EE
 * send queue with the caller-supplied transaction id. It must observe NewLocalEvent for that id and
 * resolve SentEvent for the same id to the authoritative event id. `sendRaw`, a generated transaction
 * id, a Unit-only result, or a whole-file attachment implementation does not satisfy this contract.
 */
interface QueuedEncryptedRoomMessageSender {
    suspend fun enqueueEncryptedRoomMessage(
        transactionId: TransactionId,
        canonicalContentJson: String,
    ): Result<QueuedEncryptedRoomMessageReceipt>
}

data class QueuedEncryptedRoomMessageReceipt(
    val transactionId: TransactionId,
    val eventId: EventId,
    val localEchoObserved: Boolean,
)
