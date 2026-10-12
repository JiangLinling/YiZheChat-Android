/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.media

import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.matrix.api.media.MediaSendCancellationResult
import io.element.android.libraries.matrix.api.media.MediaSubmissionFact
import io.element.android.libraries.matrix.impl.timeline.acceptedMediaSubmission
import io.element.android.libraries.matrix.api.media.MediaUploadHandler
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import io.element.android.libraries.matrix.api.media.MediaSendOutcomeUnknownException

/** Native join proves preparation/enqueue only. An exact raw carrier or original SDK SentEvent proves commit. */
internal class CancellableProtectedMediaUpload(
    override val protectedMediaTransportName: String,
    scope: CoroutineScope,
    join: suspend () -> Unit,
    private val stopPreparation: () -> Unit,
    private val observe: suspend () -> List<MatrixTimelineItem.Event>,
    private val clearFiles: () -> Unit,
    private val timeoutMillis: Long,
    private val accepted: suspend (MediaSubmissionFact) -> Unit = {},
    private val observeCommit: suspend () -> MediaSubmissionFact? = { null },
    private val cleanupCompleted: suspend (Long) -> Unit = {},
    nativeExited: () -> Unit = {},
) : MediaUploadHandler {
    private val mutex = Mutex()
    private var filesCleared = false
    private var clearedAt: Long? = null
    private var cleanupRecorded = false

    private suspend fun clearDerivedFiles() = mutex.withLock {
        if (!filesCleared) { clearFiles(); clearedAt = System.currentTimeMillis(); filesCleared = true }
        cleanupCompleted(checkNotNull(clearedAt))
        cleanupRecorded = true
    }

    // Native exit and actual cleanup remain owned by the send, even after its finite caller wait.
    private val nativeExit = scope.async {
        runCatchingExceptions {
            val joined = runCatchingExceptions { join() }
            clearDerivedFiles()
            joined.getOrThrow()
        }
    }.also { job ->
        // Run only after the result is complete, never from inside its cleanup callback.
        job.invokeOnCompletion { nativeExited() }
    }

    override suspend fun await(): Result<Unit> = runCatchingExceptions {
        val joined = nativeExit.await()
        if (!cleanupRecorded) clearDerivedFiles()
        val committed = withTimeoutOrNull(timeoutMillis) {
            while (true) {
                val fact = observeCommit() ?: acceptedMediaSubmission(observe(), protectedMediaTransportName)
                    ?.copy(localCleanupAtEpochMillis = clearedAt)
                if (fact != null) { accepted(fact); return@withTimeoutOrNull true }
                delay(50)
            }
        }
        if (committed != true) {
            // An original authoritative commit wins over a join error; an error alone proves no absence.
            joined.getOrThrow()
            throw MediaSendOutcomeUnknownException()
        }
    }

    // Legacy callers cannot convert a signal to success or destruct a possibly committed share.
    override fun cancel() = stopPreparation()

    override suspend fun cancelPending(): Result<MediaSendCancellationResult> = runCatchingExceptions {
        stopPreparation()
        nativeExit.await() // Never await native cleanup while holding its mutex.
        mutex.withLock {
            if (!filesCleared) { clearFiles(); clearedAt = System.currentTimeMillis(); filesCleared = true }
            cleanupCompleted(checkNotNull(clearedAt))
            var items = observe()
            repeat(20) {
                if (items.isEmpty()) { delay(50); items = observe() }
            }
            val item = items.singleOrNull() ?: return@withLock MediaSendCancellationResult.Unknown
            val committedId = acceptedMediaSubmission(items, protectedMediaTransportName)?.eventId?.let { io.element.android.libraries.matrix.api.core.EventId(it) }
            if (committedId != null) { return@withLock MediaSendCancellationResult.Committed(committedId) }
            val handle = item.event.sendHandleProvider() ?: return@withLock MediaSendCancellationResult.Unknown
            handle.abort().getOrThrow()
            // R5 abort(true) can mean a later redaction was scheduled during event HTTP. Even a
            // progress snapshot can be stale. Stop the exact queue, retain its shares/draft and
            // report unknown until an authoritative event or absence result is available.
            MediaSendCancellationResult.Unknown
        }
    }
}
