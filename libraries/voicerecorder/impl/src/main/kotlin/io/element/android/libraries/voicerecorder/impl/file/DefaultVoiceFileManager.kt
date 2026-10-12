/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.voicerecorder.impl.file

import dev.zacsweers.metro.ContributesBinding
import io.element.android.libraries.core.hash.md5
import io.element.android.libraries.di.BaseDirectory
import io.element.android.libraries.di.CacheDirectory
import io.element.android.libraries.di.RoomScope
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.room.BaseRoom
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@ContributesBinding(RoomScope::class)
class DefaultVoiceFileManager(
    @CacheDirectory private val cacheDir: File,
    private val config: VoiceFileConfig,
    room: BaseRoom,
    @BaseDirectory private val sessionsBaseDirectory: File,
) : VoiceFileManager {
    private val roomId: RoomId = room.roomId

    init {
        recoverAbandonedFiles()
    }

    override fun recoverAbandonedFiles() {
        val directory = roomDirectory()
        directory.listFiles()
            ?.filter(File::isFile)
            ?.filter { it.extension == config.fileExt }
            ?.forEach { file ->
                val target = file.canonicalFile
                val ownership = acquireOwnership(target) ?: return@forEach
                try {
                    markCaptureCleanup(target, "VOICE_CAPTURE_CANCELLED_AND_CLEANED")
                    deleteOwnedFile(target)
                } finally {
                    ownership.close()
                    ownership.ownerFile.delete()
                }
            }
        listOf(directory, captureIntentDirectory()).flatMap { it.listFiles().orEmpty().toList() }
            .filter { it.isFile && it.name.endsWith(OWNER_FILE_SUFFIX) }
            ?.filter { ownerFile ->
                val scratchName = ownerFile.name.removeSuffix(OWNER_FILE_SUFFIX)
                !File(roomDirectory(), scratchName).exists()
            }
            ?.forEach { ownerFile ->
                val ownership = acquireOwnerFile(ownerFile.canonicalFile) ?: return@forEach
                ownership.close()
                ownerFile.delete()
            }
    }

    override fun createFile(): File = createScratchFile("${UUID.randomUUID()}.${config.fileExt}")

    override fun createFile(operationId: String): File {
        require(CAPTURE_ID.matches(operationId)) { "Invalid voice capture operation identity." }
        val target = File(roomDirectory(), "$operationId.${config.fileExt}").canonicalFile
        if (ACTIVE_SCRATCH_OWNERS.containsKey(target.path)) {
            check(target.isFile) { "Reserved voice capture scratch is missing." }
            return target
        }
        return createScratchFile(target.name, operationId)
    }

    override fun discardPreparedCapture(operationId: String) {
        require(CAPTURE_ID.matches(operationId))
        deleteFile(File(roomDirectory(), "$operationId.${config.fileExt}"))
    }

    override fun pendingCaptureCleanups(): Set<String> = captureIntentDirectory().listFiles().orEmpty()
        .filter { it.isFile && it.name.endsWith(INTENT_SUFFIX) }
        .map { it.name.removeSuffix(INTENT_SUFFIX) }
        .filter { operationId ->
            CAPTURE_ID.matches(operationId) &&
                !File(roomDirectory(), "$operationId.${config.fileExt}").exists() &&
                !ownerFileFor(File(roomDirectory(), "$operationId.${config.fileExt}")).exists() &&
                !ACTIVE_SCRATCH_OWNERS.containsKey(File(roomDirectory(), "$operationId.${config.fileExt}").canonicalPath)
        }.toSet()

    override fun captureCleanupResult(operationId: String): String {
        check(operationId in pendingCaptureCleanups())
        return File(captureIntentDirectory(), operationId + INTENT_SUFFIX).readText().also {
            check(it in CLEANUP_RESULTS) { "Voice capture cleanup outcome is unavailable." }
        }
    }

    override fun markCaptureCleanup(file: File, resultCode: String) {
        require(resultCode in CLEANUP_RESULTS)
        val operationId = file.name.removeSuffix(".${config.fileExt}")
        if (!CAPTURE_ID.matches(operationId)) return // Legacy scratch has no capture operation to acknowledge.
        check(file.canonicalFile.parentFile == roomDirectory().canonicalFile)
        val marker = File(captureIntentDirectory(), operationId + INTENT_SUFFIX)
        if (!marker.exists()) {
            val previous = File(roomDirectory(), operationId + INTENT_SUFFIX)
            check(previous.isFile) { "Voice capture intent is missing." }
            check(marker.parentFile.isDirectory || marker.parentFile.mkdirs())
            RandomAccessFile(marker, "rw").use { intent ->
                intent.writeBytes(previous.readText().also { check(it in CLEANUP_RESULTS) })
                intent.fd.sync()
            }
            marker.restrictToOwner()
            check(previous.delete()) { "Unable to migrate old voice capture intent." }
        }
        RandomAccessFile(marker, "rw").use { intent ->
            intent.setLength(0)
            intent.writeBytes(resultCode)
            intent.fd.sync()
        }
    }

    override fun acknowledgeCaptureCleanup(operationId: String) {
        check(operationId in pendingCaptureCleanups()) { "Voice capture cleanup is not established." }
        val marker = File(captureIntentDirectory(), operationId + INTENT_SUFFIX)
        check(!marker.exists() || marker.delete()) { "Unable to acknowledge voice capture cleanup." }
    }

    private fun createScratchFile(fileName: String, operationId: String? = null): File {
        val roomDir = roomDirectory().apply {
            check(isDirectory || mkdirs()) { "Unable to create controlled voice scratch directory." }
        }
        val scratchFile = File(roomDir, fileName).canonicalFile
        val ownership = checkNotNull(acquireOwnership(scratchFile)) {
            "Unable to reserve controlled voice scratch ownership."
        }
        try {
            if (operationId != null) {
                // This contains an operation identity only, never audio, credentials or keys.
                val marker = File(captureIntentDirectory(), operationId + INTENT_SUFFIX)
                RandomAccessFile(marker, "rw").use { intent ->
                    intent.setLength(0)
                    intent.writeBytes("VOICE_CAPTURE_CANCELLED_AND_CLEANED")
                    intent.fd.sync()
                }
                marker.restrictToOwner()
            }
            check(scratchFile.createNewFile()) { "Unable to reserve controlled voice scratch file." }
            scratchFile.restrictToOwner()
            check(ACTIVE_SCRATCH_OWNERS.putIfAbsent(scratchFile.path, ownership) == null) {
                "Voice scratch ownership already exists."
            }
            return scratchFile
        } catch (failure: Exception) {
            scratchFile.delete()
            ownership.close()
            ownership.ownerFile.delete()
            throw failure
        }
    }

    override fun deleteFile(file: File) {
        val managedRoot = roomDirectory().canonicalFile
        val target = file.canonicalFile
        check(target.parentFile == managedRoot) { "Refusing to delete a voice file outside the managed scratch directory." }
        val ownership = ACTIVE_SCRATCH_OWNERS.remove(target.path) ?: checkNotNull(acquireOwnership(target)) {
            "Refusing to delete a voice scratch file owned by another process."
        }
        try {
            deleteOwnedFile(target)
        } finally {
            ownership.close()
            ownership.ownerFile.delete()
        }
    }

    private fun deleteOwnedFile(target: File) {
        if (target.isFile && target.length() > 0L) {
            runCatching {
                RandomAccessFile(target, "rw").use { output ->
                    val zeros = ByteArray(ERASE_BUFFER_BYTES)
                    var remaining = output.length()
                    output.seek(0)
                    while (remaining > 0L) {
                        val count = minOf(remaining, zeros.size.toLong()).toInt()
                        output.write(zeros, 0, count)
                        remaining -= count
                    }
                    output.fd.sync()
                }
            }
        }
        check(!target.exists() || target.delete()) { "Unable to delete controlled voice scratch file." }
    }

    private fun acquireOwnership(scratchFile: File): ScratchOwnership? = acquireOwnerFile(ownerFileFor(scratchFile))

    private fun acquireOwnerFile(ownerFile: File): ScratchOwnership? {
        check(ownerFile.parentFile.isDirectory || ownerFile.parentFile.mkdirs()) { "Unable to create controlled voice ownership directory." }
        val created = ownerFile.createNewFile()
        if (created) ownerFile.restrictToOwner()
        val handle = RandomAccessFile(ownerFile, "rw")
        val lock = try {
            handle.channel.tryLock()
        } catch (_: OverlappingFileLockException) {
            null
        } catch (failure: Exception) {
            handle.close()
            throw failure
        }
        if (lock == null) {
            handle.close()
            return null
        }
        return ScratchOwnership(ownerFile, handle, lock)
    }

    private fun ownerFileFor(scratchFile: File): File {
        val operationId = scratchFile.name.removeSuffix(".${config.fileExt}")
        val directory = if (CAPTURE_ID.matches(operationId)) captureIntentDirectory() else scratchFile.parentFile
        return File(directory, scratchFile.name + OWNER_FILE_SUFFIX).canonicalFile
    }

    // Keep non-sensitive operation identities and OS locks outside the plaintext cache wipe boundary.
    private fun captureIntentDirectory(): File = File(
        File(checkNotNull(sessionsBaseDirectory.parentFile), "voice_capture_intents"), roomId.value.md5(),
    )

    private fun File.restrictToOwner() {
        setReadable(false, false)
        setWritable(false, false)
        setReadable(true, true)
        setWritable(true, true)
    }

    private fun roomDirectory(): File = File(File(cacheDir, config.cacheSubdir), roomId.value.md5())

    private class ScratchOwnership(
        val ownerFile: File,
        private val handle: RandomAccessFile,
        private val lock: FileLock,
    ) {
        fun close() {
            runCatching { lock.release() }
            runCatching { handle.close() }
        }
    }

    private companion object {
        const val ERASE_BUFFER_BYTES = 8 * 1024
        const val OWNER_FILE_SUFFIX = ".lock"
        const val INTENT_SUFFIX = ".capture-intent"
        val CLEANUP_RESULTS = setOf(
            "VOICE_CAPTURE_CANCELLED_AND_CLEANED", "VOICE_CAPTURE_RELEASED_AND_CLEANED", "VOICE_CAPTURE_FAILED_AND_CLEANED",
        )
        val CAPTURE_ID = Regex("lc17-capture-[0-9a-f]{16}-[0-9a-f]{16}-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        val ACTIVE_SCRATCH_OWNERS = ConcurrentHashMap<String, ScratchOwnership>()
    }
}
