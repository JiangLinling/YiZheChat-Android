/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.securestorage.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.security.securestorage.api.AccountVaultId
import io.element.android.security.securestorage.api.KeyEnvelopeId
import io.element.android.security.securestorage.api.SecureObjectAuthenticationException
import io.element.android.security.securestorage.api.SecureObjectIdentity
import io.element.android.security.securestorage.api.SecureObjectNotFoundException
import io.element.android.security.securestorage.api.SecureObjectReference
import io.element.android.security.securestorage.api.SecureObjectWriteRequest
import io.element.android.security.securestorage.api.SecureStorageException
import io.element.android.security.securestorage.api.SecureStorageRoots
import io.element.android.security.securestorage.api.SecureStoreMaintenanceReport
import io.element.android.security.securestorage.api.SecureTransientObjectDeletion
import io.element.android.security.securestorage.api.SecureVaultDeletionReport
import io.element.android.security.securestorage.api.SecureVaultDestroyedException
import io.element.android.security.securestorage.api.SensitiveFileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DefaultSensitiveFileStore(
    private val roots: SecureStorageRoots,
    private val objectKeyEnvelopeService: ObjectKeyEnvelopeService,
) : SensitiveFileStore {
    private val operationMutex = Mutex()
    private val secureRandom = SecureRandom()
    private val transientMediaJournal = TransientMediaObjectJournal(roots)
    internal var transientMediaProcessIdentity: String = SecureMediaProcessIdentity.value

    override suspend fun transientMediaObjectRecord(identity: SecureObjectIdentity) = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            transientMediaJournal.entry(identity)?.let {
                io.element.android.security.securestorage.api.SecureTransientMediaObjectRecord(it.owner, it.identity, it.digest)
            }
        }
    }

    override suspend fun transientMediaObjectDeletion(identity: SecureObjectIdentity) = withContext(Dispatchers.IO) {
        operationMutex.withLock { transientMediaJournal.entry(identity)?.let { transientMediaJournal.deletion(it) } }
    }

    override suspend fun discardUnissuedTransientMediaObject(
        record: io.element.android.security.securestorage.api.SecureTransientMediaObjectRecord,
    ) = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            val entry = requireNotNull(transientMediaJournal.entry(record.identity))
            require(entry.owner == record.owner && entry.digest == record.recordSha256 && entry.process == transientMediaProcessIdentity)
            transientMediaJournal.markReleased(record.identity)
            transientMediaJournal.markLeaseQuiescent(record.identity, System.currentTimeMillis())
            val directory = objectDirectory(record.identity.vaultId, record.identity.purpose.formatCode, record.identity.objectId.value)
            if (!directory.exists() || directory.deleteRecursively()) {
                removeEmptyParents(directory)
                transientMediaJournal.markDeleted(entry, System.currentTimeMillis())
            }
            transientMediaJournal.deletion(entry)
        }
    }

    override suspend fun registerTransientMediaObject(request: SecureObjectWriteRequest) = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            validateWriteRequest(request)
            transientMediaJournal.register(request, transientMediaProcessIdentity)
        }
    }

    override suspend fun recoverTransientMediaObjects(): List<SecureTransientObjectDeletion> = withContext(Dispatchers.IO) {
        operationMutex.withLock { recoverTransientMediaObjectsLocked() }
    }

    override suspend fun acknowledgeTransientMediaDeletion(fact: SecureTransientObjectDeletion, receiptReference: String) = withContext(Dispatchers.IO) {
        operationMutex.withLock { transientMediaJournal.acknowledge(fact, receiptReference) }
    }

    override suspend fun recordTransientMediaLeaseQuiescent(reference: SecureObjectReference) = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            validateReference(reference)
            val identity = SecureObjectIdentity(reference.objectId, reference.vaultId, reference.purpose)
            val directory = objectDirectory(reference.vaultId, reference.purpose.formatCode, reference.objectId.value)
            require(
                !directory.exists() || File(directory, "${reference.keyEnvelopeId.value}$KEY_ENVELOPE_FILE_SUFFIX").exists() ||
                File(directory, "${reference.keyEnvelopeId.value}$DATA_FILE_SUFFIX").exists()
            ) { "Media read release belongs to another generation." }
            transientMediaJournal.markLeaseQuiescent(identity, System.currentTimeMillis())
        }
    }

    private fun recoverTransientMediaObjectsLocked(): List<SecureTransientObjectDeletion> = buildList {
        transientMediaJournal.entries().forEach { entry ->
            try {
                if (entry.process != transientMediaProcessIdentity) {
                    transientMediaJournal.markLeaseQuiescent(entry.identity, System.currentTimeMillis())
                }
                val existing = transientMediaJournal.deletion(entry)
                if (existing != null) {
                    add(existing)
                } else if (transientMediaJournal.eligible(entry, transientMediaProcessIdentity)) {
                    val directory = objectDirectory(entry.identity.vaultId, entry.identity.purpose.formatCode, entry.identity.objectId.value)
                    if (!directory.exists() || directory.deleteRecursively()) {
                        removeEmptyParents(directory)
                        transientMediaJournal.markDeleted(entry, System.currentTimeMillis())
                        add(checkNotNull(transientMediaJournal.deletion(entry)))
                    }
                }
            } catch (_: Exception) {
                // One object's publication/read failure keeps that exact obligation, not every other object hostage.
                timber.log.Timber.w("Transient media object cleanup remains pending.")
            }
        }
    }

    override suspend fun write(
        request: SecureObjectWriteRequest,
        writePlaintext: suspend (java.io.OutputStream) -> Unit,
    ): SecureObjectReference = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            validateWriteRequest(request)
            if (tombstoneFile(request.vaultId).exists()) throw SecureVaultDestroyedException()
            transientMediaJournal.register(request, transientMediaProcessIdentity)
            if (request.transientMediaOwner != null) {
                val existing = objectDirectory(request.vaultId, request.purpose.formatCode, request.objectId.value)
                check(!existing.exists() || existing.listFiles()?.isEmpty() == true) { "A transient media object identity is single use." }
            }

            val keyEnvelopeId = KeyEnvelopeId(randomIdentifier())
            val core = SecureFileCore(
                purpose = request.purpose,
                objectIdSha256 = sha256(request.objectId.value.encodeToByteArray()),
                vaultIdSha256 = sha256(request.vaultId.value.encodeToByteArray()),
                associatedDataSha256 = sha256(request.associatedData),
                keyEnvelopeId = keyEnvelopeId,
                noncePrefix = ByteArray(FILE_NONCE_PREFIX_LENGTH_BYTES).also(secureRandom::nextBytes),
            )
            val coreBytes = core.encode()
            val objectKey = KeyGenerator.getInstance(AES_ALGORITHM).run {
                init(OBJECT_KEY_SIZE_BITS, secureRandom)
                generateKey()
            }
            val wrappedObjectKey = objectKeyEnvelopeService.wrap(request.vaultId, objectKey, coreBytes)
            val objectDirectory = objectDirectory(request.vaultId, request.purpose.formatCode, request.objectId.value).ensureDirectory()
            cleanupPartials(objectDirectory)

            val dataFile = File(objectDirectory, "${keyEnvelopeId.value}$DATA_FILE_SUFFIX")
            val keyEnvelopeFile = File(objectDirectory, "${keyEnvelopeId.value}$KEY_ENVELOPE_FILE_SUFFIX")
            val dataPartial = File(objectDirectory, "$PARTIAL_FILE_PREFIX${keyEnvelopeId.value}-data")
            val keyEnvelopePartial = File(objectDirectory, "$PARTIAL_FILE_PREFIX${keyEnvelopeId.value}-key")
            try {
                val plaintextLength = writeEncryptedData(dataPartial, objectKey, coreBytes, core, writePlaintext)
                request.expectedPlaintextLength?.let { expectedLength ->
                    require(plaintextLength == expectedLength) { "Plaintext length does not match the declared length." }
                }
                writeAndSync(keyEnvelopePartial, encodeKeyEnvelope(coreBytes, wrappedObjectKey))
                publish(dataPartial, dataFile)
                try {
                    publish(keyEnvelopePartial, keyEnvelopeFile)
                } catch (failure: Exception) {
                    dataFile.delete()
                    throw failure
                }
                SecureObjectReference(
                    objectId = request.objectId,
                    vaultId = request.vaultId,
                    purpose = request.purpose,
                    keyEnvelopeId = keyEnvelopeId,
                    formatVersion = SECURE_FILE_FORMAT_VERSION,
                    plaintextLength = plaintextLength,
                    ciphertextLength = dataFile.length(),
                    associatedDataSha256 = core.associatedDataSha256.toHex(),
                )
            } finally {
                dataPartial.delete()
                keyEnvelopePartial.delete()
                objectKey.encoded?.fill(0)
            }
        }
    }

    override suspend fun <T> read(
        reference: SecureObjectReference,
        readPlaintext: suspend (java.io.InputStream) -> T,
    ): T = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            validateReference(reference)
            if (tombstoneFile(reference.vaultId).exists()) throw SecureVaultDestroyedException()
            val directory = objectDirectory(reference.vaultId, reference.purpose.formatCode, reference.objectId.value)
            val dataFile = File(directory, "${reference.keyEnvelopeId.value}$DATA_FILE_SUFFIX")
            val keyEnvelopeFile = File(directory, "${reference.keyEnvelopeId.value}$KEY_ENVELOPE_FILE_SUFFIX")
            val keyEnvelopeBytes = readBoundedKeyEnvelope(keyEnvelopeFile) ?: throw SecureObjectNotFoundException()
            if (!dataFile.isFile) throw SecureObjectNotFoundException()
            if (dataFile.length() != reference.ciphertextLength) throw SecureObjectAuthenticationException()

            FileInputStream(dataFile).use { fileInput ->
                val dataInput = DataInputStream(fileInput)
                val (core, coreBytes) = dataInput.readSecureDataHeader()
                authenticateReference(reference, core)
                val wrappedObjectKey = decodeKeyEnvelope(keyEnvelopeBytes, coreBytes)
                val objectKey = unwrapObjectKey(reference.vaultId, wrappedObjectKey, coreBytes)
                try {
                    migrateLegacyKeyEnvelope(reference.vaultId, keyEnvelopeFile, wrappedObjectKey, objectKey, coreBytes)
                    val plaintextInput = ChunkedAeadInputStream(
                        input = dataInput,
                        key = objectKey,
                        coreBytes = coreBytes,
                        noncePrefix = core.noncePrefix,
                        chunkSize = core.chunkSize,
                    )
                    val result = readPlaintext(plaintextInput)
                    plaintextInput.verifyToEnd()
                    if (plaintextInput.plaintextLength != reference.plaintextLength) throw SecureObjectAuthenticationException()
                    result
                } finally {
                    objectKey.encoded?.fill(0)
                }
            }
        }
    }

    override suspend fun readRange(
        reference: SecureObjectReference,
        offset: Long,
        byteCount: Int,
    ): ByteArray = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            require(offset >= 0) { "Plaintext offset cannot be negative." }
            require(byteCount in 0..MAX_RANDOM_ACCESS_READ_BYTES) { "Random-access read is too large." }
            validateReference(reference)
            if (tombstoneFile(reference.vaultId).exists()) throw SecureVaultDestroyedException()
            if (byteCount == 0 || offset >= reference.plaintextLength) return@withLock ByteArray(0)

            val directory = objectDirectory(reference.vaultId, reference.purpose.formatCode, reference.objectId.value)
            val dataFile = File(directory, "${reference.keyEnvelopeId.value}$DATA_FILE_SUFFIX")
            val keyEnvelopeFile = File(directory, "${reference.keyEnvelopeId.value}$KEY_ENVELOPE_FILE_SUFFIX")
            val keyEnvelopeBytes = readBoundedKeyEnvelope(keyEnvelopeFile) ?: throw SecureObjectNotFoundException()

            try {
                if (!dataFile.isFile) throw SecureObjectNotFoundException()
                if (dataFile.length() != reference.ciphertextLength) throw SecureObjectAuthenticationException()
                val (core, coreBytes, dataOffset) = FileInputStream(dataFile).use { fileInput ->
                    val (decodedCore, decodedBytes) = DataInputStream(fileInput).readSecureDataHeader()
                    Triple(decodedCore, decodedBytes, fileInput.channel.position())
                }
                try {
                    authenticateReference(reference, core)
                    val wrappedObjectKey = decodeKeyEnvelope(keyEnvelopeBytes, coreBytes)
                    try {
                        val objectKey = unwrapObjectKey(reference.vaultId, wrappedObjectKey, coreBytes)
                        try {
                            migrateLegacyKeyEnvelope(reference.vaultId, keyEnvelopeFile, wrappedObjectKey, objectKey, coreBytes)
                            RandomAccessFile(dataFile, "r").use { encryptedFile ->
                                authenticateRandomAccessTerminal(
                                    encryptedFile = encryptedFile,
                                    reference = reference,
                                    core = core,
                                    coreBytes = coreBytes,
                                    dataOffset = dataOffset,
                                    objectKey = objectKey,
                                )
                                readAuthenticatedRange(
                                    encryptedFile = encryptedFile,
                                    reference = reference,
                                    core = core,
                                    coreBytes = coreBytes,
                                    dataOffset = dataOffset,
                                    objectKey = objectKey,
                                    offset = offset,
                                    byteCount = byteCount,
                                )
                            }
                        } finally {
                            objectKey.encoded?.fill(0)
                        }
                    } finally {
                        wrappedObjectKey.initializationVector.fill(0)
                        wrappedObjectKey.ciphertext.fill(0)
                    }
                } finally {
                    core.noncePrefix.fill(0)
                    coreBytes.fill(0)
                }
            } catch (failure: SecureStorageException) {
                throw failure
            } catch (failure: Exception) {
                throw SecureObjectAuthenticationException(failure)
            } finally {
                keyEnvelopeBytes.fill(0)
            }
        }
    }

    override suspend fun readRangeInto(
        reference: SecureObjectReference,
        offset: Long,
        byteCount: Int,
        destination: ByteArray,
        destinationOffset: Int,
    ): Int = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            require(offset >= 0) { "Plaintext offset cannot be negative." }
            require(byteCount in 0..MAX_RANDOM_ACCESS_READ_BYTES) { "Random-access read is too large." }
            require(destinationOffset in 0..destination.size)
            require(byteCount <= destination.size - destinationOffset)
            validateReference(reference)
            if (tombstoneFile(reference.vaultId).exists()) throw SecureVaultDestroyedException()
            if (byteCount == 0 || offset >= reference.plaintextLength) return@withLock 0

            val directory = objectDirectory(reference.vaultId, reference.purpose.formatCode, reference.objectId.value)
            val dataFile = File(directory, "${reference.keyEnvelopeId.value}$DATA_FILE_SUFFIX")
            val keyEnvelopeFile = File(directory, "${reference.keyEnvelopeId.value}$KEY_ENVELOPE_FILE_SUFFIX")
            val keyEnvelopeBytes = readBoundedKeyEnvelope(keyEnvelopeFile) ?: throw SecureObjectNotFoundException()

            try {
                if (!dataFile.isFile) throw SecureObjectNotFoundException()
                if (dataFile.length() != reference.ciphertextLength) throw SecureObjectAuthenticationException()
                val (core, coreBytes, dataOffset) = FileInputStream(dataFile).use { fileInput ->
                    val (decodedCore, decodedBytes) = DataInputStream(fileInput).readSecureDataHeader()
                    Triple(decodedCore, decodedBytes, fileInput.channel.position())
                }
                try {
                    authenticateReference(reference, core)
                    val wrappedObjectKey = decodeKeyEnvelope(keyEnvelopeBytes, coreBytes)
                    try {
                        val objectKey = unwrapObjectKey(reference.vaultId, wrappedObjectKey, coreBytes)
                        try {
                            migrateLegacyKeyEnvelope(reference.vaultId, keyEnvelopeFile, wrappedObjectKey, objectKey, coreBytes)
                            RandomAccessFile(dataFile, "r").use { encryptedFile ->
                                authenticateRandomAccessTerminal(
                                    encryptedFile = encryptedFile,
                                    reference = reference,
                                    core = core,
                                    coreBytes = coreBytes,
                                    dataOffset = dataOffset,
                                    objectKey = objectKey,
                                )
                                readAuthenticatedRangeInto(
                                    encryptedFile = encryptedFile,
                                    reference = reference,
                                    core = core,
                                    coreBytes = coreBytes,
                                    dataOffset = dataOffset,
                                    objectKey = objectKey,
                                    offset = offset,
                                    byteCount = byteCount,
                                    destination = destination,
                                    destinationOffset = destinationOffset,
                                )
                            }
                        } finally {
                            objectKey.encoded?.fill(0)
                        }
                    } finally {
                        wrappedObjectKey.initializationVector.fill(0)
                        wrappedObjectKey.ciphertext.fill(0)
                    }
                } finally {
                    core.noncePrefix.fill(0)
                    coreBytes.fill(0)
                }
            } catch (failure: SecureStorageException) {
                destination.fill(0, destinationOffset, destinationOffset + byteCount)
                throw failure
            } catch (failure: Exception) {
                destination.fill(0, destinationOffset, destinationOffset + byteCount)
                throw SecureObjectAuthenticationException(failure)
            } finally {
                keyEnvelopeBytes.fill(0)
            }
        }
    }

    private fun authenticateRandomAccessTerminal(
        encryptedFile: RandomAccessFile,
        reference: SecureObjectReference,
        core: SecureFileCore,
        coreBytes: ByteArray,
        dataOffset: Long,
        objectKey: SecretKey,
    ) {
        val fullChunks = reference.plaintextLength / core.chunkSize
        val remainder = reference.plaintextLength % core.chunkSize
        val chunkCount = fullChunks + if (remainder == 0L) 0 else 1
        require(chunkCount <= Int.MAX_VALUE)
        val fullRecordLength = CHUNK_RECORD_HEADER_BYTES + core.chunkSize.toLong() + GCM_TAG_LENGTH_BYTES
        val terminalOffset = dataOffset + fullChunks * fullRecordLength +
            if (remainder == 0L) 0 else CHUNK_RECORD_HEADER_BYTES + remainder + GCM_TAG_LENGTH_BYTES
        encryptedFile.seek(terminalOffset)
        if (encryptedFile.readInt() != TERMINAL_PLAINTEXT_LENGTH) throw SecureObjectAuthenticationException()
        if (encryptedFile.readInt() != GCM_TAG_LENGTH_BYTES) throw SecureObjectAuthenticationException()
        val ciphertext = ByteArray(GCM_TAG_LENGTH_BYTES).also(encryptedFile::readFully)
        try {
            authenticateTerminalChunk(
                key = objectKey,
                noncePrefix = core.noncePrefix,
                chunkIndex = chunkCount.toInt(),
                coreBytes = coreBytes,
                ciphertext = ciphertext,
            )
            if (encryptedFile.filePointer != encryptedFile.length()) throw SecureObjectAuthenticationException()
        } finally {
            ciphertext.fill(0)
        }
    }

    private fun readAuthenticatedRange(
        encryptedFile: RandomAccessFile,
        reference: SecureObjectReference,
        core: SecureFileCore,
        coreBytes: ByteArray,
        dataOffset: Long,
        objectKey: SecretKey,
        offset: Long,
        byteCount: Int,
    ): ByteArray {
        val readableBytes = minOf(byteCount.toLong(), reference.plaintextLength - offset).toInt()
        val output = ByteArray(readableBytes)
        val fullRecordLength = CHUNK_RECORD_HEADER_BYTES + core.chunkSize.toLong() + GCM_TAG_LENGTH_BYTES
        var outputOffset = 0
        var plaintextOffset = offset
        return try {
            while (outputOffset < output.size) {
                val chunkIndex = plaintextOffset / core.chunkSize
                require(chunkIndex <= Int.MAX_VALUE)
                val offsetInChunk = (plaintextOffset % core.chunkSize).toInt()
                val expectedPlaintextSize = minOf(
                    core.chunkSize.toLong(),
                    reference.plaintextLength - chunkIndex * core.chunkSize,
                ).toInt()
                val recordOffset = dataOffset + chunkIndex * fullRecordLength
                encryptedFile.seek(recordOffset)
                val plaintextSize = encryptedFile.readInt()
                val ciphertextSize = encryptedFile.readInt()
                if (plaintextSize != expectedPlaintextSize || ciphertextSize != plaintextSize + GCM_TAG_LENGTH_BYTES) {
                    throw SecureObjectAuthenticationException()
                }
                val ciphertext = ByteArray(ciphertextSize).also(encryptedFile::readFully)
                val plaintext = try {
                    decryptAuthenticatedDataChunk(
                        key = objectKey,
                        noncePrefix = core.noncePrefix,
                        chunkIndex = chunkIndex.toInt(),
                        coreBytes = coreBytes,
                        ciphertext = ciphertext,
                    )
                } finally {
                    ciphertext.fill(0)
                }
                try {
                    val copyLength = minOf(output.size - outputOffset, plaintext.size - offsetInChunk)
                    plaintext.copyInto(
                        destination = output,
                        destinationOffset = outputOffset,
                        startIndex = offsetInChunk,
                        endIndex = offsetInChunk + copyLength,
                    )
                    outputOffset += copyLength
                    plaintextOffset += copyLength
                } finally {
                    plaintext.fill(0)
                }
            }
            output
        } catch (failure: Exception) {
            output.fill(0)
            throw failure
        }
    }

    private fun readAuthenticatedRangeInto(
        encryptedFile: RandomAccessFile,
        reference: SecureObjectReference,
        core: SecureFileCore,
        coreBytes: ByteArray,
        dataOffset: Long,
        objectKey: SecretKey,
        offset: Long,
        byteCount: Int,
        destination: ByteArray,
        destinationOffset: Int,
    ): Int {
        val readableBytes = minOf(byteCount.toLong(), reference.plaintextLength - offset).toInt()
        var copiedBytes = 0
        var plaintextOffset = offset
        return try {
            while (copiedBytes < readableBytes) {
                val chunkIndex = plaintextOffset / core.chunkSize
                require(chunkIndex <= Int.MAX_VALUE)
                val offsetInChunk = (plaintextOffset % core.chunkSize).toInt()
                val expectedPlaintextSize = minOf(
                    core.chunkSize.toLong(),
                    reference.plaintextLength - chunkIndex * core.chunkSize,
                ).toInt()
                val fullRecordLength = CHUNK_RECORD_HEADER_BYTES + core.chunkSize.toLong() + GCM_TAG_LENGTH_BYTES
                val recordOffset = dataOffset + chunkIndex * fullRecordLength
                encryptedFile.seek(recordOffset)
                val plaintextSize = encryptedFile.readInt()
                val ciphertextSize = encryptedFile.readInt()
                if (plaintextSize != expectedPlaintextSize || ciphertextSize != plaintextSize + GCM_TAG_LENGTH_BYTES) {
                    throw SecureObjectAuthenticationException()
                }
                val ciphertext = ByteArray(ciphertextSize).also(encryptedFile::readFully)
                val plaintext = try {
                    decryptAuthenticatedDataChunk(
                        key = objectKey,
                        noncePrefix = core.noncePrefix,
                        chunkIndex = chunkIndex.toInt(),
                        coreBytes = coreBytes,
                        ciphertext = ciphertext,
                    )
                } finally {
                    ciphertext.fill(0)
                }
                try {
                    val copyLength = minOf(readableBytes - copiedBytes, plaintext.size - offsetInChunk)
                    plaintext.copyInto(
                        destination = destination,
                        destinationOffset = destinationOffset + copiedBytes,
                        startIndex = offsetInChunk,
                        endIndex = offsetInChunk + copyLength,
                    )
                    copiedBytes += copyLength
                    plaintextOffset += copyLength
                } finally {
                    plaintext.fill(0)
                }
            }
            copiedBytes
        } catch (failure: Exception) {
            destination.fill(0, destinationOffset, destinationOffset + byteCount)
            throw failure
        }
    }

    override suspend fun delete(reference: SecureObjectReference): Boolean = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            validateReference(reference)
            val identity = SecureObjectIdentity(reference.objectId, reference.vaultId, reference.purpose)
            val directory = objectDirectory(reference.vaultId, reference.purpose.formatCode, reference.objectId.value)
            val keyEnvelopeFile = File(directory, "${reference.keyEnvelopeId.value}$KEY_ENVELOPE_FILE_SUFFIX")
            val dataFile = File(directory, "${reference.keyEnvelopeId.value}$DATA_FILE_SUFFIX")
            // A stale/nonexistent generation must not release another live generation at this identity.
            if (!directory.exists() || keyEnvelopeFile.exists() || dataFile.exists()) transientMediaJournal.markReleased(identity)
            val keyDeleted = deleteIfPresent(keyEnvelopeFile)
            val dataDeleted = keyDeleted && deleteIfPresent(dataFile)
            removeEmptyParents(directory)
            if (keyDeleted && dataDeleted && !directory.exists()) {
                transientMediaJournal.entry(identity)?.let { transientMediaJournal.markDeleted(it, System.currentTimeMillis()) }
            }
            keyDeleted && dataDeleted
        }
    }

    override suspend fun deleteObject(identity: SecureObjectIdentity): Boolean = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            requireValidIdentifier(identity.objectId.value, "objectId")
            requireValidIdentifier(identity.vaultId.value, "vaultId")
            transientMediaJournal.markReleased(identity)
            val directory = objectDirectory(identity.vaultId, identity.purpose.formatCode, identity.objectId.value)
            val deleted = !directory.exists() || directory.deleteRecursively()
            if (deleted) removeEmptyParents(directory)
            if (deleted) transientMediaJournal.entry(identity)?.let { transientMediaJournal.markDeleted(it, System.currentTimeMillis()) }
            deleted
        }
    }

    override suspend fun deleteVault(vaultId: AccountVaultId): SecureVaultDeletionReport = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            requireValidIdentifier(vaultId.value, "vaultId")
            val tombstoneWritten = writeTombstone(vaultId)
            val keyDestroyed = tombstoneWritten && objectKeyEnvelopeService.destroyVaultKey(vaultId)
            val vaultDirectory = vaultDirectory(vaultId)
            val filesDeleted = keyDestroyed && (!vaultDirectory.exists() || vaultDirectory.deleteRecursively())
            SecureVaultDeletionReport(
                vaultId = vaultId,
                tombstoneWritten = tombstoneWritten,
                keyDestroyed = keyDestroyed,
                filesDeleted = filesDeleted,
            )
        }
    }

    override suspend fun runMaintenance(): SecureStoreMaintenanceReport = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            recoverTransientMediaObjectsLocked()
            val objectsRoot = File(roots.persistentRoot(), OBJECTS_DIRECTORY)
            if (!objectsRoot.exists()) return@withLock SecureStoreMaintenanceReport(0, 0, 0)
            val files = objectsRoot.walkTopDown().filter(File::isFile).toList()
            var partialFilesDeleted = 0
            var orphanDataFilesDeleted = 0
            var orphanKeyEnvelopesDeleted = 0
            files.filter { it.name.startsWith(PARTIAL_FILE_PREFIX) }.forEach { partial ->
                if (partial.delete()) partialFilesDeleted++
            }
            files.filter { it.name.endsWith(DATA_FILE_SUFFIX) }.forEach { dataFile ->
                val keyFile = File(dataFile.parentFile, dataFile.name.removeSuffix(DATA_FILE_SUFFIX) + KEY_ENVELOPE_FILE_SUFFIX)
                if (!keyFile.isFile && dataFile.delete()) orphanDataFilesDeleted++
            }
            files.filter { it.name.endsWith(KEY_ENVELOPE_FILE_SUFFIX) }.forEach { keyFile ->
                val dataFile = File(keyFile.parentFile, keyFile.name.removeSuffix(KEY_ENVELOPE_FILE_SUFFIX) + DATA_FILE_SUFFIX)
                if (!dataFile.isFile && keyFile.delete()) orphanKeyEnvelopesDeleted++
            }
            SecureStoreMaintenanceReport(partialFilesDeleted, orphanDataFilesDeleted, orphanKeyEnvelopesDeleted)
        }
    }

    private suspend fun writeEncryptedData(
        partialFile: File,
        objectKey: SecretKey,
        coreBytes: ByteArray,
        core: SecureFileCore,
        writePlaintext: suspend (java.io.OutputStream) -> Unit,
    ): Long {
        FileOutputStream(partialFile).use { fileOutput ->
            val dataOutput = DataOutputStream(fileOutput)
            dataOutput.writeSecureDataHeader(coreBytes)
            val encryptingOutput = ChunkedAeadOutputStream(
                output = dataOutput,
                key = objectKey,
                coreBytes = coreBytes,
                noncePrefix = core.noncePrefix,
                chunkSize = core.chunkSize,
            )
            writePlaintext(encryptingOutput)
            encryptingOutput.finish()
            dataOutput.flush()
            fileOutput.fd.sync()
            return encryptingOutput.plaintextLength
        }
    }

    private fun authenticateReference(reference: SecureObjectReference, core: SecureFileCore) {
        val valid = core.formatVersion == reference.formatVersion &&
            core.purpose == reference.purpose &&
            core.keyEnvelopeId == reference.keyEnvelopeId &&
            core.objectIdSha256.contentEquals(sha256(reference.objectId.value.encodeToByteArray())) &&
            core.vaultIdSha256.contentEquals(sha256(reference.vaultId.value.encodeToByteArray())) &&
            core.associatedDataSha256.toHex() == reference.associatedDataSha256
        if (!valid) throw SecureObjectAuthenticationException()
    }

    private suspend fun unwrapObjectKey(
        vaultId: AccountVaultId,
        wrappedObjectKey: WrappedObjectKey,
        coreBytes: ByteArray,
    ): SecretKey = try {
        objectKeyEnvelopeService.unwrap(vaultId, wrappedObjectKey, coreBytes)
    } catch (failure: SecureStorageException) {
        throw failure
    } catch (failure: Exception) {
        throw SecureObjectAuthenticationException(failure)
    }

    private fun validateWriteRequest(request: SecureObjectWriteRequest) {
        requireValidIdentifier(request.objectId.value, "objectId")
        requireValidIdentifier(request.vaultId.value, "vaultId")
        require(request.associatedData.size <= MAX_ASSOCIATED_DATA_LENGTH_BYTES) { "Associated data is too large." }
        val expectedPlaintextLength = request.expectedPlaintextLength
        require(expectedPlaintextLength == null || expectedPlaintextLength >= 0) { "Plaintext length cannot be negative." }
    }

    private fun validateReference(reference: SecureObjectReference) {
        requireValidIdentifier(reference.objectId.value, "objectId")
        requireValidIdentifier(reference.vaultId.value, "vaultId")
        require(reference.formatVersion == SECURE_FILE_FORMAT_VERSION)
        require(reference.plaintextLength >= 0)
        require(reference.ciphertextLength > 0)
        require(reference.keyEnvelopeId.value.matches(KEY_ENVELOPE_ID_PATTERN))
        require(reference.associatedDataSha256.matches(SHA256_PATTERN))
    }

    private fun requireValidIdentifier(value: String, field: String) {
        require(value.isNotBlank() && value.length <= MAX_IDENTIFIER_LENGTH) { "$field must be an opaque non-empty identifier." }
    }

    private fun objectDirectory(vaultId: AccountVaultId, purposeCode: Int, objectId: String): File = File(
        vaultDirectory(vaultId),
        "$purposeCode/${sha256Hex(objectId)}",
    ).requireInside(roots.persistentRoot())

    private fun vaultDirectory(vaultId: AccountVaultId): File = File(
        roots.persistentRoot(),
        "$OBJECTS_DIRECTORY/${sha256Hex(vaultId.value)}",
    ).requireInside(roots.persistentRoot())

    private fun tombstoneFile(vaultId: AccountVaultId): File = File(
        roots.persistentRoot(),
        "$TOMBSTONES_DIRECTORY/${sha256Hex(vaultId.value)}$TOMBSTONE_FILE_SUFFIX",
    ).requireInside(roots.persistentRoot())

    private fun writeTombstone(vaultId: AccountVaultId): Boolean {
        val tombstone = tombstoneFile(vaultId)
        if (tombstone.isFile) return true
        val tombstoneDirectory = requireNotNull(tombstone.parentFile).ensureDirectory()
        val partial = File(tombstoneDirectory, "$PARTIAL_FILE_PREFIX${tombstone.name}-${randomIdentifier()}")
        return try {
            FileOutputStream(partial).use { output ->
                DataOutputStream(output).use { data ->
                    data.write(TOMBSTONE_MAGIC)
                    data.writeInt(SECURE_FILE_FORMAT_VERSION)
                    data.flush()
                    output.fd.sync()
                }
            }
            publish(partial, tombstone)
            true
        } catch (failure: IOException) {
            false
        } finally {
            partial.delete()
        }
    }

    private fun readBoundedKeyEnvelope(file: File): ByteArray? = try {
        AtomicSecureRecordIO.read(file, MAX_KEY_ENVELOPE_FILE_LENGTH_BYTES)
    } catch (failure: IOException) {
        throw SecureObjectAuthenticationException(failure)
    }

    private suspend fun migrateLegacyKeyEnvelope(
        vaultId: AccountVaultId,
        keyEnvelopeFile: File,
        wrappedObjectKey: WrappedObjectKey,
        objectKey: SecretKey,
        coreBytes: ByteArray,
    ) {
        if (wrappedObjectKey.wrappingScheme == ObjectKeyWrappingScheme.ACCOUNT_VAULT_DOMAIN) return
        val upgraded = objectKeyEnvelopeService.wrap(vaultId, objectKey, coreBytes)
        AtomicSecureRecordIO.replace(keyEnvelopeFile, encodeKeyEnvelope(coreBytes, upgraded))
        if (
            wrappedObjectKey.wrappingScheme == ObjectKeyWrappingScheme.LEGACY_PER_VAULT_KEYSTORE &&
            !hasLegacyKeyEnvelopes(vaultId)
        ) {
            objectKeyEnvelopeService.retireLegacyVaultKey(vaultId)
        }
    }

    private fun hasLegacyKeyEnvelopes(vaultId: AccountVaultId): Boolean = vaultDirectory(vaultId)
        .walkTopDown()
        .filter { it.isFile && it.name.endsWith(KEY_ENVELOPE_FILE_SUFFIX) }
        .any { envelopeFile ->
            try {
                val bytes = readBoundedKeyEnvelope(envelopeFile) ?: return@any true
                decodeKeyEnvelopeWrappingScheme(bytes) == ObjectKeyWrappingScheme.LEGACY_PER_VAULT_KEYSTORE
            } catch (_: SecureStorageException) {
                true
            }
        }

    private fun writeAndSync(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
    }

    private fun publish(partial: File, destination: File) {
        check(!destination.exists())
        if (!partial.renameTo(destination)) throw IOException("Unable to atomically publish secure object.")
    }

    private fun File.ensureDirectory(): File = apply {
        if (!isDirectory && !mkdirs()) throw IOException("Unable to create secure storage directory.")
    }

    private fun File.requireInside(root: File): File = apply {
        val rootPath = root.canonicalFile.path + File.separator
        require(canonicalFile.path.startsWith(rootPath)) { "Secure storage path escaped its root." }
    }

    private fun cleanupPartials(directory: File) {
        directory.listFiles { file -> file.name.startsWith(PARTIAL_FILE_PREFIX) }
            ?.forEach(File::delete)
    }

    private fun deleteIfPresent(file: File): Boolean = !file.exists() || file.delete()

    private fun removeEmptyParents(start: File) {
        val objectsRoot = File(roots.persistentRoot(), OBJECTS_DIRECTORY).canonicalFile
        var directory: File? = start
        while (directory != null && directory.canonicalFile != objectsRoot && directory.listFiles()?.isEmpty() == true) {
            if (!directory.delete()) return
            directory = directory.parentFile
        }
    }

    private fun randomIdentifier(): String = UUID.randomUUID().toString().replace("-", "")

    private companion object {
        const val AES_ALGORITHM = "AES"
        const val OBJECT_KEY_SIZE_BITS = 256
        const val MAX_IDENTIFIER_LENGTH = 1024
        const val MAX_ASSOCIATED_DATA_LENGTH_BYTES = 64 * 1024
        const val MAX_KEY_ENVELOPE_FILE_LENGTH_BYTES = 4 * 1024L
        const val MAX_RANDOM_ACCESS_READ_BYTES = 4 * 1024 * 1024
        const val CHUNK_RECORD_HEADER_BYTES = Int.SIZE_BYTES * 2L
        const val TERMINAL_PLAINTEXT_LENGTH = -1
        const val OBJECTS_DIRECTORY = "objects"
        const val TOMBSTONES_DIRECTORY = "tombstones"
        const val DATA_FILE_SUFFIX = ".yzsf"
        const val KEY_ENVELOPE_FILE_SUFFIX = ".yzkey"
        const val TOMBSTONE_FILE_SUFFIX = ".destroyed"
        const val PARTIAL_FILE_PREFIX = ".partial-"
        val KEY_ENVELOPE_ID_PATTERN = Regex("[0-9a-f]{32}")
        val SHA256_PATTERN = Regex("[0-9a-f]{64}")
        val TOMBSTONE_MAGIC = "YZVT0001".encodeToByteArray()
    }
}
