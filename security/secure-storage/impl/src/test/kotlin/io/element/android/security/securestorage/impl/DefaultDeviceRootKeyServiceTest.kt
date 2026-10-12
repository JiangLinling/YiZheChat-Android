/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.securestorage.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.security.securestorage.api.HardwareSecurityLevel
import io.element.android.security.securestorage.api.SecureObjectAuthenticationException
import io.element.android.security.securestorage.api.SecureStorageKeyUnavailableException
import io.element.android.security.securestorage.api.SecureStorageRoots
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class DefaultDeviceRootKeyServiceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `creates stable installation identity and reports actual backend level`() = runTest {
        val backend = TestDeviceRootKeyBackend(HardwareSecurityLevel.STRONGBOX, strongBoxRequested = true)
        val service = service(roots(), backend)

        val first = service.ensureReady()
        val second = service.ensureReady()

        assertThat(first).isEqualTo(second)
        assertThat(first.installationId).hasLength(64)
        assertThat(first.hardwareSecurityLevel).isEqualTo(HardwareSecurityLevel.STRONGBOX)
        assertThat(first.hardwareBacked).isTrue()
        assertThat(first.strongBoxRequested).isTrue()
        assertThat(temporaryFolder.root.walkTopDown().filter(File::isFile).map(File::getName).toList())
            .containsAtLeast("installation-id.yzid", "device-root.yzdrk")
    }

    @Test
    fun `wrapped keys are installation and purpose bound`() = runTest {
        val service = service(roots(), TestDeviceRootKeyBackend())
        val keyMaterial = ByteArray(32).also(SecureRandom()::nextBytes)
        val binding = "account-a-media-key".encodeToByteArray()

        val wrapped = service.wrapKey(keyMaterial, binding)

        assertThat(service.unwrapKey(wrapped, binding)).isEqualTo(keyMaterial)
        val failure = captureFailure { service.unwrapKey(wrapped, "account-b-media-key".encodeToByteArray()) }
        assertThat(failure).isInstanceOf(SecureObjectAuthenticationException::class.java)
    }

    @Test
    fun `copied no-backup records without the old keystore key fail closed`() = runTest {
        val roots = roots()
        val firstBackend = TestDeviceRootKeyBackend()
        val firstService = service(roots, firstBackend)
        val wrapped = firstService.wrapKey(ByteArray(32) { it.toByte() }, "vault".encodeToByteArray())
        val restoredService = service(roots, TestDeviceRootKeyBackend())

        assertThat(captureFailure { restoredService.ensureReady() })
            .isInstanceOf(SecureStorageKeyUnavailableException::class.java)
        assertThat(captureFailure { restoredService.unwrapKey(wrapped, "vault".encodeToByteArray()) })
            .isInstanceOf(SecureStorageKeyUnavailableException::class.java)
    }

    @Test
    fun `tampered installation identity is rejected`() = runTest {
        val service = service(roots(), TestDeviceRootKeyBackend())
        service.ensureReady()
        val identityFile = temporaryFolder.root.walkTopDown().first { it.name == "installation-id.yzid" }
        identityFile.flipLastByteForDeviceRootTest()

        assertThat(captureFailure { service.ensureReady() })
            .isInstanceOf(SecureObjectAuthenticationException::class.java)
    }

    @Test
    fun `destroy writes an irreversible local tombstone and removes the backend key`() = runTest {
        val backend = TestDeviceRootKeyBackend()
        val service = service(roots(), backend)
        val wrapped = service.wrapKey(ByteArray(32) { 7 }, "vault".encodeToByteArray())

        assertThat(service.destroy()).isTrue()
        assertThat(backend.keyCount).isEqualTo(0)
        assertThat(captureFailure { service.ensureReady() }).isInstanceOf(SecureStorageKeyUnavailableException::class.java)
        assertThat(captureFailure { service.unwrapKey(wrapped, "vault".encodeToByteArray()) })
            .isInstanceOf(SecureStorageKeyUnavailableException::class.java)
    }

    private fun service(roots: SecureStorageRoots, backend: DeviceRootKeyBackend) = DefaultDeviceRootKeyService(
        installationIdentityStore = InstallationIdentityStore(roots),
        recordStore = DeviceRootKeyRecordStore(roots),
        backend = backend,
    )

    private fun roots(): SecureStorageRoots = object : SecureStorageRoots {
        override fun persistentRoot(): File = File(temporaryFolder.root, "persistent")
        override fun runtimeRoot(): File = File(temporaryFolder.root, "runtime")
    }

    private suspend fun captureFailure(block: suspend () -> Unit): Throwable? = try {
        block()
        null
    } catch (failure: Throwable) {
        failure
    }
}

internal class TestDeviceRootKeyBackend(
    val level: HardwareSecurityLevel = HardwareSecurityLevel.TRUSTED_EXECUTION_ENVIRONMENT,
    private val strongBoxRequested: Boolean = false,
) : DeviceRootKeyBackend {
    private val keys = mutableMapOf<String, SecretKey>()
    val keyCount: Int get() = keys.size

    override fun load(alias: String): DeviceRootKeyBackendDescriptor? = keys[alias]?.let {
        DeviceRootKeyBackendDescriptor(level, strongBoxRequested)
    }

    override fun create(alias: String): DeviceRootKeyBackendDescriptor {
        keys.getOrPut(alias) {
            KeyGenerator.getInstance("AES").run {
                init(256)
                generateKey()
            }
        }
        return requireNotNull(load(alias))
    }

    override fun wrap(alias: String, keyMaterial: ByteArray, associatedData: ByteArray): BackendWrappedKey {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, requireNotNull(keys[alias]))
            updateAAD(associatedData)
        }
        return BackendWrappedKey(cipher.iv, cipher.doFinal(keyMaterial))
    }

    override fun unwrap(alias: String, wrappedKey: BackendWrappedKey, associatedData: ByteArray): ByteArray {
        val key = keys[alias] ?: throw SecureStorageKeyUnavailableException()
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, wrappedKey.initializationVector))
            updateAAD(associatedData)
            doFinal(wrappedKey.ciphertext)
        }
    }

    override fun destroy(alias: String): Boolean {
        keys.remove(alias)
        return true
    }
}

private fun File.flipLastByteForDeviceRootTest() {
    val bytes = readBytes()
    bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
    writeBytes(bytes)
}
