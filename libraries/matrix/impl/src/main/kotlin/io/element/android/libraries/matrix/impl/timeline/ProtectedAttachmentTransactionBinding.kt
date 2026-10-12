/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.timeline

import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.item.event.MessageContent
import io.element.android.libraries.matrix.api.timeline.item.event.MessageTypeWithAttachment
import io.element.android.libraries.matrix.api.timeline.item.event.TimelineItemEventOrigin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One live producer handoff; ciphertext matching never grants plaintext or an event binding. */
internal class ProtectedAttachmentTransactionBinding(
    private val transportName: String,
    private val captionCarrier: String?,
    private val bindTransaction: suspend (String) -> Unit,
) {
    private val mutex = Mutex()
    private var transactionId: String? = null

    suspend fun bind(item: MatrixTimelineItem.Event): Boolean = mutex.withLock {
        val event = item.event
        val type = (event.content as? MessageContent)?.type as? MessageTypeWithAttachment ?: return@withLock false
        val candidate = item.transactionId?.value?.takeIf {
            it.isNotBlank() && it.length <= 256 && it.none(Char::isWhitespace)
        } ?: return@withLock false
        // Only the actual SDK local echo of this in-flight producer may supply the transaction.
        // Server events still require their separate authoritative event registration.
        if (item.eventId != null || !event.isOwn || event.isRemote || event.origin != TimelineItemEventOrigin.LOCAL ||
            type.filename != transportName || type.caption != captionCarrier) return@withLock false
        transactionId?.let { return@withLock it == candidate }
        bindTransaction(candidate)
        transactionId = candidate
        true
    }
}
