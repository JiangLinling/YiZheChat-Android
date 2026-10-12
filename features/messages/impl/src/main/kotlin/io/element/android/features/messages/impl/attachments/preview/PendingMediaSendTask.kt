/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.messages.impl.attachments.preview

import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.matrix.api.media.MediaSendCancellationContext
import io.element.android.libraries.matrix.api.media.MediaSendCancellationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One selected item owns its task. Cancelling it must not cancel another selected item. */
internal class PendingMediaSendTask(
    scope: CoroutineScope,
    private val clearUnqueuedFiles: () -> Unit,
    send: suspend () -> Boolean,
) {
    val owner = MediaSendCancellationContext()
    private val mutex = Mutex()
    private val job = scope.async(owner, start = CoroutineStart.LAZY) { runCatchingExceptions { send() } }

    fun requestStop() { owner.request(); job.cancel() }

    fun closeUnused() { if (!job.isActive && !job.isCompleted) job.cancel() }

    suspend fun send(): Boolean = try {
        if (owner.requested) false else { job.start(); job.await().getOrThrow() }
    } catch (cancelled: CancellationException) {
        if (!owner.requested) throw cancelled
        false
    }

    suspend fun cancel(): Result<MediaSendCancellationResult> = runCatchingExceptions {
        mutex.withLock {
            owner.request()
            job.cancelAndJoin()
            withContext(NonCancellable) {
                owner.cleanupFailure?.let { throw it }
                val result = owner.handler?.cancelPending()?.getOrThrow()
                    ?: if (owner.enqueueAttempted || owner.protectionInFlight) MediaSendCancellationResult.Unknown else MediaSendCancellationResult.Cancelled
                if (result == MediaSendCancellationResult.Cancelled) clearUnqueuedFiles()
                result
            }
        }
    }
}
