/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.securestorage.test

import io.element.android.security.securestorage.api.DeviceRootKeyDescriptor
import io.element.android.security.securestorage.api.DeviceRootKeyService
import io.element.android.security.securestorage.api.DeviceWrappedKey
import io.element.android.security.securestorage.api.HardwareSecurityLevel
import io.element.android.security.securestorage.api.SecureStorageKeyUnavailableException
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class FakeDeviceRootKeyService(
    private val installationId: String = "fake-installation",
    private val hardwareSecurityLevel: HardwareSecurityLevel = HardwareSecurityLevel.UNKNOWN,
) : DeviceRootKeyService {
    private var rootKey: SecretKey? = newKey()
    private val installationIdSha256 = MessageDigest.getInstance("SHA-256").digest(installationId.encodeToByteArray())

    override suspend fun ensureReady(): DeviceRootKeyDescriptor {
        if (rootKey == null) throw SecureStorageKeyUnavailableException()
        return DeviceRootKeyDescriptor(installationId, 1, hardwareSecurityLevel, strongBoxRequested = false)
    }

    override suspend fun wrapKey(keyMaterial: ByteArray, binding: ByteArray): DeviceWrappedKey {
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, rootKey ?: throw SecureStorageKeyUnavailableException())
            updateAAD(binding)
        }
        return DeviceWrappedKey(1, 1, installationIdSha256, cipher.iv, cipher.doFinal(keyMaterial))
    }

    override suspend fun unwrapKey(wrappedKey: DeviceWrappedKey, binding: ByteArray): ByteArray =
        Cipher.getInstance(AES_GCM_TRANSFORMATION).run {
            init(
                Cipher.DECRYPT_MODE,
                rootKey ?: throw SecureStorageKeyUnavailableException(),
                GCMParameterSpec(GCM_TAG_LENGTH_BITS, wrappedKey.initializationVector),
            )
            updateAAD(binding)
            doFinal(wrappedKey.ciphertext)
        }

    override suspend fun destroy(): Boolean {
        rootKey = null
        return true
    }

    private fun newKey(): SecretKey = KeyGenerator.getInstance(AES_ALGORITHM).run {
        init(256)
        generateKey()
    }

    private companion object {
        const val AES_ALGORITHM = "AES"
        const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128
    }
}
