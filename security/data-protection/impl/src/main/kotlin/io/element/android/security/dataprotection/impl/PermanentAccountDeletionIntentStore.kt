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
import io.element.android.security.dataprotection.api.DeletionOperationId
import io.element.android.security.dataprotection.api.DeletionReason
import io.element.android.security.dataprotection.api.DeletionRequest
import io.element.android.security.dataprotection.api.DeletionScope
import io.element.android.security.dataprotection.api.PermanentAccountDeletionHandle
import io.element.android.security.dataprotection.api.PermanentAccountDeletionState
import io.element.android.security.securestorage.api.DeviceRootKeyService
import io.element.android.security.securestorage.api.DeviceWrappedKey
import io.element.android.security.securestorage.api.SecureStorageRoots
import kotlinx.coroutines.Dispatchers
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

enum class PermanentDeletionPhase {
    DRAFT,
    RECOVERY_DRAFT,
    PREPARED,
    REMOTE_COMMITTED,
    LOCAL_CLEARED,
    SUBMISSION_BLOCKED,
}

data class PendingPermanentAccountDeletion(
    val handle: PermanentAccountDeletionHandle,
    val accountIdHash: String,
    val eraseData: Boolean,
    val idempotencyKey: String,
    val confirmationToken: String,
    val localDeletionRequest: DeletionRequest,
    val phase: PermanentDeletionPhase,
    val deletionIntentId: String? = null,
    val installationId: String? = null,
    val preparedAtEpochMillis: Long? = null,
    val expiresAtEpochMillis: Long? = null,
    val lastDeletionState: PermanentAccountDeletionState? = null,
    val clientReceiptToken: String? = null,
    val clientReceiptScopeHash: String? = null,
    val clientReceiptTokenExpiresAtEpochMillis: Long? = null,
    val localManifestDigestSha256: String? = null,
    val localAttestationEvidenceHash: String? = null,
    val localClearedAtEpochMillis: Long? = null,
)

data class PendingPermanentDeletionRecords(
    val records: List<PendingPermanentAccountDeletion>,
    val unreadableRecordCount: Int,
)

interface PermanentAccountDeletionIntentStore {
    suspend fun save(record: PendingPermanentAccountDeletion)
    suspend fun load(handle: PermanentAccountDeletionHandle): PendingPermanentAccountDeletion?
    suspend fun loadAll(): PendingPermanentDeletionRecords
    suspend fun delete(handle: PermanentAccountDeletionHandle): Boolean
}

/**
 * Append-only, no-backup deletion intent store.
 *
 * The confirmation token and local deletion scope are encrypted with a random record key. That key is wrapped by
 * the non-exportable installation root key. Each update creates a new fsynced revision; process death therefore
 * leaves either the previous authenticated record or the next one, never a partially overwritten secret.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class EncryptedPermanentAccountDeletionIntentStore(
    private val roots: SecureStorageRoots,
    private val deviceRootKeyService: DeviceRootKeyService,
) : PermanentAccountDeletionIntentStore {
    private val random = SecureRandom()

    override suspend fun save(record: PendingPermanentAccountDeletion): Unit = withContext(Dispatchers.IO) {
        validate(record)
        val operationHash = operationHash(record.handle)
        val payload = encodePayload(record)
        val recordKey = ByteArray(RECORD_KEY_BYTES).also(random::nextBytes)
        try {
            val binding = binding(operationHash)
            val wrappedKey = deviceRootKeyService.wrapKey(recordKey, binding)
            val cipher = Cipher.getInstance(AES_GCM).apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(recordKey, AES))
                updateAAD(binding)
            }
            val encoded = encodeEnvelope(wrappedKey, cipher.iv, cipher.doFinal(payload))
            writeNextRevision(operationHash, encoded)
            encoded.fill(0)
        } finally {
            payload.fill(0)
            recordKey.fill(0)
        }
    }

    override suspend fun load(handle: PermanentAccountDeletionHandle): PendingPermanentAccountDeletion? =
        withContext(Dispatchers.IO) {
            val operationHash = operationHash(handle)
            revisionFiles(operationHash)
                .sortedByDescending(::revisionOf)
                .firstNotNullOfOrNull { file -> runCatching { decodeFile(file, operationHash) }.getOrNull() }
                ?.also { check(it.handle == handle) }
        }

    override suspend fun loadAll(): PendingPermanentDeletionRecords = withContext(Dispatchers.IO) {
        directory().listFiles().orEmpty().filter { it.name.startsWith(PARTIAL_PREFIX) }.forEach(File::delete)
        var unreadable = 0
        val records = revisionFiles()
            .groupBy(::operationHashOf)
            .mapNotNull { (operationHash, files) ->
                var selected: PendingPermanentAccountDeletion? = null
                files.sortedByDescending(::revisionOf).forEach { file ->
                    if (selected == null) {
                        runCatching { decodeFile(file, operationHash) }
                            .onSuccess { selected = it }
                            .onFailure { unreadable += 1 }
                    }
                }
                selected
            }
            .sortedBy { it.localDeletionRequest.requestedAtEpochMillis }
        PendingPermanentDeletionRecords(records, unreadable)
    }

    override suspend fun delete(handle: PermanentAccountDeletionHandle): Boolean = withContext(Dispatchers.IO) {
        val files = revisionFiles(operationHash(handle))
        files.all { !it.exists() || it.delete() }
    }

    private suspend fun decodeFile(file: File, expectedOperationHash: String): PendingPermanentAccountDeletion {
        if (file.length() !in 1..MAX_RECORD_BYTES) error("Permanent deletion record length is invalid.")
        val input = DataInputStream(ByteArrayInputStream(file.readBytes()))
        val magic = ByteArray(FILE_MAGIC.size).also(input::readFully)
        check(magic.contentEquals(FILE_MAGIC))
        val fileFormatVersion = input.readInt()
        check(fileFormatVersion in 1..FILE_FORMAT_VERSION)
        val wrappedKey = DeviceWrappedKey(
            formatVersion = input.readInt(),
            rootKeyVersion = input.readLong(),
            installationIdSha256 = input.readSized(MAX_INSTALLATION_HASH_BYTES),
            initializationVector = input.readSized(MAX_NONCE_BYTES),
            ciphertext = input.readSized(MAX_WRAPPED_KEY_BYTES),
        )
        val nonce = input.readSized(MAX_NONCE_BYTES)
        val ciphertext = input.readSized(MAX_CIPHERTEXT_BYTES)
        check(input.available() == 0)
        val binding = binding(expectedOperationHash)
        val recordKey = deviceRootKeyService.unwrapKey(wrappedKey, binding)
        val plaintext = try {
            Cipher.getInstance(AES_GCM).run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(recordKey, AES), GCMParameterSpec(GCM_TAG_BITS, nonce))
                updateAAD(binding)
                doFinal(ciphertext)
            }
        } finally {
            recordKey.fill(0)
        }
        return try {
            decodePayload(plaintext, fileFormatVersion).also { record ->
                validate(record)
                check(operationHash(record.handle) == expectedOperationHash)
            }
        } finally {
            plaintext.fill(0)
        }
    }

    private fun encodePayload(record: PendingPermanentAccountDeletion): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeUTF(record.handle.value)
            output.writeUTF(record.accountIdHash)
            output.writeBoolean(record.eraseData)
            output.writeUTF(record.idempotencyKey)
            output.writeUTF(record.confirmationToken)
            val request = record.localDeletionRequest
            output.writeUTF(request.operationId.value)
            val scope = request.scope as DeletionScope.Account
            output.writeUTF(scope.accountIdHash)
            output.writeUTF(scope.vaultId)
            output.writeUTF(scope.localSessionId)
            output.writeUTF(scope.sessionFilePath)
            output.writeUTF(scope.sessionCachePath)
            output.writeLong(request.policyVersion)
            output.writeLong(request.requestedAtEpochMillis)
            output.writeUTF(record.phase.name)
            output.writeNullableUtf(record.deletionIntentId)
            output.writeNullableUtf(record.installationId)
            output.writeNullableLong(record.preparedAtEpochMillis)
            output.writeNullableLong(record.expiresAtEpochMillis)
            output.writeNullableUtf(record.lastDeletionState?.name)
            output.writeNullableUtf(record.clientReceiptToken)
            output.writeNullableUtf(record.clientReceiptScopeHash)
            output.writeNullableLong(record.clientReceiptTokenExpiresAtEpochMillis)
            output.writeNullableUtf(record.localManifestDigestSha256)
            output.writeNullableUtf(record.localAttestationEvidenceHash)
            output.writeNullableLong(record.localClearedAtEpochMillis)
        }
        bytes.toByteArray()
    }

    private fun decodePayload(payload: ByteArray, fileFormatVersion: Int): PendingPermanentAccountDeletion =
        DataInputStream(ByteArrayInputStream(payload)).use { input ->
            val handle = PermanentAccountDeletionHandle(input.readUTF())
            val accountIdHash = input.readUTF()
            val eraseData = input.readBoolean()
            val idempotencyKey = input.readUTF()
            val confirmationToken = input.readUTF()
            val request = DeletionRequest(
                operationId = DeletionOperationId(input.readUTF()),
                scope = DeletionScope.Account(
                    accountIdHash = input.readUTF(),
                    vaultId = input.readUTF(),
                    localSessionId = input.readUTF(),
                    sessionFilePath = input.readUTF(),
                    sessionCachePath = input.readUTF(),
                ),
                reason = DeletionReason.USER_REQUEST,
                policyVersion = input.readLong(),
                requestedAtEpochMillis = input.readLong(),
            )
            val phase = enumValueOf<PermanentDeletionPhase>(input.readUTF())
            val deletionIntentId = input.readNullableUtf()
            val installationId = input.readNullableUtf()
            val preparedAt = input.readNullableLong()
            val expiresAt = input.readNullableLong()
            val lastDeletionState = if (fileFormatVersion >= 2) input.readNullableUtf()?.let(PermanentAccountDeletionState::valueOf) else null
            val clientReceiptToken = if (fileFormatVersion >= 2) input.readNullableUtf() else null
            val clientReceiptScopeHash = if (fileFormatVersion >= 2) input.readNullableUtf() else null
            val clientReceiptTokenExpiresAt = if (fileFormatVersion >= 2) input.readNullableLong() else null
            val localManifestDigest = if (fileFormatVersion >= 2) input.readNullableUtf() else null
            val localAttestationEvidence = if (fileFormatVersion >= 2) input.readNullableUtf() else null
            val localClearedAt = if (fileFormatVersion >= 2) input.readNullableLong() else null
            check(input.available() == 0)
            PendingPermanentAccountDeletion(
                handle = handle,
                accountIdHash = accountIdHash,
                eraseData = eraseData,
                idempotencyKey = idempotencyKey,
                confirmationToken = confirmationToken,
                localDeletionRequest = request,
                phase = phase,
                deletionIntentId = deletionIntentId,
                installationId = installationId,
                preparedAtEpochMillis = preparedAt,
                expiresAtEpochMillis = expiresAt,
                lastDeletionState = lastDeletionState,
                clientReceiptToken = clientReceiptToken,
                clientReceiptScopeHash = clientReceiptScopeHash,
                clientReceiptTokenExpiresAtEpochMillis = clientReceiptTokenExpiresAt,
                localManifestDigestSha256 = localManifestDigest,
                localAttestationEvidenceHash = localAttestationEvidence,
                localClearedAtEpochMillis = localClearedAt,
            )
        }

    private fun encodeEnvelope(wrappedKey: DeviceWrappedKey, nonce: ByteArray, ciphertext: ByteArray): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(FILE_MAGIC)
                output.writeInt(FILE_FORMAT_VERSION)
                output.writeInt(wrappedKey.formatVersion)
                output.writeLong(wrappedKey.rootKeyVersion)
                output.writeSized(wrappedKey.installationIdSha256)
                output.writeSized(wrappedKey.initializationVector)
                output.writeSized(wrappedKey.ciphertext)
                output.writeSized(nonce)
                output.writeSized(ciphertext)
            }
            bytes.toByteArray()
        }

    private fun writeNextRevision(operationHash: String, bytes: ByteArray) {
        val nextRevision = (revisionFiles(operationHash).maxOfOrNull(::revisionOf) ?: 0) + 1
        check(nextRevision <= MAX_REVISION)
        val target = File(directory(), "$PENDING_PREFIX$operationHash-${nextRevision.toString().padStart(REVISION_WIDTH, '0')}$SUFFIX")
        val partial = File(directory(), "$PARTIAL_PREFIX${UUID.randomUUID()}")
        try {
            FileOutputStream(partial).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            check(partial.renameTo(target)) { "Unable to publish permanent deletion intent." }
            revisionFiles(operationHash).filter { it != target }.forEach(File::delete)
        } finally {
            partial.delete()
        }
    }

    private fun validate(record: PendingPermanentAccountDeletion) {
        require(record.handle.value == record.localDeletionRequest.operationId.value)
        require(HASH_PATTERN.matches(record.accountIdHash))
        require(record.localDeletionRequest.reason == DeletionReason.USER_REQUEST)
        val scope = record.localDeletionRequest.scope as? DeletionScope.Account
            ?: error("Permanent account deletion requires account scope.")
        require(scope.accountIdHash == record.accountIdHash)
        require(record.idempotencyKey.matches(OPAQUE_PATTERN))
        require(record.confirmationToken.matches(TOKEN_PATTERN))
        if (record.phase !in setOf(
                PermanentDeletionPhase.DRAFT,
                PermanentDeletionPhase.RECOVERY_DRAFT,
                PermanentDeletionPhase.SUBMISSION_BLOCKED,
            )
        ) {
            requireNotNull(record.deletionIntentId).also { require(it.matches(OPAQUE_PATTERN)) }
            requireNotNull(record.installationId).also { require(it.length in 16..256) }
            requireNotNull(record.preparedAtEpochMillis).also { require(it > 0) }
            requireNotNull(record.expiresAtEpochMillis).also { require(it > 0) }
        }
        record.clientReceiptScopeHash?.let { require(HASH_PATTERN.matches(it)) }
        record.localManifestDigestSha256?.let { require(HASH_PATTERN.matches(it)) }
        record.localAttestationEvidenceHash?.let { require(HASH_PATTERN.matches(it)) }
        if (record.phase == PermanentDeletionPhase.LOCAL_CLEARED) {
            requireNotNull(record.clientReceiptToken).also { require(it.length in 32..4096) }
            requireNotNull(record.clientReceiptScopeHash)
            requireNotNull(record.clientReceiptTokenExpiresAtEpochMillis)
            requireNotNull(record.localManifestDigestSha256)
            requireNotNull(record.localAttestationEvidenceHash)
            requireNotNull(record.localClearedAtEpochMillis)
        }
    }

    private fun directory(): File = File(roots.persistentRoot(), DIRECTORY).also {
        check(it.isDirectory || it.mkdirs()) { "Unable to create permanent deletion intent directory." }
    }

    private fun revisionFiles(operationHash: String? = null): List<File> = directory().listFiles().orEmpty().filter { file ->
        val match = FILE_PATTERN.matchEntire(file.name) ?: return@filter false
        operationHash == null || match.groupValues[1] == operationHash
    }

    private fun operationHashOf(file: File): String = requireNotNull(FILE_PATTERN.matchEntire(file.name)).groupValues[1]
    private fun revisionOf(file: File): Int = requireNotNull(FILE_PATTERN.matchEntire(file.name)).groupValues[2].toInt()
    private fun operationHash(handle: PermanentAccountDeletionHandle): String = sha256(handle.value.encodeToByteArray()).toHex()
    private fun binding(operationHash: String): ByteArray = "$BINDING_DOMAIN\u0000$operationHash".encodeToByteArray()
    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun DataOutputStream.writeSized(value: ByteArray) {
        writeInt(value.size)
        write(value)
    }

    private fun DataInputStream.readSized(maximum: Int): ByteArray {
        val size = readInt()
        require(size in 1..maximum)
        return ByteArray(size).also(::readFully)
    }

    private fun DataOutputStream.writeNullableUtf(value: String?) {
        writeBoolean(value != null)
        value?.let(::writeUTF)
    }

    private fun DataInputStream.readNullableUtf(): String? = if (readBoolean()) readUTF() else null

    private fun DataOutputStream.writeNullableLong(value: Long?) {
        writeBoolean(value != null)
        value?.let(::writeLong)
    }

    private fun DataInputStream.readNullableLong(): Long? = if (readBoolean()) readLong() else null

    private companion object {
        const val DIRECTORY = "permanent-account-deletion"
        const val PENDING_PREFIX = "pending-"
        const val PARTIAL_PREFIX = ".partial-"
        const val SUFFIX = ".yzpd"
        const val REVISION_WIDTH = 8
        const val FILE_FORMAT_VERSION = 2
        const val RECORD_KEY_BYTES = 32
        const val GCM_TAG_BITS = 128
        const val AES = "AES"
        const val AES_GCM = "AES/GCM/NoPadding"
        const val BINDING_DOMAIN = "yizhe-permanent-account-deletion-v1"
        const val MAX_INSTALLATION_HASH_BYTES = 32
        const val MAX_NONCE_BYTES = 32
        const val MAX_WRAPPED_KEY_BYTES = 8192
        const val MAX_CIPHERTEXT_BYTES = 64 * 1024
        const val MAX_RECORD_BYTES = 80L * 1024L
        const val MAX_REVISION = 99_999_999
        val FILE_MAGIC = "YZPD0001".encodeToByteArray()
        val HASH_PATTERN = Regex("[0-9a-f]{64}")
        val OPAQUE_PATTERN = Regex("[A-Za-z0-9_-]{20,128}")
        val TOKEN_PATTERN = Regex("[A-Za-z0-9_-]{32,128}")
        val FILE_PATTERN = Regex("pending-([0-9a-f]{64})-([0-9]{8})\\.yzpd")
    }
}
