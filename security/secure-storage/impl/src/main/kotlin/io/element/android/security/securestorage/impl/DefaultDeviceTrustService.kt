/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.securestorage.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.security.securestorage.api.DeviceAttestationEvidence
import io.element.android.security.securestorage.api.DeviceControlRequestSignature
import io.element.android.security.securestorage.api.DeviceRiskSignal
import io.element.android.security.securestorage.api.DeviceRootKeyDescriptor
import io.element.android.security.securestorage.api.DeviceRootKeyService
import io.element.android.security.securestorage.api.DeviceTrustDiagnosticOutcome
import io.element.android.security.securestorage.api.DeviceTrustDiagnosticSink
import io.element.android.security.securestorage.api.DeviceTrustDiagnosticStage
import io.element.android.security.securestorage.api.DeviceTrustService
import io.element.android.security.securestorage.api.DeviceTrustSnapshot
import io.element.android.security.securestorage.api.DeviceTrustState
import io.element.android.security.securestorage.api.HardwareSecurityLevel
import io.element.android.security.securestorage.api.ManagedDeviceLicense
import io.element.android.security.securestorage.api.SecureObjectAuthenticationException
import io.element.android.security.securestorage.api.SecureStorageKeyUnavailableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DefaultDeviceTrustService(
    private val deviceRootKeyService: DeviceRootKeyService,
    private val installationIdentityStore: InstallationIdentityStore,
    private val recordStore: DeviceTrustRecordStore,
    private val attestationBackend: DeviceAttestationBackend,
    private val platformSignalSource: DevicePlatformSignalSource,
) : DeviceTrustService {
    private val mutex = Mutex()
    private val snapshot = MutableStateFlow(notEnrolledSnapshot())
    private var latestEvidence: DeviceAttestationEvidence? = null

    override fun trust(): Flow<DeviceTrustSnapshot> = snapshot.asStateFlow()

    override suspend fun initialize(): DeviceTrustSnapshot = withContext(Dispatchers.IO) {
        mutex.withLock { currentLocked() }
    }

    override suspend fun current(): DeviceTrustSnapshot = current(null)

    override suspend fun current(diagnostics: DeviceTrustDiagnosticSink?): DeviceTrustSnapshot = withContext(Dispatchers.IO) {
        withTrustLock(diagnostics) { buffered -> currentLocked(buffered) }
    }

    override suspend fun enroll(attestationChallenge: ByteArray): DeviceAttestationEvidence = generateEvidence(attestationChallenge)

    override suspend fun refresh(attestationChallenge: ByteArray): DeviceAttestationEvidence = generateEvidence(attestationChallenge)

    override suspend fun signControlStatement(statement: ByteArray): DeviceControlRequestSignature = signControlStatement(statement, null)

    override suspend fun signControlStatement(
        statement: ByteArray,
        diagnostics: DeviceTrustDiagnosticSink?,
    ): DeviceControlRequestSignature = withContext(Dispatchers.IO) {
        require(statement.isNotEmpty() && statement.size <= MAX_CONTROL_STATEMENT_LENGTH_BYTES)
        withTrustLock(diagnostics) { buffered ->
            val trust = currentLocked(buffered)
            if (trust.trustState == DeviceTrustState.LOST) {
                throw SecureStorageKeyUnavailableException()
            }
            val identity = diagnosticStage(buffered, DeviceTrustDiagnosticStage.INSTALLATION_LOAD) { installationIdentityStore.loadOrCreate() }
            diagnosticStage(buffered, DeviceTrustDiagnosticStage.DAK_LOAD) {
                checkNotNull(attestationBackend.load(aliasFor(identity))) { "Device attestation key is unavailable." }
            }
            DeviceControlRequestSignature(
                installationId = identity.value,
                attestationVersion = trust.attestationVersion,
                signature = diagnosticStage(buffered, DeviceTrustDiagnosticStage.HARDWARE_SIGN) {
                    attestationBackend.sign(aliasFor(identity), statement)
                },
            )
        }
    }

    override suspend fun applyRemoteTrust(
        attestationVersion: Long,
        verifiedAtEpochMillis: Long,
    ): DeviceTrustSnapshot = withContext(Dispatchers.IO) {
        require(attestationVersion > 0)
        require(verifiedAtEpochMillis > 0)
        mutex.withLock {
            val identity = installationIdentityStore.loadOrCreate()
            val existing = recordStore.read() ?: throw SecureStorageKeyUnavailableException()
            verifyInstallation(existing, identity)
            if (existing.trustState == DeviceTrustState.LOST || existing.trustState == DeviceTrustState.REVOKED) {
                throw SecureStorageKeyUnavailableException()
            }
            if (existing.attestationVersion != attestationVersion) throw SecureObjectAuthenticationException()
            if (existing.riskSignals.any(DeviceRiskSignal::blocksLocalVaultAccess)) throw SecureObjectAuthenticationException()
            if (DeviceRiskSignal.ATTESTATION_CERTIFICATE_CHAIN_UNAVAILABLE in existing.riskSignals ||
                DeviceRiskSignal.HARDWARE_ATTESTATION_UNAVAILABLE in existing.riskSignals
            ) {
                throw SecureObjectAuthenticationException()
            }
            val descriptor = attestationBackend.load(aliasFor(identity)) ?: throw SecureStorageKeyUnavailableException()
            if (!MessageDigest.isEqual(existing.attestationPublicKeySha256, descriptor.publicKeySha256)) {
                throw SecureObjectAuthenticationException()
            }
            if (descriptor.hardwareSecurityLevel != HardwareSecurityLevel.STRONGBOX &&
                descriptor.hardwareSecurityLevel != HardwareSecurityLevel.TRUSTED_EXECUTION_ENVIRONMENT
            ) {
                throw SecureObjectAuthenticationException()
            }
            val trusted = existing.copy(
                trustState = DeviceTrustState.TRUSTED,
                lastVerifiedAtEpochMillis = verifiedAtEpochMillis,
                managedLicense = null,
            )
            recordStore.write(trusted)
            latestEvidence = null
            trusted.toSnapshot(identity).also { snapshot.value = it }
        }
    }

    override suspend fun applyRemoteManagedPending(
        attestationVersion: Long,
        verifiedAtEpochMillis: Long,
    ): DeviceTrustSnapshot = withContext(Dispatchers.IO) {
        require(attestationVersion > 0)
        require(verifiedAtEpochMillis > 0)
        mutex.withLock {
            val identity = installationIdentityStore.loadOrCreate()
            val existing = recordStore.read() ?: throw SecureStorageKeyUnavailableException()
            verifyInstallation(existing, identity)
            if (existing.trustState == DeviceTrustState.LOST || existing.trustState == DeviceTrustState.REVOKED) {
                throw SecureStorageKeyUnavailableException()
            }
            if (existing.attestationVersion != attestationVersion ||
                existing.riskSignals.any(DeviceRiskSignal::blocksLocalVaultAccess)
            ) {
                throw SecureObjectAuthenticationException()
            }
            // The server verifies the attestation chain independently and can require managed review even when
            // an Android compatibility container reports a TEE key without a local risk signal. Accepting this
            // signed pending decision grants no vault or protected-content access; a separately signed, online,
            // key/account/version-bound managed license is still required before either becomes available.
            val descriptor = attestationBackend.load(aliasFor(identity)) ?: throw SecureStorageKeyUnavailableException()
            if (!MessageDigest.isEqual(existing.attestationPublicKeySha256, descriptor.publicKeySha256)) {
                throw SecureObjectAuthenticationException()
            }
            val pending = existing.copy(
                trustState = DeviceTrustState.MANAGED,
                lastVerifiedAtEpochMillis = verifiedAtEpochMillis,
                managedLicense = null,
            )
            recordStore.write(pending)
            latestEvidence = null
            pending.toSnapshot(identity).also { snapshot.value = it }
        }
    }

    override suspend fun applyRemoteManagedTrust(
        attestationVersion: Long,
        license: ManagedDeviceLicense,
        verifiedAtEpochMillis: Long,
    ): DeviceTrustSnapshot = withContext(Dispatchers.IO) {
        require(attestationVersion > 0)
        require(verifiedAtEpochMillis > 0)
        mutex.withLock {
            val identity = installationIdentityStore.loadOrCreate()
            val existing = recordStore.read() ?: throw SecureStorageKeyUnavailableException()
            verifyInstallation(existing, identity)
            if (existing.trustState == DeviceTrustState.LOST || existing.trustState == DeviceTrustState.REVOKED) {
                throw SecureStorageKeyUnavailableException()
            }
            if (existing.attestationVersion != attestationVersion ||
                existing.riskSignals.any(DeviceRiskSignal::blocksLocalVaultAccess)
            ) {
                throw SecureObjectAuthenticationException()
            }
            val descriptor = attestationBackend.load(aliasFor(identity)) ?: throw SecureStorageKeyUnavailableException()
            if (!MessageDigest.isEqual(existing.attestationPublicKeySha256, descriptor.publicKeySha256)) {
                throw SecureObjectAuthenticationException()
            }
            val platform = platformSignalSource.assess()
            val priorRevision = existing.managedLicense?.revision ?: 0L
            if (license.installationId != identity.value ||
                !license.devicePublicKeySha256.equals(existing.attestationPublicKeySha256.toHex(), ignoreCase = true) ||
                license.appPackageName != platform.packageName ||
                license.appVersionCode > platform.appVersionCode ||
                license.revision < priorRevision ||
                license.allowedScopes.any { it !in MANAGED_LICENSE_SCOPES } ||
                !license.isActive(verifiedAtEpochMillis) ||
                license.expiresAtEpochMillis - license.issuedAtEpochMillis > MAX_MANAGED_LICENSE_LIFETIME_MILLIS
            ) {
                throw SecureObjectAuthenticationException()
            }
            val managed = existing.copy(
                trustState = DeviceTrustState.MANAGED,
                lastVerifiedAtEpochMillis = verifiedAtEpochMillis,
                managedLicense = license,
            )
            recordStore.write(managed)
            latestEvidence = null
            managed.toSnapshot(identity).also { snapshot.value = it }
        }
    }

    override suspend fun markLocked() {
        mutex.withLock { latestEvidence = null }
    }

    override suspend fun applyRemoteRestriction(
        state: DeviceTrustState,
        verifiedAtEpochMillis: Long,
    ): DeviceTrustSnapshot = withContext(Dispatchers.IO) {
        require(state == DeviceTrustState.STALE || state == DeviceTrustState.REVOKED)
        require(verifiedAtEpochMillis > 0)
        mutex.withLock {
            latestEvidence = null
            val root = deviceRootKeyService.ensureReady()
            val identity = installationIdentityStore.loadOrCreate()
            val existing = recordStore.read()
            verifyInstallation(existing, identity)
            val platform = platformSignalSource.assess()
            val keyDescriptor = attestationBackend.load(aliasFor(identity))
            val restrictiveState = when {
                existing?.trustState == DeviceTrustState.REVOKED -> DeviceTrustState.REVOKED
                state == DeviceTrustState.REVOKED -> DeviceTrustState.REVOKED
                existing?.trustState == DeviceTrustState.LOST -> DeviceTrustState.LOST
                else -> DeviceTrustState.STALE
            }
            val record = DeviceTrustRecord(
                installationIdSha256 = identity.sha256,
                attestationPublicKeySha256 = existing?.attestationPublicKeySha256
                    ?: keyDescriptor?.publicKeySha256
                    ?: ByteArray(SHA256_LENGTH_BYTES),
                appSigningCertificateSha256 = existing?.appSigningCertificateSha256 ?: platform.signingCertificateSha256,
                hardwareSecurityLevel = existing?.hardwareSecurityLevel
                    ?: keyDescriptor?.hardwareSecurityLevel
                    ?: root.hardwareSecurityLevel,
                trustState = restrictiveState,
                attestationVersion = existing?.attestationVersion ?: 0,
                lastEvidenceAtEpochMillis = existing?.lastEvidenceAtEpochMillis,
                lastVerifiedAtEpochMillis = verifiedAtEpochMillis,
                riskSignals = existing?.riskSignals.orEmpty() + platform.riskSignals,
                managedLicense = null,
            )
            recordStore.write(record)
            record.toSnapshot(identity).also { snapshot.value = it }
        }
    }

    override suspend fun destroy(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            latestEvidence = null
            val identity = installationIdentityStore.loadOrCreate()
            val platform = platformSignalSource.assess()
            val existing = recordStore.read()
            val alias = aliasFor(identity)
            val descriptor = attestationBackend.load(alias)
            val lost = DeviceTrustRecord(
                installationIdSha256 = identity.sha256,
                attestationPublicKeySha256 = existing?.attestationPublicKeySha256
                    ?: descriptor?.publicKeySha256
                    ?: ByteArray(SHA256_LENGTH_BYTES),
                appSigningCertificateSha256 = existing?.appSigningCertificateSha256 ?: platform.signingCertificateSha256,
                hardwareSecurityLevel = existing?.hardwareSecurityLevel ?: HardwareSecurityLevel.UNKNOWN,
                trustState = DeviceTrustState.LOST,
                attestationVersion = maxOf(existing?.attestationVersion ?: 0L, INITIAL_ATTESTATION_VERSION),
                lastEvidenceAtEpochMillis = existing?.lastEvidenceAtEpochMillis,
                lastVerifiedAtEpochMillis = existing?.lastVerifiedAtEpochMillis,
                riskSignals = existing?.riskSignals.orEmpty(),
            )
            recordStore.write(lost)
            val destroyed = attestationBackend.destroy(alias)
            snapshot.value = lost.toSnapshot(identity)
            destroyed
        }
    }

    private suspend fun generateEvidence(challenge: ByteArray): DeviceAttestationEvidence = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(challenge.size in MIN_CHALLENGE_LENGTH_BYTES..MAX_CHALLENGE_LENGTH_BYTES)
            val root = deviceRootKeyService.ensureReady()
            val identity = installationIdentityStore.loadOrCreate()
            val existing = recordStore.read()
            verifyInstallation(existing, identity)
            if (existing?.trustState == DeviceTrustState.LOST || existing?.trustState == DeviceTrustState.REVOKED) {
                throw SecureStorageKeyUnavailableException()
            }
            val platform = platformSignalSource.assess()
            val alias = aliasFor(identity)
            val loadedKey = attestationBackend.load(alias)
            if (existing != null && (loadedKey == null || !MessageDigest.isEqual(existing.attestationPublicKeySha256, loadedKey.publicKeySha256))) {
                val lost = existing.copy(trustState = DeviceTrustState.LOST)
                recordStore.write(lost)
                snapshot.value = lost.toSnapshot(identity)
                throw SecureStorageKeyUnavailableException()
            }
            val keyDescriptor = loadedKey ?: attestationBackend.create(alias, challenge)
            val risks = collectRisks(existing, platform, keyDescriptor)
            val attestationVersion = Math.addExact(existing?.attestationVersion ?: 0L, 1L)
            val evidenceTime = System.currentTimeMillis()
            val hardwareLevel = weakestHardwareLevel(root.hardwareSecurityLevel, keyDescriptor.hardwareSecurityLevel)
            val statement = buildStatement(
                identity = identity,
                root = root,
                keyDescriptor = keyDescriptor,
                platform = platform,
                challengeSha256 = sha256(challenge),
                attestationVersion = attestationVersion,
                evidenceTime = evidenceTime,
                riskSignals = risks,
            )
            val evidence = DeviceAttestationEvidence(
                installationId = identity.value,
                attestationVersion = attestationVersion,
                challengeSha256 = sha256(challenge),
                statement = statement,
                signedStatement = attestationBackend.sign(alias, statement),
                attestationPublicKeySha256 = keyDescriptor.publicKeySha256.copyOf(),
                certificateChainDer = keyDescriptor.certificateChainDer.map(ByteArray::copyOf),
                hardwareSecurityLevel = hardwareLevel,
                appPackageName = platform.packageName,
                appVersionCode = platform.appVersionCode,
                appSigningCertificateSha256 = platform.signingCertificateSha256.map(ByteArray::copyOf),
                riskSignals = risks,
            )
            val trustState = if (risks.any(DeviceRiskSignal::blocksLocalVaultAccess)) {
                DeviceTrustState.INTEGRITY_WARNING
            } else {
                // Only a server that validates the Android attestation extension may advance PENDING to TRUSTED.
                DeviceTrustState.PENDING
            }
            val record = DeviceTrustRecord(
                installationIdSha256 = identity.sha256,
                attestationPublicKeySha256 = keyDescriptor.publicKeySha256,
                appSigningCertificateSha256 = platform.signingCertificateSha256,
                hardwareSecurityLevel = hardwareLevel,
                trustState = trustState,
                attestationVersion = attestationVersion,
                lastEvidenceAtEpochMillis = evidenceTime,
                lastVerifiedAtEpochMillis = null,
                riskSignals = risks,
            )
            recordStore.write(record)
            latestEvidence = evidence
            snapshot.value = record.toSnapshot(identity)
            evidence
        }
    }

    private suspend fun currentLocked(diagnostics: DeviceTrustDiagnosticSink? = null): DeviceTrustSnapshot {
        val root = diagnosticStage(diagnostics, DeviceTrustDiagnosticStage.ROOT_READY) { deviceRootKeyService.ensureReady() }
        val identity = diagnosticStage(diagnostics, DeviceTrustDiagnosticStage.INSTALLATION_LOAD) { installationIdentityStore.loadOrCreate() }
        val platform = diagnosticStage(diagnostics, DeviceTrustDiagnosticStage.PLATFORM_ASSESS) { platformSignalSource.assess() }
        val existing = diagnosticStage(diagnostics, DeviceTrustDiagnosticStage.RECORD_READ) { recordStore.read() }
        if (existing == null) {
            // A DAK without a durable record can only be the result of an interrupted first enrollment. Reusing it
            // would bind the next server challenge to the wrong certificate, so retire it before retrying.
            diagnosticStage(diagnostics, DeviceTrustDiagnosticStage.DAK_RETIRE) { attestationBackend.destroy(aliasFor(identity)) }
            return DeviceTrustSnapshot(
                installationId = identity.value,
                hardwareSecurityLevel = root.hardwareSecurityLevel,
                trustState = if (platform.riskSignals.any(DeviceRiskSignal::blocksLocalVaultAccess)) {
                    DeviceTrustState.INTEGRITY_WARNING
                } else {
                    DeviceTrustState.NOT_ENROLLED
                },
                attestationVersion = 0,
                lastVerifiedAtEpochMillis = null,
                riskSignals = platform.riskSignals,
            ).also { snapshot.value = it }
        }
        diagnosticStage(diagnostics, DeviceTrustDiagnosticStage.RECORD_VALIDATE) { verifyInstallation(existing, identity) }
        val keyDescriptor = diagnosticStage(diagnostics, DeviceTrustDiagnosticStage.DAK_LOAD) { attestationBackend.load(aliasFor(identity)) }
        val risks = if (keyDescriptor == null) {
            existing.riskSignals + platform.riskSignals
        } else {
            collectRisks(existing, platform, keyDescriptor)
        }
        val keyMatchesRecord = keyDescriptor != null &&
            MessageDigest.isEqual(existing.attestationPublicKeySha256, keyDescriptor.publicKeySha256)
            val state = when {
            existing.trustState == DeviceTrustState.REVOKED -> DeviceTrustState.REVOKED
            existing.trustState == DeviceTrustState.LOST || !keyMatchesRecord -> DeviceTrustState.LOST
            risks.any(DeviceRiskSignal::blocksLocalVaultAccess) -> DeviceTrustState.INTEGRITY_WARNING
            existing.trustState == DeviceTrustState.MANAGED &&
                existing.managedLicense != null &&
                !existing.managedLicense.isActive(System.currentTimeMillis()) -> DeviceTrustState.STALE
            else -> existing.trustState
        }
        val updated = existing.copy(
            hardwareSecurityLevel = keyDescriptor?.let { weakestHardwareLevel(root.hardwareSecurityLevel, it.hardwareSecurityLevel) }
                ?: existing.hardwareSecurityLevel,
            trustState = state,
            riskSignals = risks,
        )
        // Only these three fields are changed by the original copy above; still write on every call.
        val writeStage = if (diagnostics != null &&
            (updated.hardwareSecurityLevel != existing.hardwareSecurityLevel ||
                updated.trustState != existing.trustState || updated.riskSignals != existing.riskSignals)
        ) DeviceTrustDiagnosticStage.RECORD_WRITE_CHANGED else DeviceTrustDiagnosticStage.RECORD_WRITE_UNCHANGED
        diagnosticStage(diagnostics, writeStage) { recordStore.write(updated) }
        return updated.toSnapshot(identity).also { snapshot.value = it }
    }

    private suspend fun <T> withTrustLock(
        diagnostics: DeviceTrustDiagnosticSink?,
        block: suspend (DeviceTrustDiagnosticSink?) -> T,
    ): T {
        if (diagnostics == null) return mutex.withLock { block(null) }
        // Each actual call owns only fixed stage/clock/outcome tuples, never trust or proof material.
        val buffered = TrustDiagnosticBuffer()
        val waitStart = System.nanoTime()
        try {
            mutex.lock()
        } catch (failure: Throwable) {
            // This caller never acquired the mutex: no HOLD, and never unlock another caller's mutex.
            reportDiagnostic(buffered, DeviceTrustDiagnosticStage.MUTEX_WAIT, waitStart, diagnosticOutcome(failure))
            buffered.flushTo(diagnostics)
            throw failure
        }
        val acquired = System.nanoTime()
        var outcome = DeviceTrustDiagnosticOutcome.OK
        try {
            reportDiagnostic(buffered, DeviceTrustDiagnosticStage.MUTEX_WAIT, waitStart, DeviceTrustDiagnosticOutcome.OK, acquired)
            return block(buffered)
        } catch (failure: Throwable) {
            outcome = diagnosticOutcome(failure)
            throw failure
        } finally {
            val beforeUnlock = System.nanoTime()
            mutex.unlock()
            reportDiagnostic(buffered, DeviceTrustDiagnosticStage.MUTEX_HOLD, acquired, outcome, beforeUnlock)
            // Logging/observers run only after this caller's actual unlock, even on body cancellation.
            buffered.flushTo(diagnostics)
        }
    }

    private suspend fun <T> diagnosticStage(
        diagnostics: DeviceTrustDiagnosticSink?,
        stage: DeviceTrustDiagnosticStage,
        block: suspend () -> T,
    ): T {
        if (diagnostics == null) return block()
        val start = System.nanoTime()
        var outcome = DeviceTrustDiagnosticOutcome.OK
        try {
            return block()
        } catch (failure: Throwable) {
            outcome = diagnosticOutcome(failure)
            throw failure
        } finally {
            reportDiagnostic(diagnostics, stage, start, outcome)
        }
    }

    private fun diagnosticOutcome(failure: Throwable): DeviceTrustDiagnosticOutcome =
        if (failure is CancellationException) DeviceTrustDiagnosticOutcome.CANCELLED else DeviceTrustDiagnosticOutcome.ERROR

    private fun reportDiagnostic(
        diagnostics: DeviceTrustDiagnosticSink,
        stage: DeviceTrustDiagnosticStage,
        start: Long,
        outcome: DeviceTrustDiagnosticOutcome,
        end: Long = System.nanoTime(),
    ) {
        // Observational callbacks cannot replace a product result or its original exception/cancellation.
        runCatching { diagnostics.finished(stage, start, end, outcome) }
    }

    private class TrustDiagnosticBuffer : DeviceTrustDiagnosticSink {
        private val events = ArrayList<Event>(16)

        override fun finished(
            stage: DeviceTrustDiagnosticStage,
            startNanos: Long,
            endNanos: Long,
            outcome: DeviceTrustDiagnosticOutcome,
        ) {
            events += Event(stage, startNanos, endNanos, outcome)
        }

        fun flushTo(observer: DeviceTrustDiagnosticSink) {
            events.forEach { event ->
                // One failed observer callback cannot suppress later stages or alter the original result/cause.
                runCatching { observer.finished(event.stage, event.startNanos, event.endNanos, event.outcome) }
            }
        }

        private data class Event(
            val stage: DeviceTrustDiagnosticStage,
            val startNanos: Long,
            val endNanos: Long,
            val outcome: DeviceTrustDiagnosticOutcome,
        )
    }

    private fun collectRisks(
        existing: DeviceTrustRecord?,
        platform: DevicePlatformAssessment,
        descriptor: DeviceAttestationKeyDescriptor,
    ): Set<DeviceRiskSignal> = buildSet {
        addAll(platform.riskSignals)
        if (existing != null && !sameDigests(existing.appSigningCertificateSha256, platform.signingCertificateSha256)) {
            add(DeviceRiskSignal.APP_SIGNING_IDENTITY_CHANGED)
        }
        if (descriptor.certificateChainDer.size <= 1) add(DeviceRiskSignal.ATTESTATION_CERTIFICATE_CHAIN_UNAVAILABLE)
        if (!descriptor.hasHardwareAttestation) add(DeviceRiskSignal.HARDWARE_ATTESTATION_UNAVAILABLE)
    }

    private fun buildStatement(
        identity: InstallationIdentity,
        root: DeviceRootKeyDescriptor,
        keyDescriptor: DeviceAttestationKeyDescriptor,
        platform: DevicePlatformAssessment,
        challengeSha256: ByteArray,
        attestationVersion: Long,
        evidenceTime: Long,
        riskSignals: Set<DeviceRiskSignal>,
    ): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.write(EVIDENCE_STATEMENT_MAGIC)
            output.writeInt(EVIDENCE_STATEMENT_FORMAT_VERSION)
            // Bind the exact public installation identifier sent on the enrollment wire. The identifier is
            // lowercase hex, so hashing the underlying random bytes would produce a different digest on the
            // control plane and would leave the hardware proof impossible to enroll.
            output.write(sha256(identity.value.encodeToByteArray()))
            output.write(challengeSha256)
            output.writeLong(attestationVersion)
            output.writeLong(evidenceTime)
            output.writeInt(root.hardwareSecurityLevel.ordinal)
            output.writeInt(keyDescriptor.hardwareSecurityLevel.ordinal)
            output.write(keyDescriptor.publicKeySha256)
            output.writeUTF(platform.packageName)
            output.writeLong(platform.appVersionCode)
            output.writeLong(platform.firstInstallTimeEpochMillis)
            output.writeInt(platform.signingCertificateSha256.size)
            platform.signingCertificateSha256.sortedBy { it.toHex() }.forEach(output::write)
            output.writeInt(riskSignals.size)
            riskSignals.sortedBy(DeviceRiskSignal::ordinal).forEach { output.writeInt(it.ordinal) }
        }
        bytes.toByteArray()
    }

    private fun verifyInstallation(record: DeviceTrustRecord?, identity: InstallationIdentity) {
        if (record != null && !MessageDigest.isEqual(record.installationIdSha256, identity.sha256)) {
            throw SecureObjectAuthenticationException()
        }
    }

    private fun sameDigests(first: List<ByteArray>, second: List<ByteArray>): Boolean =
        first.map(ByteArray::toHex).sorted() == second.map(ByteArray::toHex).sorted()

    private fun weakestHardwareLevel(first: HardwareSecurityLevel, second: HardwareSecurityLevel): HardwareSecurityLevel =
        if (hardwareStrength(first) <= hardwareStrength(second)) first else second

    private fun hardwareStrength(level: HardwareSecurityLevel): Int = when (level) {
        HardwareSecurityLevel.UNKNOWN -> 0
        HardwareSecurityLevel.SOFTWARE -> 1
        HardwareSecurityLevel.TRUSTED_EXECUTION_ENVIRONMENT -> 2
        HardwareSecurityLevel.STRONGBOX -> 3
    }

    private fun DeviceTrustRecord.toSnapshot(identity: InstallationIdentity): DeviceTrustSnapshot = DeviceTrustSnapshot(
        installationId = identity.value,
        hardwareSecurityLevel = hardwareSecurityLevel,
        trustState = trustState,
        attestationVersion = attestationVersion,
        lastVerifiedAtEpochMillis = lastVerifiedAtEpochMillis,
        lastEvidenceAtEpochMillis = lastEvidenceAtEpochMillis,
        attestationPublicKeySha256 = attestationPublicKeySha256.toHex(),
        riskSignals = riskSignals,
        managedLicense = managedLicense,
    )

    private fun aliasFor(identity: InstallationIdentity): String = DAK_ALIAS_PREFIX + identity.sha256.toHex().take(ALIAS_DIGEST_LENGTH)

    private fun notEnrolledSnapshot(): DeviceTrustSnapshot = DeviceTrustSnapshot(
        installationId = "",
        hardwareSecurityLevel = HardwareSecurityLevel.UNKNOWN,
        trustState = DeviceTrustState.NOT_ENROLLED,
        attestationVersion = 0,
        lastVerifiedAtEpochMillis = null,
    )

    private companion object {
        const val INITIAL_ATTESTATION_VERSION = 1L
        const val SHA256_LENGTH_BYTES = 32
        const val MIN_CHALLENGE_LENGTH_BYTES = 16
        const val MAX_CHALLENGE_LENGTH_BYTES = 1024
        const val MAX_CONTROL_STATEMENT_LENGTH_BYTES = 8 * 1024
        const val ALIAS_DIGEST_LENGTH = 32
        const val DAK_ALIAS_PREFIX = "yizhe.device-attestation.v1."
        const val EVIDENCE_STATEMENT_FORMAT_VERSION = 1
        const val MAX_MANAGED_LICENSE_LIFETIME_MILLIS = 90L * 24 * 60 * 60 * 1_000
        val EVIDENCE_STATEMENT_MAGIC = "YZDE0001".encodeToByteArray()
        val MANAGED_LICENSE_SCOPES = setOf(
            "PROTECTED_MESSAGES",
            "PROTECTED_MEDIA",
            "PROTECTED_PROFILE",
            "PROTECTED_RTC",
        )
    }
}
