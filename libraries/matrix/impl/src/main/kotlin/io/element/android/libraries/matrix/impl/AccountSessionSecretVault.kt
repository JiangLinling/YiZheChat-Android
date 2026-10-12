/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl

import dev.zacsweers.metro.Inject
import io.element.android.libraries.featureflag.api.FeatureFlagService
import io.element.android.libraries.featureflag.api.FeatureFlags
import io.element.android.libraries.sessionstorage.api.SessionData
import io.element.android.security.securestorage.api.AccountVaultId
import io.element.android.security.securestorage.api.VaultKeyDomain
import io.element.android.security.securestorage.api.VaultKeyService
import io.element.android.security.securestorage.api.VaultProvisioningRequest
import io.element.android.security.securestorage.api.VaultWrappedKey
import io.element.android.security.securestorage.api.accountIdHash
import io.element.android.security.securestorage.api.deriveAccountVaultId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Keeps account credentials and the Matrix store key cryptographically tied to the account Vault.
 *
 * The session database only persists authenticated Vault envelopes. A runtime copy containing plaintext is
 * produced immediately before handing a session to the Rust SDK and is never written back to the session store.
 */
@Inject
class AccountSessionSecretVault(
    private val vaultKeyService: VaultKeyService,
    private val featureFlagService: FeatureFlagService,
) {
    suspend fun protectForStorage(sessionData: SessionData): SessionData {
        val vaultId = prepareVault(sessionData)
        return sessionData.copy(
            accessToken = protect(vaultId, sessionData, SecretField.ACCESS_TOKEN, sessionData.accessToken),
            refreshToken = sessionData.refreshToken?.let { protect(vaultId, sessionData, SecretField.REFRESH_TOKEN, it) },
            oAuthData = sessionData.oAuthData?.let { protect(vaultId, sessionData, SecretField.OAUTH_DATA, it) },
            passphrase = sessionData.passphrase?.let { protect(vaultId, sessionData, SecretField.MATRIX_STORE, it) },
        )
    }

    suspend fun exposeForRuntime(sessionData: SessionData): SessionData {
        val vaultId = prepareVault(sessionData)
        return sessionData.copy(
            accessToken = expose(vaultId, sessionData, SecretField.ACCESS_TOKEN, sessionData.accessToken),
            refreshToken = sessionData.refreshToken?.let { expose(vaultId, sessionData, SecretField.REFRESH_TOKEN, it) },
            oAuthData = sessionData.oAuthData?.let { expose(vaultId, sessionData, SecretField.OAUTH_DATA, it) },
            passphrase = sessionData.passphrase?.let { expose(vaultId, sessionData, SecretField.MATRIX_STORE, it) },
        )
    }

    internal fun isProtected(value: String): Boolean = value.startsWith(MARKER_PREFIX)

    private suspend fun prepareVault(sessionData: SessionData): AccountVaultId {
        val vaultId = deriveAccountVaultId(sessionData.userId, sessionData.deviceId)
        vaultKeyService.provision(
            VaultProvisioningRequest(
                vaultId = vaultId,
                accountIdHash = accountIdHash(sessionData.userId),
                requireUserAuthentication = false,
                requireTrustedHardware = featureFlagService.isFeatureEnabled(FeatureFlags.HardwareBackedVault),
            )
        )
        check(vaultKeyService.unlock(vaultId)) { "Unable to unlock the account Vault." }
        return vaultId
    }

    private suspend fun protect(
        vaultId: AccountVaultId,
        sessionData: SessionData,
        field: SecretField,
        value: String,
    ): String {
        if (isProtected(value)) {
            decodeEnvelope(value, field.domain)
            return value
        }
        val valueBytes = value.encodeToByteArray()
        val padded = encodePlaintext(valueBytes)
        valueBytes.fill(0)
        return try {
            val wrapped = vaultKeyService.wrapKey(vaultId, field.domain, padded, binding(sessionData, field))
            encodeEnvelope(wrapped)
        } finally {
            padded.fill(0)
        }
    }

    private suspend fun expose(
        vaultId: AccountVaultId,
        sessionData: SessionData,
        field: SecretField,
        value: String,
    ): String {
        if (!isProtected(value)) return value
        val wrapped = decodeEnvelope(value, field.domain)
        val plaintext = vaultKeyService.unwrapKey(vaultId, wrapped, binding(sessionData, field))
        return try {
            decodePlaintext(plaintext).decodeToString(throwOnInvalidSequence = true)
        } finally {
            plaintext.fill(0)
        }
    }

    private fun binding(sessionData: SessionData, field: SecretField): ByteArray =
        "$BINDING_PREFIX\u0000${sessionData.userId}\u0000${sessionData.deviceId}\u0000${field.code}".encodeToByteArray()

    private fun encodePlaintext(value: ByteArray): ByteArray {
        require(value.size in 1..MAX_VALUE_BYTES)
        val size = maxOf(MIN_WRAPPABLE_BYTES, Int.SIZE_BYTES + value.size)
        return ByteArray(size).also { encoded ->
            encoded[0] = (value.size ushr 24).toByte()
            encoded[1] = (value.size ushr 16).toByte()
            encoded[2] = (value.size ushr 8).toByte()
            encoded[3] = value.size.toByte()
            value.copyInto(encoded, destinationOffset = Int.SIZE_BYTES)
        }
    }

    private fun decodePlaintext(encoded: ByteArray): ByteArray {
        require(encoded.size in MIN_WRAPPABLE_BYTES..MAX_WRAPPABLE_BYTES)
        val size = encoded[0].toInt().and(0xff).shl(24) or
            encoded[1].toInt().and(0xff).shl(16) or
            encoded[2].toInt().and(0xff).shl(8) or
            encoded[3].toInt().and(0xff)
        require(size in 1..MAX_VALUE_BYTES && Int.SIZE_BYTES + size <= encoded.size)
        return encoded.copyOfRange(Int.SIZE_BYTES, Int.SIZE_BYTES + size)
    }

    private fun encodeEnvelope(wrapped: VaultWrappedKey): String {
        val encoded = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(ENVELOPE_MAGIC)
                output.writeInt(wrapped.formatVersion)
                output.writeLong(wrapped.vaultKeyVersion)
                output.writeInt(wrapped.domain.formatCode)
                output.writeInt(wrapped.initializationVector.size)
                output.writeInt(wrapped.ciphertext.size)
                output.write(wrapped.initializationVector)
                output.write(wrapped.ciphertext)
            }
            bytes.toByteArray()
        }
        return MARKER_PREFIX + encoded.toHexString()
    }

    private fun decodeEnvelope(value: String, expectedDomain: VaultKeyDomain): VaultWrappedKey {
        require(isProtected(value))
        val encoded = value.removePrefix(MARKER_PREFIX).hexToByteArray()
        require(encoded.size in MIN_ENVELOPE_BYTES..MAX_ENVELOPE_BYTES)
        return DataInputStream(ByteArrayInputStream(encoded)).use { input ->
            val magic = ByteArray(ENVELOPE_MAGIC.size).also(input::readFully)
            require(magic.contentEquals(ENVELOPE_MAGIC))
            val formatVersion = input.readInt()
            val keyVersion = input.readLong()
            val domainCode = input.readInt()
            val ivSize = input.readInt()
            val ciphertextSize = input.readInt()
            require(domainCode == expectedDomain.formatCode)
            require(ivSize in 1..MAX_IV_BYTES)
            require(ciphertextSize in 1..MAX_CIPHERTEXT_BYTES)
            require(input.available() == ivSize + ciphertextSize)
            val iv = ByteArray(ivSize).also(input::readFully)
            val ciphertext = ByteArray(ciphertextSize).also(input::readFully)
            require(input.available() == 0)
            VaultWrappedKey(formatVersion, keyVersion, expectedDomain, iv, ciphertext)
        }
    }

    private enum class SecretField(val code: String, val domain: VaultKeyDomain) {
        ACCESS_TOKEN("access-token", VaultKeyDomain.CREDENTIAL),
        REFRESH_TOKEN("refresh-token", VaultKeyDomain.CREDENTIAL),
        OAUTH_DATA("oauth-data", VaultKeyDomain.CREDENTIAL),
        MATRIX_STORE("matrix-store", VaultKeyDomain.MATRIX_STORE),
    }

    private companion object {
        const val MARKER_PREFIX = "yzvault1:"
        const val BINDING_PREFIX = "yizhe-session-secret-v1"
        const val MIN_WRAPPABLE_BYTES = 16
        const val MAX_WRAPPABLE_BYTES = 4096
        const val MAX_VALUE_BYTES = MAX_WRAPPABLE_BYTES - Int.SIZE_BYTES
        const val MAX_IV_BYTES = 32
        const val MAX_CIPHERTEXT_BYTES = 8192
        const val MIN_ENVELOPE_BYTES = 8 + Int.SIZE_BYTES * 4 + Long.SIZE_BYTES + 1
        const val MAX_ENVELOPE_BYTES = MIN_ENVELOPE_BYTES + MAX_IV_BYTES + MAX_CIPHERTEXT_BYTES
        val ENVELOPE_MAGIC = "YZSV0001".encodeToByteArray()
    }
}
