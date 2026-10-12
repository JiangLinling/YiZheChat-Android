/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.api.media

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Android owns the pre-dispatch fact; only a matching Matrix accepted event owns ACCEPTED. */
enum class MediaSubmissionPhase { PRE_SDK, UNKNOWN, ACCEPTED }

data class MediaSubmissionFact(
    val phase: MediaSubmissionPhase,
    val transportName: String? = null,
    val eventId: String? = null,
    val transactionId: String? = null,
    val contentIds: Set<String> = emptySet(),
    val localCleanupAtEpochMillis: Long? = null,
    val clientAppliedAtEpochMillis: Long? = null,
    val uploadOperationId: String? = null,
) {
    init {
        require(transportName == null || transportName.matches(Regex("\\.yizhe-[A-Za-z0-9.~_-]+\\.yzm")))
        require(phase != MediaSubmissionPhase.ACCEPTED ||
            (transportName != null && eventId != null && eventId.startsWith("$") && eventId.length in 2..512 && eventId.none(Char::isWhitespace)))
        require(phase == MediaSubmissionPhase.ACCEPTED || eventId == null)
        require(contentIds.all { it.matches(Regex("[A-Za-z0-9._-]{8,96}")) })
        require(localCleanupAtEpochMillis == null || localCleanupAtEpochMillis > 0)
        require(clientAppliedAtEpochMillis == null || (phase == MediaSubmissionPhase.ACCEPTED && clientAppliedAtEpochMillis > 0))
    }
}

/** Extends the existing encrypted draft, without introducing another operation identity. */
class MediaSubmissionContext(
    val beforeSdk: suspend (String) -> Unit,
    val accepted: suspend (MediaSubmissionFact) -> Unit,
    val beforeKmsStore: suspend (String) -> Unit = {},
    val observed: suspend (MediaSubmissionFact) -> Unit = {},
    val originalDeadline: suspend () -> Long? = { null },
) : AbstractCoroutineContextElement(Key) {
    var keyStoreStarted: Boolean = false
        private set
    suspend fun recordKeyStore(contentId: String) {
        beforeKmsStore(contentId)
        keyStoreStarted = true
    }
    companion object Key : CoroutineContext.Key<MediaSubmissionContext>
}
