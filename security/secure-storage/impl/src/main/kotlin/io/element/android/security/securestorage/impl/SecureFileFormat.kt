/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.securestorage.impl

import io.element.android.security.securestorage.api.lowercaseHex

import io.element.android.security.securestorage.api.KeyEnvelopeId
import io.element.android.security.securestorage.api.SecureObjectAuthenticationException
import io.element.android.security.securestorage.api.SecureObjectPurpose
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest

internal const val SECURE_FILE_FORMAT_VERSION = 1
internal const val SECURE_FILE_CHUNK_SIZE = 64 * 1024
internal const val GCM_TAG_LENGTH_BYTES = 16
internal const val GCM_NONCE_LENGTH_BYTES = 12
internal const val FILE_NONCE_PREFIX_LENGTH_BYTES = GCM_NONCE_LENGTH_BYTES - Int.SIZE_BYTES

private const val SHA256_LENGTH_BYTES = 32
private const val ENVELOPE_ID_LENGTH_BYTES = 16
private const val CORE_LENGTH_BYTES =
    Int.SIZE_BYTES * 3 + SHA256_LENGTH_BYTES * 3 + ENVELOPE_ID_LENGTH_BYTES + FILE_NONCE_PREFIX_LENGTH_BYTES
private const val MAX_WRAPPED_KEY_LENGTH_BYTES = 1024
private const val LEGACY_KEY_ENVELOPE_FORMAT_VERSION = 1
private const val DEVICE_ROOT_KEY_ENVELOPE_FORMAT_VERSION = 2
private const val KEY_ENVELOPE_FORMAT_VERSION = 3
private val DATA_MAGIC = "YZSF0001".encodeToByteArray()
private val KEY_ENVELOPE_MAGIC = "YZKE0001".encodeToByteArray()

internal data class SecureFileCore(
    val purpose: SecureObjectPurpose,
    val objectIdSha256: ByteArray,
    val vaultIdSha256: ByteArray,
    val associatedDataSha256: ByteArray,
    val keyEnvelopeId: KeyEnvelopeId,
    val noncePrefix: ByteArray,
    val chunkSize: Int = SECURE_FILE_CHUNK_SIZE,
    val formatVersion: Int = SECURE_FILE_FORMAT_VERSION,
) {
    fun encode(): ByteArray = ByteArrayOutputStream(CORE_LENGTH_BYTES).use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(formatVersion)
            output.writeInt(chunkSize)
            output.writeInt(purpose.formatCode)
            output.write(objectIdSha256.requireLength(SHA256_LENGTH_BYTES))
            output.write(vaultIdSha256.requireLength(SHA256_LENGTH_BYTES))
            output.write(associatedDataSha256.requireLength(SHA256_LENGTH_BYTES))
            output.write(keyEnvelopeId.value.hexToBytes(ENVELOPE_ID_LENGTH_BYTES))
            output.write(noncePrefix.requireLength(FILE_NONCE_PREFIX_LENGTH_BYTES))
        }
        bytes.toByteArray()
    }
}

data class WrappedObjectKey(
    val initializationVector: ByteArray,
    val ciphertext: ByteArray,
    val wrappingScheme: ObjectKeyWrappingScheme = ObjectKeyWrappingScheme.DEVICE_ROOT_VAULT,
    val vaultKeyVersion: Long = 1L,
)

enum class ObjectKeyWrappingScheme(val formatCode: Int) {
    LEGACY_PER_VAULT_KEYSTORE(1),
    DEVICE_ROOT_VAULT(2),
    ACCOUNT_VAULT_DOMAIN(3),
}

internal fun DataOutputStream.writeSecureDataHeader(coreBytes: ByteArray) {
    require(coreBytes.size == CORE_LENGTH_BYTES)
    write(DATA_MAGIC)
    writeInt(coreBytes.size)
    write(coreBytes)
}

internal fun DataInputStream.readSecureDataHeader(): Pair<SecureFileCore, ByteArray> = authenticateFormat {
    require(readExact(DATA_MAGIC.size).contentEquals(DATA_MAGIC))
    require(readInt() == CORE_LENGTH_BYTES)
    val coreBytes = readExact(CORE_LENGTH_BYTES)
    decodeCore(coreBytes) to coreBytes
}

internal fun encodeKeyEnvelope(coreBytes: ByteArray, wrappedObjectKey: WrappedObjectKey): ByteArray =
    ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.write(KEY_ENVELOPE_MAGIC)
            output.writeInt(KEY_ENVELOPE_FORMAT_VERSION)
            output.writeInt(wrappedObjectKey.wrappingScheme.formatCode)
            output.writeLong(wrappedObjectKey.vaultKeyVersion)
            output.write(sha256(coreBytes))
            output.writeInt(wrappedObjectKey.initializationVector.size)
            output.write(wrappedObjectKey.initializationVector)
            output.writeInt(wrappedObjectKey.ciphertext.size)
            output.write(wrappedObjectKey.ciphertext)
        }
        bytes.toByteArray()
    }

internal fun decodeKeyEnvelope(bytes: ByteArray, coreBytes: ByteArray): WrappedObjectKey = authenticateFormat {
    DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        require(input.readExact(KEY_ENVELOPE_MAGIC.size).contentEquals(KEY_ENVELOPE_MAGIC))
        val envelopeFormatVersion = input.readInt()
        val wrappingScheme = when (envelopeFormatVersion) {
            LEGACY_KEY_ENVELOPE_FORMAT_VERSION -> ObjectKeyWrappingScheme.LEGACY_PER_VAULT_KEYSTORE
            DEVICE_ROOT_KEY_ENVELOPE_FORMAT_VERSION, KEY_ENVELOPE_FORMAT_VERSION -> input.readInt().let { schemeCode ->
                requireNotNull(ObjectKeyWrappingScheme.entries.firstOrNull { it.formatCode == schemeCode })
            }
            else -> error("Unsupported key envelope version.")
        }
        val vaultKeyVersion = if (envelopeFormatVersion == KEY_ENVELOPE_FORMAT_VERSION) input.readLong() else 1L
        require(vaultKeyVersion > 0)
        require(input.readExact(SHA256_LENGTH_BYTES).contentEquals(sha256(coreBytes)))
        val ivLength = input.readInt()
        require(ivLength == GCM_NONCE_LENGTH_BYTES)
        val initializationVector = input.readExact(ivLength)
        val wrappedKeyLength = input.readInt()
        require(wrappedKeyLength in GCM_TAG_LENGTH_BYTES..MAX_WRAPPED_KEY_LENGTH_BYTES)
        val ciphertext = input.readExact(wrappedKeyLength)
        require(input.read() == -1)
        WrappedObjectKey(initializationVector, ciphertext, wrappingScheme, vaultKeyVersion)
    }
}

internal fun decodeKeyEnvelopeWrappingScheme(bytes: ByteArray): ObjectKeyWrappingScheme = authenticateFormat {
    DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        require(input.readExact(KEY_ENVELOPE_MAGIC.size).contentEquals(KEY_ENVELOPE_MAGIC))
        when (input.readInt()) {
            LEGACY_KEY_ENVELOPE_FORMAT_VERSION -> ObjectKeyWrappingScheme.LEGACY_PER_VAULT_KEYSTORE
            DEVICE_ROOT_KEY_ENVELOPE_FORMAT_VERSION, KEY_ENVELOPE_FORMAT_VERSION -> input.readInt().let { schemeCode ->
                requireNotNull(ObjectKeyWrappingScheme.entries.firstOrNull { it.formatCode == schemeCode })
            }
            else -> error("Unsupported key envelope version.")
        }
    }
}

internal fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

internal fun sha256Hex(value: String): String = sha256(value.encodeToByteArray()).toHex()

internal fun ByteArray.toHex(): String = lowercaseHex()

private fun decodeCore(bytes: ByteArray): SecureFileCore = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
    val formatVersion = input.readInt()
    val chunkSize = input.readInt()
    val purpose = SecureObjectPurpose.fromFormatCode(input.readInt())
    require(formatVersion == SECURE_FILE_FORMAT_VERSION)
    require(chunkSize == SECURE_FILE_CHUNK_SIZE)
    requireNotNull(purpose)
    val objectIdSha256 = input.readExact(SHA256_LENGTH_BYTES)
    val vaultIdSha256 = input.readExact(SHA256_LENGTH_BYTES)
    val associatedDataSha256 = input.readExact(SHA256_LENGTH_BYTES)
    val keyEnvelopeId = KeyEnvelopeId(input.readExact(ENVELOPE_ID_LENGTH_BYTES).toHex())
    val noncePrefix = input.readExact(FILE_NONCE_PREFIX_LENGTH_BYTES)
    require(input.read() == -1)
    SecureFileCore(
        purpose = purpose,
        objectIdSha256 = objectIdSha256,
        vaultIdSha256 = vaultIdSha256,
        associatedDataSha256 = associatedDataSha256,
        keyEnvelopeId = keyEnvelopeId,
        noncePrefix = noncePrefix,
        chunkSize = chunkSize,
        formatVersion = formatVersion,
    )
}

private inline fun <T> authenticateFormat(block: () -> T): T = try {
    block()
} catch (failure: SecureObjectAuthenticationException) {
    throw failure
} catch (failure: Exception) {
    throw SecureObjectAuthenticationException(failure)
}

private fun DataInputStream.readExact(length: Int): ByteArray = ByteArray(length).also(::readFully)

private fun ByteArray.requireLength(expectedLength: Int): ByteArray = apply { require(size == expectedLength) }

private fun String.hexToBytes(expectedLength: Int): ByteArray {
    require(length == expectedLength * 2)
    return ByteArray(expectedLength) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
