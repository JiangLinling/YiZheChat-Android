/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.security.securestorage.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.security.securestorage.api.SecureMediaCleanupException
import io.element.android.security.securestorage.api.SecureObjectReference
import io.element.android.security.securestorage.api.SecureObjectStagingCleanupItem
import io.element.android.security.securestorage.api.SecureObjectStagingCleanupReport
import io.element.android.security.securestorage.api.SecureObjectStagingStore
import io.element.android.security.securestorage.api.SecureObjectStagingTransaction
import io.element.android.security.securestorage.api.SecureObjectWriteRequest
import io.element.android.security.securestorage.api.SecureStorageRoots
import io.element.android.security.securestorage.api.SensitiveFileStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Process-owned encrypted staging in front of [SensitiveFileStore]. The staging key is never persisted, so a
 * process-death remnant is ciphertext without its key and is removed by the next maintenance pass.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DefaultSecureObjectStagingStore(
    private val roots: SecureStorageRoots,
    private val sensitiveFileStore: SensitiveFileStore,
) : SecureObjectStagingStore {
    private val activeTransactions = ConcurrentHashMap.newKeySet<String>()
    private val secureRandom = SecureRandom()

    override suspend fun stage(
        request: SecureObjectWriteRequest,
        writePlaintext: suspend (java.io.OutputStream) -> Unit,
    ): SecureObjectStagingTransaction = withContext(Dispatchers.IO) {
        sensitiveFileStore.registerTransientMediaObject(request)
        val transactionId = UUID.randomUUID().toString().replace("-", "")
        val directory = File(roots.runtimeRoot(), STAGING_DIRECTORY).canonicalFile.apply {
            if (!isDirectory && !mkdirs()) error("Unable to create secure staging directory.")
        }
        require(directory.path.startsWith(roots.runtimeRoot().canonicalFile.path + File.separator))
        cleanupInactive(directory)
        val encryptedFile = File(directory, "$transactionId$STAGING_SUFFIX")
        val keyBytes = ByteArray(KEY_LENGTH_BYTES).also(secureRandom::nextBytes)
        val nonce = ByteArray(NONCE_LENGTH_BYTES).also(secureRandom::nextBytes)
        activeTransactions += transactionId
        try {
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, AES_ALGORITHM), GCMParameterSpec(TAG_LENGTH_BITS, nonce))
                updateAAD(request.stagingAad())
            }
            var plaintextLength = 0L
            FileOutputStream(encryptedFile).use { fileOutput ->
                CipherOutputStream(fileOutput, cipher).use { encryptedOutput ->
                    val counting = object : java.io.OutputStream() {
                        override fun write(value: Int) {
                            encryptedOutput.write(value)
                            plaintextLength++
                        }

                        override fun write(bytes: ByteArray, offset: Int, length: Int) {
                            encryptedOutput.write(bytes, offset, length)
                            plaintextLength += length
                        }

                        override fun flush() = encryptedOutput.flush()
                    }
                    writePlaintext(counting)
                    counting.flush()
                }
            }
            RandomAccessFile(encryptedFile, "rw").use { it.fd.sync() }
            request.expectedPlaintextLength?.let { expected ->
                require(plaintextLength == expected) { "Plaintext length does not match the declared length." }
            }
            Transaction(transactionId, request, plaintextLength, encryptedFile, keyBytes, nonce)
        } catch (failure: Throwable) {
            val removed = !encryptedFile.exists() || encryptedFile.delete()
            activeTransactions -= transactionId
            keyBytes.fill(0)
            nonce.fill(0)
            if (!removed) {
                throw SecureMediaCleanupException(
                    SecureObjectStagingCleanupReport(
                        transactionId,
                        listOf(SecureObjectStagingCleanupItem("encrypted-staging", false)),
                    ),
                    failure,
                )
            }
            throw failure
        }
    }

    private inner class Transaction(
        override val transactionId: String,
        override val request: SecureObjectWriteRequest,
        override val plaintextLength: Long,
        private val encryptedFile: File,
        private val keyBytes: ByteArray,
        private val nonce: ByteArray,
    ) : SecureObjectStagingTransaction {
        private val mutex = Mutex()
        private var state = State.SEALED

        override suspend fun commit(): SecureObjectReference = mutex.withLock {
            check(state == State.SEALED) { "Secure staging transaction was already consumed." }
            try {
                val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION).apply {
                    init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, AES_ALGORITHM), GCMParameterSpec(TAG_LENGTH_BITS, nonce))
                    updateAAD(request.stagingAad())
                }
                val reference = sensitiveFileStore.write(request) { secureOutput ->
                    FileInputStream(encryptedFile).use { fileInput ->
                        CipherInputStream(fileInput, cipher).use { plaintextInput ->
                            plaintextInput.copyTo(secureOutput, COPY_BUFFER_BYTES)
                        }
                    }
                }
                val report = cleanup(State.COMMITTED)
                if (!report.complete) {
                    val objectRemoved = sensitiveFileStore.delete(reference)
                    throw SecureMediaCleanupException(
                        report.copy(items = report.items + SecureObjectStagingCleanupItem("committed-object", objectRemoved))
                    )
                }
                reference
            } catch (failure: Throwable) {
                val report = withContext(NonCancellable) { cleanup(State.ABORTED) }
                if (!report.complete && failure !is SecureMediaCleanupException) {
                    throw SecureMediaCleanupException(report, failure)
                }
                throw failure
            }
        }

        override suspend fun abort(): SecureObjectStagingCleanupReport = mutex.withLock {
            cleanup(State.ABORTED)
        }

        private fun cleanup(finalState: State): SecureObjectStagingCleanupReport {
            val removed = !encryptedFile.exists() || encryptedFile.delete()
            keyBytes.fill(0)
            nonce.fill(0)
            activeTransactions -= transactionId
            if (state == State.SEALED) state = finalState
            return SecureObjectStagingCleanupReport(
                transactionId,
                listOf(SecureObjectStagingCleanupItem("encrypted-staging", removed)),
            )
        }
    }

    private fun cleanupInactive(directory: File) {
        directory.listFiles { file -> file.name.endsWith(STAGING_SUFFIX) }
            ?.filterNot { it.name.removeSuffix(STAGING_SUFFIX) in activeTransactions }
            ?.forEach(File::delete)
    }

    private fun SecureObjectWriteRequest.stagingAad(): ByteArray = buildString {
        append("yizhe-authorized-staging-v1\u0000")
        append(vaultId.value)
        append('\u0000')
        append(objectId.value)
        append('\u0000')
        append(purpose.formatCode)
        append('\u0000')
        append(associatedDataSha256())
    }.encodeToByteArray()

    private fun SecureObjectWriteRequest.associatedDataSha256(): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(associatedData).joinToString("") { "%02x".format(it) }

    private enum class State { SEALED, COMMITTED, ABORTED }

    private companion object {
        const val STAGING_DIRECTORY = "authorized-media-staging"
        const val STAGING_SUFFIX = ".encrypted-staging"
        const val AES_ALGORITHM = "AES"
        const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_LENGTH_BYTES = 32
        const val NONCE_LENGTH_BYTES = 12
        const val TAG_LENGTH_BITS = 128
        const val COPY_BUFFER_BYTES = 64 * 1024
    }
}
