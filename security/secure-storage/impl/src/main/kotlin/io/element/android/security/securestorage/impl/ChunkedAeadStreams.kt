/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.securestorage.impl

import io.element.android.security.securestorage.api.SecureObjectAuthenticationException
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class ChunkedAeadOutputStream(
    private val output: DataOutputStream,
    private val key: SecretKey,
    private val coreBytes: ByteArray,
    private val noncePrefix: ByteArray,
    private val chunkSize: Int,
) : OutputStream() {
    private val buffer = ByteArray(chunkSize)
    private var bufferSize = 0
    private var chunkIndex = 0
    private var finished = false
    private var successfullyFinished = false

    var plaintextLength: Long = 0
        private set

    override fun write(value: Int) {
        ensureOpen()
        buffer[bufferSize++] = value.toByte()
        plaintextLength++
        if (bufferSize == buffer.size) writeBufferedChunk()
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        ensureOpen()
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size)
        var sourceOffset = offset
        var remaining = length
        while (remaining > 0) {
            val copyLength = minOf(remaining, buffer.size - bufferSize)
            bytes.copyInto(buffer, destinationOffset = bufferSize, startIndex = sourceOffset, endIndex = sourceOffset + copyLength)
            bufferSize += copyLength
            sourceOffset += copyLength
            remaining -= copyLength
            plaintextLength += copyLength
            if (bufferSize == buffer.size) writeBufferedChunk()
        }
    }

    override fun flush() {
        output.flush()
    }

    override fun close() {
        finish()
    }

    fun finish() {
        if (finished) {
            check(successfullyFinished) { "Secure output stream was aborted." }
            return
        }
        try {
            if (bufferSize > 0) writeBufferedChunk()
            val terminalCiphertext = encryptChunk(
                key = key,
                noncePrefix = noncePrefix,
                chunkIndex = chunkIndex,
                recordType = RECORD_TYPE_TERMINAL,
                coreBytes = coreBytes,
                plaintext = EMPTY_BYTES,
            )
            output.writeInt(TERMINAL_PLAINTEXT_LENGTH)
            output.writeInt(terminalCiphertext.size)
            output.write(terminalCiphertext)
            output.flush()
            successfullyFinished = true
        } finally { discard() }
    }

    /** Ends a failed producer without writing a terminal record or retaining partial plaintext. */
    fun discard() {
        buffer.fill(0)
        bufferSize = 0
        finished = true
    }

    private fun writeBufferedChunk() {
        check(chunkIndex < Int.MAX_VALUE)
        val plaintext = buffer.copyOf(bufferSize)
        try {
            val ciphertext = encryptChunk(
                key = key,
                noncePrefix = noncePrefix,
                chunkIndex = chunkIndex,
                recordType = RECORD_TYPE_DATA,
                coreBytes = coreBytes,
                plaintext = plaintext,
            )
            output.writeInt(bufferSize)
            output.writeInt(ciphertext.size)
            output.write(ciphertext)
            chunkIndex++
        } catch (failure: Throwable) {
            finished = true // A failed record cannot be retried with the same AEAD nonce.
            throw failure
        } finally {
            plaintext.fill(0)
            buffer.fill(0)
            bufferSize = 0
        }
    }

    private fun ensureOpen() = check(!finished) { "Secure output stream is already finished." }
}

internal class ChunkedAeadInputStream(
    private val input: DataInputStream,
    private val key: SecretKey,
    private val coreBytes: ByteArray,
    private val noncePrefix: ByteArray,
    private val chunkSize: Int,
) : InputStream() {
    private var plaintext = EMPTY_BYTES
    private var plaintextOffset = 0
    private var chunkIndex = 0
    private var authenticatedEnd = false
    private var closed = false
    private var failed = false

    var plaintextLength: Long = 0
        private set

    override fun read(): Int {
        if (!ensurePlaintextAvailable()) return -1
        return plaintext[plaintextOffset++].toInt() and 0xff
    }

    override fun read(destination: ByteArray, offset: Int, length: Int): Int {
        require(offset >= 0 && length >= 0 && offset + length <= destination.size)
        if (length == 0) return 0
        if (!ensurePlaintextAvailable()) return -1
        val copyLength = minOf(length, plaintext.size - plaintextOffset)
        plaintext.copyInto(destination, destinationOffset = offset, startIndex = plaintextOffset, endIndex = plaintextOffset + copyLength)
        plaintextOffset += copyLength
        return copyLength
    }

    override fun available(): Int = plaintext.size - plaintextOffset

    override fun close() {
        plaintext.fill(0)
        plaintext = EMPTY_BYTES
        plaintextOffset = 0
        closed = true
    }

    fun verifyToEnd() {
        if (failed) throw SecureObjectAuthenticationException()
        plaintextOffset = plaintext.size
        while (!authenticatedEnd) {
            loadNextRecord()
            plaintextOffset = plaintext.size
        }
    }

    private fun ensurePlaintextAvailable(): Boolean {
        check(!closed) { "Secure input stream is closed." }
        while (plaintextOffset == plaintext.size && !authenticatedEnd) loadNextRecord()
        return plaintextOffset < plaintext.size
    }

    private fun loadNextRecord() = try {
        authenticateChunk {
            plaintext.fill(0)
            plaintext = EMPTY_BYTES
            plaintextOffset = 0
            val plaintextSize = input.readInt()
            val ciphertextSize = input.readInt()
            if (plaintextSize == TERMINAL_PLAINTEXT_LENGTH) {
                require(ciphertextSize == GCM_TAG_LENGTH_BYTES)
                val terminalPlaintext = decryptChunk(
                    key = key,
                    noncePrefix = noncePrefix,
                    chunkIndex = chunkIndex,
                    recordType = RECORD_TYPE_TERMINAL,
                    coreBytes = coreBytes,
                    ciphertext = input.readExact(ciphertextSize),
                )
                try { require(terminalPlaintext.isEmpty()) } finally { terminalPlaintext.fill(0) }
                require(input.read() == -1)
                plaintext = EMPTY_BYTES
                plaintextOffset = 0
                authenticatedEnd = true
            } else {
                require(plaintextSize in 1..chunkSize)
                require(ciphertextSize == plaintextSize + GCM_TAG_LENGTH_BYTES)
                plaintext = decryptChunk(
                    key = key,
                    noncePrefix = noncePrefix,
                    chunkIndex = chunkIndex,
                    recordType = RECORD_TYPE_DATA,
                    coreBytes = coreBytes,
                    ciphertext = input.readExact(ciphertextSize),
                )
                require(plaintext.size == plaintextSize)
                plaintextOffset = 0
                plaintextLength += plaintextSize
                chunkIndex++
            }
        }
    } catch (failure: Throwable) {
        failed = true
        close()
        throw failure
    }
}

private fun encryptChunk(
    key: SecretKey,
    noncePrefix: ByteArray,
    chunkIndex: Int,
    recordType: Byte,
    coreBytes: ByteArray,
    plaintext: ByteArray,
): ByteArray = cipher(Cipher.ENCRYPT_MODE, key, noncePrefix, chunkIndex, recordType, coreBytes).doFinal(plaintext)

private fun decryptChunk(
    key: SecretKey,
    noncePrefix: ByteArray,
    chunkIndex: Int,
    recordType: Byte,
    coreBytes: ByteArray,
    ciphertext: ByteArray,
): ByteArray = try {
    cipher(Cipher.DECRYPT_MODE, key, noncePrefix, chunkIndex, recordType, coreBytes).doFinal(ciphertext)
} catch (failure: Exception) {
    throw SecureObjectAuthenticationException(failure)
}

internal fun decryptAuthenticatedDataChunk(
    key: SecretKey,
    noncePrefix: ByteArray,
    chunkIndex: Int,
    coreBytes: ByteArray,
    ciphertext: ByteArray,
): ByteArray = decryptChunk(
    key = key,
    noncePrefix = noncePrefix,
    chunkIndex = chunkIndex,
    recordType = RECORD_TYPE_DATA,
    coreBytes = coreBytes,
    ciphertext = ciphertext,
)

internal fun authenticateTerminalChunk(
    key: SecretKey,
    noncePrefix: ByteArray,
    chunkIndex: Int,
    coreBytes: ByteArray,
    ciphertext: ByteArray,
) {
    val plaintext = decryptChunk(
        key = key,
        noncePrefix = noncePrefix,
        chunkIndex = chunkIndex,
        recordType = RECORD_TYPE_TERMINAL,
        coreBytes = coreBytes,
        ciphertext = ciphertext,
    )
    try {
        if (plaintext.isNotEmpty()) throw SecureObjectAuthenticationException()
    } finally {
        plaintext.fill(0)
    }
}

private fun cipher(
    mode: Int,
    key: SecretKey,
    noncePrefix: ByteArray,
    chunkIndex: Int,
    recordType: Byte,
    coreBytes: ByteArray,
): Cipher {
    require(noncePrefix.size == FILE_NONCE_PREFIX_LENGTH_BYTES)
    val nonce = ByteBuffer.allocate(GCM_NONCE_LENGTH_BYTES)
        .put(noncePrefix)
        .putInt(chunkIndex)
        .array()
    return Cipher.getInstance(AES_GCM_TRANSFORMATION).apply {
        init(mode, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce))
        updateAAD(coreBytes)
        updateAAD(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(chunkIndex).array())
        updateAAD(byteArrayOf(recordType))
    }
}

private inline fun authenticateChunk(block: () -> Unit) {
    try {
        block()
    } catch (failure: SecureObjectAuthenticationException) {
        throw failure
    } catch (failure: EOFException) {
        throw SecureObjectAuthenticationException(failure)
    } catch (failure: IllegalArgumentException) {
        throw SecureObjectAuthenticationException(failure)
    }
}

private fun DataInputStream.readExact(length: Int): ByteArray = ByteArray(length).also(::readFully)

private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
private const val GCM_TAG_LENGTH_BITS = GCM_TAG_LENGTH_BYTES * Byte.SIZE_BITS
private const val TERMINAL_PLAINTEXT_LENGTH = -1
private const val RECORD_TYPE_DATA: Byte = 1
private const val RECORD_TYPE_TERMINAL: Byte = 2
private val EMPTY_BYTES = ByteArray(0)
