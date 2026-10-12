/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.securestorage.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.security.securestorage.api.AccountVaultId
import io.element.android.security.securestorage.api.DeviceTrustSnapshot
import io.element.android.security.securestorage.api.DeviceTrustState
import io.element.android.security.securestorage.api.HardwareSecurityLevel
import io.element.android.security.securestorage.api.KeyRotationReason
import io.element.android.security.securestorage.api.SecureObjectAuthenticationException
import io.element.android.security.securestorage.api.SecureStorageKeyUnavailableException
import io.element.android.security.securestorage.api.SecureStorageRoots
import io.element.android.security.securestorage.api.SecureVaultDestroyedException
import io.element.android.security.securestorage.api.VaultDestroyReason
import io.element.android.security.securestorage.api.VaultKeyDomain
import io.element.android.security.securestorage.api.VaultProvisioningRequest
import io.element.android.security.securestorage.api.VaultState
import io.element.android.security.securestorage.api.VaultWrappedKey
import io.element.android.security.securestorage.api.accountIdHash
import io.element.android.security.securestorage.test.FakeDeviceTrustService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class DefaultVaultKeyServiceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `revalidating an unlocked vault does not publish a false revocation`() = runTest {
        val fixture = fixture()
        fixture.prepare()
        val states = mutableListOf<VaultState>()
        val observation = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            fixture.service.state(VAULT_ID).toList(states)
        }
        fixture.service.provision(request())
        assertThat(fixture.service.unlock(VAULT_ID)).isTrue()
        assertThat(states).containsExactly(VaultState.UNLOCKED)
        fixture.service.lock(VAULT_ID)
        assertThat(states.last()).isEqualTo(VaultState.LOCKED)
        assertThat(fixture.service.isUnlocked(VAULT_ID)).isFalse()
        observation.cancel()
    }

    @Test
    fun `reprovision authentication failure revokes an already unlocked vault`() = runTest {
        val fixture = fixture()
        fixture.prepare()
        assertThat(captureFailure {
            fixture.service.provision(request().copy(accountIdHash = accountIdHash("another-account")))
        }).isInstanceOf(SecureObjectAuthenticationException::class.java)
        assertThat(fixture.service.isUnlocked(VAULT_ID)).isFalse()
        assertThat(fixture.service.state(VAULT_ID).first()).isEqualTo(VaultState.ERROR)
    }

    @Test
    fun `provision creates one account key and every domain key while remaining locked`() = runTest {
        val fixture = fixture()

        val descriptor = fixture.service.provision(request())

        assertThat(descriptor.keyVersion).isEqualTo(1)
        assertThat(fixture.service.state(VAULT_ID).first()).isEqualTo(VaultState.LOCKED)
        assertThat(fixture.service.isUnlocked(VAULT_ID)).isFalse()
        val files = temporaryFolder.root.walkTopDown().filter(File::isFile).toList()
        assertThat(files.count { it.extension == "yzdk" }).isEqualTo(VaultKeyDomain.entries.size)
        assertThat(files.map(File::getName)).containsAtLeast("manifest.yzav", "${sha256Hex(VAULT_ID.value)}.yzvk")
    }

    @Test
    fun `lock releases the account key and blocks wrapping and unwrapping`() = runTest {
        val fixture = fixture()
        fixture.prepare()
        val binding = "object-binding".encodeToByteArray()
        val wrapped = fixture.service.wrapKey(VAULT_ID, VaultKeyDomain.MEDIA, ByteArray(32) { it.toByte() }, binding)

        fixture.service.lock(VAULT_ID)

        assertThat(fixture.service.isUnlocked(VAULT_ID)).isFalse()
        assertThat(captureFailure {
            fixture.service.wrapKey(VAULT_ID, VaultKeyDomain.MEDIA, ByteArray(32), binding)
        }).isInstanceOf(SecureStorageKeyUnavailableException::class.java)
        assertThat(captureFailure {
            fixture.service.unwrapKey(VAULT_ID, wrapped, binding)
        }).isInstanceOf(SecureStorageKeyUnavailableException::class.java)
    }

    @Test
    fun `a restarted process must explicitly unlock before decrypting`() = runTest {
        val roots = roots()
        val backend = TestDeviceRootKeyBackend()
        val first = fixture(roots, backend)
        first.prepare()
        val binding = byteArrayOf(1, 2, 3)
        val plaintext = ByteArray(32) { (it + 7).toByte() }
        val wrapped = first.service.wrapKey(VAULT_ID, VaultKeyDomain.MEDIA, plaintext, binding)

        val restarted = fixture(roots, backend)

        assertThat(captureFailure {
            restarted.service.unwrapKey(VAULT_ID, wrapped, binding)
        }).isInstanceOf(SecureStorageKeyUnavailableException::class.java)
        assertThat(restarted.service.unlock(VAULT_ID)).isTrue()
        assertThat(restarted.service.unwrapKey(VAULT_ID, wrapped, binding)).isEqualTo(plaintext)
    }

    @Test
    fun `wrapped keys are bound to immutable object metadata`() = runTest {
        val fixture = fixture()
        fixture.prepare()
        val wrapped = fixture.service.wrapKey(VAULT_ID, VaultKeyDomain.MEDIA, ByteArray(32) { 9 }, byteArrayOf(1))

        assertThat(captureFailure {
            fixture.service.unwrapKey(VAULT_ID, wrapped, byteArrayOf(2))
        }).isInstanceOf(SecureObjectAuthenticationException::class.java)
    }

    @Test
    fun `rotation advances the active domain version without orphaning existing objects`() = runTest {
        val fixture = fixture()
        fixture.prepare()
        val firstPlaintext = ByteArray(32) { 1 }
        val secondPlaintext = ByteArray(32) { 2 }
        val binding = "rotation-binding".encodeToByteArray()
        val first = fixture.service.wrapKey(VAULT_ID, VaultKeyDomain.MEDIA, firstPlaintext, binding)

        val descriptor = fixture.service.rotate(VAULT_ID, KeyRotationReason.SCHEDULED)
        val second = fixture.service.wrapKey(VAULT_ID, VaultKeyDomain.MEDIA, secondPlaintext, binding)

        assertThat(descriptor.keyVersion).isEqualTo(2)
        assertThat(first.vaultKeyVersion).isEqualTo(1)
        assertThat(second.vaultKeyVersion).isEqualTo(2)
        assertThat(fixture.service.unwrapKey(VAULT_ID, first, binding)).isEqualTo(firstPlaintext)
        assertThat(fixture.service.unwrapKey(VAULT_ID, second, binding)).isEqualTo(secondPlaintext)
    }

    @Test
    fun `batch six direct account key envelopes remain readable during migration`() = runTest {
        val fixture = fixture()
        fixture.prepare()
        val binding = "batch-six-binding".encodeToByteArray()
        val plaintext = ByteArray(32) { 6 }
        val accountKeyEnvelope = requireNotNull(fixture.envelopeStore.read(VAULT_ID))
        val accountKey = fixture.deviceRootKeyService.unwrapKey(accountKeyEnvelope, fixture.envelopeStore.binding(VAULT_ID))
        val legacy = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(accountKey, "AES"))
                updateAAD(binding)
            }
            VaultWrappedKey(1, 1, VaultKeyDomain.MEDIA, cipher.iv, cipher.doFinal(plaintext))
        } finally {
            accountKey.fill(0)
        }

        assertThat(fixture.service.unwrapKey(VAULT_ID, legacy, binding)).isEqualTo(plaintext)
    }

    @Test
    fun `destroy tombstones the vault and makes deleted key material unrecoverable`() = runTest {
        val fixture = fixture()
        fixture.prepare()

        assertThat(fixture.service.destroy(VAULT_ID, VaultDestroyReason.ACCOUNT_LOGOUT)).isTrue()

        val files = temporaryFolder.root.walkTopDown().filter(File::isFile).toList()
        assertThat(files.any { it.extension == "yzdk" || it.extension == "yzvk" }).isFalse()
        assertThat(files.map(File::getName)).contains("manifest.yzav")
        assertThat(captureFailure { fixture.service.provision(request()) })
            .isInstanceOf(SecureVaultDestroyedException::class.java)
        assertThat(captureFailure { fixture.service.unlock(VAULT_ID) })
            .isInstanceOf(SecureVaultDestroyedException::class.java)
    }

    @Test
    fun `trusted hardware policy rejects a software-only device`() = runTest {
        val fixture = fixture(backend = TestDeviceRootKeyBackend(HardwareSecurityLevel.SOFTWARE))

        assertThat(captureFailure { fixture.service.provision(request(requireTrustedHardware = true)) })
            .isInstanceOf(SecureStorageKeyUnavailableException::class.java)
        assertThat(fixture.service.state(VAULT_ID).first()).isEqualTo(VaultState.ERROR)
    }

    @Test
    fun `signed managed review may bootstrap a software-backed credential vault without protected access`() = runTest {
        val fixture = fixture(
            backend = TestDeviceRootKeyBackend(HardwareSecurityLevel.SOFTWARE),
            deviceTrustState = DeviceTrustState.MANAGED,
        )

        val descriptor = fixture.service.provision(request(requireTrustedHardware = true))

        assertThat(descriptor.requiresTrustedHardware).isTrue()
        assertThat(fixture.service.unlock(VAULT_ID)).isTrue()
    }

    @Test
    fun `trusted hardware policy permits local bootstrap while remote attestation is pending`() = runTest {
        val fixture = fixture(deviceTrustState = DeviceTrustState.PENDING)

        val descriptor = fixture.service.provision(request(requireTrustedHardware = true))

        assertThat(descriptor.requiresTrustedHardware).isTrue()
        assertThat(fixture.service.state(VAULT_ID).first()).isEqualTo(VaultState.LOCKED)
    }

    @Test
    fun `local vault rejects a restrictive device trust state even without hardware policy`() = runTest {
        val fixture = fixture(deviceTrustState = DeviceTrustState.INTEGRITY_WARNING)

        assertThat(captureFailure { fixture.service.provision(request(requireTrustedHardware = false)) })
            .isInstanceOf(SecureStorageKeyUnavailableException::class.java)
        assertThat(fixture.service.state(VAULT_ID).first()).isEqualTo(VaultState.ERROR)
    }

    private fun fixture(
        roots: SecureStorageRoots = roots(),
        backend: TestDeviceRootKeyBackend = TestDeviceRootKeyBackend(),
        deviceTrustState: DeviceTrustState = DeviceTrustState.TRUSTED,
    ): Fixture {
        val deviceRootKeyService = DefaultDeviceRootKeyService(
            InstallationIdentityStore(roots),
            DeviceRootKeyRecordStore(roots),
            backend,
        )
        val envelopeStore = VaultKeyEnvelopeStore(roots)
        return Fixture(
            service = DefaultVaultKeyService(
                deviceRootKeyService = deviceRootKeyService,
                deviceTrustService = FakeDeviceTrustService(
                    DeviceTrustSnapshot(
                        installationId = "test-installation",
                        hardwareSecurityLevel = backend.level,
                        trustState = deviceTrustState,
                        attestationVersion = 1,
                        lastVerifiedAtEpochMillis = 1,
                    )
                ),
                vaultKeyEnvelopeStore = envelopeStore,
                accountVaultRecordStore = AccountVaultRecordStore(roots),
                legacyVaultKeyService = NoOpLegacyVaultKeyServiceForVaultTest,
            ),
            deviceRootKeyService = deviceRootKeyService,
            envelopeStore = envelopeStore,
        )
    }

    private fun roots(): SecureStorageRoots = object : SecureStorageRoots {
        override fun persistentRoot(): File = File(temporaryFolder.root, "persistent")
        override fun runtimeRoot(): File = File(temporaryFolder.root, "runtime")
    }

    private fun request(requireTrustedHardware: Boolean = false) = VaultProvisioningRequest(
        vaultId = VAULT_ID,
        accountIdHash = accountIdHash("@alice:example.org"),
        requireUserAuthentication = true,
        requireTrustedHardware = requireTrustedHardware,
    )

    private suspend fun captureFailure(block: suspend () -> Unit): Throwable? = try {
        block()
        null
    } catch (failure: Throwable) {
        failure
    }

    private data class Fixture(
        val service: DefaultVaultKeyService,
        val deviceRootKeyService: DefaultDeviceRootKeyService,
        val envelopeStore: VaultKeyEnvelopeStore,
    ) {
        suspend fun prepare() {
            service.provision(
                VaultProvisioningRequest(
                    vaultId = VAULT_ID,
                    accountIdHash = accountIdHash("@alice:example.org"),
                    requireUserAuthentication = true,
                    requireTrustedHardware = false,
                )
            )
            check(service.unlock(VAULT_ID))
        }
    }

    private companion object {
        val VAULT_ID = AccountVaultId("matrix-device-v1:account-a")
    }
}

private object NoOpLegacyVaultKeyServiceForVaultTest : LegacyVaultKeyService {
    override fun unwrap(vaultId: AccountVaultId, wrappedObjectKey: WrappedObjectKey, associatedData: ByteArray) =
        throw SecureStorageKeyUnavailableException()

    override fun destroy(vaultId: AccountVaultId): Boolean = true
}
