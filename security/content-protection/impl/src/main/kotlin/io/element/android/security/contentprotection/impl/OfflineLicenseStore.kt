/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.contentprotection.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.security.contentprotection.api.ContentId
import io.element.android.security.contentprotection.api.ContentProtectionException
import io.element.android.security.contentprotection.api.ContentProtectionFormat
import io.element.android.security.contentprotection.api.KmsShareReference
import io.element.android.security.contentprotection.api.OfflineLease
import io.element.android.security.contentprotection.api.OfflineLeaseMode
import io.element.android.security.contentprotection.api.RemoteDeviceAccessState
import io.element.android.security.contentprotection.api.RemoteRevocationSnapshot
import io.element.android.security.securestorage.api.AccountVaultId
import io.element.android.security.securestorage.api.SecureStorageException
import io.element.android.security.securestorage.api.SecureStorageRoots
import io.element.android.security.securestorage.api.VaultKeyDomain
import io.element.android.security.securestorage.api.VaultKeyService
import io.element.android.security.securestorage.api.VaultWrappedKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

data class OfflineLicenseRecord(
    val lease: OfflineLease,
    val anchorElapsedRealtimeMillis: Long,
    val anchorWallClockEpochMillis: Long,
    val lastObservedWallClockEpochMillis: Long,
    val remainingUses: Int,
)

data class PersistedRevocationState(
    val sequence: Long = 0,
    val issuedAtEpochMillis: Long = 0,
    val refreshAfterEpochMillis: Long = 0,
    val expiresAtEpochMillis: Long = 0,
    val deviceAccessState: RemoteDeviceAccessState = RemoteDeviceAccessState.ACTIVE,
    val revokedLeaseIds: Set<String> = emptySet(),
    val revokedContentIds: Set<ContentId> = emptySet(),
    val locallyRevokedContentIds: Set<ContentId> = emptySet(),
)

interface OfflineLicenseStore {
    suspend fun readLicense(vaultId: AccountVaultId, contentId: ContentId, reference: KmsShareReference): OfflineLicenseRecord?

    suspend fun writeLicense(vaultId: AccountVaultId, record: OfflineLicenseRecord)

    suspend fun deleteLicense(vaultId: AccountVaultId, contentId: ContentId, reference: KmsShareReference): Boolean

    suspend fun readRevocations(vaultId: AccountVaultId): PersistedRevocationState

    suspend fun applySnapshot(vaultId: AccountVaultId, snapshot: RemoteRevocationSnapshot): PersistedRevocationState

    suspend fun revokeContent(vaultId: AccountVaultId, contentId: ContentId): PersistedRevocationState

    suspend fun clearVault(vaultId: AccountVaultId): Boolean
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class VaultOfflineLicenseStore(
    private val roots: SecureStorageRoots,
    private val vaultKeyService: VaultKeyService,
) : OfflineLicenseStore {
    private val mutex = Mutex()
    private val secureRandom = SecureRandom()

    override suspend fun readLicense(
        vaultId: AccountVaultId,
        contentId: ContentId,
        reference: KmsShareReference,
    ): OfflineLicenseRecord? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val key = licenseKey(contentId, reference)
            readRecord(vaultId, key, LICENSE_RECORD_TYPE)?.let(::decodeLicenseRecord)
        }
    }

    override suspend fun writeLicense(vaultId: AccountVaultId, record: OfflineLicenseRecord) = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(record.remainingUses in 0..record.lease.maximumUses)
            val key = licenseKey(record.lease.contentId, record.lease.reference)
            writeRecord(vaultId, key, LICENSE_RECORD_TYPE, encodeLicenseRecord(record))
        }
    }

    override suspend fun deleteLicense(
        vaultId: AccountVaultId,
        contentId: ContentId,
        reference: KmsShareReference,
    ): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock { deleteRecord(vaultId, licenseKey(contentId, reference)) }
    }

    override suspend fun readRevocations(vaultId: AccountVaultId): PersistedRevocationState = withContext(Dispatchers.IO) {
        mutex.withLock {
            readRecord(vaultId, REVOCATION_RECORD_KEY, REVOCATION_RECORD_TYPE)?.let(::decodeRevocationState)
                ?: PersistedRevocationState()
        }
    }

    override suspend fun applySnapshot(
        vaultId: AccountVaultId,
        snapshot: RemoteRevocationSnapshot,
    ): PersistedRevocationState = withContext(Dispatchers.IO) {
        mutex.withLock {
            val existing = readRecord(vaultId, REVOCATION_RECORD_KEY, REVOCATION_RECORD_TYPE)
                ?.let(::decodeRevocationState)
                ?: PersistedRevocationState()
            if (snapshot.sequence < existing.sequence) throw ContentProtectionException.AuthenticationFailed()
            val merged = PersistedRevocationState(
                sequence = snapshot.sequence,
                issuedAtEpochMillis = maxOf(existing.issuedAtEpochMillis, snapshot.issuedAtEpochMillis),
                refreshAfterEpochMillis = snapshot.refreshAfterEpochMillis,
                expiresAtEpochMillis = snapshot.expiresAtEpochMillis,
                deviceAccessState = mostRestrictive(existing.deviceAccessState, snapshot.deviceAccessState),
                revokedLeaseIds = existing.revokedLeaseIds + snapshot.revokedLeaseIds,
                revokedContentIds = existing.revokedContentIds + snapshot.revokedContentIds,
                locallyRevokedContentIds = existing.locallyRevokedContentIds,
            )
            writeRecord(vaultId, REVOCATION_RECORD_KEY, REVOCATION_RECORD_TYPE, encodeRevocationState(merged))
            merged
        }
    }

    override suspend fun revokeContent(vaultId: AccountVaultId, contentId: ContentId): PersistedRevocationState =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val existing = readRecord(vaultId, REVOCATION_RECORD_KEY, REVOCATION_RECORD_TYPE)
                    ?.let(::decodeRevocationState)
                    ?: PersistedRevocationState()
                val updated = existing.copy(locallyRevokedContentIds = existing.locallyRevokedContentIds + contentId)
                writeRecord(vaultId, REVOCATION_RECORD_KEY, REVOCATION_RECORD_TYPE, encodeRevocationState(updated))
                updated
            }
        }

    override suspend fun clearVault(vaultId: AccountVaultId): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val directory = vaultDirectory(vaultId)
            !directory.exists() || directory.deleteRecursively()
        }
    }

    private suspend fun writeRecord(vaultId: AccountVaultId, recordKey: String, recordType: Int, plaintext: ByteArray) {
        require(plaintext.size <= MAX_PLAINTEXT_BYTES)
        val core = recordCore(vaultId, recordKey, recordType)
        val dataKey = KeyGenerator.getInstance(AES_ALGORITHM).run {
            init(AES_KEY_SIZE_BITS, secureRandom)
            generateKey()
        }
        val keyBytes = requireNotNull(dataKey.encoded)
        try {
            val wrappedKey = vaultKeyService.wrapKey(vaultId, VaultKeyDomain.OFFLINE_LICENSE, keyBytes, core)
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, dataKey)
                updateAAD(core)
            }
            val encoded = encodeContainer(core, wrappedKey, cipher.iv, cipher.doFinal(plaintext))
            publish(recordFile(vaultId, recordKey), encoded)
        } finally {
            keyBytes.fill(0)
            plaintext.fill(0)
        }
    }

    private suspend fun readRecord(vaultId: AccountVaultId, recordKey: String, recordType: Int): ByteArray? {
        val file = recoverRecordFile(vaultId, recordKey) ?: return null
        val expectedCore = recordCore(vaultId, recordKey, recordType)
        return try {
            val container = decodeContainer(readBounded(file))
            if (!MessageDigest.isEqual(container.core, expectedCore)) throw ContentProtectionException.AuthenticationFailed()
            val keyBytes = vaultKeyService.unwrapKey(vaultId, container.wrappedKey, expectedCore)
            try {
                Cipher.getInstance(AES_GCM_TRANSFORMATION).run {
                    init(Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(keyBytes, AES_ALGORITHM), GCMParameterSpec(GCM_TAG_BITS, container.nonce))
                    updateAAD(expectedCore)
                    doFinal(container.ciphertext)
                }
            } finally {
                keyBytes.fill(0)
            }
        } catch (failure: ContentProtectionException) {
            throw failure
        } catch (failure: SecureStorageException) {
            throw ContentProtectionException.OfflineLicenseUnavailable(failure)
        } catch (failure: Exception) {
            throw ContentProtectionException.AuthenticationFailed(failure)
        }
    }

    private fun publish(destination: File, bytes: ByteArray) {
        val directory = requireNotNull(destination.parentFile)
        if (!directory.isDirectory && !directory.mkdirs()) throw ContentProtectionException.OfflineLicenseUnavailable()
        val partial = File(directory, destination.name + PARTIAL_SUFFIX)
        val backup = File(directory, destination.name + BACKUP_SUFFIX)
        partial.delete()
        FileOutputStream(partial).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
        if (backup.exists() && !backup.delete()) throw ContentProtectionException.OfflineLicenseUnavailable()
        if (destination.exists() && !destination.renameTo(backup)) throw ContentProtectionException.OfflineLicenseUnavailable()
        if (!partial.renameTo(destination)) {
            if (backup.exists()) backup.renameTo(destination)
            throw ContentProtectionException.OfflineLicenseUnavailable()
        }
        backup.delete()
    }

    private fun recoverRecordFile(vaultId: AccountVaultId, recordKey: String): File? {
        val destination = recordFile(vaultId, recordKey)
        val backup = File(destination.parentFile, destination.name + BACKUP_SUFFIX)
        val partial = File(destination.parentFile, destination.name + PARTIAL_SUFFIX)
        if (destination.isFile) {
            backup.delete()
            partial.delete()
            return destination
        }
        partial.delete()
        if (backup.isFile && backup.renameTo(destination)) return destination
        return null
    }

    private fun deleteRecord(vaultId: AccountVaultId, recordKey: String): Boolean {
        val file = recordFile(vaultId, recordKey)
        val backup = File(file.parentFile, file.name + BACKUP_SUFFIX)
        val partial = File(file.parentFile, file.name + PARTIAL_SUFFIX)
        return listOf(file, backup, partial).all { !it.exists() || it.delete() }
    }

    private fun encodeLicenseRecord(record: OfflineLicenseRecord): ByteArray = binaryRecord(LICENSE_PAYLOAD_MAGIC) {
        val lease = record.lease
        writeString(lease.leaseId)
        writeString(lease.reference.value)
        writeString(lease.contentId.value)
        writeString(lease.accountIdHash)
        writeString(lease.installationId)
        writeString(lease.recipientKeyId)
        writeInt(lease.mode.ordinal)
        writeLong(lease.policyVersion)
        writeBytes(lease.immutableContextSha256)
        writeBytes(lease.requestNonce)
        writeLong(lease.bootCount)
        writeLong(lease.issuedAtEpochMillis)
        writeLong(lease.expiresAtEpochMillis)
        writeLong(lease.serverTimeEpochMillis)
        writeLong(lease.refreshAfterEpochMillis)
        writeLong(lease.revocationSequence)
        writeInt(lease.maximumUses)
        writeBytes(lease.encryptedKmsShare)
        writeBytes(lease.serverSignature)
        writeLong(record.anchorElapsedRealtimeMillis)
        writeLong(record.anchorWallClockEpochMillis)
        writeLong(record.lastObservedWallClockEpochMillis)
        writeInt(record.remainingUses)
    }

    private fun decodeLicenseRecord(bytes: ByteArray): OfflineLicenseRecord = authenticatePayload {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            input.requireMagic(LICENSE_PAYLOAD_MAGIC)
            val lease = OfflineLease(
                leaseId = input.readString(),
                reference = KmsShareReference(input.readString()),
                contentId = ContentId(input.readString()),
                accountIdHash = input.readString(),
                installationId = input.readString(),
                recipientKeyId = input.readString(),
                mode = OfflineLeaseMode.entries[input.readBoundedOrdinal(OfflineLeaseMode.entries.size)],
                policyVersion = input.readLong(),
                immutableContextSha256 = input.readBytes(ContentProtectionFormat.SHA256_SIZE_BYTES),
                requestNonce = input.readBytes(MAX_NONCE_BYTES),
                bootCount = input.readLong(),
                issuedAtEpochMillis = input.readLong(),
                expiresAtEpochMillis = input.readLong(),
                serverTimeEpochMillis = input.readLong(),
                refreshAfterEpochMillis = input.readLong(),
                revocationSequence = input.readLong(),
                maximumUses = input.readInt(),
                encryptedKmsShare = input.readBytes(MAX_WRAPPED_SHARE_BYTES),
                serverSignature = input.readBytes(MAX_SIGNATURE_BYTES),
            )
            val result = OfflineLicenseRecord(
                lease = lease,
                anchorElapsedRealtimeMillis = input.readLong(),
                anchorWallClockEpochMillis = input.readLong(),
                lastObservedWallClockEpochMillis = input.readLong(),
                remainingUses = input.readInt(),
            )
            require(input.read() == -1)
            require(result.remainingUses in 0..lease.maximumUses)
            result
        }
    }

    private fun encodeRevocationState(state: PersistedRevocationState): ByteArray = binaryRecord(REVOCATION_PAYLOAD_MAGIC) {
        writeLong(state.sequence)
        writeLong(state.issuedAtEpochMillis)
        writeLong(state.refreshAfterEpochMillis)
        writeLong(state.expiresAtEpochMillis)
        writeInt(state.deviceAccessState.ordinal)
        writeStrings(state.revokedLeaseIds)
        writeStrings(state.revokedContentIds.mapTo(mutableSetOf()) { it.value })
        writeStrings(state.locallyRevokedContentIds.mapTo(mutableSetOf()) { it.value })
    }

    private fun decodeRevocationState(bytes: ByteArray): PersistedRevocationState = authenticatePayload {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            input.requireMagic(REVOCATION_PAYLOAD_MAGIC)
            val state = PersistedRevocationState(
                sequence = input.readLong(),
                issuedAtEpochMillis = input.readLong(),
                refreshAfterEpochMillis = input.readLong(),
                expiresAtEpochMillis = input.readLong(),
                deviceAccessState = RemoteDeviceAccessState.entries[input.readBoundedOrdinal(RemoteDeviceAccessState.entries.size)],
                revokedLeaseIds = input.readStrings(),
                revokedContentIds = input.readStrings().mapTo(mutableSetOf(), ::ContentId),
                locallyRevokedContentIds = input.readStrings().mapTo(mutableSetOf(), ::ContentId),
            )
            require(input.read() == -1)
            state
        }
    }

    private fun encodeContainer(core: ByteArray, wrappedKey: VaultWrappedKey, nonce: ByteArray, ciphertext: ByteArray): ByteArray =
        binaryRecord(CONTAINER_MAGIC) {
            writeBytes(core)
            writeInt(wrappedKey.formatVersion)
            writeLong(wrappedKey.vaultKeyVersion)
            writeInt(wrappedKey.domain.formatCode)
            writeBytes(wrappedKey.initializationVector)
            writeBytes(wrappedKey.ciphertext)
            writeBytes(nonce)
            writeBytes(ciphertext)
        }

    private fun decodeContainer(bytes: ByteArray): EncryptedContainer = authenticatePayload {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            input.requireMagic(CONTAINER_MAGIC)
            val core = input.readBytes(MAX_CORE_BYTES)
            val wrappedKeyFormat = input.readInt()
            val wrappedKeyVersion = input.readLong()
            val wrappedKeyDomainCode = input.readInt()
            val wrappedKey = VaultWrappedKey(
                formatVersion = wrappedKeyFormat,
                vaultKeyVersion = wrappedKeyVersion,
                domain = VaultKeyDomain.entries.single { it.formatCode == wrappedKeyDomainCode },
                initializationVector = input.readBytes(MAX_NONCE_BYTES),
                ciphertext = input.readBytes(MAX_WRAPPED_KEY_BYTES),
            )
            val result = EncryptedContainer(
                core = core,
                wrappedKey = wrappedKey,
                nonce = input.readBytes(MAX_NONCE_BYTES),
                ciphertext = input.readBytes(MAX_CIPHERTEXT_BYTES),
            )
            require(wrappedKey.domain == VaultKeyDomain.OFFLINE_LICENSE)
            require(result.nonce.size == GCM_NONCE_BYTES)
            require(input.read() == -1)
            result
        }
    }

    private fun recordCore(vaultId: AccountVaultId, recordKey: String, recordType: Int): ByteArray = binaryRecord(CORE_MAGIC) {
        writeInt(recordType)
        write(sha256(vaultId.value.encodeToByteArray()))
        write(sha256(recordKey.encodeToByteArray()))
    }

    private fun binaryRecord(magic: ByteArray, block: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.write(magic)
            output.writeInt(RECORD_FORMAT_VERSION)
            output.block()
        }
        bytes.toByteArray()
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.encodeToByteArray()
        require(bytes.size <= MAX_STRING_BYTES)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataOutputStream.writeBytes(value: ByteArray) {
        require(value.size <= MAX_CIPHERTEXT_BYTES)
        writeInt(value.size)
        write(value)
    }

    private fun DataOutputStream.writeStrings(values: Set<String>) {
        require(values.size <= MAX_REVOCATION_IDS)
        writeInt(values.size)
        values.sorted().forEach { writeString(it) }
    }

    private fun DataInputStream.requireMagic(magic: ByteArray) {
        val actual = ByteArray(magic.size).also(::readFully)
        require(actual.contentEquals(magic))
        require(readInt() == RECORD_FORMAT_VERSION)
    }

    private fun DataInputStream.readString(): String {
        val length = readInt()
        require(length in 1..MAX_STRING_BYTES)
        return ByteArray(length).also(::readFully).decodeToString()
    }

    private fun DataInputStream.readBytes(maximum: Int): ByteArray {
        val length = readInt()
        require(length in 1..maximum)
        return ByteArray(length).also(::readFully)
    }

    private fun DataInputStream.readStrings(): Set<String> {
        val count = readInt()
        require(count in 0..MAX_REVOCATION_IDS)
        return buildSet(count) { repeat(count) { require(add(readString())) } }
    }

    private fun DataInputStream.readBoundedOrdinal(size: Int): Int = readInt().also { require(it in 0 until size) }

    private fun readBounded(file: File): ByteArray {
        if (!file.isFile || file.length() !in 1..MAX_RECORD_BYTES) throw ContentProtectionException.AuthenticationFailed()
        return FileInputStream(file).use { input ->
            val bytes = input.readBytes()
            if (bytes.size.toLong() > MAX_RECORD_BYTES) throw ContentProtectionException.AuthenticationFailed()
            bytes
        }
    }

    private fun vaultDirectory(vaultId: AccountVaultId): File {
        require(vaultId.value.isNotBlank())
        val root = roots.persistentRoot().canonicalFile
        return File(root, "$STORE_DIRECTORY/${sha256Hex(vaultId.value)}").canonicalFile.also {
            require(it.path.startsWith(root.path + File.separator))
        }
    }

    private fun recordFile(vaultId: AccountVaultId, recordKey: String): File =
        File(vaultDirectory(vaultId), sha256Hex(recordKey) + RECORD_SUFFIX)

    private fun licenseKey(contentId: ContentId, reference: KmsShareReference): String =
        "license\u0000${contentId.value}\u0000${reference.value}"

    private fun mostRestrictive(first: RemoteDeviceAccessState, second: RemoteDeviceAccessState): RemoteDeviceAccessState =
        if (first.ordinal >= second.ordinal) first else second

    private fun sha256(value: ByteArray): ByteArray = MessageDigest.getInstance(SHA256).digest(value)

    private fun sha256Hex(value: String): String = sha256(value.encodeToByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private data class EncryptedContainer(
        val core: ByteArray,
        val wrappedKey: VaultWrappedKey,
        val nonce: ByteArray,
        val ciphertext: ByteArray,
    )

    private companion object {
        const val RECORD_FORMAT_VERSION = 1
        const val LICENSE_RECORD_TYPE = 1
        const val REVOCATION_RECORD_TYPE = 2
        const val AES_ALGORITHM = "AES"
        const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        const val AES_KEY_SIZE_BITS = 256
        const val GCM_TAG_BITS = 128
        const val GCM_NONCE_BYTES = 12
        const val MAX_NONCE_BYTES = 64
        const val MAX_SIGNATURE_BYTES = 256
        const val MAX_WRAPPED_SHARE_BYTES = 1024
        const val MAX_WRAPPED_KEY_BYTES = 2048
        const val MAX_STRING_BYTES = 4096
        const val MAX_CORE_BYTES = 256
        const val MAX_PLAINTEXT_BYTES = 1024 * 1024
        const val MAX_CIPHERTEXT_BYTES = MAX_PLAINTEXT_BYTES + 1024
        const val MAX_RECORD_BYTES = 2L * 1024 * 1024
        const val MAX_REVOCATION_IDS = 4096
        const val STORE_DIRECTORY = "offline-licenses"
        const val RECORD_SUFFIX = ".yzol"
        const val PARTIAL_SUFFIX = ".partial"
        const val BACKUP_SUFFIX = ".backup"
        const val REVOCATION_RECORD_KEY = "revocations"
        const val SHA256 = "SHA-256"
        val CORE_MAGIC = "YZOLC001".encodeToByteArray()
        val CONTAINER_MAGIC = "YZOLE001".encodeToByteArray()
        val LICENSE_PAYLOAD_MAGIC = "YZOLL001".encodeToByteArray()
        val REVOCATION_PAYLOAD_MAGIC = "YZOLR001".encodeToByteArray()
    }
}

private inline fun <T> authenticatePayload(block: () -> T): T = try {
    block()
} catch (failure: ContentProtectionException) {
    throw failure
} catch (failure: EOFException) {
    throw ContentProtectionException.AuthenticationFailed(failure)
} catch (failure: Exception) {
    throw ContentProtectionException.AuthenticationFailed(failure)
}
