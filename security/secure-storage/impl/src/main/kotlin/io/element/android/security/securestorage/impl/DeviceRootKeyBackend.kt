/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.securestorage.impl

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.security.securestorage.api.HardwareSecurityLevel
import io.element.android.security.securestorage.api.SecureObjectAuthenticationException
import io.element.android.security.securestorage.api.SecureStorageKeyUnavailableException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

data class DeviceRootKeyBackendDescriptor(
    val hardwareSecurityLevel: HardwareSecurityLevel,
    val strongBoxRequested: Boolean,
)

data class BackendWrappedKey(
    val initializationVector: ByteArray,
    val ciphertext: ByteArray,
)

interface DeviceRootKeyBackend {
    fun load(alias: String): DeviceRootKeyBackendDescriptor?

    fun create(alias: String): DeviceRootKeyBackendDescriptor

    fun wrap(alias: String, keyMaterial: ByteArray, associatedData: ByteArray): BackendWrappedKey

    fun unwrap(alias: String, wrappedKey: BackendWrappedKey, associatedData: ByteArray): ByteArray

    fun destroy(alias: String): Boolean
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class AndroidDeviceRootKeyBackend(
    @ApplicationContext private val context: Context,
) : DeviceRootKeyBackend {
    private val keyStores = ReloadingAndroidKeyStore()

    override fun load(alias: String): DeviceRootKeyBackendDescriptor? {
        val key = keyStores.read { it.getKey(alias, null) as? SecretKey } ?: return null
        return DeviceRootKeyBackendDescriptor(
            hardwareSecurityLevel = hardwareSecurityLevel(key),
            strongBoxRequested = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                hardwareSecurityLevel(key) == HardwareSecurityLevel.STRONGBOX,
        )
    }

    override fun create(alias: String): DeviceRootKeyBackendDescriptor {
        load(alias)?.let { return it }
        val requestStrongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        if (requestStrongBox) {
            try {
                createStrongBoxKey(alias)
                return requireNotNull(load(alias)).copy(strongBoxRequested = true)
            } catch (_: Exception) {
                // The advertised StrongBox can be out of slots or unavailable. Fall through to the default Keystore.
                destroy(alias)
            }
        }
        generate(alias, strongBox = false)
        return requireNotNull(load(alias))
    }

    override fun wrap(alias: String, keyMaterial: ByteArray, associatedData: ByteArray): BackendWrappedKey = try {
        val key = requireKey(alias)
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key)
            updateAAD(associatedData)
        }
        BackendWrappedKey(cipher.iv, cipher.doFinal(keyMaterial))
    } catch (failure: Exception) {
        throw SecureStorageKeyUnavailableException(failure)
    }

    override fun unwrap(alias: String, wrappedKey: BackendWrappedKey, associatedData: ByteArray): ByteArray = try {
        val key = requireKey(alias)
        Cipher.getInstance(AES_GCM_TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, wrappedKey.initializationVector))
            updateAAD(associatedData)
            doFinal(wrappedKey.ciphertext)
        }
    } catch (failure: SecureStorageKeyUnavailableException) {
        throw failure
    } catch (failure: Exception) {
        throw SecureObjectAuthenticationException(failure)
    }

    override fun destroy(alias: String): Boolean = try {
        keyStores.read { keyStore ->
            if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
            (!keyStore.containsAlias(alias)).takeIf { it }
        } == true
    } catch (_: Exception) {
        false
    }

    private fun createStrongBoxKey(alias: String) {
        generate(alias, strongBox = true)
    }

    private fun generate(alias: String, strongBox: Boolean) {
        val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(ROOT_KEY_SIZE_BITS)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) builder.setIsStrongBoxBacked(strongBox)
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(builder.build())
            generateKey()
        }
    }

    private fun requireKey(alias: String): SecretKey = keyStores.read { it.getKey(alias, null) as? SecretKey }
        ?: throw SecureStorageKeyUnavailableException()

    @Suppress("DEPRECATION")
    private fun hardwareSecurityLevel(key: SecretKey): HardwareSecurityLevel = try {
        val keyInfo = SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE).getKeySpec(key, KeyInfo::class.java) as KeyInfo
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            when (keyInfo.getSecurityLevel()) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> HardwareSecurityLevel.STRONGBOX
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> HardwareSecurityLevel.TRUSTED_EXECUTION_ENVIRONMENT
                // Android defines UNKNOWN_SECURE as being at least equivalent to a TEE.
                KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE -> HardwareSecurityLevel.TRUSTED_EXECUTION_ENVIRONMENT
                KeyProperties.SECURITY_LEVEL_SOFTWARE -> HardwareSecurityLevel.SOFTWARE
                else -> HardwareSecurityLevel.UNKNOWN
            }
        } else if (keyInfo.isInsideSecureHardware()) {
            HardwareSecurityLevel.TRUSTED_EXECUTION_ENVIRONMENT
        } else {
            HardwareSecurityLevel.SOFTWARE
        }
    } catch (_: Exception) {
        HardwareSecurityLevel.UNKNOWN
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        const val ROOT_KEY_SIZE_BITS = 256
        const val GCM_TAG_LENGTH_BITS = 128
    }
}
