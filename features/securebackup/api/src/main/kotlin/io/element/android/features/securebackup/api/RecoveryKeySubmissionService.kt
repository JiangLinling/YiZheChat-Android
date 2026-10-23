/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.securebackup.api

/**
 * Completes recovery-key verification through the existing LC-08 authority chain.
 *
 * Recovery material is accepted only for the duration of this call and must never be persisted
 * by callers. A successful result means Matrix verification and the server/KMS checks completed;
 * it does not let a UI bypass the surrounding LC-05/LC-06 startup gate.
 */
sealed interface RecoverySessionState {
    data object Discovering : RecoverySessionState
    data object None : RecoverySessionState
    data class Pending(val operationId: String, val includeConfidential: Boolean = false) : RecoverySessionState
    data class Waiting(
        val operationId: String,
        val recoveryId: String,
        val expiresAtEpochMillis: Long,
        val includeConfidential: Boolean = false,
        val oldDeviceConfirmed: Boolean = false,
        val administratorApproved: Boolean = false,
    ) : RecoverySessionState
    data class Rejected(val reasonCode: String) : RecoverySessionState
}

val RecoverySessionState.requiresGate: Boolean get() = this !is RecoverySessionState.None

private val noRecoveryState = kotlinx.coroutines.flow.MutableStateFlow<RecoverySessionState>(RecoverySessionState.None)

fun interface RecoveryKeySubmissionService {
    val recoveryState: kotlinx.coroutines.flow.StateFlow<RecoverySessionState> get() = noRecoveryState
    suspend fun restorePendingRecovery(): Result<Unit> = Result.success(Unit)
    suspend fun refreshPendingRecovery(): Result<Unit> = Result.failure(IllegalStateException("RECOVERY_REFRESH_UNSUPPORTED"))
    suspend fun recover(recoveryKey: String): Result<Unit>
    suspend fun recover(recoveryKey: String, includeConfidential: Boolean): Result<Unit> =
        if (includeConfidential) Result.failure(IllegalStateException("CONFIDENTIAL_RECOVERY_UNSUPPORTED")) else recover(recoveryKey)
}
