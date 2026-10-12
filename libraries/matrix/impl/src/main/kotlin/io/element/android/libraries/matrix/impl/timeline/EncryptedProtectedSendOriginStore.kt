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

import io.element.android.security.contentprotection.api.ContentKind
import io.element.android.security.dataprotection.api.FullAppLifecycleId

@Serializable
internal data class ProtectedSendOriginRecord(
    val operationId: String, val lifecycleId: String, val accountIdHash: String,
    val matrixDeviceIdHash: String, val installationId: String, val roomIdHash: String,
    val eventId: String?, val transactionId: String?, val contentId: String,
    val contentKind: String, val carrierSha256: String, val generation: Long? = null,
    val preparedEnvelope: String? = null,
    val preparedIntentSha256: String? = null,
    // null is a legacy/unknown boundary, never evidence that no request was sent.
    val preparationStarted: Boolean? = null,
    val relayEditIntent: String? = null,
    val relayEditResolved: Boolean = false,
) {
    fun origin() = ProtectedEventOwnershipResolver.Origin(operationId, FullAppLifecycleId.valueOf(lifecycleId),
        accountIdHash, matrixDeviceIdHash, installationId, roomIdHash, eventId, transactionId, contentId,
        ContentKind.valueOf(contentKind), carrierSha256, generation)
    companion object {
        fun from(origin: ProtectedEventOwnershipResolver.Origin) = ProtectedSendOriginRecord(
            origin.operationId, origin.lifecycleId.name, origin.accountIdHash, origin.matrixDeviceIdHash,
            origin.installationId, origin.roomIdHash, origin.eventId, origin.transactionId,
            origin.contentId, origin.contentKind.name, origin.carrierSha256, origin.generation)
    }
}

data class PreparedProtectedSendOrigin(
    val origin: ProtectedEventOwnershipResolver.Origin,
    val encodedEnvelope: String,
    val intentSha256: String,
)

data class ReservedProtectedSendOrigin(
    val origin: ProtectedEventOwnershipResolver.Origin,
    val intentSha256: String,
    val preparationStarted: Boolean?,
)

data class RecoverableRelayEditOrigin(val origin: ProtectedEventOwnershipResolver.Origin, val encodedIntent: String)

interface ProtectedSendOriginStore {
    suspend fun rememberRelayEditRecovery(scope: ContentVersionLineageScope, targetEventId: String,
        origin: ProtectedEventOwnershipResolver.Origin, encodedIntent: String,
    ): Unit = error("Durable relay recovery is unavailable.")
    suspend fun pendingRelayEditRecovery(scope: ContentVersionLineageScope, targetEventId: String): RecoverableRelayEditOrigin? = null
    suspend fun resolveRelayEditRecovery(scope: ContentVersionLineageScope, targetEventId: String, operationId: String): Unit =
        error("Durable relay recovery is unavailable.")
    suspend fun <T> withPreparation(scope: ContentVersionLineageScope, contentId: String, block: suspend () -> T): T = block()
    suspend fun find(scope: ContentVersionLineageScope, contentId: String): ProtectedEventOwnershipResolver.Origin?
    suspend fun remember(scope: ContentVersionLineageScope, origin: ProtectedEventOwnershipResolver.Origin)
    suspend fun prepared(scope: ContentVersionLineageScope, contentId: String): PreparedProtectedSendOrigin? = null
    suspend fun reserved(scope: ContentVersionLineageScope, contentId: String): ReservedProtectedSendOrigin? = null
    suspend fun markPreparationStarted(scope: ContentVersionLineageScope, origin: ProtectedEventOwnershipResolver.Origin,
        intentSha256: String,
    ): Unit = error("Durable protected interaction preparation boundary is unavailable.")
    suspend fun reservePreparation(scope: ContentVersionLineageScope, origin: ProtectedEventOwnershipResolver.Origin,
        intentSha256: String,
    ): Unit = error("Durable protected interaction preparation is unavailable.")
    suspend fun rememberPrepared(scope: ContentVersionLineageScope, origin: ProtectedEventOwnershipResolver.Origin,
        encodedEnvelope: String, intentSha256: String,
    ): Unit = error("Durable protected interaction preparation is unavailable.")
}

/** One encrypted producer record per content; atomic pointers retain failed encrypted-object cleanup debt. */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class EncryptedProtectedSendOriginStore(
    private val files: SensitiveFileStore,
    private val roots: SecureStorageRoots,
) : ProtectedSendOriginStore {
    private fun relayRecoveryScope(scope: ContentVersionLineageScope, targetEventId: String): ContentVersionLineageScope {
        require(ProtectedEventOwnershipResolver.isCanonicalEvent(targetEventId))
        return contentScope(scope, "relay-edit-recovery-v1:" + sha256(targetEventId.encodeToByteArray()))
    }

    override suspend fun rememberRelayEditRecovery(scope: ContentVersionLineageScope, targetEventId: String,
        origin: ProtectedEventOwnershipResolver.Origin, encodedIntent: String,
    ) {
        require(origin.lifecycleId == FullAppLifecycleId.LC_15 && origin.contentKind == ContentKind.RELAY_OPERATION)
        require(origin.roomIdHash == sha256(scope.roomId.encodeToByteArray()) && encodedIntent.length <= 16_000)
        val key = relayRecoveryScope(scope, targetEventId)
        locked(key) { file ->
            val pointer = readPointer(file, key)
            val previous = pointer.current?.let { readSnapshot(key, pointer.generation, it).single() }
            val record = ProtectedSendOriginRecord.from(origin).copy(relayEditIntent = encodedIntent)
            if (previous?.operationId == origin.operationId) {
                require(previous.copy(relayEditResolved = false) == record) { "Original relay edit intent is immutable." }
                return@locked
            }
            check(previous == null || previous.relayEditResolved) { "An original relay edit still requires recovery." }
            save(file, key, pointer, listOf(record))
        }
    }

    override suspend fun pendingRelayEditRecovery(scope: ContentVersionLineageScope, targetEventId: String): RecoverableRelayEditOrigin? {
        val key = relayRecoveryScope(scope, targetEventId)
        return locked(key) { file ->
            val pointer = readPointer(file, key)
            pointer.current?.let { readSnapshot(key, pointer.generation, it).single() }
                ?.takeUnless { it.relayEditResolved }
                ?.let { RecoverableRelayEditOrigin(it.origin(), checkNotNull(it.relayEditIntent)) }
        }
    }

    override suspend fun resolveRelayEditRecovery(scope: ContentVersionLineageScope, targetEventId: String, operationId: String) {
        val key = relayRecoveryScope(scope, targetEventId)
        locked(key) { file ->
            val pointer = readPointer(file, key)
            val previous = pointer.current?.let { readSnapshot(key, pointer.generation, it).single() } ?: return@locked
            require(previous.operationId == operationId) { "Only the original relay edit can resolve its recovery entry." }
            if (!previous.relayEditResolved) save(file, key, pointer, listOf(previous.copy(relayEditResolved = true)))
        }
    }

    override suspend fun <T> withPreparation(scope: ContentVersionLineageScope, contentId: String,
        block: suspend () -> T,
    ): T = withContext(Dispatchers.IO) {
        val key = contentScope(scope, contentId)
        val root = File(roots.persistentRoot(), "protected-send-origin-v1").canonicalFile
        if (!root.isDirectory && !root.mkdirs()) error("Cannot create send origin directory.")
        val file = File(root, sha256("${key.vaultId.value}\u0000${key.roomId}".encodeToByteArray()) + ".prepare.lock")
        // This is the existing journal's writer serialization, including across
        // process recreation. Its record commit lock remains independent.
        locks.computeIfAbsent(file.path) { Mutex() }.withLock {
            RandomAccessFile(file, "rw").use { raf ->
                raf.channel.use { channel -> channel.lock().use { block() } }
            }
        }
    }
    private fun contentScope(scope: ContentVersionLineageScope, contentId: String): ContentVersionLineageScope {
        require(contentId.isNotBlank() && contentId.length <= 256 && '\u0000' !in contentId)
        return scope.copy(roomId = scope.roomId + "\u0000" + contentId)
    }
    override suspend fun find(scope: ContentVersionLineageScope, contentId: String): ProtectedEventOwnershipResolver.Origin? {
        val key = contentScope(scope, contentId)
        return locked(key) { file ->
            val pointer = readPointer(file, key)
            pointer.current?.let { readSnapshot(key, pointer.generation, it).singleOrNull()?.origin() }
        }
    }
    override suspend fun remember(scope: ContentVersionLineageScope, origin: ProtectedEventOwnershipResolver.Origin) {
        rememberRecord(scope, origin)
    }
    override suspend fun prepared(scope: ContentVersionLineageScope, contentId: String): PreparedProtectedSendOrigin? {
        val key = contentScope(scope, contentId)
        return locked(key) { file ->
            val pointer = readPointer(file, key)
            pointer.current?.let { readSnapshot(key, pointer.generation, it).singleOrNull() }
                ?.takeIf { it.preparedIntentSha256 != null && it.preparedEnvelope != null }
                ?.also { require(sha256(checkNotNull(it.preparedEnvelope).encodeToByteArray()) == it.carrierSha256) }
                ?.let { PreparedProtectedSendOrigin(it.origin(), checkNotNull(it.preparedEnvelope), checkNotNull(it.preparedIntentSha256)) }
        }
    }
    override suspend fun rememberPrepared(scope: ContentVersionLineageScope, origin: ProtectedEventOwnershipResolver.Origin,
        encodedEnvelope: String, intentSha256: String,
    ) {
        require(encodedEnvelope.length <= 512_000)
        require(intentSha256.matches(Regex("[0-9a-f]{64}")))
        require(sha256(encodedEnvelope.encodeToByteArray()) == origin.carrierSha256)
        rememberRecord(scope, origin, encodedEnvelope, intentSha256)
    }
    override suspend fun reserved(scope: ContentVersionLineageScope, contentId: String): ReservedProtectedSendOrigin? {
        val key = contentScope(scope, contentId)
        return locked(key) { file ->
            val pointer = readPointer(file, key)
            pointer.current?.let { readSnapshot(key, pointer.generation, it).singleOrNull() }
                ?.takeIf { it.preparedIntentSha256 != null && it.preparedEnvelope == null && it.carrierSha256.isEmpty() &&
                    it.eventId == null && it.transactionId == null && it.generation == null }
                ?.let { ReservedProtectedSendOrigin(it.origin(), checkNotNull(it.preparedIntentSha256), it.preparationStarted) }
        }
    }
    override suspend fun markPreparationStarted(scope: ContentVersionLineageScope, origin: ProtectedEventOwnershipResolver.Origin,
        intentSha256: String,
    ) {
        val reservation = checkNotNull(reserved(scope, origin.contentId)) { "Original preparation reservation is unavailable." }
        require(reservation.origin == origin && reservation.intentSha256 == intentSha256)
        require(reservation.preparationStarted != null) { "Legacy preparation boundary remains unknown." }
        rememberRecord(scope, origin, intentSha256 = intentSha256, preparationStarted = true)
    }
    override suspend fun reservePreparation(scope: ContentVersionLineageScope, origin: ProtectedEventOwnershipResolver.Origin,
        intentSha256: String,
    ) {
        require(origin.carrierSha256.isEmpty() && origin.eventId == null && origin.transactionId == null && origin.generation == null)
        require(intentSha256.matches(Regex("[0-9a-f]{64}")))
        rememberRecord(scope, origin, intentSha256 = intentSha256, preparationStarted = false)
    }
    private suspend fun rememberRecord(scope: ContentVersionLineageScope, origin: ProtectedEventOwnershipResolver.Origin,
        preparedEnvelope: String? = null, intentSha256: String? = null,
        preparationStarted: Boolean? = null,
    ) {
        val key = contentScope(scope, origin.contentId)
        locked(key) { file ->
            val pointer = readPointer(file, key)
            val previous = pointer.current?.let { readSnapshot(key, pointer.generation, it).single() }
            require(preparedEnvelope == null || previous?.preparationStarted != false) {
                "Protected preparation cannot complete before its KMS dispatch boundary."
            }
            val record = ProtectedSendOriginRecord.from(origin).copy(
                preparedEnvelope = preparedEnvelope ?: previous?.preparedEnvelope,
                preparedIntentSha256 = intentSha256 ?: previous?.preparedIntentSha256,
                preparationStarted = preparationStarted ?: previous?.preparationStarted,
            )
            val reserved = previous != null && previous.carrierSha256.isEmpty() &&
                previous.preparedIntentSha256 != null && previous.preparedEnvelope == null
            if (previous == record) return@locked
            require(previous == null || previous.copy(eventId = record.eventId, transactionId = record.transactionId,
                preparedEnvelope = record.preparedEnvelope, preparedIntentSha256 = record.preparedIntentSha256,
                carrierSha256 = if (reserved) record.carrierSha256 else previous.carrierSha256,
                preparationStarted = if (previous.preparationStarted == false && record.preparationStarted == true)
                    true else previous.preparationStarted,
                generation = if (reserved) record.generation else previous.generation) == record &&
                (previous.eventId == null || previous.eventId == record.eventId) &&
                (previous.transactionId == null || previous.transactionId == record.transactionId) &&
                (previous.preparedEnvelope == null || previous.preparedEnvelope == record.preparedEnvelope) &&
                (previous.preparedIntentSha256 == null || previous.preparedIntentSha256 == record.preparedIntentSha256)) {
                "Protected send origin cannot be rebound to another identity."
            }
            save(file, key, pointer, listOf(record))
        }
    }
    private suspend fun save(file: File, scope: ContentVersionLineageScope, pointer: Pointer, values: List<ProtectedSendOriginRecord>) {
        require(values.size <= 1)
        val identity = SecureObjectIdentity(
            SecureObjectId("protected-send-origin-v1:${UUID.randomUUID()}"),
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

    private suspend fun readSnapshot(scope: ContentVersionLineageScope, generation: Long, pointer: Ref): List<ProtectedSendOriginRecord> {
        val reference = pointer.toReference(scope.vaultId)
        require(reference.associatedDataSha256 == sha256(binding(scope, generation))) { "Send origin journal reference binding mismatch." }
        return files.read(reference) { input ->
            val encoded = input.readBytes()
            try {
                require(encoded.size <= 512 * 1024) { "Send origin journal exceeds limit." }
                json.decodeFromString<List<ProtectedSendOriginRecord>>(encoded.decodeToString()).also { require(it.size <= 1) }
            } finally {
                encoded.fill(0)
            }
        }
    }

    private suspend fun <T> locked(scope: ContentVersionLineageScope, action: suspend (File) -> T): T = withContext(Dispatchers.IO) {
        require(scope.vaultId.value.isNotBlank() && scope.roomId.isNotBlank())
        val root = File(roots.persistentRoot(), "protected-send-origin-v1").canonicalFile
        if (!root.isDirectory && !root.mkdirs()) error("Cannot create send origin directory.")
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
        require(file.isFile && file.length() in 1..256_000) { "Send origin pointer is corrupt." }
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
        "protected-send-origin-v1\u0000${scope.vaultId.value}\u0000${sha256(scope.roomId.encodeToByteArray())}\u0000$generation".encodeToByteArray()

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
