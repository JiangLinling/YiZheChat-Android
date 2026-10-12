/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.sessionstorage.impl

import io.element.android.libraries.androidutils.crypto.ClientSecret
import io.element.android.security.securestorage.api.DeviceRootKeyService
import io.element.android.security.securestorage.api.DeviceWrappedKey
import io.element.encrypteddb.passphrase.DatabaseSecretProvider
import io.element.encrypteddb.passphrase.RandomDatabaseSecretProvider
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Keeps the SQLCipher key wrapped by YiZheChat's non-exportable Device Root Key.
 *
 * Existing Tink-encrypted key files are migrated in place without changing the SQLCipher key. The legacy file is
 * deleted only after the authenticated device-root envelope is durable, so a crash cannot strand the database.
 */
internal class DeviceRootDatabaseSecretProvider(
    private val deviceRootKeyService: DeviceRootKeyService,
    private val envelopeFile: File,
    private val legacyProvider: RandomDatabaseSecretProvider,
    private val legacySecretFile: File,
    databaseName: String,
) : DatabaseSecretProvider {
    private val binding = BINDING_MAGIC + sha256(databaseName.encodeToByteArray())
    private val secureRandom = SecureRandom()
    private val lock = Any()

    override fun getSecret(): ClientSecret = synchronized(lock) {
        readEnvelope()?.let { envelope ->
            val plaintext = runBlocking { deviceRootKeyService.unwrapKey(envelope, binding) }
            try {
                require(plaintext.size == DATABASE_KEY_LENGTH_BYTES)
                return ClientSecret.fromRawBytes(plaintext.copyOf())
            } finally {
                plaintext.fill(0)
            }
        }

        val keyMaterial = if (legacySecretFile.isFile) {
            (legacyProvider.getSecret() as? ClientSecret.RawKey)?.bytes?.copyOf()
                ?: error("Legacy database key has an unsupported format.")
        } else {
            ByteArray(DATABASE_KEY_LENGTH_BYTES).also(secureRandom::nextBytes)
        }
        try {
            val wrapped = runBlocking {
                deviceRootKeyService.ensureReady()
                deviceRootKeyService.wrapKey(keyMaterial, binding)
            }
            writeEnvelope(wrapped)
            if (legacySecretFile.exists()) check(legacyProvider.reset()) { "Unable to retire the legacy database key." }
            ClientSecret.fromRawBytes(keyMaterial.copyOf())
        } finally {
            keyMaterial.fill(0)
        }
    }

    override fun reset(): Boolean = synchronized(lock) {
        !envelopeFile.exists() || envelopeFile.delete()
    }

    private fun readEnvelope(): DeviceWrappedKey? {
        if (!envelopeFile.isFile) return null
        val bytes = envelopeFile.readBytes()
        require(bytes.size in MIN_RECORD_LENGTH_BYTES..MAX_RECORD_LENGTH_BYTES)
        val payload = bytes.copyOfRange(0, bytes.size - CHECKSUM_LENGTH_BYTES)
        val checksum = bytes.copyOfRange(bytes.size - CHECKSUM_LENGTH_BYTES, bytes.size)
        require(MessageDigest.isEqual(checksum, sha256(payload)))
        return DataInputStream(ByteArrayInputStream(payload)).use { input ->
            require(input.readExact(MAGIC.size).contentEquals(MAGIC))
            require(input.readInt() == RECORD_FORMAT_VERSION)
            val wrappedFormat = input.readInt()
            val rootVersion = input.readLong()
            val installationHash = input.readExact(SHA256_LENGTH_BYTES)
            val iv = input.readExact(input.readInt().also { require(it == GCM_NONCE_LENGTH_BYTES) })
            val ciphertext = input.readExact(input.readInt().also { require(it in MIN_CIPHERTEXT_LENGTH_BYTES..MAX_CIPHERTEXT_LENGTH_BYTES) })
            require(input.read() == -1)
            DeviceWrappedKey(wrappedFormat, rootVersion, installationHash, iv, ciphertext)
        }
    }

    private fun writeEnvelope(envelope: DeviceWrappedKey) {
        envelopeFile.parentFile?.mkdirs()
        val payload = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(MAGIC)
                output.writeInt(RECORD_FORMAT_VERSION)
                output.writeInt(envelope.formatVersion)
                output.writeLong(envelope.rootKeyVersion)
                output.write(envelope.installationIdSha256.requireSize(SHA256_LENGTH_BYTES))
                output.writeInt(envelope.initializationVector.size)
                output.write(envelope.initializationVector)
                output.writeInt(envelope.ciphertext.size)
                output.write(envelope.ciphertext)
            }
            bytes.toByteArray()
        }
        val encoded = payload + sha256(payload)
        val temporary = File(envelopeFile.parentFile, "${envelopeFile.name}.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(encoded)
            output.fd.sync()
        }
        check(temporary.renameTo(envelopeFile)) { "Unable to commit the device-root database key envelope." }
    }

    private fun DataInputStream.readExact(length: Int): ByteArray = ByteArray(length).also(::readFully)

    private companion object {
        const val RECORD_FORMAT_VERSION = 1
        const val DATABASE_KEY_LENGTH_BYTES = 32
        const val SHA256_LENGTH_BYTES = 32
        const val CHECKSUM_LENGTH_BYTES = 32
        const val GCM_NONCE_LENGTH_BYTES = 12
        const val MIN_CIPHERTEXT_LENGTH_BYTES = 32
        const val MAX_CIPHERTEXT_LENGTH_BYTES = 1024
        const val MIN_RECORD_LENGTH_BYTES = 8 + 4 + 4 + 8 + 32 + 4 + 12 + 4 + 32 + 32
        const val MAX_RECORD_LENGTH_BYTES = 4096
        val MAGIC = "YZDBK001".encodeToByteArray()
        val BINDING_MAGIC = "YZDBKB01".encodeToByteArray()
    }
}

private fun ByteArray.requireSize(expectedSize: Int): ByteArray = apply { require(size == expectedSize) }

private fun sha256(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)
