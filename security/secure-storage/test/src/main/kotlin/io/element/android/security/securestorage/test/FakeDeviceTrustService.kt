/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.securestorage.test

import io.element.android.security.securestorage.api.DeviceAttestationEvidence
import io.element.android.security.securestorage.api.DeviceControlRequestSignature
import io.element.android.security.securestorage.api.DeviceRiskSignal
import io.element.android.security.securestorage.api.DeviceTrustService
import io.element.android.security.securestorage.api.DeviceTrustSnapshot
import io.element.android.security.securestorage.api.DeviceTrustState
import io.element.android.security.securestorage.api.HardwareSecurityLevel
import io.element.android.security.securestorage.api.ManagedDeviceLicense
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeDeviceTrustService(
    initialSnapshot: DeviceTrustSnapshot = DeviceTrustSnapshot(
        installationId = "fake-installation",
        hardwareSecurityLevel = HardwareSecurityLevel.UNKNOWN,
        trustState = DeviceTrustState.NOT_ENROLLED,
        attestationVersion = 0,
        lastVerifiedAtEpochMillis = null,
    ),
) : DeviceTrustService {
    private val snapshot = MutableStateFlow(initialSnapshot)

    override fun trust(): Flow<DeviceTrustSnapshot> = snapshot.asStateFlow()

    override suspend fun initialize(): DeviceTrustSnapshot = snapshot.value

    override suspend fun current(): DeviceTrustSnapshot = snapshot.value

    override suspend fun enroll(attestationChallenge: ByteArray): DeviceAttestationEvidence {
        snapshot.value = snapshot.value.copy(
            trustState = DeviceTrustState.TRUSTED,
            attestationVersion = snapshot.value.attestationVersion + 1,
        )
        return evidence(attestationChallenge)
    }

    override suspend fun refresh(attestationChallenge: ByteArray): DeviceAttestationEvidence = enroll(attestationChallenge)

    override suspend fun signControlStatement(statement: ByteArray): DeviceControlRequestSignature = DeviceControlRequestSignature(
        installationId = snapshot.value.installationId,
        attestationVersion = snapshot.value.attestationVersion,
        signature = statement.copyOf(),
    )

    override suspend fun applyRemoteTrust(attestationVersion: Long, verifiedAtEpochMillis: Long): DeviceTrustSnapshot {
        snapshot.value = snapshot.value.copy(
            trustState = DeviceTrustState.TRUSTED,
            attestationVersion = attestationVersion,
            lastVerifiedAtEpochMillis = verifiedAtEpochMillis,
        )
        return snapshot.value
    }

    override suspend fun applyRemoteManagedPending(
        attestationVersion: Long,
        verifiedAtEpochMillis: Long,
    ): DeviceTrustSnapshot {
        snapshot.value = snapshot.value.copy(
            trustState = DeviceTrustState.MANAGED,
            attestationVersion = attestationVersion,
            lastVerifiedAtEpochMillis = verifiedAtEpochMillis,
            managedLicense = null,
        )
        return snapshot.value
    }

    override suspend fun applyRemoteManagedTrust(
        attestationVersion: Long,
        license: ManagedDeviceLicense,
        verifiedAtEpochMillis: Long,
    ): DeviceTrustSnapshot {
        snapshot.value = snapshot.value.copy(
            trustState = DeviceTrustState.MANAGED,
            attestationVersion = attestationVersion,
            lastVerifiedAtEpochMillis = verifiedAtEpochMillis,
            managedLicense = license,
        )
        return snapshot.value
    }

    override suspend fun markLocked() = Unit

    override suspend fun applyRemoteRestriction(state: DeviceTrustState, verifiedAtEpochMillis: Long): DeviceTrustSnapshot {
        require(state == DeviceTrustState.STALE || state == DeviceTrustState.REVOKED)
        snapshot.value = snapshot.value.copy(trustState = state, lastVerifiedAtEpochMillis = verifiedAtEpochMillis)
        return snapshot.value
    }

    override suspend fun destroy(): Boolean {
        snapshot.value = snapshot.value.copy(trustState = DeviceTrustState.LOST)
        return true
    }

    fun set(snapshot: DeviceTrustSnapshot) {
        this.snapshot.value = snapshot
    }

    private fun evidence(challenge: ByteArray): DeviceAttestationEvidence = DeviceAttestationEvidence(
        installationId = snapshot.value.installationId,
        attestationVersion = snapshot.value.attestationVersion,
        challengeSha256 = challenge.copyOf(32),
        statement = challenge.copyOf(),
        signedStatement = ByteArray(64),
        attestationPublicKeySha256 = ByteArray(32),
        certificateChainDer = listOf(ByteArray(1)),
        hardwareSecurityLevel = snapshot.value.hardwareSecurityLevel,
        appPackageName = "io.element.android.x",
        appVersionCode = 1,
        appSigningCertificateSha256 = listOf(ByteArray(32)),
        riskSignals = emptySet<DeviceRiskSignal>(),
    )
}
