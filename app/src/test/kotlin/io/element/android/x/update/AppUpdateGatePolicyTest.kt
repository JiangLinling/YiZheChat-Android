/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.update

import com.google.common.truth.Truth.assertThat
import io.element.android.security.dataprotection.api.ManagedAppRelease
import io.element.android.security.dataprotection.api.ManagementSyncReport
import io.element.android.security.dataprotection.api.ManagementSyncStatus
import io.element.android.security.dataprotection.api.StartupSecurityReadiness
import io.element.android.security.dataprotection.api.StartupSecurityReadinessState
import org.junit.Test

class AppUpdateGatePolicyTest {
    @Test
    fun `a rejected admission permits only a verified mandatory update and never account content`() {
        assertThat(releaseAdmissionDecision(false, Result.success(release(mandatory = true))))
            .isEqualTo(ReleaseAdmissionDecision.UPDATE_ONLY)
        listOf(null, Result.success(null), Result.success(release(mandatory = false)), Result.failure<ManagedAppRelease?>(SecurityException()))
            .forEach { result ->
                assertThat(releaseAdmissionDecision(false, result)).isEqualTo(ReleaseAdmissionDecision.KEEP_RESTRICTED)
            }
        assertThat(releaseAdmissionDecision(true, Result.success(null))).isEqualTo(ReleaseAdmissionDecision.CONTENT_ALLOWED)
        assertThat(releaseAdmissionDecision(true, Result.failure(SecurityException()))).isEqualTo(ReleaseAdmissionDecision.KEEP_RESTRICTED)
    }

    @Test
    fun `release retries do not trigger pending device evidence re-enrollment`() {
        assertThat(shouldRefreshPendingEvidence("COMPATIBILITY_EVIDENCE_REJECTED")).isTrue()
        assertThat(shouldRefreshPendingEvidence("DEVICE_TRUST_NOT_READY")).isTrue()
        listOf("RELEASE_MANIFEST_UNAVAILABLE", "CONTROL_SERVER_UNAVAILABLE", "CONTROL_REQUEST_TIMEOUT", "DEVICE_REVOKED")
            .forEach { assertThat(shouldRefreshPendingEvidence(it)).isFalse() }
    }

    @Test
    fun `binding conflict exposes explicit handover while other security failures remain blocked`() {
        val conflict = startupGateAccessibilityContract("COMPATIBILITY_BINDING_CONFLICT")
        assertThat(conflict.primaryAction).isEqualTo(StartupGatePrimaryAction.TRANSFER_MANAGED_AUTHORIZATION)
        assertThat(conflict.userState).isEqualTo(StartupGateUserState.BLOCKED)
        assertThat(shouldAutomaticallyRetryStartup(conflict.reasonCode)).isFalse()
        listOf("COMPATIBILITY_EVIDENCE_REJECTED", "DEVICE_TRUST_NOT_READY", "CONTROL_TLS_REJECTED").forEach {
            assertThat(startupGateAccessibilityContract(it).primaryAction).isEqualTo(StartupGatePrimaryAction.RETRY_SECURITY_CHECK)
        }
    }

    @Test
    fun `retained startup preparation preserves mandatory release and production manifest gates`() {
        assertThat(canPrepareRetainedAppContent(true, false, null)).isTrue()
        assertThat(canPrepareRetainedAppContent(true, true, release(mandatory = true))).isFalse()
        assertThat(canPrepareRetainedAppContent(false, false, null)).isFalse()
        assertThat(canPrepareRetainedAppContent(false, false, release(mandatory = false))).isFalse()
        assertThat(canPrepareRetainedAppContent(false, true, release(mandatory = false))).isTrue()
        assertThat(canPrepareRetainedAppContent(false, true, release(mandatory = true))).isFalse()
    }

    @Test
    fun `known network loss cannot reopen protected content through a fresh online cache`() {
        assertThat(restrictEntryWithoutOfflineGrant(false, true, false)).isTrue()
        assertThat(restrictEntryWithoutOfflineGrant(true, false, false)).isTrue()
        assertThat(restrictEntryWithoutOfflineGrant(true, true, true)).isFalse()
        assertThat(restrictEntryWithoutOfflineGrant(false, false, false)).isFalse()
    }

    @Test
    fun `temporary failures recover automatically but signed security rejection does not`() {
        listOf("CONTROL_SERVER_UNAVAILABLE", "CONTROL_RATE_LIMITED", "OFFLINE_ENTRY_EXPIRED", "STARTUP_READINESS_TIMEOUT")
            .forEach { assertThat(shouldAutomaticallyRetryStartup(it)).isTrue() }
        listOf("CONTROL_SIGNATURE_INVALID", "DEVICE_REVOKED", "SECURITY_POLICY_REJECTED")
            .forEach { assertThat(shouldAutomaticallyRetryStartup(it)).isFalse() }
        assertThat((0..5).map(::startupRetryDelayMillis)).containsExactly(1000L, 2000L, 5000L, 10000L, 30000L, 30000L).inOrder()
    }

    @Test
    fun `debug bypasses only the release check after fresh startup readiness`() {
        val ready = StartupSecurityReadiness(
            state = StartupSecurityReadinessState.READY_FOR_LC01,
            observedAtEpochMillis = 1_000,
            expiresAtEpochMillis = 2_000,
        )
        val notReady = StartupSecurityReadiness(
            state = StartupSecurityReadinessState.RETRYABLE_FAILURE,
            observedAtEpochMillis = 1_000,
        )

        assertThat(shouldBypassReleaseVersionCheck(isDebugBuild = true, ready, nowEpochMillis = 1_500)).isTrue()
        assertThat(shouldBypassReleaseVersionCheck(isDebugBuild = false, ready, nowEpochMillis = 1_500)).isFalse()
        assertThat(shouldBypassReleaseVersionCheck(isDebugBuild = true, notReady, nowEpochMillis = 1_500)).isFalse()
        assertThat(shouldBypassReleaseVersionCheck(isDebugBuild = true, ready, nowEpochMillis = 2_500)).isFalse()
    }

    @Test
    fun `startup reason matrix provides safe semantics for every changed state`() {
        val contracts = STARTUP_GATE_REASON_CODES.map(::startupGateAccessibilityContract)

        assertThat(contracts.map { it.reasonCode }).containsExactlyElementsIn(STARTUP_GATE_REASON_CODES)
        contracts.forEach { contract ->
            assertThat(contract.focusOrder).containsExactly("status", "primary_action").inOrder()
            assertThat(contract.errorNotColorOnly).isTrue()
            if (contract.userState != StartupGateUserState.CHECKING) {
                assertThat(contract.primaryAction).isNotEqualTo(StartupGatePrimaryAction.NONE)
            }
        }
        assertThat(startupGateAccessibilityContract("raw exception: secret").reasonCode)
            .isEqualTo("STARTUP_UNKNOWN")
        assertThat(startupGateAccessibilityContract("STARTUP_READINESS_TIMEOUT").retrySemantics)
            .isEqualTo(StartupGateRetrySemantics.RESUME_SAME_READINESS_OPERATION)
        assertThat(startupGateAccessibilityContract("STARTUP_AUTHORITY_CONTRADICTORY").userState)
            .isEqualTo(StartupGateUserState.DIAGNOSTIC)
        assertThat(startupGateAccessibilityContract("MANDATORY_UPDATE_REQUIRED").primaryAction)
            .isEqualTo(StartupGatePrimaryAction.DOWNLOAD_UPDATE)
    }

    @Test
    fun `each startup failure has distinct user copy`() {
        val nonFailureStates = setOf(
            "STARTUP_READINESS_BOOTSTRAPPING",
            "MANDATORY_UPDATE_REQUIRED",
            "UPDATE_DOWNLOAD_FAILED",
            "INSTALLER_RETURN",
        )
        val failureCodes = STARTUP_GATE_REASON_CODES - nonFailureStates
        val copies = failureCodes.associateWith(::startupGateCopy)

        assertThat(copies.values.map { it.titleRes to it.descriptionRes }.toSet()).hasSize(failureCodes.size)
    }

    @Test
    fun `cached release does not bypass startup readiness`() {
        val retryable = StartupSecurityReadiness(
            state = StartupSecurityReadinessState.RETRYABLE_FAILURE,
            policyVersion = 7,
            observedAtEpochMillis = 1_000,
        )
        assertThat(canUseBoundedOfflineStartup(retryable, offlineDeadlineExceeded = false)).isFalse()
        assertThat(
            canUseBoundedOfflineStartup(retryable.copy(offlineEligible = true), offlineDeadlineExceeded = false)
        ).isTrue()
    }

    @Test
    fun `verified cached release opens the app unless it requires an update`() {
        val optionalRelease = release(mandatory = false)
        val mandatoryRelease = release(mandatory = true)

        assertThat(canOpenAppShellFromCachedRelease(hasValidCachedManifest = true, cachedRelease = null)).isTrue()
        assertThat(canOpenAppShellFromCachedRelease(hasValidCachedManifest = true, cachedRelease = optionalRelease)).isTrue()
        assertThat(canOpenAppShellFromCachedRelease(hasValidCachedManifest = true, cachedRelease = mandatoryRelease)).isFalse()
        assertThat(canOpenAppShellFromCachedRelease(hasValidCachedManifest = false, cachedRelease = optionalRelease)).isFalse()
    }

    @Test
    fun `offline startup requires a verified cached policy inside its deadline`() {
        val retryWithPolicy = ManagementSyncReport(
            status = ManagementSyncStatus.RETRY_REQUIRED,
            acceptedPolicyVersion = 7,
        )

        assertThat(canUseBoundedOfflineStartup(retryWithPolicy, offlineDeadlineExceeded = false)).isTrue()
        assertThat(canUseBoundedOfflineStartup(retryWithPolicy, offlineDeadlineExceeded = true)).isFalse()
    }

    @Test
    fun `offline startup fails closed without a signed policy or after security rejection`() {
        val missingPolicy = ManagementSyncReport(status = ManagementSyncStatus.RETRY_REQUIRED)
        val rejected = ManagementSyncReport(
            status = ManagementSyncStatus.SECURITY_REJECTED,
            acceptedPolicyVersion = 7,
        )

        assertThat(canUseBoundedOfflineStartup(missingPolicy, offlineDeadlineExceeded = false)).isFalse()
        assertThat(canUseBoundedOfflineStartup(rejected, offlineDeadlineExceeded = false)).isFalse()
    }

    private fun release(mandatory: Boolean) = ManagedAppRelease(
        versionName = "26.06.8-internal",
        versionCode = 202606082,
        releaseNotes = "Security compatibility update",
        packageName = "com.yizhe.chat.internal",
        apkFallbackUrl = "https://security.yizhesy.cn/v1/app/releases/files/test.apk",
        mandatory = mandatory,
        apkUrl = "https://example.invalid/app.apk",
        apkSha256 = "00".repeat(32),
        buildId = "test-build-202606082",
        signingCertificateSha256 = "11".repeat(32),
    )
}
