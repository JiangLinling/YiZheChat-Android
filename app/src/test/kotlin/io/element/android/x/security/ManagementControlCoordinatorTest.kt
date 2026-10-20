/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.security

import com.google.common.truth.Truth.assertThat
import io.element.android.security.dataprotection.api.ActiveDataProtectionPolicy
import io.element.android.security.dataprotection.api.DataProtectionPolicySource
import io.element.android.security.dataprotection.api.DataProtectionPolicyV1
import io.element.android.security.dataprotection.api.HardwareTrustRequirement
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.ManagementCommandType
import io.element.android.security.dataprotection.api.ManagementControlService
import io.element.android.security.dataprotection.api.ManagementSyncReport
import io.element.android.security.dataprotection.api.ManagementSyncStatus
import io.element.android.security.dataprotection.api.ManagementPolicyApplication
import io.element.android.security.dataprotection.api.ManagementPolicyApplicationState
import io.element.android.security.dataprotection.api.PolicyCleanupOperation
import io.element.android.security.dataprotection.api.PolicyCleanupOperationState
import io.element.android.security.dataprotection.api.ManagedDeviceSecurityState
import io.element.android.security.dataprotection.api.StartupSecurityReadinessState
import io.element.android.security.dataprotection.api.VerifiedManagementCommand
import io.element.android.security.dataprotection.api.toStartupSecurityReadiness
import io.element.android.security.dataprotection.api.NotificationExposure
import io.element.android.security.dataprotection.api.OfflineAccessMode
import io.element.android.security.dataprotection.api.ScreenCapturePolicy
import io.element.android.security.securestorage.api.DeviceTrustState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.InvalidKeyException
import javax.net.ssl.SSLHandshakeException

class ManagementControlCoordinatorTest {
    @Test
    fun `only a signed session scoped handover can yield the global cleanup gate`() {
        val handover = command(ManagementCommandType.CLEAR_ACCOUNT).copy(
            reasonCode = "ACCOUNT_MOBILE_SUPERSEDED", targetMatrixDeviceIdHash = "b".repeat(64), mobileGeneration = 2,
        )
        assertThat(handover.isScopedMobileHandover()).isTrue()
        assertThat(handover.copy(reasonCode = "ADMIN_REVOKE").isScopedMobileHandover()).isFalse()
        assertThat(handover.copy(targetMatrixDeviceIdHash = null).isScopedMobileHandover()).isFalse()
        assertThat(handover.copy(mobileGeneration = null).isScopedMobileHandover()).isFalse()
        assertThat(handover.copy(type = ManagementCommandType.REVOKE).isScopedMobileHandover()).isFalse()
    }

    @Test
    fun `remote commands bind to their own complete lifecycle`() {
        val expected = mapOf(
            ManagementCommandType.LOCK to FullAppLifecycleId.LC_09,
            ManagementCommandType.REVOKE to FullAppLifecycleId.LC_07,
            ManagementCommandType.CLEAR_ACCOUNT to FullAppLifecycleId.LC_22,
            ManagementCommandType.CLEAR_CONTENT to FullAppLifecycleId.LC_21,
        )

        expected.forEach { (type, lifecycleId) ->
            assertThat(command(type).lifecycleBinding().lifecycleId).isEqualTo(lifecycleId)
        }
    }

    @Test
    fun `revoked installation replays only its cached revoke closure`() {
        val revoke = command(ManagementCommandType.REVOKE)
        val unrelated = command(ManagementCommandType.CLEAR_ACCOUNT)

        assertThat(
            commandsAllowedWhileRevoked(DeviceTrustState.REVOKED, listOf(unrelated, revoke))
        ).containsExactly(revoke)
    }

    @Test
    fun `active installation does not bypass ordinary synchronization with cached commands`() {
        assertThat(
            commandsAllowedWhileRevoked(
                DeviceTrustState.TRUSTED,
                listOf(command(ManagementCommandType.REVOKE)),
            )
        ).isEmpty()
    }

    @Test
    fun `startup failures retain a stable user visible cause`() {
        assertThat(startupFailureReasonCode(UnknownHostException())).isEqualTo("CONTROL_DNS_UNAVAILABLE")
        assertThat(startupFailureReasonCode(ConnectException())).isEqualTo("CONTROL_CONNECTION_FAILED")
        assertThat(startupFailureReasonCode(SocketTimeoutException())).isEqualTo("CONTROL_REQUEST_TIMEOUT")
        assertThat(startupFailureReasonCode(IOException())).isEqualTo("CONTROL_NETWORK_IO")
        assertThat(startupFailureReasonCode(SSLHandshakeException("test"))).isEqualTo("CONTROL_TLS_REJECTED")
        assertThat(startupFailureReasonCode(InvalidKeyException())).isEqualTo("DEVICE_SIGNING_KEY_UNAVAILABLE")
        assertThat(startupFailureReasonCode(HttpStatusException())).isEqualTo("CONTROL_SERVER_REJECTED")
        assertThat(startupFailureReasonCode(SecurityException())).isEqualTo("SECURITY_POLICY_REJECTED")
        assertThat(startupFailureReasonCode(IllegalStateException())).isEqualTo("STARTUP_AUTHORITY_CONTRADICTORY")
        assertThat(startupFailureReasonCode(Exception())).isEqualTo("CONTROL_PLANE_UNAVAILABLE")
    }

    @Test
    fun `startup dependency chain enrolls before LC02 and synchronizes only inside LC02`() = runTest {
        val events = mutableListOf<String>()

        val result = runOrderedStartupDependencyChain(
            ensureEnrollment = { events += "enrollment" },
            runLc02 = { afterLc02 ->
                events += "lc02"
                afterLc02("lc02-operation")
            },
            afterLc02 = { operationId ->
                events += "management-sync:$operationId"
                "ready"
            },
        )

        assertThat(result).isEqualTo("ready")
        assertThat(events).containsExactly(
            "enrollment",
            "lc02",
            "management-sync:lc02-operation",
        ).inOrder()
    }

    @Test
    fun `readiness classification requires an accepted policy`() {
        val report = ManagementSyncReport(
            status = ManagementSyncStatus.SYNCHRONIZED,
            deviceSecurityState = ManagedDeviceSecurityState.READY,
        )

        assertThat(
            report.toStartupSecurityReadiness(
                installationId = "installation-1234567890",
                observedAtEpochMillis = 1_000,
            ).state
        )
            .isEqualTo(StartupSecurityReadinessState.RETRYABLE_FAILURE)
    }

    @Test
    fun `policy apply handshake pending remains a stable retryable startup reason`() {
        val readiness = ManagementSyncReport(
            status = ManagementSyncStatus.RETRY_REQUIRED,
            acceptedPolicyVersion = 12,
            errorCode = "POLICY_APPLICATION_HANDSHAKE_PENDING",
        ).toStartupSecurityReadiness(observedAtEpochMillis = 1_000)

        assertThat(readiness.state).isEqualTo(StartupSecurityReadinessState.RETRYABLE_FAILURE)
        assertThat(readiness.reasonCode).isEqualTo("REMOTE_POLICY_APPLICATION_PENDING")
    }

    @Test
    fun `policy application evidence pending remains a stable retryable startup reason`() {
        val readiness = ManagementSyncReport(
            status = ManagementSyncStatus.RETRY_REQUIRED,
            acceptedPolicyVersion = 12,
            errorCode = "POLICY_APPLICATION_EVIDENCE_PENDING",
        ).toStartupSecurityReadiness(observedAtEpochMillis = 1_000)

        assertThat(readiness.state).isEqualTo(StartupSecurityReadinessState.RETRYABLE_FAILURE)
        assertThat(readiness.reasonCode).isEqualTo("REMOTE_POLICY_APPLICATION_PENDING")
    }

    @Test
    fun `unsupported R05 policy remains a stable security rejection`() {
        val readiness = ManagementSyncReport(
            status = ManagementSyncStatus.SECURITY_REJECTED,
            acceptedPolicyVersion = 12,
            errorCode = "POLICY_APPLICATION_BLOCKED_VERSION",
        ).toStartupSecurityReadiness(observedAtEpochMillis = 1_000)

        assertThat(readiness.state).isEqualTo(StartupSecurityReadinessState.SECURITY_REJECTED)
        assertThat(readiness.reasonCode).isEqualTo("UNSUPPORTED_POLICY_VERSION")
    }

    @Test
    fun `successful no-op synchronization keeps authorized projections stable`() {
        assertThat(
            shouldInvalidateProtectedProjections(
                policyProjectionImpact = PolicyProjectionImpact.NONE,
                authorizationChanged = false,
                hasGlobalInvalidationCommand = false,
                securityRejected = false,
                offlineDeadlineExceeded = false,
            )
        ).isFalse()
    }

    @Test
    fun `security boundary still invalidates authorized projections`() {
        assertThat(
            shouldInvalidateProtectedProjections(
                policyProjectionImpact = PolicyProjectionImpact.NONE,
                authorizationChanged = true,
                hasGlobalInvalidationCommand = false,
                securityRejected = false,
                offlineDeadlineExceeded = false,
            )
        ).isTrue()
    }

    @Test
    fun `successful content clear does not globally invalidate unrelated projections`() {
        assertThat(ManagementCommandType.CLEAR_CONTENT.requiresGlobalProjectionInvalidation()).isFalse()
        assertThat(
            shouldInvalidateProtectedProjections(
                policyProjectionImpact = PolicyProjectionImpact.NONE,
                authorizationChanged = false,
                hasGlobalInvalidationCommand = ManagementCommandType.CLEAR_CONTENT.requiresGlobalProjectionInvalidation(),
                securityRejected = false,
                offlineDeadlineExceeded = false,
            )
        ).isFalse()
    }

    @Test
    fun `device trust commands retain hard invalidation while account clear is targeted`() {
        assertThat(
            listOf(
                ManagementCommandType.LOCK,
                ManagementCommandType.REVOKE,
            ).all(ManagementCommandType::requiresGlobalProjectionInvalidation)
        ).isTrue()
        assertThat(ManagementCommandType.CLEAR_ACCOUNT.requiresGlobalProjectionInvalidation()).isFalse()
    }

    @Test
    fun `verified retention policy update does not invalidate existing projections`() {
        val before = activePolicy(version = 10, retentionMillis = 24 * HOUR_MILLIS)
        val after = activePolicy(version = 11, retentionMillis = 30 * DAY_MILLIS)

        assertThat(classifyPolicyProjectionImpact(before, after))
            .isEqualTo(PolicyProjectionImpact.PRESENTATION_ONLY)
        assertThat(
            shouldInvalidateProtectedProjections(
                policyProjectionImpact = classifyPolicyProjectionImpact(before, after),
                authorizationChanged = false,
                hasGlobalInvalidationCommand = false,
                securityRejected = false,
                offlineDeadlineExceeded = false,
            )
        ).isFalse()
    }

    @Test
    fun `verified policy loss remains a hard fail closed boundary`() {
        val before = activePolicy(version = 10)
        val after = activePolicy(version = 10, source = DataProtectionPolicySource.BUILT_IN_BASELINE)

        assertThat(classifyPolicyProjectionImpact(before, after))
            .isEqualTo(PolicyProjectionImpact.HARD_INVALIDATION)
    }

    @Test
    fun `verified policy recovery retries without global invalidation`() {
        val before = activePolicy(version = 10, source = DataProtectionPolicySource.BUILT_IN_BASELINE)
        val after = activePolicy(version = 11)

        assertThat(classifyPolicyProjectionImpact(before, after))
            .isEqualTo(PolicyProjectionImpact.ACCESS_RECOVERY)
        assertThat(
            shouldInvalidateProtectedProjections(
                policyProjectionImpact = classifyPolicyProjectionImpact(before, after),
                authorizationChanged = false,
                hasGlobalInvalidationCommand = false,
                securityRejected = false,
                offlineDeadlineExceeded = false,
            )
        ).isFalse()
    }

    @Test
    fun `verified access policy change remains a hard fail closed boundary`() {
        val before = activePolicy(version = 10)
        val after = activePolicy(
            version = 11,
            maximumOfflineMillis = 0,
            offlineAccessMode = OfflineAccessMode.ONLINE_REQUIRED,
        )

        assertThat(classifyPolicyProjectionImpact(before, after))
            .isEqualTo(PolicyProjectionImpact.HARD_INVALIDATION)
    }

    @Test
    fun `verified policy identity change remains a hard fail closed boundary`() {
        val before = activePolicy(version = 10)
        val after = activePolicy(version = 11, policyId = "replacement-security-domain")

        assertThat(classifyPolicyProjectionImpact(before, after))
            .isEqualTo(PolicyProjectionImpact.HARD_INVALIDATION)
    }

    @Test
    fun `policy apply handshake requires one exact synchronized verified version`() {
        val active = activePolicy(version = 12)
        val application = policyApplication(version = 12)

        assertThat(
            hasExactPolicyApplicationAuthority(
                ManagementSyncReport(ManagementSyncStatus.SYNCHRONIZED, acceptedPolicyVersion = 12),
                active,
                application,
            )
        ).isTrue()
        assertThat(
            hasExactPolicyApplicationAuthority(
                ManagementSyncReport(ManagementSyncStatus.SYNCHRONIZED, acceptedPolicyVersion = 11),
                active,
                application,
            )
        ).isFalse()
        assertThat(
            hasExactPolicyApplicationAuthority(
                ManagementSyncReport(ManagementSyncStatus.SYNCHRONIZED, acceptedPolicyVersion = 12),
                activePolicy(version = 12, source = DataProtectionPolicySource.BUILT_IN_BASELINE),
                application,
            )
        ).isFalse()
        assertThat(
            hasExactPolicyApplicationAuthority(
                ManagementSyncReport(ManagementSyncStatus.SYNCHRONIZED, acceptedPolicyVersion = 12),
                active,
                application.copy(
                    state = ManagementPolicyApplicationState.APPLIED_CLEANUP_PENDING,
                    applyHandshakeAtEpochMillis = 1_000,
                ),
            )
        ).isTrue()
    }

    @Test
    fun `cleanup handshake reuses only the exact durable application plan`() {
        val operations = listOf(
            PolicyCleanupOperation(
                operationId = "r05-policy-12-authorization-cache",
                kind = "AUTHORIZATION_CACHE",
            ),
            PolicyCleanupOperation(
                operationId = "r05-policy-12-clipboard-clear",
                kind = "CLIPBOARD_CLEAR",
            ),
        )
        val application = policyApplication(version = 12).copy(cleanupOperations = operations)

        assertThat(cleanupPlanForExactVersionHandshake(application)).isEqualTo(operations)
    }

    @Test
    fun `persisted exact version handshake reuses its original cleanup plan`() {
        assertThat(shouldStagePolicyCleanup(policyApplication(version = 12))).isTrue()
        assertThat(
            shouldStagePolicyCleanup(
                policyApplication(version = 12).copy(applyHandshakeAtEpochMillis = 1_000)
            )
        ).isFalse()
    }

    @Test
    fun `pending authorization cleanup is retried after exact handshake restart`() {
        val operation = PolicyCleanupOperation(
            operationId = "r05-policy-12-authorization-cache",
            kind = "AUTHORIZATION_CACHE",
        )
        val application = policyApplication(version = 12).copy(
            cleanupOperations = listOf(operation),
            applyHandshakeAtEpochMillis = 1_000,
        )

        assertThat(pendingAuthorizationCacheCleanup(application)).isEqualTo(operation)
        assertThat(
            pendingAuthorizationCacheCleanup(
                application.copy(
                    cleanupOperations = listOf(operation.copy(state = PolicyCleanupOperationState.COMPLETE))
                )
            )
        ).isNull()
    }

    @Test
    fun `successful evidence remains cleanup pending until every durable operation is complete`() {
        val pending = PolicyCleanupOperation(
            operationId = "r05-policy-12-content-retention",
            kind = "CONTENT_RETENTION",
        )
        val complete = PolicyCleanupOperation(
            operationId = "r05-policy-12-clipboard-clear",
            kind = "CLIPBOARD_CLEAR",
            state = PolicyCleanupOperationState.COMPLETE,
        )
        val evidence = successfulPolicyApplicationEvidence(
            application = policyApplication(version = 12).copy(
                requiredCapabilities = listOf("CAPTURE_BLOCK"),
                cleanupOperations = listOf(complete, pending),
                applyHandshakeAtEpochMillis = 1_000,
            ),
            appliedAtEpochMillis = 2_000,
        )

        assertThat(evidence.result).isEqualTo(ManagementPolicyApplicationState.APPLIED_CLEANUP_PENDING)
        assertThat(evidence.enforcedCapabilities).containsExactly("CAPTURE_BLOCK")
        assertThat(evidence.refusedCapabilities).isEmpty()
    }

    @Test
    fun `successful evidence becomes applied only after durable cleanup is complete`() {
        val evidence = successfulPolicyApplicationEvidence(
            application = policyApplication(version = 12).copy(
                cleanupOperations = listOf(
                    PolicyCleanupOperation(
                        operationId = "r05-policy-12-clipboard-clear",
                        kind = "CLIPBOARD_CLEAR",
                        state = PolicyCleanupOperationState.COMPLETE,
                    )
                ),
                applyHandshakeAtEpochMillis = 1_000,
            ),
            appliedAtEpochMillis = 2_000,
        )

        assertThat(evidence.result).isEqualTo(ManagementPolicyApplicationState.APPLIED)
    }

    @Test
    fun `production receipt bridge sends exact durable successful evidence to management service`() = runTest {
        val service = mockk<ManagementControlService>()
        val evidence = slot<io.element.android.security.dataprotection.api.PolicyApplicationEvidence>()
        coEvery { service.preparePolicyApplicationReceipt(capture(evidence)) } returns true
        val application = policyApplication(version = 12).copy(applyHandshakeAtEpochMillis = 1_000)

        assertThat(
            prepareSuccessfulPolicyApplicationReceipt(
                managementControlService = service,
                application = application,
                appliedAtEpochMillis = 2_000,
            )
        ).isTrue()
        assertThat(evidence.captured.version).isEqualTo(12)
        assertThat(evidence.captured.result).isEqualTo(ManagementPolicyApplicationState.APPLIED)
        coVerify(exactly = 1) { service.preparePolicyApplicationReceipt(evidence.captured) }
    }

    @Test
    fun `management heartbeat runs while the process is visible`() {
        assertThat(
            shouldKeepManagementHeartbeatRunning(
                isProcessStarted = true,
                hasRingingCall = false,
                isInCall = false,
            )
        ).isTrue()
    }

    @Test
    fun `management heartbeat continues for a ringing call in background`() {
        assertThat(
            shouldKeepManagementHeartbeatRunning(
                isProcessStarted = false,
                hasRingingCall = true,
                isInCall = false,
            )
        ).isTrue()
    }

    @Test
    fun `management heartbeat continues for an active call behind lockscreen`() {
        assertThat(
            shouldKeepManagementHeartbeatRunning(
                isProcessStarted = false,
                hasRingingCall = false,
                isInCall = true,
            )
        ).isTrue()
    }

    @Test
    fun `management heartbeat stops only when app and calls are inactive`() {
        assertThat(
            shouldKeepManagementHeartbeatRunning(
                isProcessStarted = false,
                hasRingingCall = false,
                isInCall = false,
            )
        ).isFalse()
    }

    @Test
    fun `managed approval pending may finish startup without granting protected access`() {
        assertThat(
            shouldRejectLocalAccess(
                allowsLocalVaultAccess = false,
                managedApprovalPending = true,
            )
        ).isFalse()
    }

    @Test
    fun `ordinary restricted trust still fails startup closed`() {
        assertThat(
            shouldRejectLocalAccess(
                allowsLocalVaultAccess = false,
                managedApprovalPending = false,
            )
        ).isTrue()
    }

    private fun activePolicy(
        version: Long,
        retentionMillis: Long = DAY_MILLIS,
        source: DataProtectionPolicySource = DataProtectionPolicySource.VERIFIED_REMOTE,
        policyId: String = "test-policy",
        maximumOfflineMillis: Long = HOUR_MILLIS,
        offlineAccessMode: OfflineAccessMode = OfflineAccessMode.BOUNDED_OFFLINE,
        allowClipboard: Boolean = false,
    ) = ActiveDataProtectionPolicy(
        policy = DataProtectionPolicyV1(
            policyId = policyId,
            version = version,
            issuedAtEpochMillis = 1,
            effectiveAtEpochMillis = 1,
            expiresAtEpochMillis = null,
            contentRetentionMillis = retentionMillis,
            maximumOfflineMillis = maximumOfflineMillis,
            offlineAccessMode = offlineAccessMode,
            notificationExposure = NotificationExposure.CONTENT_ALLOWED,
            hardwareTrustRequirement = HardwareTrustRequirement.TEE_OR_STRONGBOX,
            allowContentExport = false,
            allowClipboard = allowClipboard,
            allowExternalShare = false,
            screenCapturePolicy = ScreenCapturePolicy.BLOCK,
        ),
        source = source,
    )

    private fun command(type: ManagementCommandType) = VerifiedManagementCommand(
        commandId = "command-1234567890",
        installationId = "installation-1234567890",
        type = type,
        accountIdHash = if (type == ManagementCommandType.CLEAR_ACCOUNT) "a".repeat(64) else null,
        contentId = if (type == ManagementCommandType.CLEAR_CONTENT) "content-1234567890" else null,
        version = 1,
        issuedAtEpochMillis = 1,
        notBeforeEpochMillis = 1,
        expiresAtEpochMillis = Long.MAX_VALUE,
    )

    private fun policyApplication(version: Long) = ManagementPolicyApplication(
        operationId = "operation-policy-$version",
        policyDomain = "GLOBAL_POLICY",
        version = version,
        digest = "a".repeat(64),
        effectiveAtEpochMillis = 1,
        requiredCapabilities = emptyList(),
        state = ManagementPolicyApplicationState.PENDING_APPLY,
    )

    private companion object {
        const val HOUR_MILLIS = 60L * 60 * 1_000
        const val DAY_MILLIS = 24 * HOUR_MILLIS
    }

    private class HttpStatusException : Exception()
}
