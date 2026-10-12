/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.api.media

import io.element.android.libraries.matrix.api.core.EventId
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Local ownership of one existing LC16 transfer; it neither grants access nor receipts remote completion. */
class MediaSendCancellationContext : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<MediaSendCancellationContext>

    @Volatile var requested: Boolean = false
        private set
    @Volatile var enqueueAttempted: Boolean = false
        private set
    @Volatile var protectionInFlight: Boolean = false
        private set
    fun beforeProtection() { protectionInFlight = true }
    fun afterProtection() { protectionInFlight = false }
    @Volatile var handler: MediaUploadHandler? = null
        private set
    @Volatile var cleanupFailure: Throwable? = null

    @Synchronized fun request() { requested = true }
    @Synchronized fun beforeEnqueue() {
        check(!requested) { "Media cancellation already requested." }
        enqueueAttempted = true
    }
    fun attach(handler: MediaUploadHandler) { this.handler = handler }
}

sealed interface MediaSendCancellationResult {
    data object Cancelled : MediaSendCancellationResult
    data class Committed(val eventId: EventId) : MediaSendCancellationResult
    data object Unknown : MediaSendCancellationResult
}

class MediaCancellationPendingException(cause: Throwable) : IllegalStateException("Media cancellation is awaiting reconciliation.", cause)

class MediaSendOutcomeUnknownException : IllegalStateException("Media submission is awaiting reconciliation.")
