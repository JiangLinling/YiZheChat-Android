/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.appnav.securitysetup

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.verification.SessionVerifiedStatus
import io.element.android.security.contentprotection.api.ContentProtectionException
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleOperation
import io.element.android.security.dataprotection.api.LifecycleOperationStatus
import io.element.android.security.dataprotection.api.LifecyclePhase
import io.element.android.security.dataprotection.api.LifecycleSubjectHashes
import org.junit.Test

class SecuritySetupProgressTest {
    @org.junit.Test
    fun `synchronization denial remains recoverable without escaping its caller`() = kotlinx.coroutines.test.runTest {
        var phase = SecuritySetupPhase.READY
        val denied = java.io.IOException("TEST_AUTHORITY_DENIED")
        runSecuritySetupSynchronization(onFailure = { phase = it.toSecuritySetupPhase() }) { throw denied }
        assertThat(phase).isEqualTo(SecuritySetupPhase.SERVICE_UNAVAILABLE)
    }

    @org.junit.Test
    fun `synchronization cancellation propagates without being reported as availability failure`() = kotlinx.coroutines.test.runTest {
        var failures = 0
        val cancelled = kotlinx.coroutines.CancellationException("TEST_CANCELLED")
        val result = runCatching {
            runSecuritySetupSynchronization(onFailure = { failures++ }) { throw cancelled }
        }
        assertThat(result.exceptionOrNull()).isSameInstanceAs(cancelled)
        assertThat(failures).isEqualTo(0)
    }

    @org.junit.Test
    fun `successful synchronization does not call the failure path`() = kotlinx.coroutines.test.runTest {
        var successes = 0
        var failures = 0
        runSecuritySetupSynchronization(onFailure = { failures++ }) { successes++ }
        assertThat(successes).isEqualTo(1)
        assertThat(failures).isEqualTo(0)
    }

    private val accountIdHash = "a".repeat(64)
    private val matrixDeviceIdHash = "b".repeat(64)

    @Test
    fun `terminal latest activation does not fall back to an older pending login`() {
        val stalePending = anActivation(
            operationId = "stale-pending-operation",
            status = LifecycleOperationStatus.RUNNING,
            createdAt = 100,
        )
        val currentCompleted = anActivation(
            operationId = "current-completed-operation",
            status = LifecycleOperationStatus.SUCCEEDED,
            createdAt = 200,
            accountIdHash = accountIdHash,
            matrixDeviceIdHash = matrixDeviceIdHash,
        )

        assertThat(
            selectPendingFirstActivation(
                operations = listOf(stalePending, currentCompleted),
                accountIdHash = accountIdHash,
                matrixDeviceIdHash = matrixDeviceIdHash,
                nowEpochMillis = 1_000,
            )
        ).isNull()
    }

    @Test
    fun `newest pending activation remains resumable`() {
        val olderCompleted = anActivation(
            operationId = "older-completed-operation",
            status = LifecycleOperationStatus.SUCCEEDED,
            createdAt = 100,
        )
        val currentPending = anActivation(
            operationId = "current-pending-operation",
            status = LifecycleOperationStatus.RUNNING,
            createdAt = 200,
            accountIdHash = accountIdHash,
            matrixDeviceIdHash = matrixDeviceIdHash,
        )

        assertThat(
            selectPendingFirstActivation(
                operations = listOf(olderCompleted, currentPending),
                accountIdHash = accountIdHash,
                matrixDeviceIdHash = matrixDeviceIdHash,
                nowEpochMillis = 1_000,
            )
        )
            .isEqualTo(currentPending)
    }

    @Test
    fun `newer activation for another account cannot hide the current pending activation`() {
        val currentPending = anActivation(
            operationId = "current-pending-operation",
            status = LifecycleOperationStatus.RUNNING,
            createdAt = 200,
            accountIdHash = accountIdHash,
            matrixDeviceIdHash = matrixDeviceIdHash,
        )
        val unrelatedCompleted = anActivation(
            operationId = "unrelated-completed-operation",
            status = LifecycleOperationStatus.SUCCEEDED,
            createdAt = 300,
            accountIdHash = "c".repeat(64),
            matrixDeviceIdHash = "d".repeat(64),
        )

        assertThat(
            selectPendingFirstActivation(
                operations = listOf(currentPending, unrelatedCompleted),
                accountIdHash = accountIdHash,
                matrixDeviceIdHash = matrixDeviceIdHash,
                nowEpochMillis = 1_000,
            )
        ).isEqualTo(currentPending)
    }

    @Test
    fun `expired latest activation is never resumed`() {
        val expired = anActivation(
            operationId = "expired-operation",
            status = LifecycleOperationStatus.RUNNING,
            createdAt = 100,
            accountIdHash = accountIdHash,
            matrixDeviceIdHash = matrixDeviceIdHash,
        )

        assertThat(
            selectPendingFirstActivation(
                operations = listOf(expired),
                accountIdHash = accountIdHash,
                matrixDeviceIdHash = matrixDeviceIdHash,
                nowEpochMillis = 60_101,
            )
        ).isNull()
        assertThat(
            hasSuccessfulFirstActivationReceipt(
                operations = listOf(expired),
                accountIdHash = accountIdHash,
                matrixDeviceIdHash = matrixDeviceIdHash,
            )
        ).isFalse()
    }

    @Test
    fun `only a successful terminal LC-05 receipt can bypass resume`() {
        val succeeded = anActivation(
            operationId = "succeeded-operation",
            status = LifecycleOperationStatus.SUCCEEDED,
            createdAt = 100,
            accountIdHash = accountIdHash,
            matrixDeviceIdHash = matrixDeviceIdHash,
        )
        val failed = anActivation(
            operationId = "failed-operation",
            status = LifecycleOperationStatus.FAILED_FINAL,
            createdAt = 200,
            accountIdHash = accountIdHash,
            matrixDeviceIdHash = matrixDeviceIdHash,
        )

        assertThat(hasSuccessfulFirstActivationReceipt(listOf(succeeded), accountIdHash, matrixDeviceIdHash)).isTrue()
        assertThat(hasSuccessfulFirstActivationReceipt(listOf(succeeded, failed), accountIdHash, matrixDeviceIdHash)).isFalse()
    }

    @Test
    fun `only typed device recovery becomes a recoverable user state`() {
        assertThat(ContentProtectionException.DeviceRecoveryRequired().toSecuritySetupPhase())
            .isEqualTo(SecuritySetupPhase.RECOVERY_METHOD_REQUIRED)
        assertThat(ContentProtectionException.AccessDenied().toSecuritySetupPhase())
            .isEqualTo(SecuritySetupPhase.SECURITY_BLOCKED)
        assertThat(ContentProtectionException.AuthenticationFailed().toSecuritySetupPhase())
            .isEqualTo(SecuritySetupPhase.SECURITY_BLOCKED)
        assertThat(ContentProtectionException.KmsUnavailable().toSecuritySetupPhase())
            .isEqualTo(SecuritySetupPhase.SERVICE_UNAVAILABLE)
    }

    @Test
    fun `service unavailable automatically retries three times with bounded backoff`() {
        assertThat(SECURITY_SETUP_AUTO_RETRY_DELAYS_MILLIS).containsExactly(
            2_000L,
            4_000L,
            8_000L,
        ).inOrder()
    }

    @Test
    fun `first activation binds both account and Matrix device identity`() {
        val request = firstActivationOperationRequest(accountIdHash, matrixDeviceIdHash)

        assertThat(request.lifecycleId).isEqualTo(io.element.android.security.dataprotection.api.FullAppLifecycleId.LC_05)
        assertThat(request.trigger).isEqualTo("first_activation")
        assertThat(request.accountIdHash).isEqualTo(accountIdHash)
        assertThat(request.matrixDeviceIdHash).isEqualTo(matrixDeviceIdHash)
    }

    @Test
    fun `location required highlights device information collection`() {
        assertThat(SecuritySetupPhase.LOCATION_REQUIRED.toSecurityProgress()).containsExactly(
            SecurityProgressState.COMPLETE,
            SecurityProgressState.CURRENT,
            SecurityProgressState.PENDING,
        ).inOrder()
    }

    @Test
    fun `validation pending highlights system security validation`() {
        assertThat(SecuritySetupPhase.VALIDATION_PENDING.toSecurityProgress()).containsExactly(
            SecurityProgressState.COMPLETE,
            SecurityProgressState.COMPLETE,
            SecurityProgressState.CURRENT,
        ).inOrder()
    }

    @Test
    fun `managed approval pending remains at system security validation`() {
        assertThat(SecuritySetupPhase.MANAGED_APPROVAL_PENDING.toSecurityProgress()).containsExactly(
            SecurityProgressState.COMPLETE,
            SecurityProgressState.COMPLETE,
            SecurityProgressState.CURRENT,
        ).inOrder()
    }

    @Test
    fun `ready advances to secure initialization`() {
        assertThat(SecuritySetupPhase.READY.toSecurityProgress()).containsExactly(
            SecurityProgressState.COMPLETE,
            SecurityProgressState.COMPLETE,
            SecurityProgressState.COMPLETE,
        ).inOrder()
    }

    @Test
    fun `not passed marks validation as error without exposing internal approval state`() {
        assertThat(SecuritySetupPhase.ACTION_REQUIRED.toSecurityProgress()).containsExactly(
            SecurityProgressState.COMPLETE,
            SecurityProgressState.COMPLETE,
            SecurityProgressState.ERROR,
        ).inOrder()
    }

    @Test
    fun `completed returning user bypasses setup rendering once remote approval is ready`() {
        assertThat(
            canResumeCompletedSecuritySetup(
                phase = SecuritySetupPhase.READY,
                locallyCompleted = true,
                localRequirementsLoaded = true,
                sessionVerificationStatus = SessionVerifiedStatus.Verified,
                lockScreenSetupRequired = false,
            )
        ).isTrue()
    }

    @Test
    fun `optional analytics choice cannot keep a verified returning user in startup`() {
        assertThat(
            canResumeCompletedSecuritySetup(
                phase = SecuritySetupPhase.READY,
                locallyCompleted = true,
                localRequirementsLoaded = true,
                sessionVerificationStatus = SessionVerifiedStatus.Verified,
                lockScreenSetupRequired = false,
            )
        ).isTrue()
    }

    @Test
    fun `completed marker cannot bypass an unmet security requirement`() {
        assertThat(
            canResumeCompletedSecuritySetup(
                phase = SecuritySetupPhase.READY,
                locallyCompleted = true,
                localRequirementsLoaded = true,
                sessionVerificationStatus = SessionVerifiedStatus.NotVerified,
                lockScreenSetupRequired = false,
            )
        ).isFalse()
    }

    @Test
    fun `completed returning user sees startup waiting while local requirements load`() {
        assertThat(
            shouldShowStartupWaiting(
                phase = SecuritySetupPhase.READY,
                locallyCompleted = true,
                localRequirementsLoaded = false,
                canResumeCompletedSetup = false,
            )
        ).isTrue()
    }

    @Test
    fun `loaded unmet requirement renders setup instead of bypassing it`() {
        assertThat(
            shouldShowStartupWaiting(
                phase = SecuritySetupPhase.READY,
                locallyCompleted = true,
                localRequirementsLoaded = true,
                canResumeCompletedSetup = false,
            )
        ).isFalse()
    }

    private fun anActivation(
        operationId: String,
        status: LifecycleOperationStatus,
        createdAt: Long,
        accountIdHash: String? = null,
        matrixDeviceIdHash: String? = null,
    ) = LifecycleOperation(
        operationId = operationId,
        lifecycleId = FullAppLifecycleId.LC_05,
        idempotencyKey = operationId,
        subjects = LifecycleSubjectHashes(
            accountIdHash = accountIdHash,
            matrixDeviceIdHash = matrixDeviceIdHash,
        ),
        status = status,
        currentPhase = if (status.isTerminal) LifecyclePhase.RECEIPTED else LifecyclePhase.CLIENT_APPLIED,
        terminalReceiptId = if (status.isTerminal) "terminal-receipt-id" else null,
        deadlineEpochMillis = createdAt + 60_000,
        createdAtEpochMillis = createdAt,
        updatedAtEpochMillis = createdAt,
    )
}
