/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.contentprotection.impl

import android.os.SystemClock
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.security.contentprotection.api.ContentId
import io.element.android.security.contentprotection.api.ContentProtectionException
import io.element.android.security.contentprotection.api.ContentProtectionFormat
import io.element.android.security.contentprotection.api.DeviceRuntimeBindingProvider
import io.element.android.security.contentprotection.api.DeviceShareEnvelope
import io.element.android.security.contentprotection.api.DeviceShareKeyService
import io.element.android.security.contentprotection.api.KmsClientConfiguration
import io.element.android.security.contentprotection.api.KmsClientConfigurationProvider
import io.element.android.security.contentprotection.api.KmsShareClient
import io.element.android.security.contentprotection.api.OfflineLeaseMode
import io.element.android.security.contentprotection.api.OfflineLicenseClock
import io.element.android.security.contentprotection.api.OfflineLicenseService
import io.element.android.security.contentprotection.api.OfflineLicenseState
import io.element.android.security.contentprotection.api.OfflineLicenseStatus
import io.element.android.security.contentprotection.api.ReleaseKmsShareRequest
import io.element.android.security.contentprotection.api.ReleasedKmsShare
import io.element.android.security.contentprotection.api.RemoteDeviceAccessState
import io.element.android.security.contentprotection.api.RemoteRevocationService
import io.element.android.security.securestorage.api.AccountVaultId
import io.element.android.security.securestorage.api.DeviceTrustService
import io.element.android.security.securestorage.api.DeviceTrustState
import io.element.android.security.securestorage.api.HardwareSecurityLevel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest

@ContributesBinding(AppScope::class)
class AndroidOfflineLicenseClock : OfflineLicenseClock {
    override fun wallClockEpochMillis(): Long = System.currentTimeMillis()

    override fun elapsedRealtimeMillis(): Long = SystemClock.elapsedRealtime()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DefaultOfflineLicenseService(
    private val store: OfflineLicenseStore,
    private val kmsShareClient: KmsShareClient,
    private val remoteRevocationService: RemoteRevocationService,
    private val deviceShareKeyService: DeviceShareKeyService,
    private val deviceTrustService: DeviceTrustService,
    private val runtimeBindingProvider: DeviceRuntimeBindingProvider,
    private val clock: OfflineLicenseClock,
    private val configurationProvider: KmsClientConfigurationProvider,
) : OfflineLicenseService {
    private val mutex = Mutex()

    override suspend fun requireAccessAllowed(vaultId: AccountVaultId, contentId: ContentId) = mutex.withLock {
        rejectRevoked(store.readRevocations(vaultId), contentId, leaseId = null)
    }

    override suspend fun releaseOrAcquire(vaultId: AccountVaultId, request: ReleaseKmsShareRequest): ReleasedKmsShare = mutex.withLock {
        releaseOrAcquireLocked(vaultId, request)
    }

    override suspend fun inspect(
        vaultId: AccountVaultId,
        contentId: ContentId,
        reference: io.element.android.security.contentprotection.api.KmsShareReference,
    ): OfflineLicenseStatus = mutex.withLock {
        val revocations = store.readRevocations(vaultId)
        if (revocations.deviceAccessState != RemoteDeviceAccessState.ACTIVE ||
            contentId in revocations.revokedContentIds || contentId in revocations.locallyRevokedContentIds
        ) {
            return@withLock OfflineLicenseStatus(OfflineLicenseState.REVOKED)
        }
        val record = store.readLicense(vaultId, contentId, reference)
            ?: return@withLock OfflineLicenseStatus(OfflineLicenseState.REFRESH_REQUIRED)
        val lease = record.lease
        val base = OfflineLicenseStatus(
            state = OfflineLicenseState.VALID,
            remainingUses = record.remainingUses,
            expiresAtEpochMillis = lease.expiresAtEpochMillis,
            refreshAfterEpochMillis = lease.refreshAfterEpochMillis,
        )
        if (lease.revocationSequence < revocations.sequence) {
            return@withLock base.copy(state = OfflineLicenseState.REPLAY_REJECTED)
        }
        if (lease.bootCount != runtimeBindingProvider.bootCount()) {
            return@withLock base.copy(state = OfflineLicenseState.BINDING_INVALID)
        }
        val nowElapsed = clock.elapsedRealtimeMillis()
        val nowWall = clock.wallClockEpochMillis()
        val configuration = configurationProvider.configuration()
            ?: return@withLock base.copy(state = OfflineLicenseState.REFRESH_REQUIRED)
        if (nowElapsed < record.anchorElapsedRealtimeMillis ||
            nowWall + configuration.clockRollbackToleranceMillis < record.lastObservedWallClockEpochMillis
        ) {
            return@withLock base.copy(state = OfflineLicenseState.EPOCH_INVALID)
        }
        val estimatedServerTime = safeAdd(lease.serverTimeEpochMillis, nowElapsed - record.anchorElapsedRealtimeMillis)
        val effectiveTime = maxOf(estimatedServerTime, nowWall - configuration.clockRollbackToleranceMillis)
        when {
            effectiveTime >= lease.expiresAtEpochMillis -> base.copy(state = OfflineLicenseState.EXPIRED)
            effectiveTime >= lease.refreshAfterEpochMillis -> base.copy(state = OfflineLicenseState.REFRESH_REQUIRED)
            record.remainingUses <= 0 -> base.copy(state = OfflineLicenseState.EXHAUSTED)
            else -> base
        }
    }

    override suspend fun revokeContent(vaultId: AccountVaultId, contentId: ContentId): Boolean = mutex.withLock {
        store.revokeContent(vaultId, contentId)
        true
    }

    override suspend fun clearVault(vaultId: AccountVaultId): Boolean = mutex.withLock {
        store.clearVault(vaultId)
    }

    private suspend fun releaseOrAcquireLocked(vaultId: AccountVaultId, request: ReleaseKmsShareRequest): ReleasedKmsShare {
        val configuration = requireConfiguration()
        requireEligibleDevice(request.requestingInstallationId)
        synchronizeIfDue(vaultId, request)
        val revocations = store.readRevocations(vaultId)
        rejectRevoked(revocations, request.contentId, leaseId = null)

        val existing = store.readLicense(vaultId, request.contentId, request.reference)
        if (existing != null) {
            try {
                return consume(vaultId, request, existing, revocations, configuration)
            } catch (failure: ContentProtectionException.Expired) {
                store.deleteLicense(vaultId, request.contentId, request.reference)
            } catch (failure: ContentProtectionException.OfflineLicenseUnavailable) {
                store.deleteLicense(vaultId, request.contentId, request.reference)
            } catch (failure: ContentProtectionException.ReplayRejected) {
                // The revocation sequence moved forward after this lease was issued. Discard the
                // stale record and perform the single online acquisition below. A stale freshly
                // issued lease still fails closed in validateLease; this path never loops.
                store.deleteLicense(vaultId, request.contentId, request.reference)
            }
        }

        val lease = kmsShareClient.issueOfflineLease(request)
        val nowElapsed = clock.elapsedRealtimeMillis()
        val nowWall = clock.wallClockEpochMillis()
        val record = OfflineLicenseRecord(
            lease = lease,
            anchorElapsedRealtimeMillis = nowElapsed,
            anchorWallClockEpochMillis = nowWall,
            lastObservedWallClockEpochMillis = nowWall,
            remainingUses = lease.maximumUses,
        )
        validateLease(request, record, store.readRevocations(vaultId), configuration)
        store.writeLicense(vaultId, record)
        return consume(vaultId, request, record, store.readRevocations(vaultId), configuration)
    }

    private suspend fun synchronizeIfDue(vaultId: AccountVaultId, request: ReleaseKmsShareRequest) {
        val current = store.readRevocations(vaultId)
        val now = clock.wallClockEpochMillis()
        if (current.refreshAfterEpochMillis > now && current.expiresAtEpochMillis > now) return
        try {
            remoteRevocationService.synchronize(vaultId, request.accountIdHash, request.requestingInstallationId)
        } catch (_: ContentProtectionException.KmsUnavailable) {
            // A still-valid signed lease may bridge a network outage. Every other failure remains fail-closed.
        }
    }

    private suspend fun consume(
        vaultId: AccountVaultId,
        request: ReleaseKmsShareRequest,
        record: OfflineLicenseRecord,
        revocations: PersistedRevocationState,
        configuration: KmsClientConfiguration,
    ): ReleasedKmsShare {
        requireEligibleDevice(request.requestingInstallationId)
        validateLease(request, record, revocations, configuration)
        val lease = record.lease
        val nowElapsed = clock.elapsedRealtimeMillis()
        val nowWall = clock.wallClockEpochMillis()
        if (nowElapsed < record.anchorElapsedRealtimeMillis) {
            store.deleteLicense(vaultId, request.contentId, request.reference)
            throw ContentProtectionException.ClockRollbackDetected()
        }
        if (nowWall + configuration.clockRollbackToleranceMillis < record.lastObservedWallClockEpochMillis) {
            store.deleteLicense(vaultId, request.contentId, request.reference)
            throw ContentProtectionException.ClockRollbackDetected()
        }
        val elapsed = nowElapsed - record.anchorElapsedRealtimeMillis
        val estimatedServerTime = safeAdd(lease.serverTimeEpochMillis, elapsed)
        val effectiveTime = maxOf(estimatedServerTime, nowWall - configuration.clockRollbackToleranceMillis)
        val deadline = minOf(lease.expiresAtEpochMillis, lease.refreshAfterEpochMillis, request.policy.expiresAtEpochMillis)
        if (effectiveTime >= deadline) {
            store.deleteLicense(vaultId, request.contentId, request.reference)
            throw ContentProtectionException.Expired()
        }
        if (record.remainingUses <= 0) {
            store.deleteLicense(vaultId, request.contentId, request.reference)
            throw ContentProtectionException.OfflineLicenseUnavailable()
        }

        // Persist consumption before returning key material. A crash can waste a use, but cannot mint an extra one.
        store.writeLicense(
            vaultId,
            record.copy(
                lastObservedWallClockEpochMillis = maxOf(record.lastObservedWallClockEpochMillis, nowWall),
                remainingUses = record.remainingUses - 1,
            )
        )
        val kmsShare = deviceShareKeyService.unwrapShare(
            envelope = DeviceShareEnvelope(
                recipientInstallationId = lease.installationId,
                recipientKeyId = lease.recipientKeyId,
                algorithm = ContentProtectionFormat.DEVICE_SHARE_ALGORITHM,
                ciphertext = lease.encryptedKmsShare,
            ),
            contentId = lease.contentId,
            immutableContextSha256 = lease.immutableContextSha256,
        )
        return ReleasedKmsShare(
            grantId = lease.leaseId,
            reference = lease.reference,
            contentId = lease.contentId,
            policyVersion = lease.policyVersion,
            installationId = lease.installationId,
            immutableContextSha256 = lease.immutableContextSha256.copyOf(),
            requestNonce = request.requestNonce.copyOf(),
            mode = lease.mode,
            issuedAtEpochMillis = lease.issuedAtEpochMillis,
            expiresAtEpochMillis = lease.expiresAtEpochMillis,
            authorizationDeadlineEpochMillis = deadline,
            serverTimeEpochMillis = estimatedServerTime,
            bootCount = lease.bootCount,
            kmsShare = kmsShare,
        )
    }

    @Suppress("ComplexCondition")
    private fun validateLease(
        request: ReleaseKmsShareRequest,
        record: OfflineLicenseRecord,
        revocations: PersistedRevocationState,
        configuration: KmsClientConfiguration,
    ) {
        val lease = record.lease
        rejectRevoked(revocations, lease.contentId, lease.leaseId)
        if (lease.revocationSequence < revocations.sequence) {
            throw ContentProtectionException.ReplayRejected()
        }
        val earliestRefreshEpochMillis = lease.serverTimeEpochMillis + 1
        if (lease.mode != OfflineLeaseMode.BOUNDED_OFFLINE ||
            lease.reference != request.reference ||
            lease.contentId != request.contentId ||
            lease.accountIdHash != request.accountIdHash ||
            lease.installationId != request.requestingInstallationId ||
            lease.recipientKeyId != request.requestingKeyId ||
            lease.policyVersion != request.policy.policyVersion ||
            lease.bootCount != runtimeBindingProvider.bootCount() ||
            !MessageDigest.isEqual(lease.immutableContextSha256, request.immutableContextSha256) ||
            lease.maximumUses !in 1..configuration.maximumOfflineLicenseUses ||
            lease.revocationSequence < 0 ||
            lease.refreshAfterEpochMillis !in earliestRefreshEpochMillis..lease.expiresAtEpochMillis ||
            lease.expiresAtEpochMillis > request.policy.expiresAtEpochMillis ||
            lease.expiresAtEpochMillis - lease.issuedAtEpochMillis > configuration.maximumOfflineLeaseMillis
        ) {
            throw ContentProtectionException.AuthenticationFailed()
        }
        if (record.anchorElapsedRealtimeMillis < 0 || record.anchorWallClockEpochMillis <= 0 || record.remainingUses < 0) {
            throw ContentProtectionException.AuthenticationFailed()
        }
    }

    @Suppress("ComplexCondition")
    private fun rejectRevoked(state: PersistedRevocationState, contentId: ContentId, leaseId: String?) {
        if (state.deviceAccessState != RemoteDeviceAccessState.ACTIVE ||
            contentId in state.revokedContentIds ||
            contentId in state.locallyRevokedContentIds ||
            leaseId != null && leaseId in state.revokedLeaseIds
        ) {
            throw ContentProtectionException.Revoked()
        }
    }

    private suspend fun requireEligibleDevice(installationId: String) {
        val trust = deviceTrustService.current()
        if (trust.installationId != installationId || trust.trustState != DeviceTrustState.TRUSTED ||
            trust.hardwareSecurityLevel !in TRUSTED_HARDWARE_LEVELS
        ) {
            throw ContentProtectionException.AccessDenied()
        }
    }

    private fun requireConfiguration(): KmsClientConfiguration = configurationProvider.configuration()?.also {
        if (it.maximumOfflineLeaseMillis !in 1..MAXIMUM_OFFLINE_LEASE_MILLIS ||
            it.maximumOfflineLicenseUses !in 1..MAXIMUM_OFFLINE_LICENSE_USES ||
            it.clockRollbackToleranceMillis !in 0..minOf(it.maximumOfflineLeaseMillis, MAXIMUM_CLOCK_ROLLBACK_TOLERANCE_MILLIS)
        ) {
            throw ContentProtectionException.KmsUnavailable()
        }
    } ?: throw ContentProtectionException.KmsUnavailable()

    private fun safeAdd(first: Long, second: Long): Long = try {
        Math.addExact(first, second)
    } catch (_: ArithmeticException) {
        Long.MAX_VALUE
    }

    private companion object {
        const val MAXIMUM_OFFLINE_LEASE_MILLIS = 24L * 60 * 60 * 1_000
        const val MAXIMUM_OFFLINE_LICENSE_USES = 10_000
        const val MAXIMUM_CLOCK_ROLLBACK_TOLERANCE_MILLIS = 5L * 60 * 1_000
        val TRUSTED_HARDWARE_LEVELS = setOf(
            HardwareSecurityLevel.STRONGBOX,
            HardwareSecurityLevel.TRUSTED_EXECUTION_ENVIRONMENT,
        )
    }
}
