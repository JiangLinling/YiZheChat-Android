/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.call.impl.security

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.security.securestorage.api.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

@Serializable
data class CallFinalizationRecord(
    val operationId: String,
    val sessionId: String,
    val deviceId: String,
    val roomId: String,
    val audioOnly: Boolean,
    val attemptId: String?,
    val invitationId: String?,
    val incoming: Boolean,
    val connected: Boolean = false,
    val connectedAt: Long? = null,
    val recordSent: Boolean = false,
    val createdAt: Long,
    val recordPublicationStopped: CallRecordPublicationStop? = null,
)

@Serializable
enum class CallRecordPublicationStop { CONTACT_RESTRICTED, MEMBERSHIP_ENDED }

internal val CallFinalizationRecord.recordResolved: Boolean
    get() = recordSent || recordPublicationStopped != null

enum class CallCleanupPhase { ACTIVE, RELEASED, CLEANUP_FAILED }

/** Contains opaque IDs and storage references only. The call identities remain inside the account Vault. */
data class PendingCallFinalization(
    val key: String,
    val vaultId: AccountVaultId,
    val owner: String,
    val phase: CallCleanupPhase,
    val operationId: String,
    val accountHash: String,
    val deviceHash: String,
    val roomHash: String,
    val noInvitationConfirmed: Boolean = false,
    val releasedAt: Long? = null,
)

interface CallFinalizationStore {
    suspend fun pending(): List<PendingCallFinalization>
    suspend fun read(pending: PendingCallFinalization): CallFinalizationRecord
    suspend fun put(vaultId: AccountVaultId, owner: String, record: CallFinalizationRecord): PendingCallFinalization
    suspend fun mark(key: String, phase: CallCleanupPhase, noInvitationConfirmed: Boolean? = null, releasedAt: Long? = null)
    suspend fun remove(key: String)
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class EncryptedCallFinalizationStore(
    private val files: SensitiveFileStore,
    private val roots: SecureStorageRoots,
) : CallFinalizationStore {
    private val mutex = Mutex()
    private val json = Json

    @Serializable
    private data class Pointer(
        val key: String,
        val vault: String,
        val owner: String,
        val phase: String,
        val operationId: String,
        val accountHash: String,
        val deviceHash: String,
        val roomHash: String,
        val objectId: String,
        val envelopeId: String,
        val format: Int,
        val plainLength: Long,
        val cipherLength: Long,
        val bindingHash: String,
        val noInvitationConfirmed: Boolean = false,
        val releasedAt: Long? = null,
    ) {
        fun pending() = PendingCallFinalization(key, AccountVaultId(vault), owner, CallCleanupPhase.valueOf(phase),
            operationId, accountHash, deviceHash, roomHash, noInvitationConfirmed, releasedAt)
        fun reference() = SecureObjectReference(SecureObjectId(objectId), AccountVaultId(vault), SecureObjectPurpose.OFFLINE_OUTBOX,
            KeyEnvelopeId(envelopeId), format, plainLength, cipherLength, bindingHash)
    }

    private fun root() = File(roots.persistentRoot(), "rtc-finalization-v1").also {
        check(it.isDirectory || it.mkdirs()) { "Cannot create call recovery index." }
    }
    private fun file(key: String): File {
        require(key.matches(Regex("[a-f0-9]{64}")))
        return File(root(), "$key.json")
    }
    private fun load(key: String): Pointer {
        val source = file(key)
        check(source.length() in 1..16_384) { "Invalid call recovery pointer." }
        return json.decodeFromString<Pointer>(source.readText()).also { check(it.key == key) }
    }
    private fun save(pointer: Pointer) {
        val target = file(pointer.key)
        val partial = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.partial")
        try {
            FileOutputStream(partial).use { out -> out.write(json.encodeToString(pointer).encodeToByteArray()); out.fd.sync() }
            Files.move(partial.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { partial.delete() }
    }

    override suspend fun pending(): List<PendingCallFinalization> = withContext(Dispatchers.IO) { mutex.withLock {
        root().listFiles().orEmpty().filter { it.name.matches(Regex("[a-f0-9]{64}\\.json")) }
            .map { load(it.nameWithoutExtension).pending() }
    } }

    override suspend fun read(pending: PendingCallFinalization): CallFinalizationRecord = withContext(Dispatchers.IO) { mutex.withLock {
        val pointer = load(pending.key)
        check(pointer.vault == pending.vaultId.value)
        check(pointer.bindingHash == hash(binding(pointer.key, pointer.vault)))
        files.read(pointer.reference()) { stream ->
            val encoded = stream.readBytes()
            try {
                check(encoded.size in 1..16_384)
                json.decodeFromString<CallFinalizationRecord>(encoded.decodeToString()).also {
                    check(hash("${pointer.vault}\u0000${it.operationId}".encodeToByteArray()) == pointer.key)
                    check(deriveAccountVaultId(it.sessionId, it.deviceId) == pending.vaultId)
                    check(it.operationId == pointer.operationId && hash(it.sessionId.encodeToByteArray()) == pointer.accountHash &&
                        hash(it.deviceId.encodeToByteArray()) == pointer.deviceHash && hash(it.roomId.encodeToByteArray()) == pointer.roomHash)
                }
            } finally { encoded.fill(0) }
        }
    } }

    override suspend fun put(vaultId: AccountVaultId, owner: String, record: CallFinalizationRecord): PendingCallFinalization =
        withContext(Dispatchers.IO) { mutex.withLock {
            check(deriveAccountVaultId(record.sessionId, record.deviceId) == vaultId)
            val key = hash("${vaultId.value}\u0000${record.operationId}".encodeToByteArray())
            val previous = if (file(key).exists()) load(key) else null
            val bytes = json.encodeToString(record).encodeToByteArray()
            val reference = try {
                files.write(SecureObjectWriteRequest(SecureObjectId("rtc-finalization:${UUID.randomUUID()}"), vaultId,
                    SecureObjectPurpose.OFFLINE_OUTBOX, binding(key, vaultId.value), bytes.size.toLong())) { it.write(bytes) }
            } finally { bytes.fill(0) }
            val pointer = Pointer(key, vaultId.value, previous?.owner ?: owner, previous?.phase ?: CallCleanupPhase.ACTIVE.name,
                record.operationId, hash(record.sessionId.encodeToByteArray()), hash(record.deviceId.encodeToByteArray()), hash(record.roomId.encodeToByteArray()),
                reference.objectId.value, reference.keyEnvelopeId.value, reference.formatVersion, reference.plaintextLength,
                reference.ciphertextLength, reference.associatedDataSha256, previous?.noInvitationConfirmed ?: false, previous?.releasedAt)
            save(pointer)
            previous?.let { runCatching { files.delete(it.reference()) } }
            pointer.pending()
        } }

    override suspend fun mark(key: String, phase: CallCleanupPhase, noInvitationConfirmed: Boolean?, releasedAt: Long?) = withContext(Dispatchers.IO) { mutex.withLock {
        val pointer = load(key)
        save(pointer.copy(phase = phase.name, noInvitationConfirmed = noInvitationConfirmed ?: pointer.noInvitationConfirmed,
            releasedAt = releasedAt ?: pointer.releasedAt))
    } }

    override suspend fun remove(key: String) = withContext(Dispatchers.IO) { mutex.withLock {
        val pointer = load(key)
        check(files.delete(pointer.reference())) { "Call recovery object deletion remains pending." }
        check(file(key).delete()) { "Call recovery pointer deletion remains pending." }
    } }

    private fun binding(key: String, vault: String) = "rtc-finalization-v1\u0000$vault\u0000$key".encodeToByteArray()
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
