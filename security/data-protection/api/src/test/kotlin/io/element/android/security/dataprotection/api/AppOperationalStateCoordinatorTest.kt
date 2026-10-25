/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.dataprotection.api

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppOperationalStateCoordinatorTest {
    @Test fun `pending registration reaches limited startup while retaining its reason and denying offline content`() {
        val report = ManagementSyncReport(ManagementSyncStatus.SYNCHRONIZED, acceptedPolicyVersion = 7,
            deviceSecurityState = ManagedDeviceSecurityState.READY, errorCode = "REGISTRATION_PENDING")
        val readiness = report.toStartupSecurityReadiness(1_000, installationId = "original-installation", offlineEligible = true)
        assertThat(readiness.canStartLc01(1_001)).isTrue()
        assertThat(readiness.reasonCode).isEqualTo("REGISTRATION_PENDING")
        assertThat(readiness.offlineEligible).isFalse()
        assertThat(report.copy(acceptedPolicyVersion = null).toStartupSecurityReadiness(1_000,
            installationId = "original-installation").canStartLc01(1_001)).isFalse()
        assertThat(report.copy(status = ManagementSyncStatus.SECURITY_REJECTED,
            errorCode = "DEVICE_ADMINISTRATIVELY_LOCKED").toStartupSecurityReadiness(1_000,
            installationId = "original-installation").canStartLc01(1_001)).isFalse()
    }
    @Test fun `LC17 resource metadata cannot create preview or upload owner capability`() {
        val descriptor = LifecycleOperationOwnerDescriptor("original-live-stop-resource", FullAppLifecycleId.LC_17,
            null, LifecycleOperationOwnerPhase.AVAILABLE, 0, 1000)
        assertThat(descriptor.lifecycleId).isEqualTo(FullAppLifecycleId.LC_17)
        assertThat(runCatching { descriptor.copy(role = LifecycleOperationOwnerRole.UPLOAD) }.isFailure).isTrue()
        assertThat(runCatching { descriptor.copy(role = LifecycleOperationOwnerRole.PREVIEW, phase = LifecycleOperationOwnerPhase.OWNED) }.isFailure).isTrue()
        assertThat(runCatching { descriptor.copy(generation = 1) }.isFailure).isTrue()
    }

    @Test fun `LC17 closure protocol is restricted to account bound live stop`() {
        val original = LifecycleOperationRequest(lifecycleId = FullAppLifecycleId.LC_17, trigger = "live_location_stop",
            idempotencyKey = "original-live-stop-id", accountIdHash = "a".repeat(64), matrixDeviceIdHash = "b".repeat(64),
            roomIdHash = "c".repeat(64), deadlineEpochMillis = 1000, mediaCleanupProtocol = MEDIA_RESOURCE_CLOSURE_PROTOCOL)
        assertThat(original.mediaCleanupProtocol).isEqualTo(MEDIA_RESOURCE_CLOSURE_PROTOCOL)
        assertThat(runCatching { original.copy(trigger = "live_location_update") }.isFailure).isTrue()
        assertThat(runCatching { original.copy(trigger = "live_location_start") }.isFailure).isTrue()
        assertThat(runCatching { original.copy(accountIdHash = null, matrixDeviceIdHash = null) }.isFailure).isTrue()
    }

    @Test
    fun `explicit identity reset keeps LC08 recovery authority instead of accepting an unknown trigger`() {
        val request = lifecycleOperationRequest(FullAppLifecycleId.LC_08, "identity_reset_confirmed")
        assertThat(request.lifecycleId).isEqualTo(FullAppLifecycleId.LC_08)
        assertThat(request.trigger).isEqualTo("identity_reset_confirmed")
        assertThat(AppLifecycleEntrypoints.isRegistered(FullAppLifecycleId.LC_08, "identity_reset_unconfirmed")).isFalse()
    }

    @Test
    fun `LC16 local preview entrypoint is registered independently from upload`() {
        val request = lifecycleOperationRequest(FullAppLifecycleId.LC_16, "local_preview")
        assertThat(request.trigger).isEqualTo("local_preview")
        assertThat(AppLifecycleEntrypoints.isRegistered(FullAppLifecycleId.LC_16, "upload")).isTrue()
        assertThat(AppLifecycleEntrypoints.isRegistered(FullAppLifecycleId.LC_16, "unknown_preview")).isFalse()
    }

    @Test
    fun `only synchronized policy-backed readiness can start LC-01`() {
        val ready = ManagementSyncReport(
            status = ManagementSyncStatus.SYNCHRONIZED,
            acceptedPolicyVersion = 7,
            deviceSecurityState = ManagedDeviceSecurityState.READY,
        ).toStartupSecurityReadiness(
            installationId = "installation-1234567890",
            observedAtEpochMillis = 1_000,
        )
        val missingPolicy = ManagementSyncReport(
            status = ManagementSyncStatus.SYNCHRONIZED,
            deviceSecurityState = ManagedDeviceSecurityState.READY,
        ).toStartupSecurityReadiness(observedAtEpochMillis = 1_000)
        val retryable = ManagementSyncReport(ManagementSyncStatus.RETRY_REQUIRED)
            .toStartupSecurityReadiness(observedAtEpochMillis = 1_000)

        assertThat(ready.state).isEqualTo(StartupSecurityReadinessState.READY_FOR_LC01)
        assertThat(ready.canStartLc01(nowEpochMillis = 1_001)).isTrue()
        assertThat(ready.expiresAtEpochMillis).isEqualTo(11_000)
        assertThat(missingPolicy.state).isEqualTo(StartupSecurityReadinessState.RETRYABLE_FAILURE)
        assertThat(missingPolicy.canStartLc01(nowEpochMillis = 1_001)).isFalse()
        assertThat(retryable.canStartLc01(nowEpochMillis = 1_001)).isFalse()
    }

    @Test
    fun `missing installation binding cannot become readiness`() {
        val readiness = ManagementSyncReport(
            status = ManagementSyncStatus.SYNCHRONIZED,
            acceptedPolicyVersion = 7,
            deviceSecurityState = ManagedDeviceSecurityState.READY,
        ).toStartupSecurityReadiness(observedAtEpochMillis = 1_000)

        assertThat(readiness.state).isEqualTo(StartupSecurityReadinessState.RETRYABLE_FAILURE)
        assertThat(readiness.reasonCode).isEqualTo("INSTALLATION_IDENTITY_MISMATCH")
    }

    @Test
    fun `security rejection and disabled configuration never become readiness`() {
        val rejected = ManagementSyncReport(
            status = ManagementSyncStatus.SECURITY_REJECTED,
            errorCode = "CONTROL_SYNC_TRUST_DECISION_REJECTED",
        ).toStartupSecurityReadiness(observedAtEpochMillis = 1_000)
        val disabled = ManagementSyncReport(ManagementSyncStatus.DISABLED)
            .toStartupSecurityReadiness(observedAtEpochMillis = 1_000)

        assertThat(rejected.state).isEqualTo(StartupSecurityReadinessState.SECURITY_REJECTED)
        assertThat(rejected.reasonCode).isEqualTo("DEVICE_TRUST_NOT_READY")
        assertThat(disabled.state).isEqualTo(StartupSecurityReadinessState.DISABLED)
        assertThat(disabled.reasonCode).isEqualTo("STARTUP_CONFIGURATION_DISABLED")
    }

    @Test
    fun `projection guards map older and contradictory authority fail closed`() {
        val stale = ManagementSyncReport(
            status = ManagementSyncStatus.SECURITY_REJECTED,
            errorCode = "CONTROL_SYNC_OLDER_SNAPSHOT",
        ).toStartupSecurityReadiness(observedAtEpochMillis = 1_000)
        val contradictory = ManagementSyncReport(
            status = ManagementSyncStatus.SECURITY_REJECTED,
            errorCode = "CONTROL_SYNC_CONTRADICTORY_AUTHORITY",
        ).toStartupSecurityReadiness(observedAtEpochMillis = 1_000)

        assertThat(stale.state).isEqualTo(StartupSecurityReadinessState.UNKNOWN)
        assertThat(stale.reasonCode).isEqualTo("STARTUP_SNAPSHOT_STALE")
        assertThat(stale.canStartLc01()).isFalse()
        assertThat(contradictory.state).isEqualTo(StartupSecurityReadinessState.SECURITY_REJECTED)
        assertThat(contradictory.reasonCode).isEqualTo("STARTUP_AUTHORITY_CONTRADICTORY")
        assertThat(contradictory.canStartLc01()).isFalse()
    }

    @Test
    fun `explicit installation identity mismatch cannot become readiness`() {
        val mismatch = ManagementSyncReport(
            status = ManagementSyncStatus.SECURITY_REJECTED,
            errorCode = "INSTALLATION_IDENTITY_MISMATCH",
        ).toStartupSecurityReadiness(
            installationId = "installation-1234567890",
            observedAtEpochMillis = 1_000,
        )

        assertThat(mismatch.state).isEqualTo(StartupSecurityReadinessState.SECURITY_REJECTED)
        assertThat(mismatch.reasonCode).isEqualTo("INSTALLATION_IDENTITY_MISMATCH")
        assertThat(mismatch.canStartLc01()).isFalse()
    }

    @Test
    fun `command and transport failures retain their specific startup reason`() {
        val reasons = listOf(
            "COMMAND_EXECUTION_PENDING" to "REMOTE_COMMAND_EXECUTION_PENDING",
            "COMMAND_REPORT_PENDING" to "REMOTE_COMMAND_REPORT_PENDING",
            "COMMAND_EXECUTION_FAILED" to "REMOTE_COMMAND_EXECUTION_FAILED",
            "CONTROL_DNS_UNAVAILABLE" to "CONTROL_DNS_UNAVAILABLE",
            "CONTROL_TLS_REJECTED" to "CONTROL_TLS_REJECTED",
        )

        reasons.forEach { (source, projected) ->
            val readiness = ManagementSyncReport(
                status = ManagementSyncStatus.RETRY_REQUIRED,
                errorCode = source,
            ).toStartupSecurityReadiness(observedAtEpochMillis = 1_000)
            assertThat(readiness.reasonCode).isEqualTo(projected)
            assertThat(readiness.canStartLc01()).isFalse()
        }
    }

    @Test
    fun `expired readiness is downgraded before consumption`() {
        val readiness = StartupSecurityReadiness(
            state = StartupSecurityReadinessState.READY_FOR_LC01,
            policyVersion = 7,
            observedAtEpochMillis = 1_000,
            expiresAtEpochMillis = 2_000,
        )

        val effective = readiness.effectiveFor(nowEpochMillis = 2_000)

        assertThat(effective.state).isEqualTo(StartupSecurityReadinessState.UNKNOWN)
        assertThat(effective.reasonCode).isEqualTo("STARTUP_READINESS_EXPIRED")
        assertThat(effective.canStartLc01(nowEpochMillis = 2_000)).isFalse()
    }

    @Test
    fun `readiness expiry closes the LC-01 gate`() {
        val readiness = StartupSecurityReadiness(
            state = StartupSecurityReadinessState.READY_FOR_LC01,
            policyVersion = 7,
            observedAtEpochMillis = 1_000,
            expiresAtEpochMillis = 2_000,
        )

        assertThat(readiness.canStartLc01(nowEpochMillis = 1_999)).isTrue()
        assertThat(readiness.canStartLc01(nowEpochMillis = 2_000)).isFalse()
    }

    @Test
    fun `all 24 lifecycles have an executable Android entrypoint and UI projection`() {
        assertThat(AppLifecycleEntrypoints.lifecycleIds).containsExactlyElementsIn(FullAppLifecycleId.entries)

        val allStates = AppLifecycleUiProjector.projectAll(snapshot = null, operations = emptyMap())
        assertThat(allStates.keys).containsExactlyElementsIn(FullAppLifecycleId.entries)

        FullAppLifecycleId.entries.forEach { lifecycleId ->
            val state = checkNotNull(allStates[lifecycleId])
            assertThat(state.lifecycleId).isEqualTo(lifecycleId)
            assertThat(state.status).isEqualTo(AppOperationalStatus.UNKNOWN)
            assertThat(state.primaryAction).isEqualTo(LifecycleUiAction.NONE)
        }
    }

    @Test
    fun `a trigger cannot be attached to the wrong lifecycle`() {
        val failure = runCatching {
            LifecycleOperationRequest(
                lifecycleId = FullAppLifecycleId.LC_16,
                trigger = "permanent_delete",
                idempotencyKey = "lifecycle-request-1234",
                deadlineEpochMillis = 500,
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(failure).hasMessageThat().contains("not registered")
    }
}
