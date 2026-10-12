/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.contentprotection.impl

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.security.contentprotection.api.ContentId
import io.element.android.security.contentprotection.api.ContentProtectionException
import io.element.android.security.contentprotection.api.ContentProtectionFormat
import io.element.android.security.contentprotection.api.DeviceRuntimeBindingProvider
import io.element.android.security.contentprotection.api.DeviceShareEnvelope
import io.element.android.security.contentprotection.api.DeviceShareKeyDescriptor
import io.element.android.security.contentprotection.api.DeviceShareKeyService
import io.element.android.security.contentprotection.api.DeviceShareRecipient
import io.element.android.security.securestorage.api.DeviceRootKeyService
import io.element.android.security.securestorage.api.HardwareSecurityLevel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class AndroidDeviceShareKeyService(
    @ApplicationContext private val context: Context,
    private val deviceRootKeyService: DeviceRootKeyService,
) : DeviceShareKeyService {
    private val keyStores = ReloadingContentKeyStore()
    private val keyPresenceStore = context.getSharedPreferences(KEY_PRESENCE_STORE, Context.MODE_PRIVATE)

    override suspend fun descriptor(): DeviceShareKeyDescriptor = try {
        val root = deviceRootKeyService.ensureReady()
        val alias = aliasFor(root.installationId)
        val material = loadKeyMaterial(alias) ?: run {
            // Once a share key has been observed, its disappearance is a security event. Never generate a
            // replacement behind the server registry's back: old envelopes are bound to the original key id.
            if (keyPresenceStore.getBoolean(alias, false)) throw ContentProtectionException.DeviceKeyUnavailable()
            createKey(alias)
            loadKeyMaterial(alias) ?: throw ContentProtectionException.DeviceKeyUnavailable()
        }
        if (!keyPresenceStore.getBoolean(alias, false)) {
            check(keyPresenceStore.edit().putBoolean(alias, true).commit()) {
                "Unable to persist the device share key presence marker."
            }
        }
        val (publicKey, privateKey) = material
        DeviceShareKeyDescriptor(
            installationId = root.installationId,
            keyId = keyId(publicKey.encoded),
            algorithm = ContentProtectionFormat.DEVICE_SHARE_ALGORITHM,
            publicKeyDer = publicKey.encoded.copyOf(),
            hardwareSecurityLevel = hardwareSecurityLevel(privateKey),
        )
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (failure: ContentProtectionException) {
        throw failure
    } catch (failure: Exception) {
        throw ContentProtectionException.DeviceKeyUnavailable(failure)
    }

    override suspend fun wrapShare(
        recipient: DeviceShareRecipient,
        contentId: ContentId,
        immutableContextSha256: ByteArray,
        share: ByteArray,
    ): DeviceShareEnvelope = try {
        require(recipient.algorithm == ContentProtectionFormat.DEVICE_SHARE_ALGORITHM)
        require(immutableContextSha256.size == ContentProtectionFormat.SHA256_SIZE_BYTES)
        require(share.size == ContentProtectionFormat.SHARE_SIZE_BYTES)
        require(recipient.keyId == keyId(recipient.publicKeyDer))
        val publicKey = KeyFactory.getInstance(RSA_ALGORITHM).generatePublic(X509EncodedKeySpec(recipient.publicKeyDer))
        require((publicKey as? RSAPublicKey)?.modulus?.bitLength() ?: 0 >= RSA_KEY_SIZE_BITS)
        val payload = encodeShare(contentId, immutableContextSha256, share)
        try {
            DeviceShareEnvelope(
                recipientInstallationId = recipient.installationId,
                recipientKeyId = recipient.keyId,
                algorithm = recipient.algorithm,
                ciphertext = rsaCipher(Cipher.ENCRYPT_MODE, publicKey).doFinal(payload),
            )
        } finally {
            payload.fill(0)
        }
    } catch (failure: Exception) {
        throw ContentProtectionException.DeviceKeyUnavailable(failure)
    }

    override suspend fun unwrapShare(
        envelope: DeviceShareEnvelope,
        contentId: ContentId,
        immutableContextSha256: ByteArray,
    ): ByteArray = try {
        val local = descriptor()
        require(envelope.recipientInstallationId == local.installationId)
        require(envelope.recipientKeyId == local.keyId)
        require(envelope.algorithm == ContentProtectionFormat.DEVICE_SHARE_ALGORITHM)
        val alias = aliasFor(local.installationId)
        val privateKey = loadKeyMaterial(alias)?.second ?: throw ContentProtectionException.DeviceKeyUnavailable()
        val payload = rsaCipher(Cipher.DECRYPT_MODE, privateKey).doFinal(envelope.ciphertext)
        try {
            decodeShare(payload, contentId, immutableContextSha256)
        } finally {
            payload.fill(0)
        }
    } catch (failure: ContentProtectionException) {
        throw failure
    } catch (failure: Exception) {
        throw ContentProtectionException.AuthenticationFailed(failure)
    }

    override suspend fun destroy(): Boolean = try {
        val installationId = deviceRootKeyService.ensureReady().installationId
        val alias = aliasFor(installationId)
        val destroyed = keyStores.read { keyStore ->
            if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
            !keyStore.containsAlias(alias)
        }
        if (destroyed) keyPresenceStore.edit().remove(alias).apply()
        destroyed
    } catch (_: Exception) {
        false
    }

    private fun loadKeyMaterial(alias: String): Pair<java.security.PublicKey, PrivateKey>? =
        keyStores.readPresent { keyStore ->
            val publicKey = keyStore.getCertificate(alias)?.publicKey ?: return@readPresent null
            val privateKey = keyStore.getKey(alias, null) as? PrivateKey ?: return@readPresent null
            publicKey to privateKey
        }

    private fun createKey(alias: String) {
        val requestStrongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        if (requestStrongBox) {
            try {
                generateKey(alias, strongBox = true)
                return
            } catch (_: StrongBoxUnavailableException) {
                loadKeyMaterial(alias)?.let { return }
            } catch (_: Exception) {
                loadKeyMaterial(alias)?.let { return }
            }
        }
        generateKey(alias, strongBox = false)
    }

    private fun generateKey(alias: String, strongBox: Boolean) {
        val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(RSA_KEY_SIZE_BITS)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) builder.setIsStrongBoxBacked(strongBox)
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE).run {
            initialize(builder.build())
            generateKeyPair()
        }
    }

    private fun rsaCipher(mode: Int, key: java.security.Key): Cipher = Cipher.getInstance(RSA_OAEP_TRANSFORMATION).apply {
        init(mode, key, OAEP_SPEC)
    }

    private fun encodeShare(contentId: ContentId, contextSha256: ByteArray, share: ByteArray): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(SHARE_MAGIC)
                output.write(sha256(contentId.value.encodeToByteArray()))
                output.write(contextSha256)
                output.writeInt(share.size)
                output.write(share)
            }
            bytes.toByteArray()
        }

    private fun decodeShare(payload: ByteArray, contentId: ContentId, contextSha256: ByteArray): ByteArray =
        DataInputStream(ByteArrayInputStream(payload)).use { input ->
            require(input.readExact(SHARE_MAGIC.size).contentEquals(SHARE_MAGIC))
            require(MessageDigest.isEqual(input.readExact(ContentProtectionFormat.SHA256_SIZE_BYTES), sha256(contentId.value.encodeToByteArray())))
            require(MessageDigest.isEqual(input.readExact(ContentProtectionFormat.SHA256_SIZE_BYTES), contextSha256))
            require(input.readInt() == ContentProtectionFormat.SHARE_SIZE_BYTES)
            val share = input.readExact(ContentProtectionFormat.SHARE_SIZE_BYTES)
            require(input.read() == -1)
            share
        }

    private fun aliasFor(installationId: String): String = DEVICE_SHARE_ALIAS_PREFIX + keyId(installationId.encodeToByteArray()).take(24)

    private fun keyId(publicKeyDer: ByteArray): String = sha256(publicKeyDer).toHex()

    private fun sha256(value: ByteArray): ByteArray = MessageDigest.getInstance(SHA256).digest(value)

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    @Suppress("DEPRECATION")
    private fun hardwareSecurityLevel(privateKey: PrivateKey): HardwareSecurityLevel = try {
        val info = KeyFactory.getInstance(privateKey.algorithm, ANDROID_KEYSTORE)
            .getKeySpec(privateKey, KeyInfo::class.java) as KeyInfo
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            when (info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> HardwareSecurityLevel.STRONGBOX
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> HardwareSecurityLevel.TRUSTED_EXECUTION_ENVIRONMENT
                // Android defines UNKNOWN_SECURE as being at least equivalent to a TEE.
                KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE -> HardwareSecurityLevel.TRUSTED_EXECUTION_ENVIRONMENT
                KeyProperties.SECURITY_LEVEL_SOFTWARE -> HardwareSecurityLevel.SOFTWARE
                else -> HardwareSecurityLevel.UNKNOWN
            }
        } else if (info.isInsideSecureHardware) {
            HardwareSecurityLevel.TRUSTED_EXECUTION_ENVIRONMENT
        } else {
            HardwareSecurityLevel.SOFTWARE
        }
    } catch (_: Exception) {
        HardwareSecurityLevel.UNKNOWN
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val RSA_ALGORITHM = "RSA"
        const val RSA_KEY_SIZE_BITS = 3072
        const val RSA_OAEP_TRANSFORMATION = "RSA/ECB/OAEPPadding"
        const val SHA256 = "SHA-256"
        const val KEY_PRESENCE_STORE = "yizhechat.content.share.keys.v1"
        const val DEVICE_SHARE_ALIAS_PREFIX = "yizhechat.content.share.v1."
        val SHARE_MAGIC = "YZDS0001".encodeToByteArray()
        val OAEP_SPEC = OAEPParameterSpec(
            SHA256,
            "MGF1",
            MGF1ParameterSpec.SHA1,
            PSource.PSpecified.DEFAULT,
        )
    }
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class AndroidDeviceRuntimeBindingProvider(
    @ApplicationContext private val context: Context,
) : DeviceRuntimeBindingProvider {
    override fun bootCount(): Long = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT).toLong()
    } catch (_: Exception) {
        UNKNOWN_BOOT_COUNT
    }

    private companion object {
        const val UNKNOWN_BOOT_COUNT = -1L
    }
}
