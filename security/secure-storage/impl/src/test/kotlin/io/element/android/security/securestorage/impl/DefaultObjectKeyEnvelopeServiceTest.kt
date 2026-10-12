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
import io.element.android.security.securestorage.api.SecureObjectAuthenticationException
import io.element.android.security.securestorage.api.SecureStorageKeyUnavailableException
import io.element.android.security.securestorage.api.SecureStorageRoots
import io.element.android.security.securestorage.api.SecureVaultDestroyedException
import io.element.android.security.securestorage.api.VaultProvisioningRequest
import io.element.android.security.securestorage.api.accountIdHash
import io.element.android.security.securestorage.test.FakeDeviceTrustService
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class DefaultObjectKeyEnvelopeServiceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `new object key uses the account media domain hierarchy`() = runTest {
        val fixture = fixture()
        val vaultId = AccountVaultId("account-a")
        fixture.prepare(vaultId, "@alice:example.org")
        val objectKey = newAesKey()
        val associatedData = "immutable-object-core".encodeToByteArray()

        val wrapped = fixture.objectKeyService.wrap(vaultId, objectKey, associatedData)
        val restored = fixture.objectKeyService.unwrap(vaultId, wrapped, associatedData)

        assertThat(wrapped.wrappingScheme).isEqualTo(ObjectKeyWrappingScheme.ACCOUNT_VAULT_DOMAIN)
        assertThat(wrapped.vaultKeyVersion).isEqualTo(1)
        assertThat(restored.encoded).isEqualTo(objectKey.encoded)
        assertThat(temporaryFolder.root.walkTopDown().filter(File::isFile).map(File::getName).toList())
            .containsAtLeast("installation-id.yzid", "device-root.yzdrk", "${sha256Hex(vaultId.value)}.yzvk")
    }

    @Test
    fun `vault envelope cannot be used for another account`() = runTest {
        val fixture = fixture()
        val objectKey = newAesKey()
        val vaultA = AccountVaultId("account-a")
        val vaultB = AccountVaultId("account-b")
        fixture.prepare(vaultA, "@alice:example.org")
        fixture.prepare(vaultB, "@bob:example.org")
        val wrapped = fixture.objectKeyService.wrap(vaultA, objectKey, byteArrayOf(1, 2, 3))

        assertThat(captureFailure {
            fixture.objectKeyService.unwrap(vaultB, wrapped, byteArrayOf(1, 2, 3))
        }).isInstanceOf(SecureObjectAuthenticationException::class.java)
    }

    @Test
    fun `destroying a vault envelope is a cryptographic erase for its objects`() = runTest {
        val fixture = fixture()
        val vaultId = AccountVaultId("account-a")
        fixture.prepare(vaultId, "@alice:example.org")
        val wrapped = fixture.objectKeyService.wrap(vaultId, newAesKey(), byteArrayOf(4, 5, 6))

        assertThat(fixture.objectKeyService.destroyVaultKey(vaultId)).isTrue()
        assertThat(captureFailure {
            fixture.objectKeyService.unwrap(vaultId, wrapped, byteArrayOf(4, 5, 6))
        }).isInstanceOf(SecureVaultDestroyedException::class.java)
    }

    @Test
    fun `destroying the device root key invalidates every vault envelope`() = runTest {
        val fixture = fixture()
        val vaultId = AccountVaultId("account-a")
        fixture.prepare(vaultId, "@alice:example.org")
        val wrapped = fixture.objectKeyService.wrap(vaultId, newAesKey(), byteArrayOf(7, 8, 9))

        assertThat(fixture.deviceRootKeyService.destroy()).isTrue()
        fixture.vaultKeyService.lock(vaultId)
        assertThat(captureFailure {
            fixture.objectKeyService.unwrap(vaultId, wrapped, byteArrayOf(7, 8, 9))
        }).isInstanceOf(SecureStorageKeyUnavailableException::class.java)
    }

    private fun fixture(): Fixture {
        val roots = object : SecureStorageRoots {
            override fun persistentRoot(): File = File(temporaryFolder.root, "persistent")
            override fun runtimeRoot(): File = File(temporaryFolder.root, "runtime")
        }
        val deviceRootKeyService = DefaultDeviceRootKeyService(
            InstallationIdentityStore(roots),
            DeviceRootKeyRecordStore(roots),
            TestDeviceRootKeyBackend(),
        )
        val vaultKeyService = DefaultVaultKeyService(
            deviceRootKeyService = deviceRootKeyService,
            deviceTrustService = FakeDeviceTrustService(
                DeviceTrustSnapshot(
                    installationId = "test-installation",
                    hardwareSecurityLevel = HardwareSecurityLevel.TRUSTED_EXECUTION_ENVIRONMENT,
                    trustState = DeviceTrustState.TRUSTED,
                    attestationVersion = 1,
                    lastVerifiedAtEpochMillis = 1,
                )
            ),
            vaultKeyEnvelopeStore = VaultKeyEnvelopeStore(roots),
            accountVaultRecordStore = AccountVaultRecordStore(roots),
            legacyVaultKeyService = NoOpLegacyVaultKeyService,
        )
        return Fixture(
            deviceRootKeyService = deviceRootKeyService,
            vaultKeyService = vaultKeyService,
            objectKeyService = DefaultObjectKeyEnvelopeService(
                vaultKeyService,
                NoOpLegacyVaultKeyService,
            ),
        )
    }

    private fun newAesKey(): SecretKey = KeyGenerator.getInstance("AES").run {
        init(256)
        generateKey()
    }

    private suspend fun captureFailure(block: suspend () -> Unit): Throwable? = try {
        block()
        null
    } catch (failure: Throwable) {
        failure
    }

    private data class Fixture(
        val deviceRootKeyService: DefaultDeviceRootKeyService,
        val vaultKeyService: DefaultVaultKeyService,
        val objectKeyService: DefaultObjectKeyEnvelopeService,
    ) {
        suspend fun prepare(vaultId: AccountVaultId, accountId: String) {
            vaultKeyService.provision(
                VaultProvisioningRequest(
                    vaultId = vaultId,
                    accountIdHash = accountIdHash(accountId),
                    requireUserAuthentication = true,
                    requireTrustedHardware = false,
                )
            )
            check(vaultKeyService.unlock(vaultId))
        }
    }
}

private object NoOpLegacyVaultKeyService : LegacyVaultKeyService {
    override fun unwrap(vaultId: AccountVaultId, wrappedObjectKey: WrappedObjectKey, associatedData: ByteArray): SecretKey =
        throw SecureStorageKeyUnavailableException()

    override fun destroy(vaultId: AccountVaultId): Boolean = true
}
