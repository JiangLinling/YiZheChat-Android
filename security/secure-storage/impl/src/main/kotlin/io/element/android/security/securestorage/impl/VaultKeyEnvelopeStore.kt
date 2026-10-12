/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.securestorage.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.security.securestorage.api.AccountVaultId
import io.element.android.security.securestorage.api.DeviceWrappedKey
import io.element.android.security.securestorage.api.SecureObjectAuthenticationException
import io.element.android.security.securestorage.api.SecureStorageRoots
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

@SingleIn(AppScope::class)
@Inject
class VaultKeyEnvelopeStore(
    private val roots: SecureStorageRoots,
) {
    fun read(vaultId: AccountVaultId): DeviceWrappedKey? {
        val bytes = AtomicSecureRecordIO.read(envelopeFile(vaultId), MAX_VAULT_KEY_RECORD_LENGTH_BYTES) ?: return null
        return decode(bytes, vaultId)
    }

    fun create(vaultId: AccountVaultId, wrappedKey: DeviceWrappedKey): Boolean =
        AtomicSecureRecordIO.create(envelopeFile(vaultId), encode(vaultId, wrappedKey))

    fun delete(vaultId: AccountVaultId): Boolean = AtomicSecureRecordIO.delete(envelopeFile(vaultId))

    fun binding(vaultId: AccountVaultId): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.write(VAULT_KEY_BINDING_MAGIC)
            output.write(sha256(vaultId.value.encodeToByteArray()))
        }
        bytes.toByteArray()
    }

    private fun envelopeFile(vaultId: AccountVaultId): File {
        require(vaultId.value.isNotBlank() && vaultId.value.length <= MAX_VAULT_ID_LENGTH)
        val root = roots.persistentRoot().canonicalFile
        return File(root, "$VAULT_KEYS_DIRECTORY/${sha256Hex(vaultId.value)}$VAULT_KEY_FILE_SUFFIX").also { file ->
            require(file.canonicalFile.path.startsWith(root.path + File.separator))
        }
    }

    private fun encode(vaultId: AccountVaultId, wrappedKey: DeviceWrappedKey): ByteArray {
        val payload = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(VAULT_KEY_MAGIC)
                output.writeInt(VAULT_KEY_RECORD_FORMAT_VERSION)
                output.write(sha256(vaultId.value.encodeToByteArray()))
                output.writeInt(wrappedKey.formatVersion)
                output.writeLong(wrappedKey.rootKeyVersion)
                output.writeLengthPrefixed(wrappedKey.installationIdSha256)
                output.writeLengthPrefixed(wrappedKey.initializationVector)
                output.writeLengthPrefixed(wrappedKey.ciphertext)
            }
            bytes.toByteArray()
        }
        return payload + sha256(payload)
    }

    private fun decode(bytes: ByteArray, vaultId: AccountVaultId): DeviceWrappedKey = authenticateVaultRecord {
        require(bytes.size > CHECKSUM_LENGTH_BYTES)
        val payload = bytes.copyOfRange(0, bytes.size - CHECKSUM_LENGTH_BYTES)
        val checksum = bytes.copyOfRange(bytes.size - CHECKSUM_LENGTH_BYTES, bytes.size)
        require(MessageDigest.isEqual(checksum, sha256(payload)))
        DataInputStream(ByteArrayInputStream(payload)).use { input ->
            require(input.readExact(VAULT_KEY_MAGIC.size).contentEquals(VAULT_KEY_MAGIC))
            require(input.readInt() == VAULT_KEY_RECORD_FORMAT_VERSION)
            require(MessageDigest.isEqual(input.readExact(SHA256_LENGTH_BYTES), sha256(vaultId.value.encodeToByteArray())))
            val formatVersion = input.readInt()
            val rootKeyVersion = input.readLong()
            val installationIdSha256 = input.readLengthPrefixed(SHA256_LENGTH_BYTES, SHA256_LENGTH_BYTES)
            val initializationVector = input.readLengthPrefixed(GCM_NONCE_LENGTH_BYTES, GCM_NONCE_LENGTH_BYTES)
            val ciphertext = input.readLengthPrefixed(MIN_WRAPPED_VAULT_KEY_LENGTH_BYTES, MAX_WRAPPED_VAULT_KEY_LENGTH_BYTES)
            require(input.read() == -1)
            DeviceWrappedKey(formatVersion, rootKeyVersion, installationIdSha256, initializationVector, ciphertext)
        }
    }

    private companion object {
        const val VAULT_KEYS_DIRECTORY = "vault-keys"
        const val VAULT_KEY_FILE_SUFFIX = ".yzvk"
        const val VAULT_KEY_RECORD_FORMAT_VERSION = 1
        const val MAX_VAULT_ID_LENGTH = 1024
        const val SHA256_LENGTH_BYTES = 32
        const val CHECKSUM_LENGTH_BYTES = 32
        const val GCM_NONCE_LENGTH_BYTES = 12
        const val MIN_WRAPPED_VAULT_KEY_LENGTH_BYTES = 32
        const val MAX_WRAPPED_VAULT_KEY_LENGTH_BYTES = 1024
        const val MAX_VAULT_KEY_RECORD_LENGTH_BYTES = 4096L
        val VAULT_KEY_MAGIC = "YZVK0001".encodeToByteArray()
        val VAULT_KEY_BINDING_MAGIC = "YZAVK001".encodeToByteArray()
    }
}

private fun DataOutputStream.writeLengthPrefixed(bytes: ByteArray) {
    writeInt(bytes.size)
    write(bytes)
}

private fun DataInputStream.readLengthPrefixed(minimumLength: Int, maximumLength: Int): ByteArray {
    val length = readInt()
    require(length in minimumLength..maximumLength)
    return readExact(length)
}

private fun DataInputStream.readExact(length: Int): ByteArray = ByteArray(length).also(::readFully)

private inline fun <T> authenticateVaultRecord(block: () -> T): T = try {
    block()
} catch (failure: SecureObjectAuthenticationException) {
    throw failure
} catch (failure: Exception) {
    throw SecureObjectAuthenticationException(failure)
}
