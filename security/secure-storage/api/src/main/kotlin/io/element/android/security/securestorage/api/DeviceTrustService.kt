/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.securestorage.api

import kotlinx.coroutines.flow.Flow

enum class HardwareSecurityLevel {
    STRONGBOX,
    TRUSTED_EXECUTION_ENVIRONMENT,
    SOFTWARE,
    UNKNOWN,
}

enum class DeviceTrustState {
    NOT_ENROLLED,
    PENDING,
    TRUSTED,
    INTEGRITY_WARNING,
    STALE,
    REVOKED,
    LOST,
    /** Server-signed, online-only compatibility admission. This is not hardware trust. */
    MANAGED,
}

enum class DeviceRiskSignal(val blocksLocalVaultAccess: Boolean) {
    DEBUGGABLE_APP(false),
    TEST_KEYS_BUILD(false),
    DEBUGGER_ATTACHED(true),
    ROOT_ARTIFACT_DETECTED(true),
    APP_SIGNING_IDENTITY_UNAVAILABLE(true),
    APP_SIGNING_IDENTITY_CHANGED(true),
    ATTESTATION_CERTIFICATE_CHAIN_UNAVAILABLE(false),
    HARDWARE_ATTESTATION_UNAVAILABLE(false),
}

data class ManagedDeviceLicense(
    val licenseId: String,
    val installationId: String,
    val devicePublicKeySha256: String,
    val contentShareKeyId: String,
    val appPackageName: String,
    val appVersionCode: Long,
    val revision: Long,
    val allowedScopes: Set<String>,
    val offlineAllowed: Boolean,
    val issuedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val accountIdHashes: Set<String> = emptySet(),
) {
    fun isActive(nowEpochMillis: Long): Boolean =
        licenseId.isNotBlank() &&
            installationId.isNotBlank() &&
            devicePublicKeySha256.length == 64 &&
            contentShareKeyId.isNotBlank() &&
            revision > 0 &&
            allowedScopes.isNotEmpty() &&
            !offlineAllowed &&
            issuedAtEpochMillis > 0 &&
            expiresAtEpochMillis > nowEpochMillis

    fun allowsAccount(accountIdHash: String): Boolean = accountIdHashes.isEmpty() || accountIdHash in accountIdHashes
}

data class DeviceTrustSnapshot(
    val installationId: String,
    val hardwareSecurityLevel: HardwareSecurityLevel,
    val trustState: DeviceTrustState,
    val attestationVersion: Long,
    val lastVerifiedAtEpochMillis: Long?,
    val lastEvidenceAtEpochMillis: Long? = null,
    val attestationPublicKeySha256: String? = null,
    val riskSignals: Set<DeviceRiskSignal> = emptySet(),
    val managedLicense: ManagedDeviceLicense? = null,
) {
    /** A signed server decision has admitted this installation into the administrator-review queue. */
    val managedApprovalPending: Boolean = trustState == DeviceTrustState.MANAGED && managedLicense == null

    val allowsLocalVaultAccess: Boolean = trustState != DeviceTrustState.INTEGRITY_WARNING &&
        trustState != DeviceTrustState.REVOKED &&
        trustState != DeviceTrustState.LOST &&
        trustState != DeviceTrustState.STALE &&
        (trustState != DeviceTrustState.MANAGED || managedLicense?.isActive(System.currentTimeMillis()) == true)

    val remotelyTrusted: Boolean = trustState == DeviceTrustState.TRUSTED
}

/**
 * Challenge-bound evidence for a device registry. The server must validate the certificate chain, attestation
 * extension, app signing identity, verified boot state and [signedStatement] before changing remote trust state.
 */
data class DeviceAttestationEvidence(
    val installationId: String,
    val attestationVersion: Long,
    val challengeSha256: ByteArray,
    val statement: ByteArray,
    val signedStatement: ByteArray,
    val attestationPublicKeySha256: ByteArray,
    val certificateChainDer: List<ByteArray>,
    val hardwareSecurityLevel: HardwareSecurityLevel,
    val appPackageName: String,
    val appVersionCode: Long,
    val appSigningCertificateSha256: List<ByteArray>,
    val riskSignals: Set<DeviceRiskSignal>,
)

data class DeviceControlRequestSignature(
    val installationId: String,
    val attestationVersion: Long,
    val signature: ByteArray,
)

/** Fixed, non-sensitive stages of the original trust operation; no authority or record data. */
enum class DeviceTrustDiagnosticStage {
    MUTEX_WAIT,
    ROOT_READY,
    INSTALLATION_LOAD,
    PLATFORM_ASSESS,
    RECORD_READ,
    RECORD_VALIDATE,
    DAK_LOAD,
    DAK_RETIRE,
    RECORD_WRITE_UNCHANGED,
    RECORD_WRITE_CHANGED,
    HARDWARE_SIGN,
    /** Actual acquired-to-before-unlock interval; includes body stages, excludes observer callbacks. */
    MUTEX_HOLD,
}

enum class DeviceTrustDiagnosticOutcome { OK, ERROR, CANCELLED }

/**
 * Observes original monotonic boundaries only. Implementations must ignore callback failures.
 * The diagnostic overloads deliver callbacks in stage-completion order after releasing their own trust mutex.
 * A cancelled waiter that never acquired that mutex delivers only its wait result and never reports a hold.
 */
fun interface DeviceTrustDiagnosticSink {
    fun finished(stage: DeviceTrustDiagnosticStage, startNanos: Long, endNanos: Long, outcome: DeviceTrustDiagnosticOutcome)
}

interface DeviceTrustService {
    fun trust(): Flow<DeviceTrustSnapshot>

    suspend fun initialize(): DeviceTrustSnapshot

    suspend fun current(): DeviceTrustSnapshot

    /** Legacy implementations keep their original behavior and emit no invented stages. */
    suspend fun current(diagnostics: DeviceTrustDiagnosticSink?): DeviceTrustSnapshot = current()

    suspend fun enroll(attestationChallenge: ByteArray): DeviceAttestationEvidence

    suspend fun refresh(attestationChallenge: ByteArray): DeviceAttestationEvidence

    /** Signs a purpose-bound control-plane request with the installation DAK. */
    suspend fun signControlStatement(statement: ByteArray): DeviceControlRequestSignature

    suspend fun signControlStatement(statement: ByteArray, diagnostics: DeviceTrustDiagnosticSink?): DeviceControlRequestSignature =
        signControlStatement(statement)

    /** Applies a server-signed approval for exactly the current attestation version. */
    suspend fun applyRemoteTrust(attestationVersion: Long, verifiedAtEpochMillis: Long): DeviceTrustSnapshot

    /** Records a signed compatibility-review decision without granting protected-content access. */
    suspend fun applyRemoteManagedPending(
        attestationVersion: Long,
        verifiedAtEpochMillis: Long,
    ): DeviceTrustSnapshot

    /** Applies a server-signed, device/key/version-bound online compatibility license. */
    suspend fun applyRemoteManagedTrust(
        attestationVersion: Long,
        license: ManagedDeviceLicense,
        verifiedAtEpochMillis: Long,
    ): DeviceTrustSnapshot

    /** Clears process-only proof material. It does not manufacture or revoke a server trust decision. */
    suspend fun markLocked()

    /** Applies only a server-verified restrictive decision. It can never promote a device to TRUSTED. */
    suspend fun applyRemoteRestriction(state: DeviceTrustState, verifiedAtEpochMillis: Long): DeviceTrustSnapshot

    /** Deletes the installation DAK and leaves an irreversible local LOST tombstone. */
    suspend fun destroy(): Boolean
}
