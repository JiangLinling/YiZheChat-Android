/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.timeline

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.security.contentprotection.api.ContentVersionLineageScope
import io.element.android.security.securestorage.api.AccountVaultId
import io.element.android.security.securestorage.api.KeyEnvelopeId
import io.element.android.security.securestorage.api.SecureObjectId
import io.element.android.security.securestorage.api.SecureObjectIdentity
import io.element.android.security.securestorage.api.SecureObjectPurpose
import io.element.android.security.securestorage.api.SecureObjectReference
import io.element.android.security.securestorage.api.SecureObjectWriteRequest
import io.element.android.security.securestorage.api.SecureStorageRoots
import io.element.android.security.securestorage.api.SensitiveFileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class CaptionPendingPhase {
    AWAITING_BINDING, KMS_STORE_STARTED, PRESTORE_REJECTED, PROTECTION_OUTCOME_UNKNOWN, SERVER_REGISTERED, LINEAGE_COMMITTED, DESTRUCTION_PENDING,
}

@Serializable
data class CaptionServiceReceiptOwner(
    val accountIdHash: String,
    val matrixDeviceIdHash: String,
    val roomIdHash: String,
)

@Serializable
data class CaptionPending(
    val operationId: String,
    val targetEventId: String,
    val contentId: String,
    val rootContentId: String,
    val parentContentId: String?,
    val originOperationId: String,
    val lifecycleId: String = "LC_15",
    val parentExpiresAt: Long,
    val phase: String = CaptionPendingPhase.AWAITING_BINDING.name,
    val eventIdSha256: String? = null,
    val expiresAt: Long? = null,
    val createdAt: Long? = null,
    val serviceReceiptOwner: CaptionServiceReceiptOwner? = null,
    val serviceAppliedAt: Long? = null,
    val preStoreRejectedAt: Long? = null,
)

@Serializable
enum class CaptionRootReservationPhase { RESERVED, PENDING_ESTABLISHED, COMMITTED }

data class CaptionRootReservation(
    val contentId: String,
    val phase: CaptionRootReservationPhase,
    val existing: Boolean,
)

interface CaptionPendingStore {
    /** A vault-wide encrypted reservation; exact RESERVED binding can recover a missing pending write. */
    suspend fun reserveRootOperation(
        scope: ContentVersionLineageScope,
        operationId: String,
        contentId: String?,
    ): CaptionRootReservation
    suspend fun advanceRootOperation(
        scope: ContentVersionLineageScope,
        operationId: String,
        contentId: String,
        phase: CaptionRootReservationPhase,
    )
    suspend fun fenceDestruction(scope: ContentVersionLineageScope, contentId: String)
    suspend fun isDestructionFenced(scope: ContentVersionLineageScope, contentId: String): Boolean
    suspend fun all(scope: ContentVersionLineageScope): List<CaptionPending>
    suspend fun put(scope: ContentVersionLineageScope, pending: CaptionPending)
    suspend fun remove(scope: ContentVersionLineageScope, operationId: String)
}

/** The pointer contains only encrypted-object references; pending identifiers and bindings stay encrypted. */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class EncryptedCaptionPendingStore(
    private val files: SensitiveFileStore,
    private val roots: SecureStorageRoots,
) : CaptionPendingStore {
    /** Each vault/operation uses one OS-locked pointer; identities and phase remain encrypted. */
    override suspend fun reserveRootOperation(
        scope: ContentVersionLineageScope,
        operationId: String,
        contentId: String?,
    ): CaptionRootReservation = withRootOperationLock(scope, operationId) { file, operationHash, vaultHash ->
        val existing = readRootOperation(file, scope, operationId, operationHash, vaultHash)
        if (existing != null) {
            val record = existing.second
            if (record.roomId != scope.roomId ||
                contentId != null && record.contentId != contentId) {
                throw SecurityException("LC operation is reserved for another room or root.")
            }
            CaptionRootReservation(record.contentId, record.phase, existing = true)
        } else {
            val chosenContentId = contentId ?: UUID.randomUUID().toString()
            require(chosenContentId.isNotBlank())
            val record = RootOperationReservation(
                operationId, scope.roomId, chosenContentId, CaptionRootReservationPhase.RESERVED,
            )
            writeRootOperation(file, scope, operationHash, vaultHash, record)
            CaptionRootReservation(chosenContentId, record.phase, existing = false)
        }
    }

    override suspend fun advanceRootOperation(
        scope: ContentVersionLineageScope,
        operationId: String,
        contentId: String,
        phase: CaptionRootReservationPhase,
    ) = withRootOperationLock(scope, operationId) { file, operationHash, vaultHash ->
        val (pointer, record) = requireNotNull(readRootOperation(file, scope, operationId, operationHash, vaultHash)) {
            "Caption root operation reservation is missing."
        }
        require(record.roomId == scope.roomId && record.contentId == contentId) {
            "Caption root operation binding changed."
        }
        if (record.phase == phase) return@withRootOperationLock
        require(
            record.phase == CaptionRootReservationPhase.RESERVED && phase == CaptionRootReservationPhase.PENDING_ESTABLISHED ||
                record.phase == CaptionRootReservationPhase.PENDING_ESTABLISHED && phase == CaptionRootReservationPhase.COMMITTED
        ) { "Caption root operation phase transition is invalid." }
        writeRootOperation(file, scope, operationHash, vaultHash, record.copy(phase = phase), pointer)
    }

    private suspend fun <T> withRootOperationLock(
        scope: ContentVersionLineageScope,
        operationId: String,
        action: suspend (File, String, String) -> T,
    ): T = withContext(Dispatchers.IO) {
        require(scope.vaultId.value.isNotBlank() && scope.roomId.isNotBlank() && operationId.isNotBlank())
        val root = File(roots.persistentRoot(), "caption-root-operations-v1").canonicalFile
        if (!root.isDirectory && !root.mkdirs()) error("Cannot create caption root operation directory.")
        val operationHash = sha256("${scope.vaultId.value}\u0000$operationId".encodeToByteArray())
        val vaultHash = sha256(scope.vaultId.value.encodeToByteArray())
        val file = File(root, "$operationHash.yzcp")
        locks.computeIfAbsent(file.path) { Mutex() }.withLock {
            RandomAccessFile(File(root, file.name + ".lock"), "rw").use { raf ->
                raf.channel.use { channel -> channel.lock().use {
                    action(file, operationHash, vaultHash)
                } }
            }
        }
    }

    private suspend fun readRootOperation(
        file: File,
        scope: ContentVersionLineageScope,
        operationId: String,
        operationHash: String,
        vaultHash: String,
    ): Pair<RootOperationPointer, RootOperationReservation>? {
        if (!file.exists()) return null
        require(file.isFile && file.length() in 1..16_384) { "Caption root reservation pointer is corrupt." }
        val pointerBytes = file.readBytes()
        val pointer = try {
            json.decodeFromString<RootOperationPointer>(pointerBytes.decodeToString())
        } finally {
            pointerBytes.fill(0)
        }
        require(pointer.vaultHash == vaultHash && pointer.operationHash == operationHash) {
            "Caption root reservation pointer binding mismatch."
        }
        val binding = rootOperationBinding(scope.vaultId, operationHash)
        try {
            require(pointer.reference.associatedDataSha256 == sha256(binding)) {
                "Caption root reservation reference binding mismatch."
            }
        } finally {
            binding.fill(0)
        }
        val record = files.read(pointer.reference.toReference(scope.vaultId)) { input ->
            val encoded = input.readBytes()
            try {
                require(encoded.size in 1..16_384) { "Caption root reservation exceeds limit." }
                json.decodeFromString<RootOperationReservation>(encoded.decodeToString())
            } finally {
                encoded.fill(0)
            }
        }
        require(record.operationId == operationId && record.roomId.isNotBlank() && record.contentId.isNotBlank()) {
            "Caption root reservation is inconsistent."
        }
        return pointer to record
    }

    private suspend fun writeRootOperation(
        file: File,
        scope: ContentVersionLineageScope,
        operationHash: String,
        vaultHash: String,
        record: RootOperationReservation,
        previous: RootOperationPointer? = null,
    ) {
        val identity = SecureObjectIdentity(
            SecureObjectId("caption-root-operation-v1:${UUID.randomUUID()}"),
            scope.vaultId,
            SecureObjectPurpose.MESSAGE_REFERENCE_INDEX,
        )
        val binding = rootOperationBinding(scope.vaultId, operationHash)
        val encoded = json.encodeToString(record).encodeToByteArray()
        val reference = try {
            files.write(SecureObjectWriteRequest(
                identity.objectId, scope.vaultId, identity.purpose, binding, encoded.size.toLong(),
            )) { it.write(encoded) }
        } finally {
            binding.fill(0)
            encoded.fill(0)
        }
        val pointer = RootOperationPointer(vaultHash, operationHash, reference.toPointer())
        val pointerBytes = json.encodeToString(pointer).encodeToByteArray()
        val partial = File(file.parentFile, ".${file.name}.${UUID.randomUUID()}.partial")
        try {
            FileOutputStream(partial).use { out -> out.write(pointerBytes); out.fd.sync() }
            Files.move(partial.toPath(), file.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            pointerBytes.fill(0)
            partial.delete()
        }
        // The new authenticated pointer is committed. Old encrypted metadata is best-effort cleanup.
        previous?.let { old -> runCatching { files.delete(old.reference.toReference(scope.vaultId)) } }
    }

    private fun rootOperationBinding(vaultId: AccountVaultId, operationHash: String) =
        "caption-root-operation-v1\u0000${vaultId.value}\u0000$operationHash".encodeToByteArray()

    @Serializable private data class RootOperationPointer(
        val vaultHash: String,
        val operationHash: String,
        val reference: Ref,
    )

    @Serializable private data class RootOperationReservation(
        val operationId: String,
        val roomId: String,
        val contentId: String,
        val phase: CaptionRootReservationPhase,
    )

    /** Independent encrypted write-once tombstone. Never retire it without an LC-21 receipt. */
    override suspend fun fenceDestruction(scope: ContentVersionLineageScope, contentId: String) {
        withFenceLock(scope, contentId) { file, contentHash ->
            if (file.exists()) {
                requireNotNull(readFence(file, scope, contentId, contentHash))
                return@withFenceLock
            }
            val identity = SecureObjectIdentity(
                SecureObjectId("caption-destruction-fence-v1:" + UUID.randomUUID()),
                scope.vaultId,
                SecureObjectPurpose.MESSAGE_REFERENCE_INDEX,
            )
            val binding = fenceBinding(scope, contentHash)
            val encoded = json.encodeToString(DestructionFence(scope.roomId, contentId)).encodeToByteArray()
            val reference = try {
                files.write(SecureObjectWriteRequest(
                    identity.objectId, scope.vaultId, identity.purpose, binding, encoded.size.toLong(),
                )) { it.write(encoded) }
            } finally {
                binding.fill(0)
                encoded.fill(0)
            }
            val pointer = DestructionFencePointer(
                sha256(scope.vaultId.value.encodeToByteArray()),
                sha256(scope.roomId.encodeToByteArray()),
                contentHash,
                reference.toPointer(),
            )
            val pointerBytes = json.encodeToString(pointer).encodeToByteArray()
            val partial = File(file.parentFile, "." + file.name + "." + UUID.randomUUID() + ".partial")
            try {
                FileOutputStream(partial).use { out -> out.write(pointerBytes); out.fd.sync() }
                Files.move(partial.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                pointerBytes.fill(0)
                partial.delete()
            }
        }
    }

    override suspend fun isDestructionFenced(scope: ContentVersionLineageScope, contentId: String): Boolean =
        withFenceLock(scope, contentId) { file, contentHash ->
            readFence(file, scope, contentId, contentHash) != null
        }

    private suspend fun readFence(
        file: File,
        scope: ContentVersionLineageScope,
        contentId: String,
        contentHash: String,
    ): DestructionFence? {
        if (!file.exists()) return null
        require(file.isFile && file.length() in 1..16_384) { "Caption destruction pointer is corrupt." }
        val pointerBytes = file.readBytes()
        val pointer = try {
            json.decodeFromString<DestructionFencePointer>(pointerBytes.decodeToString())
        } finally {
            pointerBytes.fill(0)
        }
        require(pointer.vaultHash == sha256(scope.vaultId.value.encodeToByteArray()) &&
            pointer.roomHash == sha256(scope.roomId.encodeToByteArray()) &&
            pointer.contentHash == contentHash) { "Caption destruction pointer binding mismatch." }
        val binding = fenceBinding(scope, contentHash)
        try {
            require(pointer.reference.associatedDataSha256 == sha256(binding)) {
                "Caption destruction reference binding mismatch."
            }
        } finally {
            binding.fill(0)
        }
        return files.read(pointer.reference.toReference(scope.vaultId)) { input ->
            val encoded = input.readBytes()
            try {
                require(encoded.size in 1..16_384)
                json.decodeFromString<DestructionFence>(encoded.decodeToString()).also {
                    require(it.roomId == scope.roomId && it.contentId == contentId) {
                        "Caption destruction identity mismatch."
                    }
                }
            } finally {
                encoded.fill(0)
            }
        }
    }

    private suspend fun <T> withFenceLock(
        scope: ContentVersionLineageScope,
        contentId: String,
        action: suspend (File, String) -> T,
    ): T = withContext(Dispatchers.IO) {
        require(scope.vaultId.value.isNotBlank() && scope.roomId.isNotBlank() && contentId.isNotBlank())
        val root = File(roots.persistentRoot(), "caption-destruction-fence-v1").canonicalFile
        if (!root.isDirectory && !root.mkdirs()) error("Cannot create caption destruction directory.")
        val contentHash = sha256((scope.vaultId.value + "\u0000" + scope.roomId + "\u0000" + contentId).encodeToByteArray())
        val file = File(root, contentHash + ".yzcp")
        locks.computeIfAbsent(file.path) { Mutex() }.withLock {
            RandomAccessFile(File(root, file.name + ".lock"), "rw").use { raf ->
                raf.channel.use { channel -> channel.lock().use {
                    action(file, contentHash)
                } }
            }
        }
    }

    private fun fenceBinding(scope: ContentVersionLineageScope, contentHash: String) =
        ("caption-destruction-fence-v1\u0000" + scope.vaultId.value + "\u0000" + scope.roomId + "\u0000" + contentHash)
            .encodeToByteArray()

    @Serializable private data class DestructionFencePointer(
        val vaultHash: String,
        val roomHash: String,
        val contentHash: String,
        val reference: Ref,
    )
    @Serializable private data class DestructionFence(val roomId: String, val contentId: String)
    override suspend fun all(scope: ContentVersionLineageScope): List<CaptionPending> = locked(scope) { file ->
        val pointer = readPointer(file, scope)
        pointer.current?.let { readSnapshot(scope, pointer.generation, it) }.orEmpty()
    }

    override suspend fun put(scope: ContentVersionLineageScope, pending: CaptionPending) = locked(scope) { file ->
        val pointer = readPointer(file, scope)
        val old = pointer.current?.let { readSnapshot(scope, pointer.generation, it) }.orEmpty()
        val existing = old.firstOrNull { it.operationId == pending.operationId }
        require(existing == null || existing.contentId == pending.contentId && existing.targetEventId == pending.targetEventId) {
            "Caption operation is already bound to another content."
        }
        save(file, scope, pointer, old.filterNot { it.operationId == pending.operationId } + pending)
    }

    override suspend fun remove(scope: ContentVersionLineageScope, operationId: String) = locked(scope) { file ->
        val pointer = readPointer(file, scope)
        val old = pointer.current?.let { readSnapshot(scope, pointer.generation, it) }.orEmpty()
        if (old.any { it.operationId == operationId }) save(file, scope, pointer, old.filterNot { it.operationId == operationId })
    }

    private suspend fun save(file: File, scope: ContentVersionLineageScope, pointer: Pointer, values: List<CaptionPending>) {
        require(values.size <= 256)
        val identity = SecureObjectIdentity(
            SecureObjectId("caption-pending-v1:${UUID.randomUUID()}"),
            scope.vaultId,
            SecureObjectPurpose.MESSAGE_REFERENCE_INDEX,
        )
        val staged = pointer.copy(staging = pointer.staging + identity.objectId.value)
        writePointer(file, scope, staged)
        val next = Math.addExact(pointer.generation, 1)
        val binding = binding(scope, next)
        val encoded = json.encodeToString(values).encodeToByteArray()
        val reference = try {
            files.write(SecureObjectWriteRequest(identity.objectId, scope.vaultId, identity.purpose, binding, encoded.size.toLong())) {
                it.write(encoded)
            }
        } finally {
            binding.fill(0)
            encoded.fill(0)
        }
        val committed = Pointer(next, reference.toPointer(), pointer.retired + listOfNotNull(pointer.current), staged.staging)
        // This atomic pointer replacement is the commit. An interrupted write leaves the old current intact.
        writePointer(file, scope, committed)
        cleanup(file, scope, committed)
    }

    private suspend fun cleanup(file: File, scope: ContentVersionLineageScope, pointer: Pointer) {
        val stillRetired = pointer.retired.filter { ref ->
            runCatching { !files.delete(ref.toReference(scope.vaultId)) }.getOrDefault(true)
        }
        val stillStaged = pointer.staging.filter { id ->
            if (pointer.current?.objectId == id) return@filter false
            runCatching { !files.deleteObject(SecureObjectIdentity(SecureObjectId(id), scope.vaultId, SecureObjectPurpose.MESSAGE_REFERENCE_INDEX)) }
                .getOrDefault(true)
        }
        if (stillRetired != pointer.retired || stillStaged != pointer.staging) {
            runCatching { writePointer(file, scope, pointer.copy(retired = stillRetired, staging = stillStaged)) }
        }
    }

    private suspend fun readSnapshot(scope: ContentVersionLineageScope, generation: Long, pointer: Ref): List<CaptionPending> {
        val reference = pointer.toReference(scope.vaultId)
        require(reference.associatedDataSha256 == sha256(binding(scope, generation))) { "Caption journal reference binding mismatch." }
        return files.read(reference) { input ->
            val encoded = input.readBytes()
            try {
                require(encoded.size <= 512 * 1024) { "Caption journal exceeds limit." }
                json.decodeFromString<List<CaptionPending>>(encoded.decodeToString()).also { require(it.size <= 256) }
            } finally {
                encoded.fill(0)
            }
        }
    }

    private suspend fun <T> locked(scope: ContentVersionLineageScope, action: suspend (File) -> T): T = withContext(Dispatchers.IO) {
        require(scope.vaultId.value.isNotBlank() && scope.roomId.isNotBlank())
        val root = File(roots.persistentRoot(), "caption-pending-v1").canonicalFile
        if (!root.isDirectory && !root.mkdirs()) error("Cannot create caption pending directory.")
        val file = File(root, sha256("${scope.vaultId.value}\u0000${scope.roomId}".encodeToByteArray()) + ".yzcp")
        locks.computeIfAbsent(file.path) { Mutex() }.withLock {
            RandomAccessFile(File(root, file.name + ".lock"), "rw").use { raf ->
                raf.channel.use { channel -> channel.lock().use {
                    val pointer = readPointer(file, scope)
                    if (pointer.staging.isNotEmpty() || pointer.retired.isNotEmpty()) cleanup(file, scope, pointer)
                    action(file)
                } }
            }
        }
    }

    private fun readPointer(file: File, scope: ContentVersionLineageScope): Pointer {
        if (!file.exists()) return Pointer()
        require(file.isFile && file.length() in 1..256_000) { "Caption pointer is corrupt." }
        val encoded = file.readBytes()
        return try {
            json.decodeFromString<Pointer>(encoded.decodeToString()).also {
                require(it.vaultId == scope.vaultId.value && it.roomHash == sha256(scope.roomId.encodeToByteArray()))
                require((it.generation == 0L) == (it.current == null))
                require(it.staging.size <= 512 && it.retired.size <= 512)
            }
        } finally { encoded.fill(0) }
    }

    private fun writePointer(file: File, scope: ContentVersionLineageScope, pointer: Pointer) {
        val encoded = json.encodeToString(pointer.copy(
            vaultId = scope.vaultId.value,
            roomHash = sha256(scope.roomId.encodeToByteArray()),
        )).encodeToByteArray()
        val partial = File(file.parentFile, ".${file.name}.${UUID.randomUUID()}.partial")
        try {
            FileOutputStream(partial).use { out -> out.write(encoded); out.fd.sync() }
            Files.move(partial.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { encoded.fill(0); partial.delete() }
    }

    private fun binding(scope: ContentVersionLineageScope, generation: Long) =
        "caption-pending-v1\u0000${scope.vaultId.value}\u0000${sha256(scope.roomId.encodeToByteArray())}\u0000$generation".encodeToByteArray()

    private fun sha256(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    @Serializable private data class Pointer(
        val generation: Long = 0,
        val current: Ref? = null,
        val retired: List<Ref> = emptyList(),
        val staging: List<String> = emptyList(),
        val vaultId: String = "",
        val roomHash: String = "",
    )
    @Serializable private data class Ref(
        val objectId: String, val keyEnvelopeId: String, val version: Int,
        val plaintextLength: Long, val ciphertextLength: Long, val associatedDataSha256: String,
    ) {
        fun toReference(vaultId: AccountVaultId) = SecureObjectReference(
            SecureObjectId(objectId), vaultId, SecureObjectPurpose.MESSAGE_REFERENCE_INDEX,
            KeyEnvelopeId(keyEnvelopeId), version, plaintextLength, ciphertextLength, associatedDataSha256,
        )
    }
    private fun SecureObjectReference.toPointer() = Ref(
        objectId.value, keyEnvelopeId.value, formatVersion, plaintextLength, ciphertextLength, associatedDataSha256,
    )
    private companion object {
        val json = Json { ignoreUnknownKeys = false }
        val locks = ConcurrentHashMap<String, Mutex>()
    }
}
