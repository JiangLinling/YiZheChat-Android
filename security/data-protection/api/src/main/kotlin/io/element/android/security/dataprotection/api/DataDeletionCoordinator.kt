/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.dataprotection.api

@JvmInline
value class DeletionOperationId(val value: String)

enum class DeletionReason {
    RETENTION_EXPIRED,
    USER_REQUEST,
    ACCOUNT_LOGOUT,
    DEVICE_REVOKED,
    ADMINISTRATIVE_REVOCATION,
    MIGRATION_CLEANUP,
}

sealed interface DeletionScope {
    data class Account(
        val accountIdHash: String,
        val vaultId: String,
        val localSessionId: String,
        val sessionFilePath: String,
        val sessionCachePath: String,
    ) : DeletionScope
    data class Content(
        val accountIdHash: String,
        val vaultId: String,
        val contentId: String,
    ) : DeletionScope

    /** A protected media asset. [contentId] identifies the content key; [assetId] is non-secret audit metadata. */
    data class Asset(
        val accountIdHash: String,
        val vaultId: String,
        val assetId: String,
        val contentId: String,
    ) : DeletionScope
}

enum class DeletionStep {
    REQUESTED,
    MARKED_INACCESSIBLE,
    STOPPED_SYNC_AND_UI,
    PURGED_MEMORY,
    DESTROYED_KEY_ACCESS,
    CLOSED_DATABASES,
    SANITIZED_DATABASES,
    DELETED_FILES,
    CANCELLED_NOTIFICATIONS,
    REVOKED_REMOTE_ACCESS,
    WROTE_NON_CONTENT_AUDIT,
    COMPLETE,
}

data class DeletionRequest(
    val operationId: DeletionOperationId,
    val scope: DeletionScope,
    val reason: DeletionReason,
    val policyVersion: Long,
    val requestedAtEpochMillis: Long,
    /**
     * Destructive preconditions already completed by the owner of the live account session.
     *
     * The coordinator persists these confirmations before destroying storage. Only lifecycle steps which cannot be
     * safely reconstructed after process death are accepted; storage, notification and audit steps remain owned by
     * the coordinator.
     */
    val confirmedPreconditions: Set<DeletionStep> = emptySet(),
)

data class DeletionReport(
    val operationId: DeletionOperationId,
    val completedSteps: Set<DeletionStep>,
    val retryRequired: Boolean,
    val errorCode: String? = null,
) {
    val isComplete: Boolean = DeletionStep.COMPLETE in completedSteps && !retryRequired
}

data class DeletionRecoveryReport(
    val operationReports: List<DeletionReport>,
    val unreadableJournalEntries: Int,
) {
    val retryRequired: Boolean = unreadableJournalEntries > 0 || operationReports.any { it.retryRequired }
}

interface DataDeletionCoordinator {
    /** Durably records an account deletion intent without starting destructive work. */
    suspend fun prepare(request: DeletionRequest): DeletionReport

    suspend fun delete(request: DeletionRequest): DeletionReport

    /** Resumes every durable, incomplete deletion operation left by a killed process, crash or device restart. */
    suspend fun resumePending(coldStart: Boolean = false): DeletionRecoveryReport
}

/** Account-scoped local resources owned by feature modules and cleared only after LC-22 is irreversible. */
interface PermanentAccountLocalResourceCleaner {
    suspend fun clear(scope: DeletionScope.Account): Boolean
}
