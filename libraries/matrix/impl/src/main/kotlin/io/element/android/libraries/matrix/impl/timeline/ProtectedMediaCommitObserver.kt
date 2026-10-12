/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.timeline

import io.element.android.libraries.matrix.api.media.MediaSubmissionFact
import io.element.android.libraries.matrix.api.media.MediaSubmissionPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.matrix.rustcomponents.sdk.RoomSendQueueUpdate
import org.matrix.rustcomponents.sdk.SendQueueListener
import java.util.concurrent.ConcurrentHashMap

/** Room-owned SDK facts are associated only by the exact prepared LOCAL carrier's transaction.
 * Native join, NewLocalEvent, upload progress and a filename alone never prove commit.
 * Durable facts use the caller's existing encrypted draft and original operation.
 */
internal class ProtectedMediaCommitObserver(
    private val transportName: String,
    private val scope: CoroutineScope,
    private val retain: suspend (MediaSubmissionFact) -> Unit,
    private val accepted: suspend (MediaSubmissionFact) -> Unit,
) : SendQueueListener {
    private val sentEvents = ConcurrentHashMap<String, String>()
    private val mutex = Mutex()
    private var transactionId: String? = null
    private var cleanupAt: Long? = null
    private var committed: MediaSubmissionFact? = null
    var closeSubscription: () -> Unit = {}

    override fun onUpdate(update: RoomSendQueueUpdate) {
        if (update !is RoomSendQueueUpdate.SentEvent || !ProtectedEventOwnershipResolver.isCanonicalEvent(update.eventId)) return
        // Bound the pre-association cache. Saturation leaves a new candidate UNKNOWN.
        if (sentEvents.size >= 256 && !sentEvents.containsKey(update.transactionId)) return
        val previous = sentEvents.putIfAbsent(update.transactionId, update.eventId)
        if (previous != null && previous != update.eventId) sentEvents[update.transactionId] = "CONFLICT"
        scope.launch {
            try { fact() } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Retain exact facts for recovery; no second Send. */ }
        }
    }

    suspend fun bindOriginalTransaction(value: String) = mutex.withLock {
        require(value.isNotBlank() && value.length <= 256 && value.none(Char::isWhitespace))
        require(transactionId == null || transactionId == value) { "Original media transaction cannot be rebound." }
        retain(MediaSubmissionFact(MediaSubmissionPhase.UNKNOWN, transportName, transactionId = value, localCleanupAtEpochMillis = cleanupAt))
        transactionId = value
        reconcileLocked()
    }

    suspend fun localCleanupCompleted(occurredAt: Long) = mutex.withLock {
        require(occurredAt > 0)
        if (cleanupAt == null) {
            retain(MediaSubmissionFact(MediaSubmissionPhase.UNKNOWN, transportName, transactionId = transactionId,
                localCleanupAtEpochMillis = occurredAt))
            cleanupAt = occurredAt
        }
        reconcileLocked()
    }

    suspend fun fact(): MediaSubmissionFact? = mutex.withLock { reconcileLocked() }

    private suspend fun reconcileLocked(): MediaSubmissionFact? {
        committed?.let { return it }
        val transaction = transactionId ?: return null
        val clearedAt = cleanupAt ?: return null
        val event = sentEvents[transaction]?.takeIf(ProtectedEventOwnershipResolver::isCanonicalEvent) ?: return null
        val fact = MediaSubmissionFact(MediaSubmissionPhase.ACCEPTED, transportName, event, transaction,
            localCleanupAtEpochMillis = clearedAt)
        accepted(fact) // Persist the original accepted/checkpoint fact before retiring the observer.
        committed = fact
        closeSubscription()
        return fact
    }
}
