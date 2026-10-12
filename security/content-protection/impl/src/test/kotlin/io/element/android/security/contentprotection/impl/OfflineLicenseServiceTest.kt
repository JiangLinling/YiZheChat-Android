/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.contentprotection.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.security.contentprotection.api.ContentAccessDestroyReason
import io.element.android.security.contentprotection.api.ContentId
import io.element.android.security.contentprotection.api.ContentPolicyReference
import io.element.android.security.contentprotection.api.ContentProtectionException
import io.element.android.security.contentprotection.api.ContentProtectionFormat
import io.element.android.security.contentprotection.api.DeviceRuntimeBindingProvider
import io.element.android.security.contentprotection.api.DeviceShareEnvelope
import io.element.android.security.contentprotection.api.DeviceShareKeyDescriptor
import io.element.android.security.contentprotection.api.DeviceShareKeyService
import io.element.android.security.contentprotection.api.DeviceShareRecipient
import io.element.android.security.contentprotection.api.FetchRevocationsRequest
import io.element.android.security.contentprotection.api.KmsClientConfiguration
import io.element.android.security.contentprotection.api.KmsClientConfigurationProvider
import io.element.android.security.contentprotection.api.KmsShareClient
import io.element.android.security.contentprotection.api.KmsShareReference
import io.element.android.security.contentprotection.api.OfflineLease
import io.element.android.security.contentprotection.api.OfflineLeaseMode
import io.element.android.security.contentprotection.api.OfflineLicenseClock
import io.element.android.security.contentprotection.api.OfflineLicenseState
import io.element.android.security.contentprotection.api.PolicyId
import io.element.android.security.contentprotection.api.ReleaseKmsShareRequest
import io.element.android.security.contentprotection.api.ReleasedKmsShare
import io.element.android.security.contentprotection.api.RemoteDeviceAccessState
import io.element.android.security.contentprotection.api.RemoteRevocationService
import io.element.android.security.contentprotection.api.RemoteRevocationSnapshot
import io.element.android.security.contentprotection.api.RevocationSyncResult
import io.element.android.security.contentprotection.api.StoreKmsShareRequest
import io.element.android.security.securestorage.api.AccountVaultId
import io.element.android.security.securestorage.api.DeviceTrustSnapshot
import io.element.android.security.securestorage.api.DeviceTrustState
import io.element.android.security.securestorage.api.HardwareSecurityLevel
import io.element.android.security.securestorage.api.SecureStorageRoots
import io.element.android.security.securestorage.api.VaultProvisioningRequest
import io.element.android.security.securestorage.api.VaultState
import io.element.android.security.securestorage.test.FakeDeviceTrustService
import io.element.android.security.securestorage.test.FakeVaultKeyService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OfflineLicenseServiceTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `bounded lease survives process service recreation and never persists a plaintext KMS share`() = runTest {
        val vaultKeys = preparedVaultKeys()
        val roots = TestRoots(temporaryFolder.newFolder("persistent"), temporaryFolder.newFolder("runtime"))
        val first = VaultOfflineLicenseStore(roots, vaultKeys)
        val record = licenseRecord(maximumUses = 3)

        first.writeLicense(VAULT_ID, record)
        val restarted = VaultOfflineLicenseStore(roots, vaultKeys)
        val restored = restarted.readLicense(VAULT_ID, CONTENT_ID, REFERENCE)

        assertThat(restored?.lease?.leaseId).isEqualTo(LEASE_ID)
        assertThat(restored?.remainingUses).isEqualTo(3)
        val persisted = roots.persistentRoot().walkTopDown().filter(File::isFile).flatMap { it.readBytes().asSequence() }.toList().toByteArray()
        assertThat(persisted.containsSubsequence(CONTENT_ID.value.encodeToByteArray())).isFalse()
        assertThat(persisted.containsSubsequence(record.lease.encryptedKmsShare)).isFalse()
    }

    @Test
    fun `tampered persisted license fails authentication`() = runTest {
        val vaultKeys = preparedVaultKeys()
        val roots = TestRoots(temporaryFolder.newFolder("persistent-tamper"), temporaryFolder.newFolder("runtime-tamper"))
        val store = VaultOfflineLicenseStore(roots, vaultKeys)
        store.writeLicense(VAULT_ID, licenseRecord())
        val recordFile = roots.persistentRoot().walkTopDown().single { it.isFile && it.extension == "yzol" }
        val tampered = recordFile.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        recordFile.writeBytes(tampered)

        val result = runCatchingExceptions { store.readLicense(VAULT_ID, CONTENT_ID, REFERENCE) }

        assertThat(result.exceptionOrNull()).isInstanceOf(ContentProtectionException.AuthenticationFailed::class.java)
    }

    @Test
    fun `online acquisition is reused offline until its use limit is exhausted`() = runTest {
        val fixture = fixture(maximumUses = 2)

        assertThat(fixture.service.releaseOrAcquire(VAULT_ID, releaseRequest()).kmsShare).isEqualTo(KMS_SHARE)
        fixture.kms.issueUnavailable = true
        assertThat(fixture.service.releaseOrAcquire(VAULT_ID, releaseRequest()).kmsShare).isEqualTo(KMS_SHARE)
        val exhausted = runCatchingExceptions { fixture.service.releaseOrAcquire(VAULT_ID, releaseRequest()) }

        assertThat(fixture.kms.issueCount).isEqualTo(2)
        assertThat(exhausted.exceptionOrNull()).isInstanceOf(ContentProtectionException.KmsUnavailable::class.java)
    }

    @Test
    fun `released offline authority deadline keeps the earliest refresh boundary`() = runTest {
        val fixture = fixture()
        fixture.kms.refreshAfterEpochMillis = NOW + 1_000

        val released = fixture.service.releaseOrAcquire(VAULT_ID, releaseRequest())

        assertThat(released.grantId).isEqualTo(LEASE_ID)
        assertThat(released.expiresAtEpochMillis).isEqualTo(NOW + LEASE_TTL)
        assertThat(released.authorizationDeadlineEpochMillis).isEqualTo(NOW + 1_000)
        assertThat(released.bootCount).isEqualTo(BOOT_COUNT)
        assertThat(released.serverTimeEpochMillis).isEqualTo(NOW)
    }

    @Test
    fun `offline license exposes deterministic lifecycle states`() = runTest {
        val fixture = fixture(maximumUses = 2)
        assertThat(fixture.service.inspect(VAULT_ID, CONTENT_ID, REFERENCE).state).isEqualTo(OfflineLicenseState.REFRESH_REQUIRED)

        fixture.service.releaseOrAcquire(VAULT_ID, releaseRequest())
        assertThat(fixture.service.inspect(VAULT_ID, CONTENT_ID, REFERENCE)).isEqualTo(
            io.element.android.security.contentprotection.api.OfflineLicenseStatus(
                state = OfflineLicenseState.VALID,
                remainingUses = 1,
                expiresAtEpochMillis = NOW + LEASE_TTL,
                refreshAfterEpochMillis = NOW + LEASE_TTL,
            )
        )

        fixture.service.releaseOrAcquire(VAULT_ID, releaseRequest())
        assertThat(fixture.service.inspect(VAULT_ID, CONTENT_ID, REFERENCE).state).isEqualTo(OfflineLicenseState.EXHAUSTED)

        fixture.runtime.bootCount++
        assertThat(fixture.service.inspect(VAULT_ID, CONTENT_ID, REFERENCE).state).isEqualTo(OfflineLicenseState.BINDING_INVALID)
        fixture.runtime.bootCount--
        fixture.clock.wall -= CLOCK_TOLERANCE + 1
        assertThat(fixture.service.inspect(VAULT_ID, CONTENT_ID, REFERENCE).state).isEqualTo(OfflineLicenseState.EPOCH_INVALID)
    }

    @Test
    fun `offline license exposes expired revoked and replay rejected states`() = runTest {
        val expired = fixture()
        expired.service.releaseOrAcquire(VAULT_ID, releaseRequest())
        expired.clock.wall += LEASE_TTL
        expired.clock.elapsed += LEASE_TTL
        assertThat(expired.service.inspect(VAULT_ID, CONTENT_ID, REFERENCE).state).isEqualTo(OfflineLicenseState.EXPIRED)

        val revoked = fixture()
        revoked.service.releaseOrAcquire(VAULT_ID, releaseRequest())
        revoked.store.revocations = revoked.store.revocations.copy(locallyRevokedContentIds = setOf(CONTENT_ID))
        assertThat(revoked.service.inspect(VAULT_ID, CONTENT_ID, REFERENCE).state).isEqualTo(OfflineLicenseState.REVOKED)

        val replayed = fixture()
        replayed.service.releaseOrAcquire(VAULT_ID, releaseRequest())
        replayed.store.revocations = replayed.store.revocations.copy(sequence = 2)
        assertThat(replayed.service.inspect(VAULT_ID, CONTENT_ID, REFERENCE).state).isEqualTo(OfflineLicenseState.REPLAY_REJECTED)
        replayed.kms.issueUnavailable = true
        assertThat(runCatchingExceptions { replayed.service.releaseOrAcquire(VAULT_ID, releaseRequest()) }.exceptionOrNull())
            .isInstanceOf(ContentProtectionException.KmsUnavailable::class.java)
        assertThat(replayed.kms.issueCount).isEqualTo(2)
    }

    @Test
    fun `replay rejected cached lease is deleted and reacquired online exactly once`() = runTest {
        val fixture = fixture()
        fixture.service.releaseOrAcquire(VAULT_ID, releaseRequest())
        fixture.store.revocations = fixture.store.revocations.copy(sequence = 2)
        fixture.kms.revocationSequence = 2

        val released = fixture.service.releaseOrAcquire(VAULT_ID, releaseRequest())

        assertThat(released.kmsShare).isEqualTo(KMS_SHARE)
        assertThat(fixture.kms.issueCount).isEqualTo(2)
        assertThat(fixture.store.readLicense(VAULT_ID, CONTENT_ID, REFERENCE)?.lease?.revocationSequence).isEqualTo(2)
    }

    @Test
    fun `reboot and wall clock rollback invalidate a persisted license`() = runTest {
        val rebooted = fixture()
        rebooted.service.releaseOrAcquire(VAULT_ID, releaseRequest())
        rebooted.runtime.bootCount = BOOT_COUNT + 1
        rebooted.kms.issueUnavailable = true

        val rebootResult = runCatchingExceptions { rebooted.service.releaseOrAcquire(VAULT_ID, releaseRequest()) }

        assertThat(rebootResult.exceptionOrNull()).isInstanceOf(ContentProtectionException.AuthenticationFailed::class.java)

        val rolledBack = fixture()
        rolledBack.service.releaseOrAcquire(VAULT_ID, releaseRequest())
        rolledBack.clock.wall -= CLOCK_TOLERANCE + 1
        rolledBack.kms.issueUnavailable = true

        val rollbackResult = runCatchingExceptions { rolledBack.service.releaseOrAcquire(VAULT_ID, releaseRequest()) }

        assertThat(rollbackResult.exceptionOrNull()).isInstanceOf(ContentProtectionException.ClockRollbackDetected::class.java)
    }

    @Test
    fun `signed remote device revocation writes trust tombstone and destroys local key access`() = runTest {
        val store = InMemoryOfflineLicenseStore()
        val trust = trustedDevice()
        val vaultKeys = preparedVaultKeys()
        val deviceKeys = TestDeviceShareKeys()
        val kms = TestKmsClient(maximumUses = 2).apply { revokeDevice = true }
        val service = DefaultRemoteRevocationService(kms, store, trust, vaultKeys, deviceKeys)

        val result = service.synchronize(VAULT_ID, ACCOUNT_HASH, INSTALLATION_ID)

        assertThat(result.deviceAccessState).isEqualTo(RemoteDeviceAccessState.REVOKED)
        assertThat(trust.current().trustState).isEqualTo(DeviceTrustState.REVOKED)
        assertThat(vaultKeys.state(VAULT_ID).first()).isEqualTo(VaultState.DESTROYED)
        assertThat(deviceKeys.destroyed).isTrue()
        assertThat(store.cleared).isTrue()
    }

    private suspend fun preparedVaultKeys(): FakeVaultKeyService = FakeVaultKeyService().also {
        it.provision(VaultProvisioningRequest(VAULT_ID, ACCOUNT_HASH, requireUserAuthentication = false, requireTrustedHardware = false))
        check(it.unlock(VAULT_ID))
    }

    private fun fixture(maximumUses: Int = 3): Fixture {
        val store = InMemoryOfflineLicenseStore()
        val kms = TestKmsClient(maximumUses)
        val clock = TestOfflineClock()
        val runtime = MutableRuntimeBinding()
        val service = DefaultOfflineLicenseService(
            store = store,
            kmsShareClient = kms,
            remoteRevocationService = NoOpRemoteRevocationService,
            deviceShareKeyService = TestDeviceShareKeys(),
            deviceTrustService = trustedDevice(),
            runtimeBindingProvider = runtime,
            clock = clock,
            configurationProvider = KmsClientConfigurationProvider { configuration() },
        )
        return Fixture(service, kms, clock, runtime, store)
    }

    private fun trustedDevice() = FakeDeviceTrustService(
        DeviceTrustSnapshot(
            installationId = INSTALLATION_ID,
            hardwareSecurityLevel = HardwareSecurityLevel.TRUSTED_EXECUTION_ENVIRONMENT,
            trustState = DeviceTrustState.TRUSTED,
            attestationVersion = 1,
            lastVerifiedAtEpochMillis = NOW,
        )
    )

    private fun configuration() = KmsClientConfiguration(
        baseUrl = "https://kms.invalid/",
        signingPublicKeyDer = byteArrayOf(1),
        maximumOfflineLeaseMillis = LEASE_TTL,
        maximumOfflineLicenseUses = 10,
        clockRollbackToleranceMillis = CLOCK_TOLERANCE,
    )

    private fun releaseRequest() = ReleaseKmsShareRequest(
        reference = REFERENCE,
        contentId = CONTENT_ID,
        policy = POLICY,
        accountIdHash = ACCOUNT_HASH,
        requestingInstallationId = INSTALLATION_ID,
        requestingKeyId = KEY_ID,
        immutableContextSha256 = CONTEXT.copyOf(),
        requestNonce = ByteArray(ContentProtectionFormat.REQUEST_NONCE_MIN_SIZE_BYTES) { 4 },
        bootCount = BOOT_COUNT,
    )

    private fun licenseRecord(maximumUses: Int = 3) = OfflineLicenseRecord(
        lease = lease(releaseRequest(), maximumUses),
        anchorElapsedRealtimeMillis = ELAPSED,
        anchorWallClockEpochMillis = NOW,
        lastObservedWallClockEpochMillis = NOW,
        remainingUses = maximumUses,
    )

    private fun lease(request: ReleaseKmsShareRequest, maximumUses: Int) = OfflineLease(
        leaseId = LEASE_ID,
        reference = request.reference,
        contentId = request.contentId,
        accountIdHash = request.accountIdHash,
        installationId = request.requestingInstallationId,
        recipientKeyId = request.requestingKeyId,
        mode = OfflineLeaseMode.BOUNDED_OFFLINE,
        policyVersion = request.policy.policyVersion,
        immutableContextSha256 = request.immutableContextSha256.copyOf(),
        requestNonce = request.requestNonce.copyOf(),
        bootCount = request.bootCount,
        issuedAtEpochMillis = NOW,
        expiresAtEpochMillis = NOW + LEASE_TTL,
        serverTimeEpochMillis = NOW,
        refreshAfterEpochMillis = NOW + LEASE_TTL,
        revocationSequence = 1,
        maximumUses = maximumUses,
        encryptedKmsShare = ByteArray(384) { (it % 251).toByte() },
        serverSignature = ByteArray(64) { 8 },
    )

    private inner class TestKmsClient(private val maximumUses: Int) : KmsShareClient {
        var issueUnavailable = false
        var issueCount = 0
        var revokeDevice = false
        var revocationSequence = 1L
        var refreshAfterEpochMillis = NOW + LEASE_TTL

        override suspend fun storeShare(request: StoreKmsShareRequest): KmsShareReference = error("Not used")

        override suspend fun releaseShare(request: ReleaseKmsShareRequest): ReleasedKmsShare = error("Not used")

        override suspend fun issueOfflineLease(request: ReleaseKmsShareRequest): OfflineLease {
            issueCount++
            if (issueUnavailable) throw ContentProtectionException.KmsUnavailable()
            return lease(request, maximumUses).copy(
                refreshAfterEpochMillis = refreshAfterEpochMillis,
                revocationSequence = revocationSequence,
            )
        }

        override suspend fun fetchRevocations(request: FetchRevocationsRequest): RemoteRevocationSnapshot = RemoteRevocationSnapshot(
            accountIdHash = request.accountIdHash,
            installationId = request.installationId,
            sequence = request.knownSequence + 1,
            issuedAtEpochMillis = NOW,
            refreshAfterEpochMillis = NOW + 10_000,
            expiresAtEpochMillis = NOW + 20_000,
            requestNonce = request.requestNonce.copyOf(),
            deviceAccessState = if (revokeDevice) RemoteDeviceAccessState.REVOKED else RemoteDeviceAccessState.ACTIVE,
            revokedLeaseIds = emptySet(),
            revokedContentIds = emptySet(),
            serverSignature = ByteArray(64),
        )

        override suspend fun destroyShare(contentId: ContentId, reason: ContentAccessDestroyReason): Boolean = true
    }

    private class TestDeviceShareKeys : DeviceShareKeyService {
        var destroyed = false

        override suspend fun descriptor(): DeviceShareKeyDescriptor = error("Not used")

        override suspend fun wrapShare(
            recipient: DeviceShareRecipient,
            contentId: ContentId,
            immutableContextSha256: ByteArray,
            share: ByteArray,
        ): DeviceShareEnvelope = error("Not used")

        override suspend fun unwrapShare(
            envelope: DeviceShareEnvelope,
            contentId: ContentId,
            immutableContextSha256: ByteArray,
        ): ByteArray = KMS_SHARE.copyOf()

        override suspend fun destroy(): Boolean {
            destroyed = true
            return true
        }
    }

    private class MutableRuntimeBinding(var bootCount: Long = BOOT_COUNT) : DeviceRuntimeBindingProvider {
        override fun bootCount(): Long = bootCount
    }

    private class TestOfflineClock(
        var wall: Long = NOW,
        var elapsed: Long = ELAPSED,
    ) : OfflineLicenseClock {
        override fun wallClockEpochMillis(): Long = wall

        override fun elapsedRealtimeMillis(): Long = elapsed
    }

    private class InMemoryOfflineLicenseStore : OfflineLicenseStore {
        var license: OfflineLicenseRecord? = null
        var revocations = PersistedRevocationState()
        var cleared = false

        override suspend fun readLicense(
            vaultId: AccountVaultId,
            contentId: ContentId,
            reference: KmsShareReference,
        ): OfflineLicenseRecord? = license

        override suspend fun writeLicense(vaultId: AccountVaultId, record: OfflineLicenseRecord) {
            license = record
        }

        override suspend fun deleteLicense(vaultId: AccountVaultId, contentId: ContentId, reference: KmsShareReference): Boolean {
            license = null
            return true
        }

        override suspend fun readRevocations(vaultId: AccountVaultId): PersistedRevocationState = revocations

        override suspend fun applySnapshot(
            vaultId: AccountVaultId,
            snapshot: RemoteRevocationSnapshot,
        ): PersistedRevocationState {
            revocations = revocations.copy(
                sequence = snapshot.sequence,
                issuedAtEpochMillis = snapshot.issuedAtEpochMillis,
                refreshAfterEpochMillis = snapshot.refreshAfterEpochMillis,
                expiresAtEpochMillis = snapshot.expiresAtEpochMillis,
                deviceAccessState = snapshot.deviceAccessState,
                revokedLeaseIds = revocations.revokedLeaseIds + snapshot.revokedLeaseIds,
                revokedContentIds = revocations.revokedContentIds + snapshot.revokedContentIds,
            )
            return revocations
        }

        override suspend fun revokeContent(vaultId: AccountVaultId, contentId: ContentId): PersistedRevocationState {
            revocations = revocations.copy(locallyRevokedContentIds = revocations.locallyRevokedContentIds + contentId)
            return revocations
        }

        override suspend fun clearVault(vaultId: AccountVaultId): Boolean {
            cleared = true
            license = null
            return true
        }
    }

    private data object NoOpRemoteRevocationService : RemoteRevocationService {
        override suspend fun synchronize(
            vaultId: AccountVaultId,
            accountIdHash: String,
            installationId: String,
        ): RevocationSyncResult = RevocationSyncResult(0, RemoteDeviceAccessState.ACTIVE, 0, 0)
    }

    private data class Fixture(
        val service: DefaultOfflineLicenseService,
        val kms: TestKmsClient,
        val clock: TestOfflineClock,
        val runtime: MutableRuntimeBinding,
        val store: InMemoryOfflineLicenseStore,
    )

    private data class TestRoots(private val persistent: File, private val runtime: File) : SecureStorageRoots {
        override fun persistentRoot(): File = persistent

        override fun runtimeRoot(): File = runtime
    }

    private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        return indices.take(size - needle.size + 1).any { offset ->
            needle.indices.all { index -> this[offset + index] == needle[index] }
        }
    }

    private companion object {
        val VAULT_ID = AccountVaultId("vault-1")
        val CONTENT_ID = ContentId("content-secret-1")
        val REFERENCE = KmsShareReference("kms-reference-1")
        val POLICY = ContentPolicyReference(PolicyId("policy-1"), 4, 10_000_000)
        val CONTEXT = ByteArray(ContentProtectionFormat.SHA256_SIZE_BYTES) { 7 }
        val KMS_SHARE = ByteArray(ContentProtectionFormat.SHARE_SIZE_BYTES) { 11 }
        const val ACCOUNT_HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val INSTALLATION_ID = "installation-1"
        const val KEY_ID = "device-key-1"
        const val LEASE_ID = "lease-1"
        const val NOW = 2_000_000L
        const val ELAPSED = 50_000L
        const val BOOT_COUNT = 7L
        const val LEASE_TTL = 60_000L
        const val CLOCK_TOLERANCE = 1_000L
    }
}
