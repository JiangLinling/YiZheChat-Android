/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.security.dataprotection.api

@JvmInline
value class PermanentAccountDeletionHandle(val value: String)

enum class PermanentAccountDeletionState {
    PREPARED,
    FROZEN,
    MATRIX_VERIFIED,
    CRYPTO_ERASED,
    AUTHORITIES_PENDING,
    CLIENT_CLEAR_PENDING,
    RETRYABLE_FAILURE,
    BLOCKED,
    CONTRADICTED,
    COMPLETE,
}

data class SecurityErasureMilestone(
    val operationId: String,
    val accountIdHash: String,
    val generation: Long,
    val policyVersion: String,
    val completedAtEpochMillis: Long,
    val postconditionHash: String,
)

data class DeletionScopedClientCapability(
    val token: String,
    val resourceScopeHash: String,
    val expiresAtEpochMillis: Long,
)

data class PermanentAccountDeletionStatus(
    val operationId: String,
    val accountState: String,
    val deletionState: PermanentAccountDeletionState,
    val generation: Long,
    val policyVersion: String,
    val securityErasureMilestone: SecurityErasureMilestone?,
    val clientReceiptCapability: DeletionScopedClientCapability?,
    val retryable: Boolean,
    val finalReceiptPresent: Boolean,
)

data class PermanentAccountDeletionClientReceipt(
    val operationId: String,
    val installationId: String,
    val resourceScopeHash: String,
    val manifestDigestSha256: String,
    val attestationEvidenceHash: String,
    val occurredAtEpochMillis: Long,
    val deletionScopedToken: String,
)

data class PermanentAccountDeletionClientReceiptResult(
    val accepted: Boolean,
    val idempotentReplay: Boolean,
    val status: PermanentAccountDeletionStatus,
)

class PermanentAccountDeletionAwaitingMatrixException : Exception("Matrix account deactivation is not terminal yet.")

class PermanentAccountDeletionAwaitingGlobalDestroyException :
    Exception("Account destruction has not reached its server-side terminal state.")

class PermanentAccountDeletionPreparationExpiredException : Exception("Permanent account deletion preparation expired.")

class PermanentAccountDeletionSubmissionBlockedException : Exception("LC22_UPGRADE_IN_PROGRESS")

data class PermanentAccountDeletionRecoveryReport(
    val completedCount: Int,
    val waitingForMatrixDeactivationCount: Int,
    val retryRequiredCount: Int,
    val unreadableRecordCount: Int,
) {
    val retryRequired: Boolean = retryRequiredCount > 0 || unreadableRecordCount > 0
}

/**
 * Coordinates the irreversible server account tombstone with the durable local cryptographic cleanup journal.
 *
 * Local account removal never calls this interface. A permanent deletion is prepared before Matrix deactivation,
 * committed only after the server independently observes that terminal Matrix state, and can resume after process
 * death without storing the user's password.
 */
interface PermanentAccountDeletionCoordinator {
    suspend fun prepare(
        localDeletionRequest: DeletionRequest,
        eraseData: Boolean,
    ): Result<PermanentAccountDeletionHandle>

    suspend fun pending(accountIdHash: String): PermanentAccountDeletionHandle?

    /** Excludes requests explicitly rejected before freeze; those require a user retry. */
    suspend fun pendingRecovery(accountIdHash: String): PermanentAccountDeletionHandle? = pending(accountIdHash)

    suspend fun commitRemote(handle: PermanentAccountDeletionHandle): Result<AccountDeletionReceipt>

    suspend fun completeLocal(
        handle: PermanentAccountDeletionHandle,
        confirmedPreconditions: Set<DeletionStep>,
    ): DeletionReport

    /** Removes only an intent that the server has explicitly not frozen (DRAFT or SUBMISSION_BLOCKED). */
    suspend fun abandon(handle: PermanentAccountDeletionHandle): Boolean

    /** Called before account vaults or Matrix sessions are opened in a new process. */
    suspend fun resumePending(coldStart: Boolean): PermanentAccountDeletionRecoveryReport
}
