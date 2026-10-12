/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.contentprotection.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
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
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@SingleIn(AppScope::class)
@Inject
class DurableMediaSendStateStore(
    private val roots: SecureStorageRoots,
) {
    private var directorySync: (File) -> Unit = ::forceDirectory

    internal constructor(
        roots: SecureStorageRoots,
        directorySync: (File) -> Unit,
    ) : this(roots) {
        this.directorySync = directorySync
    }

    internal data class StoredState(
        val revision: Long,
        val digestSha256: String,
        val payload: ByteArray,
    )

    internal suspend fun read(namespace: String, key: String): StoredState? =
        withLockedState(namespace, key) { current, _ -> current }

    internal fun keys(namespace: String): List<String> {
        require(namespace.matches(SAFE_NAME))
        val directory = File(roots.persistentRoot(), "media-send-state/$namespace")
        if (!directory.isDirectory) return emptyList()
        return directory.listFiles().orEmpty().mapNotNull { file ->
            file.name.takeIf { it.endsWith(".state") }
                ?.removeSuffix(".state")
                ?.takeIf { it.matches(SAFE_NAME) }
        }.sorted()
    }

    internal suspend fun <T> withLockedState(
        namespace: String,
        key: String,
        action: suspend (StoredState?, (StoredState) -> Unit) -> T,
    ): T = withContext(Dispatchers.IO) {
        require(namespace.matches(SAFE_NAME) && key.matches(SAFE_NAME))
        val directory = File(roots.persistentRoot(), "media-send-state/$namespace").apply {
            check(exists() || mkdirs())
            check(isDirectory)
        }
        val stateFile = File(directory, "$key.state").canonicalFile
        require(stateFile.parentFile == directory.canonicalFile)
        val lockFile = File(directory, "$key.lock").canonicalFile
        processMutexes.computeIfAbsent(lockFile.path) { Mutex() }.withLock {
            RandomAccessFile(lockFile, "rw").use { randomAccess ->
                randomAccess.channel.use { channel ->
                    channel.lock().use {
                        val current = readState(stateFile)
                        var committed = false
                        action(current) { next ->
                            check(!committed)
                            require(next.revision > 0 && next.digestSha256.matches(SHA256_HEX))
                            require(next.payload.size <= MAX_STATE_BYTES)
                            writeState(stateFile, next)
                            committed = true
                        }
                    }
                }
            }
        }
    }

    private fun readState(file: File): StoredState? {
        if (!file.exists()) return null
        val bytes = file.readBytes()
        require(bytes.size <= MAX_STATE_BYTES + 256)
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == MAGIC)
            require(input.readInt() == VERSION)
            val revision = input.readLong().also { require(it > 0) }
            val digest = input.readUTF().also { require(it.matches(SHA256_HEX)) }
            val size = input.readInt().also { require(it in 0..MAX_STATE_BYTES) }
            val payload = ByteArray(size).also(input::readFully)
            require(input.read() == -1)
            require(MessageDigest.isEqual(digest.encodeToByteArray(), sha256Hex(payload).encodeToByteArray()))
            return StoredState(revision, digest, payload)
        }
    }

    private fun writeState(destination: File, state: StoredState) {
        require(state.digestSha256 == sha256Hex(state.payload))
        val bytes = ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(MAGIC)
                data.writeInt(VERSION)
                data.writeLong(state.revision)
                data.writeUTF(state.digestSha256)
                data.writeInt(state.payload.size)
                data.write(state.payload)
            }
            output.toByteArray()
        }
        val temporary = File(destination.parentFile, ".${destination.name}.${UUID.randomUUID()}.partial")
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(bytes)
                stream.flush()
                stream.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(), destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                throw IllegalStateException("Atomic media-send state publication is unavailable")
            }
            directorySync(requireNotNull(destination.parentFile))
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun forceDirectory(directory: File) {
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }

    private companion object {
        const val MAGIC = 0x595A4D53
        const val VERSION = 1
        const val MAX_STATE_BYTES = 2 * 1024 * 1024
        val SAFE_NAME = Regex("^[a-z0-9][a-z0-9._-]{0,95}$")
        val SHA256_HEX = Regex("^[0-9a-f]{64}$")
        val processMutexes = ConcurrentHashMap<String, Mutex>()
    }
}

internal fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }
