/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.security.contentprotection.api

enum class MediaSendJournalState {
    PREPARED,
    ALLOW_VERIFIED,
    SEND_BOUND_VERIFIED,
    MATRIX_IN_FLIGHT,
    MATRIX_UNCERTAIN,
    MATRIX_ACCEPTED,
    ACTIVATION_PENDING,
    ACTIVE,
    CLEANUP_REQUIRED,
}
data class HistoricalMediaSendPrepared(
    val operationId: String,
    val requestId: String,
    val transactionId: String,
    val roomId: String,
    val assetId: String,
    val mediaVersion: Long,
    val resourceSetDigestSha256: String,
    val manifestDigestSha256: String,
    val pagesDigestSha256: String,
    val accountIdHash: String,
    val installationId: String,
    val matrixUserId: String,
    val matrixDeviceId: String,
    val deviceTrustGeneration: Long,
    val roomEpoch: Long,
    val contentContractId: String,
    val policyDomain: String,
    val policyRevisionId: String,
    val policyVersion: Long,
    val policyDigestSha256: String,
    val kmsFenceGeneration: Long,
    val resourceRootGeneration: Long,
    val eventContentDigestSha256: String,
    val canonicalEventContentJson: String,
)

data class MediaSendJournalRecord(
    val revision: Long,
    val recordDigestSha256: String,
    val state: MediaSendJournalState,
    val prepared: HistoricalMediaSendPrepared,
    val allowEnvelope: ByteArray? = null,
    val allowEnvelopeSha256: String? = null,
    val sendBoundEnvelope: ByteArray? = null,
    val sendBoundEnvelopeSha256: String? = null,
    val matrixEventId: String? = null,
)

data class MediaSendJournalCas(
    val operationId: String,
    val requestId: String,
    val transactionId: String,
    val expectedRevision: Long,
    val expectedRecordDigestSha256: String,
    val expectedState: MediaSendJournalState,
    val nextState: MediaSendJournalState,
    val allowEnvelope: ByteArray? = null,
    val sendBoundEnvelope: ByteArray? = null,
    val matrixEventId: String? = null,
)

/** Authenticated historical journal. It cannot refresh or synthesize current authority. */
interface MediaSendJournal {
    suspend fun persistPrepared(prepared: HistoricalMediaSendPrepared): Result<MediaSendJournalRecord>
    suspend fun load(operationId: String, requestId: String, transactionId: String): Result<MediaSendJournalRecord>
    suspend fun compareAndSet(request: MediaSendJournalCas): Result<MediaSendJournalRecord>
    suspend fun pendingRecovery(limit: Int = 32): Result<List<MediaSendJournalRecord>>
}
