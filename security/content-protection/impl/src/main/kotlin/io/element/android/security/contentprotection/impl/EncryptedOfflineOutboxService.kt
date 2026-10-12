/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.security.contentprotection.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.sessionstorage.api.SessionStore
import io.element.android.security.contentprotection.api.OfflineOutboxService
import io.element.android.security.contentprotection.api.OfflineTextOutboxMessage
import io.element.android.security.securestorage.api.AccountVaultId
import io.element.android.security.securestorage.api.KeyEnvelopeId
import io.element.android.security.securestorage.api.SecureObjectId
import io.element.android.security.securestorage.api.SecureObjectPurpose
import io.element.android.security.securestorage.api.SecureObjectReference
import io.element.android.security.securestorage.api.SecureObjectWriteRequest
import io.element.android.security.securestorage.api.SecureStorageRoots
import io.element.android.security.securestorage.api.SensitiveFileStore
import io.element.android.security.securestorage.api.deriveAccountVaultId
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

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class EncryptedOfflineOutboxService(
    private val sensitiveFileStore: SensitiveFileStore,
    private val roots: SecureStorageRoots,
    private val sessionStore: SessionStore,
) : OfflineOutboxService {
    private val mutex = Mutex()

    override suspend fun stage(message: OfflineTextOutboxMessage): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            validate(message)
            val vaultId = vaultId(message.accountId)
            val index = indexFile(message.accountId, message.roomId, message.transactionId)
            val currentEntryCount = index.parentFile
                ?.listFiles { file -> file.isFile && file.name.endsWith(INDEX_SUFFIX) }
                ?.size
                ?: 0
            require(index.isFile || currentEntryCount < MAX_ENTRIES_PER_ROOM)
            if (index.isFile) removeLocked(index, vaultId, message.roomId, message.transactionId)
            val plaintext = encodeMessage(message)
            val binding = binding(message.accountId, message.roomId, message.transactionId)
            val reference = try {
                sensitiveFileStore.write(
                    SecureObjectWriteRequest(
                        objectId = objectIdForVault(vaultId, message.roomId, message.transactionId),
                        vaultId = vaultId,
                        purpose = SecureObjectPurpose.OFFLINE_OUTBOX,
                        associatedData = binding,
                        expectedPlaintextLength = plaintext.size.toLong(),
                    )
                ) { output -> output.write(plaintext) }
            } finally {
                plaintext.fill(0)
                binding.fill(0)
            }
            try {
                writeIndex(index, reference)
                true
            } catch (failure: Exception) {
                sensitiveFileStore.delete(reference)
                throw failure
            }
        }
    }

    override suspend fun messages(accountId: String, roomId: String): List<OfflineTextOutboxMessage> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val vaultId = vaultId(accountId)
                val directory = indexDirectory(accountId, roomId)
                val indices = directory.listFiles { file -> file.isFile && file.name.endsWith(INDEX_SUFFIX) }
                    ?.sortedBy { it.name }
                    .orEmpty()
                require(indices.size <= MAX_ENTRIES_PER_ROOM)
                indices.map { index ->
                    val reference = readIndex(index)
                    require(reference.vaultId == vaultId && reference.purpose == SecureObjectPurpose.OFFLINE_OUTBOX)
                    sensitiveFileStore.read(reference) { input ->
                        decodeMessage(input.readBytes()).also { message ->
                            require(message.accountId == accountId && message.roomId == roomId)
                        }
                    }
                }.sortedBy(OfflineTextOutboxMessage::createdAtEpochMillis)
            }
        }

    override suspend fun remove(accountId: String, roomId: String, transactionId: String): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                removeLocked(indexFile(accountId, roomId, transactionId), vaultId(accountId), roomId, transactionId)
            }
        }

    private suspend fun removeLocked(
        index: File,
        vaultId: AccountVaultId,
        roomId: String,
        transactionId: String,
    ): Boolean {
        if (!index.isFile) return true
        val reference = readIndex(index)
        require(reference.vaultId == vaultId)
        require(reference.objectId == objectIdForVault(vaultId, roomId, transactionId))
        val objectDeleted = sensitiveFileStore.delete(reference)
        return objectDeleted && index.delete()
    }

    private suspend fun vaultId(accountId: String): AccountVaultId {
        val session = requireNotNull(sessionStore.getSession(accountId))
        return deriveAccountVaultId(session.userId, session.deviceId)
    }

    private fun indexFile(accountId: String, roomId: String, transactionId: String): File =
        File(indexDirectory(accountId, roomId), "${sha256Hex(transactionId)}$INDEX_SUFFIX")

    private fun indexDirectory(accountId: String, roomId: String): File {
        val root = File(roots.persistentRoot(), INDEX_DIRECTORY).canonicalFile
        val directory = File(root, "${sha256Hex(accountId)}/${sha256Hex(roomId)}").canonicalFile
        require(directory.path.startsWith(root.path + File.separator))
        if (!directory.isDirectory && !directory.mkdirs()) error("Unable to create offline outbox index.")
        return directory
    }

    private fun objectIdForVault(vaultId: AccountVaultId, roomId: String, transactionId: String): SecureObjectId =
        SecureObjectId("offline-outbox-v1:${sha256Hex("${vaultId.value}\u0000$roomId\u0000$transactionId")}")

    private fun writeIndex(index: File, reference: SecureObjectReference) {
        val bytes = encodeReference(reference)
        val partial = File(index.parentFile, ".${index.name}.partial")
        try {
            FileOutputStream(partial).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            check(!index.exists() && partial.renameTo(index))
        } finally {
            bytes.fill(0)
            partial.delete()
        }
    }

    private fun readIndex(index: File): SecureObjectReference {
        require(index.length() in 1..MAX_INDEX_BYTES)
        return decodeReference(index.readBytes())
    }

    private fun validate(message: OfflineTextOutboxMessage) {
        require(message.transactionId.length in 16..256)
        require(message.accountId.isNotBlank() && message.roomId.isNotBlank())
        require(message.body.toByteArray().size <= MAX_BODY_BYTES)
        require((message.htmlBody?.toByteArray()?.size ?: 0) <= MAX_HTML_BYTES)
        require(message.mentionedUserIds.size <= MAX_MENTIONS)
        require(message.createdAtEpochMillis > 0)
    }

    private fun binding(accountId: String, roomId: String, transactionId: String): ByteArray =
        "offline-outbox-v1\u0000$accountId\u0000$roomId\u0000$transactionId".encodeToByteArray()

    private fun encodeMessage(message: OfflineTextOutboxMessage): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.write(MESSAGE_MAGIC)
            output.writeString(message.transactionId)
            output.writeString(message.accountId)
            output.writeString(message.roomId)
            output.writeString(message.body)
            output.writeNullableString(message.htmlBody)
            output.writeInt(message.mentionedUserIds.size)
            message.mentionedUserIds.forEach { output.writeString(it) }
            output.writeBoolean(message.mentionsRoom)
            output.writeNullableString(message.repliedToEventId)
            output.writeLong(message.createdAtEpochMillis)
        }
        bytes.toByteArray()
    }

    private fun decodeMessage(encoded: ByteArray): OfflineTextOutboxMessage = DataInputStream(ByteArrayInputStream(encoded)).use { input ->
        try {
            require(input.readExact(MESSAGE_MAGIC.size).contentEquals(MESSAGE_MAGIC))
            val transactionId = input.readString(256)
            val accountId = input.readString(512)
            val roomId = input.readString(512)
            val body = input.readString(MAX_BODY_BYTES)
            val html = input.readNullableString(MAX_HTML_BYTES)
            val mentionCount = input.readInt().also { require(it in 0..MAX_MENTIONS) }
            val mentions = List(mentionCount) { input.readString(512) }
            val mentionsRoom = input.readBoolean()
            val repliedTo = input.readNullableString(512)
            val createdAt = input.readLong()
            require(input.read() == -1)
            OfflineTextOutboxMessage(
                transactionId,
                accountId,
                roomId,
                body,
                html,
                mentions,
                mentionsRoom,
                repliedTo,
                createdAt,
            ).also(::validate)
        } finally {
            encoded.fill(0)
        }
    }

    private fun encodeReference(reference: SecureObjectReference): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.write(INDEX_MAGIC)
            output.writeString(reference.objectId.value)
            output.writeString(reference.vaultId.value)
            output.writeInt(reference.purpose.formatCode)
            output.writeString(reference.keyEnvelopeId.value)
            output.writeInt(reference.formatVersion)
            output.writeLong(reference.plaintextLength)
            output.writeLong(reference.ciphertextLength)
            output.writeString(reference.associatedDataSha256)
        }
        bytes.toByteArray()
    }

    private fun decodeReference(encoded: ByteArray): SecureObjectReference = DataInputStream(ByteArrayInputStream(encoded)).use { input ->
        require(input.readExact(INDEX_MAGIC.size).contentEquals(INDEX_MAGIC))
        val reference = SecureObjectReference(
            objectId = SecureObjectId(input.readString(1024)),
            vaultId = AccountVaultId(input.readString(1024)),
            purpose = SecureObjectPurpose.fromFormatCode(input.readInt()) ?: error("Unknown outbox purpose."),
            keyEnvelopeId = KeyEnvelopeId(input.readString(128)),
            formatVersion = input.readInt(),
            plaintextLength = input.readLong(),
            ciphertextLength = input.readLong(),
            associatedDataSha256 = input.readString(64),
        )
        require(input.read() == -1)
        reference
    }

    private fun DataOutputStream.writeString(value: String) {
        val encoded = value.encodeToByteArray()
        writeInt(encoded.size)
        write(encoded)
    }

    private fun DataOutputStream.writeNullableString(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeString(value)
    }

    private fun DataInputStream.readString(maxBytes: Int): String {
        val size = readInt().also { require(it in 0..maxBytes) }
        return readExact(size).decodeToString()
    }

    private fun DataInputStream.readExact(size: Int): ByteArray = ByteArray(size).also(::readFully)

    private fun DataInputStream.readNullableString(maxBytes: Int): String? =
        if (readBoolean()) readString(maxBytes) else null

    private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object {
        const val INDEX_DIRECTORY = "offline-outbox-index-v1"
        const val INDEX_SUFFIX = ".yzob"
        const val MAX_INDEX_BYTES = 8 * 1024L
        const val MAX_ENTRIES_PER_ROOM = 64
        const val MAX_BODY_BYTES = 64 * 1024
        const val MAX_HTML_BYTES = 256 * 1024
        const val MAX_MENTIONS = 512
        val MESSAGE_MAGIC = "YZOBP001".encodeToByteArray()
        val INDEX_MAGIC = "YZOBI001".encodeToByteArray()
    }
}
