/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.security.dataprotection.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.security.dataprotection.api.AccountActivationReceipt
import io.element.android.security.dataprotection.api.AccountIdentityContinuity
import io.element.android.security.dataprotection.api.AccountReactivationAttempt
import io.element.android.security.dataprotection.api.AccountReactivationCheckpoint
import io.element.android.security.dataprotection.api.AccountReactivationCheckpointState
import io.element.android.security.dataprotection.api.AccountReactivationScopeStore
import io.element.android.security.dataprotection.api.HistoryRecoveryAttempt
import io.element.android.security.dataprotection.api.PreparedAccountReactivation
import io.element.android.security.securestorage.api.DeviceRootKeyService
import io.element.android.security.securestorage.api.DeviceWrappedKey
import io.element.android.security.securestorage.api.SecureStorageRoots
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val REACTIVATION_DIRECTORY = "account-reactivation"
private const val REACTIVATION_PREFIX = "scope-"
private const val REACTIVATION_PARTIAL_PREFIX = ".partial-"
private const val REACTIVATION_SUFFIX = ".yzra"
private const val REACTIVATION_MIN_FILE_FORMAT_VERSION = 1
private const val REACTIVATION_FILE_FORMAT_VERSION = 6
private const val REACTIVATION_REVISION_WIDTH = 8
private const val REACTIVATION_RETAINED_REVISIONS = 2
private const val REACTIVATION_MAX_REVISION = 99_999_999
private const val REACTIVATION_RECORD_KEY_BYTES = 32
private const val REACTIVATION_GCM_TAG_BITS = 128
private const val REACTIVATION_AES = "AES"
private const val REACTIVATION_AES_GCM = "AES/GCM/NoPadding"
private const val REACTIVATION_BINDING_DOMAIN = "yizhe-account-reactivation-v1"
private const val REACTIVATION_MAX_INSTALLATION_HASH_BYTES = 32
private const val REACTIVATION_MAX_NONCE_BYTES = 32
private const val REACTIVATION_MAX_WRAPPED_KEY_BYTES = 8192
private const val REACTIVATION_MAX_CIPHERTEXT_BYTES = 16 * 1024
private const val REACTIVATION_MAX_RECORD_BYTES = 24L * 1024L
private const val REACTIVATION_MAX_HISTORY_ROOM_SCOPES = 512
private val REACTIVATION_FILE_MAGIC = "YZRA0001".encodeToByteArray()
private val REACTIVATION_HASH_PATTERN = Regex("[0-9a-f]{64}")
private val REACTIVATION_OPAQUE_PATTERN = Regex("[A-Za-z0-9_-]{20,128}")
private val REACTIVATION_FILE_PATTERN = Regex("scope-([0-9a-f]{64})-([0-9]{8})\\.yzra")

/**
 * Installation-bound, authenticated reactivation checkpoint.
 *
 * A new generation is fsynced before it is returned to the caller. Two authenticated revisions
 * are retained so an interrupted write can fall back without silently resetting the monotonic
 * counter. If neither revision authenticates, activation fails closed.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class EncryptedAccountReactivationScopeStore(
    private val roots: SecureStorageRoots,
    private val deviceRootKeyService: DeviceRootKeyService,
) : AccountReactivationScopeStore {
    private val mutex = Mutex()
    private val random = SecureRandom()

    override suspend fun current(
        accountIdHash: String,
        installationId: String,
        matrixDeviceIdHash: String,
    ): AccountReactivationCheckpoint? = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(REACTIVATION_HASH_PATTERN.matches(accountIdHash))
            require(installationId.length in 16..256)
            require(REACTIVATION_HASH_PATTERN.matches(matrixDeviceIdHash))
            latest(recordId(accountIdHash, installationId))
                ?.takeIf { checkpoint -> checkpoint.attempt.matrixDeviceIdHash == matrixDeviceIdHash }
        }
    }

    override suspend fun requiresCoreReadyEnforcement(
        accountIdHash: String,
        installationId: String,
        matrixDeviceIdHash: String,
    ): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(REACTIVATION_HASH_PATTERN.matches(accountIdHash))
            require(installationId.length in 16..256)
            require(REACTIVATION_HASH_PATTERN.matches(matrixDeviceIdHash))
            val recordId = recordId(accountIdHash, installationId)
            val files = revisionFiles(recordId)
            if (files.isEmpty()) return@withLock false

            // latest() authenticates the retained revisions. If every copy is corrupt it throws,
            // and the caller must keep the navigation gate closed rather than classifying this
            // installation as a fresh login.
            val checkpoint = requireNotNull(latest(recordId))
            checkpoint.reactivationRequired || checkpoint.attempt.matrixDeviceIdHash != matrixDeviceIdHash
        }
    }

    override suspend fun markAccountRemoved(
        accountIdHash: String,
        installationId: String,
        matrixDeviceIdHash: String,
    ): Boolean = updateAccountRemovalBoundary(
        accountIdHash = accountIdHash,
        installationId = installationId,
        matrixDeviceIdHash = matrixDeviceIdHash,
        reactivationRequired = true,
    )

    override suspend fun restoreAccountActive(
        accountIdHash: String,
        installationId: String,
        matrixDeviceIdHash: String,
    ): Boolean = updateAccountRemovalBoundary(
        accountIdHash = accountIdHash,
        installationId = installationId,
        matrixDeviceIdHash = matrixDeviceIdHash,
        reactivationRequired = false,
    )

    override suspend fun markExplicitIdentityReset(
        accountIdHash: String,
        installationId: String,
        matrixDeviceIdHash: String,
    ): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(REACTIVATION_HASH_PATTERN.matches(accountIdHash))
            require(installationId.length in 16..256)
            require(REACTIVATION_HASH_PATTERN.matches(matrixDeviceIdHash))
            val recordId = recordId(accountIdHash, installationId)
            val previous = if (revisionFiles(recordId).isEmpty()) null else latest(recordId)
            if (previous?.pendingExplicitIdentityReset == true &&
                previous.attempt.matrixDeviceIdHash == matrixDeviceIdHash
            ) {
                return@withLock true
            }
            previous?.let { checkpoint ->
                check(checkpoint.attempt.accountIdHash == accountIdHash)
                check(checkpoint.attempt.installationId == installationId)
            }
            val previousAttempt = previous?.attempt
            val sameDeviceScope = previousAttempt?.matrixDeviceIdHash == matrixDeviceIdHash
            val now = System.currentTimeMillis()
            val resetAttempt = AccountReactivationAttempt(
                accountIdHash = accountIdHash,
                installationId = installationId,
                matrixDeviceIdHash = matrixDeviceIdHash,
                clientScopeId = if (sameDeviceScope) checkNotNull(previousAttempt).clientScopeId else opaqueId(),
                clientGeneration = if (sameDeviceScope) checkNotNull(previousAttempt).clientGeneration + 1 else 1,
                scopeStartedAtEpochMillis = if (sameDeviceScope) {
                    checkNotNull(previousAttempt).scopeStartedAtEpochMillis
                } else {
                    maxOf(now, (previousAttempt?.scopeStartedAtEpochMillis ?: 0L) + 1L)
                },
                idempotencyKey = opaqueId(),
            )
            val resetCheckpoint = AccountReactivationCheckpoint(
                attempt = resetAttempt,
                state = AccountReactivationCheckpointState.ALLOCATED,
                historyRecoveryGeneration = previous?.historyRecoveryGeneration ?: 0L,
                pendingHistoryRoomScopeHashes = emptySet(),
                reactivationRequired = true,
                pendingExplicitIdentityReset = true,
            )
            validate(resetCheckpoint)
            writeNextRevision(recordId, encode(resetCheckpoint))
            true
        }
    }

    override suspend fun nextAttempt(
        accountIdHash: String,
        installationId: String,
        matrixDeviceIdHash: String,
    ): AccountReactivationAttempt = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(REACTIVATION_HASH_PATTERN.matches(accountIdHash))
            require(installationId.length in 16..256)
            require(REACTIVATION_HASH_PATTERN.matches(matrixDeviceIdHash))
            val recordId = recordId(accountIdHash, installationId)
            val existingFiles = revisionFiles(recordId).sortedByDescending(::revisionOf)
            val previousCheckpoint = if (existingFiles.isEmpty()) null else latest(recordId)
            val previous = previousCheckpoint?.attempt
            previous?.let {
                check(it.accountIdHash == accountIdHash && it.installationId == installationId)
            }
            val now = System.currentTimeMillis()
            val sameDeviceScope = previous?.matrixDeviceIdHash == matrixDeviceIdHash
            val attempt = AccountReactivationAttempt(
                accountIdHash = accountIdHash,
                installationId = installationId,
                matrixDeviceIdHash = matrixDeviceIdHash,
                clientScopeId = if (sameDeviceScope) checkNotNull(previous).clientScopeId else opaqueId(),
                clientGeneration = if (sameDeviceScope) checkNotNull(previous).clientGeneration + 1 else 1,
                scopeStartedAtEpochMillis = if (sameDeviceScope) {
                    checkNotNull(previous).scopeStartedAtEpochMillis
                } else {
                    maxOf(now, (previous?.scopeStartedAtEpochMillis ?: 0L) + 1L)
                },
                idempotencyKey = opaqueId(),
            )
            validate(attempt)
            writeNextRevision(
                recordId,
                encode(
                    AccountReactivationCheckpoint(
                        attempt = attempt,
                        state = AccountReactivationCheckpointState.ALLOCATED,
                        historyRecoveryGeneration = previousCheckpoint?.historyRecoveryGeneration ?: 0L,
                        reactivationRequired = previousCheckpoint?.reactivationRequired ?: false,
                        pendingExplicitIdentityReset = previousCheckpoint?.pendingExplicitIdentityReset ?: false,
                    )
                ),
            )
            attempt
        }
    }

    override suspend fun markPrepared(
        attempt: AccountReactivationAttempt,
        prepared: PreparedAccountReactivation,
    ): AccountReactivationCheckpoint = advance(attempt) { current ->
        check(current.state == AccountReactivationCheckpointState.ALLOCATED)
        require(prepared.activationId.matches(REACTIVATION_OPAQUE_PATTERN))
        require(prepared.expiresAtEpochMillis > 0)
        current.copy(
            state = AccountReactivationCheckpointState.PREPARED,
            prepared = prepared,
            receipt = null,
        )
    }

    override suspend fun markCommitted(
        attempt: AccountReactivationAttempt,
        receipt: AccountActivationReceipt,
    ): AccountReactivationCheckpoint = advance(attempt) { current ->
        check(current.state == AccountReactivationCheckpointState.PREPARED)
        validateReceipt(attempt, receipt)
        check((receipt.identityContinuity == AccountIdentityContinuity.RESET_CONFIRMED) == current.pendingExplicitIdentityReset) {
            "Signed identity continuity does not match the local reset lifecycle."
        }
        current.copy(
            state = AccountReactivationCheckpointState.COMMITTED,
            receipt = receipt,
            reactivationRequired = false,
            pendingExplicitIdentityReset = false,
        )
    }

    override suspend fun nextHistoryRecoveryAttempt(
        accountIdHash: String,
        installationId: String,
        matrixDeviceIdHash: String,
        roomScopeIds: Set<String>,
    ): HistoryRecoveryAttempt = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(REACTIVATION_HASH_PATTERN.matches(accountIdHash))
            require(installationId.length in 16..256)
            require(REACTIVATION_HASH_PATTERN.matches(matrixDeviceIdHash))
            require(roomScopeIds.isNotEmpty() && roomScopeIds.none(String::isBlank))
            val recordId = recordId(accountIdHash, installationId)
            val current = requireNotNull(latest(recordId)) { "Committed account activation is unavailable." }
            check(current.state == AccountReactivationCheckpointState.COMMITTED)
            check(current.attempt.matrixDeviceIdHash == matrixDeviceIdHash)
            val generation = current.historyRecoveryGeneration + 1L
            check(generation > 0L)
            val roomScopeHashes = roomScopeIds.mapTo(sortedSetOf()) { roomId ->
                sha256(roomId.encodeToByteArray()).toHex()
            }
            val updated = current.copy(
                historyRecoveryGeneration = generation,
                pendingHistoryRoomScopeHashes = roomScopeHashes,
            )
            validate(updated)
            writeNextRevision(recordId, encode(updated))
            HistoryRecoveryAttempt(
                accountIdHash = accountIdHash,
                installationId = installationId,
                matrixDeviceIdHash = matrixDeviceIdHash,
                generation = generation,
                roomScopeHashes = roomScopeHashes,
            )
        }
    }

    override suspend fun clearPermanentlyDeletedAccount(
        accountIdHash: String,
        installationId: String,
    ): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(REACTIVATION_HASH_PATTERN.matches(accountIdHash))
            require(installationId.length in 16..256)
            val recordId = recordId(accountIdHash, installationId)
            val revisions = revisionFiles(recordId)
            revisions.forEach { file ->
                if (file.exists() && !file.delete()) return@withLock false
            }
            revisionFiles(recordId).isEmpty()
        }
    }

    private suspend fun updateAccountRemovalBoundary(
        accountIdHash: String,
        installationId: String,
        matrixDeviceIdHash: String,
        reactivationRequired: Boolean,
    ): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(REACTIVATION_HASH_PATTERN.matches(accountIdHash))
            require(installationId.length in 16..256)
            require(REACTIVATION_HASH_PATTERN.matches(matrixDeviceIdHash))
            val recordId = recordId(accountIdHash, installationId)
            val files = revisionFiles(recordId)
            val current = if (files.isEmpty()) {
                val now = System.currentTimeMillis()
                AccountReactivationCheckpoint(
                    attempt = AccountReactivationAttempt(
                        accountIdHash = accountIdHash,
                        installationId = installationId,
                        matrixDeviceIdHash = matrixDeviceIdHash,
                        clientScopeId = opaqueId(),
                        clientGeneration = 1,
                        scopeStartedAtEpochMillis = now,
                        idempotencyKey = opaqueId(),
                    ),
                    state = AccountReactivationCheckpointState.ALLOCATED,
                )
            } else {
                requireNotNull(latest(recordId))
            }
            check(current.attempt.accountIdHash == accountIdHash)
            check(current.attempt.installationId == installationId)
            if (!reactivationRequired) {
                check(current.attempt.matrixDeviceIdHash == matrixDeviceIdHash) {
                    "A different Matrix device cannot roll back an account-removal boundary."
                }
            }
            if (current.reactivationRequired == reactivationRequired) return@withLock true
            val updated = current.copy(reactivationRequired = reactivationRequired)
            validate(updated)
            writeNextRevision(recordId, encode(updated))
            true
        }
    }

    private suspend fun advance(
        attempt: AccountReactivationAttempt,
        transform: (AccountReactivationCheckpoint) -> AccountReactivationCheckpoint,
    ): AccountReactivationCheckpoint = withContext(Dispatchers.IO) {
        mutex.withLock {
            validate(attempt)
            val recordId = recordId(attempt.accountIdHash, attempt.installationId)
            val current = requireNotNull(latest(recordId)) { "Account reactivation checkpoint is unavailable." }
            check(current.attempt == attempt) { "Account reactivation checkpoint generation changed." }
            val updated = transform(current)
            validate(updated)
            writeNextRevision(recordId, encode(updated))
            updated
        }
    }

    private suspend fun latest(recordId: String): AccountReactivationCheckpoint? {
        val files = revisionFiles(recordId).sortedByDescending(::revisionOf)
        if (files.isEmpty()) return null
        return files.firstNotNullOfOrNull { file -> runCatching { decode(file, recordId) }.getOrNull() }
            ?: error("Account reactivation checkpoint cannot be authenticated.")
    }

    private suspend fun encode(checkpoint: AccountReactivationCheckpoint): ByteArray {
        validate(checkpoint)
        val record = checkpoint.attempt
        val plaintext = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeUTF(record.accountIdHash)
                output.writeUTF(record.installationId)
                output.writeUTF(record.matrixDeviceIdHash)
                output.writeUTF(record.clientScopeId)
                output.writeLong(record.clientGeneration)
                output.writeLong(record.scopeStartedAtEpochMillis)
                output.writeUTF(record.idempotencyKey)
                output.writeUTF(checkpoint.state.name)
                output.writeBoolean(checkpoint.prepared != null)
                checkpoint.prepared?.let { prepared ->
                    output.writeUTF(prepared.activationId)
                    output.writeLong(prepared.expiresAtEpochMillis)
                }
                output.writeBoolean(checkpoint.receipt != null)
                checkpoint.receipt?.let { receipt -> output.writeReceipt(receipt) }
                output.writeLong(checkpoint.historyRecoveryGeneration)
                output.writeInt(checkpoint.pendingHistoryRoomScopeHashes.size)
                checkpoint.pendingHistoryRoomScopeHashes.sorted().forEach(output::writeUTF)
                output.writeBoolean(checkpoint.reactivationRequired)
                output.writeBoolean(checkpoint.pendingExplicitIdentityReset)
            }
            bytes.toByteArray()
        }
        val recordKey = ByteArray(REACTIVATION_RECORD_KEY_BYTES).also(random::nextBytes)
        return try {
            val binding = binding(recordId(record.accountIdHash, record.installationId))
            val wrappedKey = deviceRootKeyService.wrapKey(recordKey, binding)
            val cipher = Cipher.getInstance(REACTIVATION_AES_GCM).apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(recordKey, REACTIVATION_AES))
                updateAAD(binding)
            }
            val ciphertext = cipher.doFinal(plaintext)
            ByteArrayOutputStream().use { bytes ->
                DataOutputStream(bytes).use { output ->
                    output.write(REACTIVATION_FILE_MAGIC)
                    output.writeInt(REACTIVATION_FILE_FORMAT_VERSION)
                    output.writeInt(wrappedKey.formatVersion)
                    output.writeLong(wrappedKey.rootKeyVersion)
                    output.writeSized(wrappedKey.installationIdSha256)
                    output.writeSized(wrappedKey.initializationVector)
                    output.writeSized(wrappedKey.ciphertext)
                    output.writeSized(cipher.iv)
                    output.writeSized(ciphertext)
                }
                bytes.toByteArray()
            }
        } finally {
            plaintext.fill(0)
            recordKey.fill(0)
        }
    }

    private suspend fun decode(file: File, expectedRecordId: String): AccountReactivationCheckpoint {
        require(file.length() in 1..REACTIVATION_MAX_RECORD_BYTES)
        val input = DataInputStream(ByteArrayInputStream(file.readBytes()))
        val magic = ByteArray(REACTIVATION_FILE_MAGIC.size).also(input::readFully)
        check(magic.contentEquals(REACTIVATION_FILE_MAGIC))
        val fileFormatVersion = input.readInt()
        check(fileFormatVersion in REACTIVATION_MIN_FILE_FORMAT_VERSION..REACTIVATION_FILE_FORMAT_VERSION)
        val wrappedKey = DeviceWrappedKey(
            formatVersion = input.readInt(),
            rootKeyVersion = input.readLong(),
            installationIdSha256 = input.readSized(REACTIVATION_MAX_INSTALLATION_HASH_BYTES),
            initializationVector = input.readSized(REACTIVATION_MAX_NONCE_BYTES),
            ciphertext = input.readSized(REACTIVATION_MAX_WRAPPED_KEY_BYTES),
        )
        val nonce = input.readSized(REACTIVATION_MAX_NONCE_BYTES)
        val ciphertext = input.readSized(REACTIVATION_MAX_CIPHERTEXT_BYTES)
        check(input.available() == 0)
        val binding = binding(expectedRecordId)
        val recordKey = deviceRootKeyService.unwrapKey(wrappedKey, binding)
        val plaintext = try {
            Cipher.getInstance(REACTIVATION_AES_GCM).run {
                init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(recordKey, REACTIVATION_AES),
                    GCMParameterSpec(REACTIVATION_GCM_TAG_BITS, nonce),
                )
                updateAAD(binding)
                doFinal(ciphertext)
            }
        } finally {
            recordKey.fill(0)
        }
        return try {
            DataInputStream(ByteArrayInputStream(plaintext)).use { decoded ->
                val attempt = AccountReactivationAttempt(
                    accountIdHash = decoded.readUTF(),
                    installationId = decoded.readUTF(),
                    matrixDeviceIdHash = decoded.readUTF(),
                    clientScopeId = decoded.readUTF(),
                    clientGeneration = decoded.readLong(),
                    scopeStartedAtEpochMillis = decoded.readLong(),
                    idempotencyKey = decoded.readUTF(),
                )
                val checkpoint = if (fileFormatVersion == 1) {
                    AccountReactivationCheckpoint(attempt, AccountReactivationCheckpointState.ALLOCATED)
                } else {
                    val state = AccountReactivationCheckpointState.valueOf(decoded.readUTF())
                    val prepared = if (decoded.readBoolean()) {
                        PreparedAccountReactivation(decoded.readUTF(), decoded.readLong())
                    } else {
                        null
                    }
                    val receipt = if (decoded.readBoolean()) decoded.readReceipt(fileFormatVersion) else null
                    val historyRecoveryGeneration = if (fileFormatVersion >= 3) decoded.readLong() else 0L
                    val pendingHistoryRoomScopeHashes = if (fileFormatVersion >= 3) {
                        val count = decoded.readInt()
                        require(count in 0..REACTIVATION_MAX_HISTORY_ROOM_SCOPES)
                        buildSet(count) { repeat(count) { add(decoded.readUTF()) } }
                    } else {
                        emptySet()
                    }
                    AccountReactivationCheckpoint(
                        attempt = attempt,
                        state = state,
                        prepared = prepared,
                        receipt = receipt,
                        historyRecoveryGeneration = historyRecoveryGeneration,
                        pendingHistoryRoomScopeHashes = pendingHistoryRoomScopeHashes,
                        reactivationRequired = if (fileFormatVersion >= 4) decoded.readBoolean() else false,
                        pendingExplicitIdentityReset = if (fileFormatVersion >= 5) decoded.readBoolean() else false,
                    )
                }
                check(decoded.available() == 0)
                validate(checkpoint)
                check(recordId(attempt.accountIdHash, attempt.installationId) == expectedRecordId)
                checkpoint
            }
        } finally {
            plaintext.fill(0)
        }
    }

    private fun writeNextRevision(recordId: String, bytes: ByteArray) {
        val currentFiles = revisionFiles(recordId)
        val nextRevision = (currentFiles.maxOfOrNull(::revisionOf) ?: 0) + 1
        check(nextRevision <= REACTIVATION_MAX_REVISION)
        val target = File(
            directory(),
            "$REACTIVATION_PREFIX$recordId-${nextRevision.toString().padStart(REACTIVATION_REVISION_WIDTH, '0')}$REACTIVATION_SUFFIX",
        )
        val partial = File(directory(), "$REACTIVATION_PARTIAL_PREFIX${UUID.randomUUID()}")
        try {
            FileOutputStream(partial).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            check(partial.renameTo(target)) { "Unable to publish account reactivation checkpoint." }
            revisionFiles(recordId)
                .sortedByDescending(::revisionOf)
                .drop(REACTIVATION_RETAINED_REVISIONS)
                .forEach(File::delete)
        } finally {
            bytes.fill(0)
            partial.delete()
        }
    }

    private fun directory(): File = File(roots.persistentRoot(), REACTIVATION_DIRECTORY).also {
        check(it.isDirectory || it.mkdirs()) { "Unable to create account reactivation checkpoint directory." }
        it.listFiles().orEmpty().filter { file -> file.name.startsWith(REACTIVATION_PARTIAL_PREFIX) }.forEach(File::delete)
    }

    private fun revisionFiles(recordId: String): List<File> = directory().listFiles().orEmpty().filter { file ->
        REACTIVATION_FILE_PATTERN.matchEntire(file.name)?.groupValues?.get(1) == recordId
    }

    private fun revisionOf(file: File): Int =
        requireNotNull(REACTIVATION_FILE_PATTERN.matchEntire(file.name)).groupValues[2].toInt()
    private fun recordId(accountIdHash: String, installationId: String): String =
        sha256("$accountIdHash\u0000$installationId".encodeToByteArray()).toHex()
    private fun binding(recordId: String): ByteArray = "$REACTIVATION_BINDING_DOMAIN\u0000$recordId".encodeToByteArray()
    private fun opaqueId(): String = UUID.randomUUID().toString().replace("-", "")
    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun validate(record: AccountReactivationAttempt) {
        require(REACTIVATION_HASH_PATTERN.matches(record.accountIdHash))
        require(record.installationId.length in 16..256)
        require(REACTIVATION_HASH_PATTERN.matches(record.matrixDeviceIdHash))
        require(REACTIVATION_OPAQUE_PATTERN.matches(record.clientScopeId))
        require(record.clientGeneration > 0)
        require(record.scopeStartedAtEpochMillis > 0)
        require(REACTIVATION_OPAQUE_PATTERN.matches(record.idempotencyKey))
    }

    private fun validate(checkpoint: AccountReactivationCheckpoint) {
        validate(checkpoint.attempt)
        require(checkpoint.historyRecoveryGeneration >= 0L)
        require(checkpoint.pendingHistoryRoomScopeHashes.size <= REACTIVATION_MAX_HISTORY_ROOM_SCOPES)
        require(checkpoint.pendingHistoryRoomScopeHashes.all(REACTIVATION_HASH_PATTERN::matches))
        check(!checkpoint.pendingExplicitIdentityReset || checkpoint.reactivationRequired)
        when (checkpoint.state) {
            AccountReactivationCheckpointState.ALLOCATED -> {
                check(checkpoint.prepared == null && checkpoint.receipt == null)
            }
            AccountReactivationCheckpointState.PREPARED -> {
                check(checkpoint.prepared != null && checkpoint.receipt == null)
            }
            AccountReactivationCheckpointState.COMMITTED -> {
                check(checkpoint.prepared != null && checkpoint.receipt != null)
                validateReceipt(checkpoint.attempt, requireNotNull(checkpoint.receipt))
            }
        }
    }

    private fun validateReceipt(attempt: AccountReactivationAttempt, receipt: AccountActivationReceipt) {
        require(receipt.activationId.matches(REACTIVATION_OPAQUE_PATTERN))
        require(receipt.accountIdHash == attempt.accountIdHash)
        require(receipt.installationId == attempt.installationId)
        require(receipt.matrixDeviceIdHash == attempt.matrixDeviceIdHash)
        require(receipt.clientScopeId == attempt.clientScopeId)
        require(receipt.clientGeneration == attempt.clientGeneration)
        require(receipt.attestationVersion > 0)
        require(receipt.policyVersion > 0)
        require(REACTIVATION_HASH_PATTERN.matches(receipt.matrixCrossSigningKeyHash))
        require(REACTIVATION_HASH_PATTERN.matches(receipt.recipientKeyId))
        require(receipt.accountMobileGeneration >= 0)
        require(receipt.committedAtEpochMillis > 0)
    }

    private fun DataOutputStream.writeReceipt(receipt: AccountActivationReceipt) {
        writeUTF(receipt.activationId)
        writeUTF(receipt.accountIdHash)
        writeUTF(receipt.installationId)
        writeUTF(receipt.matrixDeviceIdHash)
        writeUTF(receipt.clientScopeId)
        writeLong(receipt.clientGeneration)
        writeLong(receipt.attestationVersion)
        writeLong(receipt.policyVersion)
        writeUTF(receipt.matrixCrossSigningKeyHash)
        writeUTF(receipt.recipientKeyId)
        writeUTF(receipt.identityContinuity.name)
        writeLong(receipt.accountMobileGeneration)
        writeLong(receipt.committedAtEpochMillis)
    }

    private fun DataInputStream.readReceipt(fileFormatVersion: Int) = AccountActivationReceipt(
        activationId = readUTF(),
        accountIdHash = readUTF(),
        installationId = readUTF(),
        matrixDeviceIdHash = readUTF(),
        clientScopeId = readUTF(),
        clientGeneration = readLong(),
        attestationVersion = readLong(),
        policyVersion = readLong(),
        matrixCrossSigningKeyHash = readUTF(),
        recipientKeyId = readUTF(),
        identityContinuity = if (fileFormatVersion >= 5) {
            AccountIdentityContinuity.valueOf(readUTF())
        } else {
            AccountIdentityContinuity.FIRST_ACTIVATION
        },
        accountMobileGeneration = if (fileFormatVersion >= 6) readLong() else 0,
        committedAtEpochMillis = readLong(),
    )

    private fun DataOutputStream.writeSized(value: ByteArray) {
        writeInt(value.size)
        write(value)
    }

    private fun DataInputStream.readSized(maximum: Int): ByteArray {
        val size = readInt()
        require(size in 1..maximum)
        return ByteArray(size).also(::readFully)
    }
}
