/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.security.contentprotection.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.security.contentprotection.api.ContentId
import io.element.android.security.contentprotection.api.ContentVersionGraph
import io.element.android.security.contentprotection.api.ContentVersionAppendResult
import io.element.android.security.contentprotection.api.ContentVersionLineageConflictException
import io.element.android.security.contentprotection.api.ContentVersionLineageCorruptException
import io.element.android.security.contentprotection.api.ContentVersionLineageScope
import io.element.android.security.contentprotection.api.ContentVersionLineageStore
import io.element.android.security.contentprotection.api.ContentVersionNode
import io.element.android.security.contentprotection.api.ContentVersionRetirementState
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
open class ProtectedContentVersionLineageStore(
    private val sensitiveFileStore: SensitiveFileStore,
    private val roots: SecureStorageRoots,
) : ContentVersionLineageStore {
    override suspend fun append(
        scope: ContentVersionLineageScope,
        node: ContentVersionNode,
    ): ContentVersionAppendResult = withScopeLock(scope) { indexFile ->
        validateScope(scope)
        validateNodeShape(node)
        val index = recoverStagingLocked(indexFile, scope, readIndex(indexFile, scope))
        val nodes = readNodes(scope, index)
        validateGraph(nodes)
        nodes.firstOrNull { it.operationId == node.operationId }?.let { existing ->
            if (existing != node) conflict("operationId is already bound to different lineage data")
            val graph = project(node.rootContentId, index.generation, nodes, index.retirements.size + index.staging.size)
            return@withScopeLock ContentVersionAppendResult(graph, graph.retirementState())
        }
        if (nodes.any { it.contentId == node.contentId }) conflict("contentId already exists in the lineage graph")
        validateAppend(nodes, node)

        val updatedNodes = nodes + node
        validateGraph(updatedNodes)
        val nextGeneration = Math.addExact(index.generation, 1L)
        val encoded = encodeGraph(scope, nextGeneration, updatedNodes)
        val binding = binding(scope, nextGeneration)
        val objectIdentity = SecureObjectIdentity(
            objectId = SecureObjectId("content-lineage-v1:${UUID.randomUUID()}"),
            vaultId = scope.vaultId,
            purpose = SecureObjectPurpose.MESSAGE_REFERENCE_INDEX,
        )
        val staged = index.copy(staging = index.staging + objectIdentity)
        writeIndex(indexFile, scope, staged)
        val newReference = try {
            sensitiveFileStore.write(
                SecureObjectWriteRequest(
                    objectId = objectIdentity.objectId,
                    vaultId = scope.vaultId,
                    purpose = SecureObjectPurpose.MESSAGE_REFERENCE_INDEX,
                    associatedData = binding,
                    expectedPlaintextLength = encoded.size.toLong(),
                )
            ) { output -> output.write(encoded) }
        } catch (failure: Exception) {
            discardStagedLocked(indexFile, scope, staged, objectIdentity)
            throw failure
        } finally {
            encoded.fill(0)
            binding.fill(0)
        }

        val committed = LineageIndex(
            generation = nextGeneration,
            current = newReference,
            retirements = index.retirements + listOfNotNull(index.current),
            staging = index.staging,
        )
        try {
            writeIndex(indexFile, scope, committed)
        } catch (failure: Exception) {
            // The new random object was never made authoritative. Best-effort deletion is safe; failure is surfaced
            // because no caller may treat an uncommitted append as successful.
            discardStagedLocked(indexFile, scope, staged, objectIdentity)
            throw failure
        }
        // From this point the append is authoritative. Retirement is a separate cleanup fact: a delete or journal
        // rewrite failure must be returned as debt and must never turn a committed append into an ambiguous throw.
        val afterRetirement = try {
            retireLocked(indexFile, scope, committed)
        } catch (_: Exception) {
            committed
        }
        val graph = project(
            node.rootContentId,
            nextGeneration,
            updatedNodes,
            afterRetirement.retirements.size + afterRetirement.staging.size,
        )
        ContentVersionAppendResult(graph, graph.retirementState())
    }

    override suspend fun graph(
        scope: ContentVersionLineageScope,
        rootContentId: ContentId,
    ): ContentVersionGraph? = withScopeLock(scope) { indexFile ->
        validateScope(scope)
        val index = recoverStagingLocked(indexFile, scope, readIndex(indexFile, scope))
        val nodes = readNodes(scope, index)
        validateGraph(nodes)
        if (nodes.none { it.rootContentId == rootContentId }) null
        else project(rootContentId, index.generation, nodes, index.retirements.size + index.staging.size)
    }

    override suspend fun rootForContent(scope: ContentVersionLineageScope, contentId: ContentId): ContentId? =
        withScopeLock(scope) { indexFile ->
            validateScope(scope)
            val index = recoverStagingLocked(indexFile, scope, readIndex(indexFile, scope))
            val nodes = readNodes(scope, index)
            validateGraph(nodes)
            nodes.singleOrNull { it.contentId == contentId }?.rootContentId
        }

    override suspend fun retryPendingRetirements(scope: ContentVersionLineageScope): Int =
        withScopeLock(scope) { indexFile ->
            validateScope(scope)
            val index = recoverStagingLocked(indexFile, scope, readIndex(indexFile, scope))
            readNodes(scope, index).also(::validateGraph)
            val retired = retireLocked(indexFile, scope, index)
            retired.retirements.size + retired.staging.size
        }

    private suspend fun recoverStagingLocked(indexFile: File, scope: ContentVersionLineageScope, index: LineageIndex): LineageIndex {
        if (index.staging.isEmpty()) return index
        val remaining = index.staging.filter { identity ->
            try {
                !sensitiveFileStore.deleteObject(identity)
            } catch (_: Exception) {
                true
            }
        }
        if (remaining.size == index.staging.size) return index
        return index.copy(staging = remaining).also { writeIndex(indexFile, scope, it) }
    }

    private suspend fun discardStagedLocked(
        indexFile: File,
        scope: ContentVersionLineageScope,
        index: LineageIndex,
        identity: SecureObjectIdentity,
    ) {
        val deleted = runCatching { sensitiveFileStore.deleteObject(identity) }.getOrDefault(false)
        if (deleted) runCatching { writeIndex(indexFile, scope, index.copy(staging = index.staging - identity)) }
    }

    private suspend fun <T> withScopeLock(
        scope: ContentVersionLineageScope,
        action: suspend (File) -> T,
    ): T = withContext(Dispatchers.IO) {
        val index = indexFile(scope)
        processMutexes.computeIfAbsent(index.path) { Mutex() }.withLock {
            val lockFile = File(index.parentFile, "${index.name}.lock")
            RandomAccessFile(lockFile, "rw").use { randomAccess ->
                randomAccess.channel.use { channel ->
                    channel.lock().use { action(index) }
                }
            }
        }
    }

    private suspend fun readNodes(scope: ContentVersionLineageScope, index: LineageIndex): List<ContentVersionNode> {
        val reference = index.current ?: return emptyList()
        requireReference(scope, index.generation, reference)
        return try {
            sensitiveFileStore.read(reference) { input -> decodeGraph(scope, index.generation, input.readBytes()) }
        } catch (failure: ContentVersionLineageCorruptException) {
            throw failure
        } catch (failure: Exception) {
            throw ContentVersionLineageCorruptException("lineage snapshot is missing or unauthenticated", failure)
        }
    }

    private suspend fun retireLocked(
        indexFile: File,
        scope: ContentVersionLineageScope,
        index: LineageIndex,
    ): LineageIndex {
        if (index.retirements.isEmpty()) return index
        val remaining = index.retirements.filter { reference ->
            try {
                !sensitiveFileStore.delete(reference)
            } catch (_: Exception) {
                true
            }
        }
        if (remaining.size == index.retirements.size) return index
        val updated = index.copy(retirements = remaining)
        writeIndex(indexFile, scope, updated)
        return updated
    }

    private fun validateAppend(nodes: List<ContentVersionNode>, node: ContentVersionNode) {
        val parentId = node.parentContentId
        if (parentId == null) {
            if (node.rootContentId != node.contentId) conflict("a root version must identify itself as root")
            if (nodes.any { it.rootContentId == node.rootContentId }) conflict("rootContentId already exists")
            return
        }
        val parent = nodes.singleOrNull { it.contentId == parentId }
            ?: conflict("parentContentId is missing")
        if (parent.rootContentId != node.rootContentId) conflict("parent belongs to a different root")
        if (node.createdAtEpochMillis < parent.createdAtEpochMillis) {
            conflict("a derived version cannot predate its parent")
        }
        if (node.expiresAtEpochMillis > parent.expiresAtEpochMillis) {
            conflict("a derived version cannot outlive its parent")
        }
    }

    private fun validateGraph(nodes: List<ContentVersionNode>) {
        if (nodes.size > MAX_NODES) corrupt("lineage graph exceeds the supported node count")
        val byContent = nodes.associateBy(ContentVersionNode::contentId)
        if (byContent.size != nodes.size) corrupt("lineage graph contains duplicate contentId values")
        if (nodes.map(ContentVersionNode::operationId).toSet().size != nodes.size) {
            corrupt("lineage graph contains duplicate operationId values")
        }
        nodes.forEach { node ->
            try {
                validateNodeShape(node)
            } catch (failure: IllegalArgumentException) {
                throw ContentVersionLineageCorruptException("lineage node is malformed", failure)
            }
            val visited = mutableSetOf<ContentId>()
            var cursor = node
            while (cursor.parentContentId != null) {
                if (!visited.add(cursor.contentId)) corrupt("lineage graph contains a cycle")
                val parent = byContent[cursor.parentContentId] ?: corrupt("lineage graph has a missing parent")
                if (parent.rootContentId != cursor.rootContentId) corrupt("lineage branch crosses roots")
                if (cursor.createdAtEpochMillis < parent.createdAtEpochMillis) corrupt("derived version predates its parent")
                if (cursor.expiresAtEpochMillis > parent.expiresAtEpochMillis) corrupt("derived expiry extends its parent")
                cursor = parent
            }
            if (cursor.contentId != node.rootContentId || cursor.rootContentId != cursor.contentId) {
                corrupt("lineage branch does not terminate at its declared root")
            }
        }
    }

    private fun validateScope(scope: ContentVersionLineageScope) {
        require(scope.vaultId.value.isNotBlank() && scope.vaultId.value.length <= MAX_ID_BYTES)
        require(scope.roomId.isNotBlank() && scope.roomId.encodeToByteArray().size <= MAX_ID_BYTES)
    }

    private fun validateNodeShape(node: ContentVersionNode) {
        require(node.contentId.value.isNotBlank() && node.contentId.value.encodeToByteArray().size <= MAX_ID_BYTES)
        require(node.rootContentId.value.isNotBlank() && node.rootContentId.value.encodeToByteArray().size <= MAX_ID_BYTES)
        require(node.parentContentId?.value?.encodeToByteArray()?.size?.let { it in 1..MAX_ID_BYTES } != false)
        require(node.operationId.encodeToByteArray().size in 16..MAX_ID_BYTES)
        require(node.eventIdSha256.matches(HEX_SHA256))
        require(node.createdAtEpochMillis > 0)
        require(node.expiresAtEpochMillis >= node.createdAtEpochMillis)
    }

    private fun project(
        root: ContentId,
        generation: Long,
        nodes: List<ContentVersionNode>,
        pendingRetirements: Int,
    ): ContentVersionGraph {
        val versions = nodes.filter { it.rootContentId == root }
            .sortedWith(compareBy(ContentVersionNode::createdAtEpochMillis, { it.contentId.value }))
        if (versions.isEmpty()) corrupt("requested lineage root is absent")
        return ContentVersionGraph(root, generation, versions, pendingRetirements)
    }

    private fun indexFile(scope: ContentVersionLineageScope): File {
        val root = File(roots.persistentRoot(), INDEX_DIRECTORY).canonicalFile
        if (!root.isDirectory && !root.mkdirs()) error("Unable to create lineage index root.")
        val name = sha256Hex("${scope.vaultId.value}\u0000${scope.roomId}") + INDEX_SUFFIX
        return File(root, name).canonicalFile.also { require(it.path.startsWith(root.path + File.separator)) }
    }

    private fun readIndex(file: File, scope: ContentVersionLineageScope): LineageIndex {
        if (!file.exists()) return LineageIndex(0, null, emptyList(), emptyList())
        if (!file.isFile || file.length() !in 1..MAX_INDEX_BYTES) corrupt("lineage index has an invalid size")
        return try {
            decodeIndex(scope, file.readBytes())
        } catch (failure: ContentVersionLineageCorruptException) {
            throw failure
        } catch (failure: Exception) {
            throw ContentVersionLineageCorruptException("lineage index is corrupt", failure)
        }
    }

    private fun writeIndex(file: File, scope: ContentVersionLineageScope, index: LineageIndex) {
        val encoded = encodeIndex(scope, index)
        try {
            persistIndex(file, encoded)
        } finally {
            encoded.fill(0)
        }
    }

    protected open fun persistIndex(file: File, encoded: ByteArray) {
        val partial = File(file.parentFile, ".${file.name}.${UUID.randomUUID()}.partial")
        try {
            FileOutputStream(partial).use { output ->
                output.write(encoded)
                output.fd.sync()
            }
            Files.move(partial.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (failure: Exception) {
            throw ContentVersionLineageCorruptException("lineage index atomic replace failed", failure)
        } finally {
            partial.delete()
        }
    }

    private fun requireReference(scope: ContentVersionLineageScope, generation: Long, reference: SecureObjectReference) {
        if (
            reference.vaultId != scope.vaultId ||
            reference.purpose != SecureObjectPurpose.MESSAGE_REFERENCE_INDEX ||
            reference.associatedDataSha256 != sha256Hex(binding(scope, generation))
        ) corrupt("lineage reference is outside its vault, room, or generation binding")
    }

    private fun encodeGraph(scope: ContentVersionLineageScope, generation: Long, nodes: List<ContentVersionNode>): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(GRAPH_MAGIC)
                output.writeString(scope.vaultId.value)
                output.writeString(sha256Hex(scope.roomId))
                output.writeLong(generation)
                output.writeInt(nodes.size)
                nodes.forEach { output.writeNode(it) }
            }
            bytes.toByteArray()
        }

    private fun decodeGraph(scope: ContentVersionLineageScope, generation: Long, encoded: ByteArray): List<ContentVersionNode> =
        DataInputStream(ByteArrayInputStream(encoded)).use { input ->
            try {
                if (!input.readExact(GRAPH_MAGIC.size).contentEquals(GRAPH_MAGIC)) corrupt("unknown lineage graph format")
                if (input.readString(MAX_ID_BYTES) != scope.vaultId.value) corrupt("lineage graph vault mismatch")
                if (input.readString(64) != sha256Hex(scope.roomId)) corrupt("lineage graph room mismatch")
                if (input.readLong() != generation) corrupt("lineage graph generation mismatch")
                val count = input.readInt().also { if (it !in 1..MAX_NODES) corrupt("invalid lineage node count") }
                List(count) { input.readNode() }.also { if (input.read() != -1) corrupt("trailing lineage graph bytes") }
            } finally {
                encoded.fill(0)
            }
        }

    private fun encodeIndex(scope: ContentVersionLineageScope, index: LineageIndex): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.write(INDEX_MAGIC)
            output.writeString(scope.vaultId.value)
            output.writeString(sha256Hex(scope.roomId))
            output.writeLong(index.generation)
            output.writeBoolean(index.current != null)
            index.current?.let { output.writeReference(it) }
            output.writeInt(index.retirements.size)
            index.retirements.forEach { output.writeReference(it) }
            output.writeInt(index.staging.size)
            index.staging.forEach { output.writeIdentity(it) }
        }
        bytes.toByteArray()
    }

    private fun decodeIndex(scope: ContentVersionLineageScope, encoded: ByteArray): LineageIndex =
        DataInputStream(ByteArrayInputStream(encoded)).use { input ->
            try {
                if (!input.readExact(INDEX_MAGIC.size).contentEquals(INDEX_MAGIC)) corrupt("unknown lineage index format")
                if (input.readString(MAX_ID_BYTES) != scope.vaultId.value) corrupt("lineage index vault mismatch")
                if (input.readString(64) != sha256Hex(scope.roomId)) corrupt("lineage index room mismatch")
                val generation = input.readLong().also { if (it < 0) corrupt("negative lineage generation") }
                val current = if (input.readBoolean()) input.readReference() else null
                val debtCount = input.readInt().also { if (it !in 0..MAX_RETIREMENTS) corrupt("invalid retirement count") }
                val retirements = List(debtCount) { input.readReference() }
                val stagingCount = input.readInt().also { if (it !in 0..MAX_RETIREMENTS) corrupt("invalid staging debt count") }
                val staging = List(stagingCount) { input.readIdentity() }
                if (input.read() != -1) corrupt("trailing lineage index bytes")
                if ((generation == 0L) != (current == null)) corrupt("lineage pointer generation is inconsistent")
                current?.let { requireReference(scope, generation, it) }
                if (retirements.distinctBy { it.keyEnvelopeId }.size != retirements.size) {
                    corrupt("lineage retirement journal contains duplicates")
                }
                if (current != null && retirements.any { it.keyEnvelopeId == current.keyEnvelopeId }) {
                    corrupt("lineage retirement journal targets the current snapshot")
                }
                retirements.forEach { reference ->
                    if (
                        reference.vaultId != scope.vaultId ||
                        reference.purpose != SecureObjectPurpose.MESSAGE_REFERENCE_INDEX ||
                        !reference.objectId.value.startsWith("content-lineage-v1:")
                    ) corrupt("lineage retirement is outside its vault or object domain")
                }
                if (staging.distinct().size != staging.size) corrupt("lineage staging journal contains duplicates")
                staging.forEach { identity ->
                    if (identity.vaultId != scope.vaultId || identity.purpose != SecureObjectPurpose.MESSAGE_REFERENCE_INDEX ||
                        !identity.objectId.value.startsWith("content-lineage-v1:")) corrupt("invalid lineage staging identity")
                }
                LineageIndex(generation, current, retirements, staging)
            } finally {
                encoded.fill(0)
            }
        }

    private fun DataOutputStream.writeNode(node: ContentVersionNode) {
        writeString(node.contentId.value)
        writeString(node.rootContentId.value)
        writeBoolean(node.parentContentId != null)
        node.parentContentId?.let { writeString(it.value) }
        writeString(node.operationId)
        writeString(node.eventIdSha256)
        writeLong(node.createdAtEpochMillis)
        writeLong(node.expiresAtEpochMillis)
    }

    private fun DataInputStream.readNode(): ContentVersionNode = ContentVersionNode(
        contentId = ContentId(readString(MAX_ID_BYTES)),
        rootContentId = ContentId(readString(MAX_ID_BYTES)),
        parentContentId = if (readBoolean()) ContentId(readString(MAX_ID_BYTES)) else null,
        operationId = readString(MAX_ID_BYTES),
        eventIdSha256 = readString(64),
        createdAtEpochMillis = readLong(),
        expiresAtEpochMillis = readLong(),
    )

    private fun DataOutputStream.writeReference(reference: SecureObjectReference) {
        writeString(reference.objectId.value)
        writeString(reference.vaultId.value)
        writeInt(reference.purpose.formatCode)
        writeString(reference.keyEnvelopeId.value)
        writeInt(reference.formatVersion)
        writeLong(reference.plaintextLength)
        writeLong(reference.ciphertextLength)
        writeString(reference.associatedDataSha256)
    }

    private fun DataOutputStream.writeIdentity(identity: SecureObjectIdentity) {
        writeString(identity.objectId.value)
        writeString(identity.vaultId.value)
        writeInt(identity.purpose.formatCode)
    }

    private fun DataInputStream.readIdentity(): SecureObjectIdentity = SecureObjectIdentity(
        objectId = SecureObjectId(readString(MAX_ID_BYTES)),
        vaultId = AccountVaultId(readString(MAX_ID_BYTES)),
        purpose = SecureObjectPurpose.fromFormatCode(readInt()) ?: corrupt("unknown staging object purpose"),
    )

    private fun DataInputStream.readReference(): SecureObjectReference = SecureObjectReference(
        objectId = SecureObjectId(readString(MAX_ID_BYTES)),
        vaultId = AccountVaultId(readString(MAX_ID_BYTES)),
        purpose = SecureObjectPurpose.fromFormatCode(readInt()) ?: corrupt("unknown secure object purpose"),
        keyEnvelopeId = KeyEnvelopeId(readString(MAX_ID_BYTES)),
        formatVersion = readInt(),
        plaintextLength = readLong(),
        ciphertextLength = readLong(),
        associatedDataSha256 = readString(64),
    )

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.encodeToByteArray()
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString(maxBytes: Int): String {
        val size = readInt().also { if (it !in 0..maxBytes) corrupt("invalid lineage string size") }
        return readExact(size).decodeToString()
    }

    private fun DataInputStream.readExact(size: Int): ByteArray = ByteArray(size).also(::readFully)

    private fun binding(scope: ContentVersionLineageScope, generation: Long): ByteArray =
        "content-lineage-v1\u0000${scope.vaultId.value}\u0000${sha256Hex(scope.roomId)}\u0000$generation".encodeToByteArray()

    private fun sha256Hex(value: String): String = sha256Hex(value.encodeToByteArray())

    private fun sha256Hex(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun conflict(message: String): Nothing = throw ContentVersionLineageConflictException(message)

    private fun corrupt(message: String): Nothing = throw ContentVersionLineageCorruptException(message)

    private fun ContentVersionGraph.retirementState(): ContentVersionRetirementState =
        if (pendingRetirementCount == 0) ContentVersionRetirementState.CLEAN else ContentVersionRetirementState.PENDING

    private data class LineageIndex(
        val generation: Long,
        val current: SecureObjectReference?,
        val retirements: List<SecureObjectReference>,
        val staging: List<SecureObjectIdentity>,
    )

    private companion object {
        const val INDEX_DIRECTORY = "content-version-lineage-v1"
        const val INDEX_SUFFIX = ".yzli"
        const val MAX_ID_BYTES = 1024
        const val MAX_NODES = 4096
        const val MAX_RETIREMENTS = 4096
        const val MAX_INDEX_BYTES = 2L * 1024 * 1024
        val HEX_SHA256 = Regex("[0-9a-f]{64}")
        val GRAPH_MAGIC = "YZLGP001".encodeToByteArray()
        val INDEX_MAGIC = "YZLIX001".encodeToByteArray()
        val processMutexes = ConcurrentHashMap<String, Mutex>()
    }
}
