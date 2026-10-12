/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.dataprotection.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.security.dataprotection.api.PlaintextMigrationStep
import io.element.android.security.securestorage.api.SecureStorageRoots
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

data class PlaintextMigrationState(
    val migrationVersion: Int,
    val completedSteps: Set<PlaintextMigrationStep>,
    val attemptCount: Int,
    val lastErrorCode: String?,
    val revision: Int,
)

data class LoadedPlaintextMigrationState(
    val state: PlaintextMigrationState?,
    val complete: Boolean,
    val unreadableEntries: Int,
)

interface PlaintextMigrationJournal {
    fun load(): LoadedPlaintextMigrationState
    fun start(migrationVersion: Int): PlaintextMigrationState
    fun update(state: PlaintextMigrationState): PlaintextMigrationState
    fun complete(state: PlaintextMigrationState): PlaintextMigrationState
}

internal class PlaintextMigrationJournalException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Immutable, checksummed progress records for the one-time plaintext cutover. */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class FilePlaintextMigrationJournal(
    private val secureStorageRoots: SecureStorageRoots,
) : PlaintextMigrationJournal {
    override fun load(): LoadedPlaintextMigrationState {
        val directory = journalDirectory()
        directory.listFiles().orEmpty().filter { it.name.startsWith(PARTIAL_PREFIX) }.forEach(File::delete)
        var unreadableEntries = 0

        val completedFile = File(directory, COMPLETED_FILE_NAME)
        if (completedFile.isFile) {
            val completed = runCatchingExceptions { decode(completedFile) }
                .onFailure { unreadableEntries += 1 }
                .getOrNull()
            if (completed != null && PlaintextMigrationStep.COMPLETE in completed.completedSteps) {
                stateFiles().forEach(File::delete)
                return LoadedPlaintextMigrationState(completed, complete = true, unreadableEntries)
            }
        }

        var selected: PlaintextMigrationState? = null
        stateFiles().sortedByDescending(::stateRevision).forEach { file ->
            if (selected != null) return@forEach
            runCatchingExceptions { decode(file) }
                .onSuccess { selected = it }
                .onFailure { unreadableEntries += 1 }
        }
        return LoadedPlaintextMigrationState(selected, complete = false, unreadableEntries)
    }

    override fun start(migrationVersion: Int): PlaintextMigrationState {
        require(migrationVersion > 0) { "Plaintext migration version must be positive." }
        val loaded = load()
        loaded.state?.let { state ->
            require(state.migrationVersion == migrationVersion) { "Plaintext migration version does not match existing progress." }
            return state
        }
        return writeState(
            PlaintextMigrationState(
                migrationVersion = migrationVersion,
                completedSteps = emptySet(),
                attemptCount = 0,
                lastErrorCode = null,
                revision = 0,
            )
        )
    }

    override fun update(state: PlaintextMigrationState): PlaintextMigrationState {
        validate(state)
        check(PlaintextMigrationStep.COMPLETE !in state.completedSteps) { "Completed plaintext migration state is immutable." }
        return writeState(state)
    }

    override fun complete(state: PlaintextMigrationState): PlaintextMigrationState {
        validate(state)
        val completed = state.copy(
            completedSteps = state.completedSteps + PlaintextMigrationStep.COMPLETE,
            lastErrorCode = null,
        )
        val target = File(journalDirectory(), COMPLETED_FILE_NAME)
        if (target.exists()) {
            val existing = runCatchingExceptions { decode(target) }.getOrNull()
            if (existing != null && existing.migrationVersion == completed.migrationVersion) {
                stateFiles().forEach(File::delete)
                return existing
            }
            if (!target.delete()) throw PlaintextMigrationJournalException("Unable to replace invalid plaintext migration completion record.")
        }
        writeNewFile(target, encode(completed))
        stateFiles().forEach(File::delete)
        return completed
    }

    private fun writeState(state: PlaintextMigrationState): PlaintextMigrationState {
        val nextRevision = stateFiles().maxOfOrNull(::stateRevision)?.plus(1) ?: 1
        val updated = state.copy(revision = nextRevision)
        val target = File(
            journalDirectory(),
            "$STATE_PREFIX${nextRevision.toString().padStart(REVISION_WIDTH, '0')}$RECORD_SUFFIX",
        )
        writeNewFile(target, encode(updated))
        return updated
    }

    private fun encode(state: PlaintextMigrationState): ByteArray {
        validate(state)
        val payload = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(JOURNAL_MAGIC)
                output.writeInt(JOURNAL_FORMAT_VERSION)
                output.writeInt(state.migrationVersion)
                output.writeInt(state.completedSteps.size)
                state.completedSteps.sortedBy(PlaintextMigrationStep::ordinal).forEach { output.writeUTF(it.name) }
                output.writeInt(state.attemptCount)
                output.writeBoolean(state.lastErrorCode != null)
                state.lastErrorCode?.let(output::writeUTF)
                output.writeInt(state.revision)
            }
            bytes.toByteArray()
        }
        if (payload.size > MAX_PAYLOAD_BYTES) throw PlaintextMigrationJournalException("Plaintext migration record is too large.")
        return payload + sha256(payload)
    }

    private fun decode(file: File): PlaintextMigrationState {
        if (file.length() !in CHECKSUM_BYTES + 1L..MAX_RECORD_BYTES) {
            throw PlaintextMigrationJournalException("Plaintext migration record has an invalid length.")
        }
        val record = file.readBytes()
        val payload = record.copyOfRange(0, record.size - CHECKSUM_BYTES)
        val checksum = record.copyOfRange(record.size - CHECKSUM_BYTES, record.size)
        if (!MessageDigest.isEqual(checksum, sha256(payload))) {
            throw PlaintextMigrationJournalException("Plaintext migration checksum verification failed.")
        }
        val state = DataInputStream(ByteArrayInputStream(payload)).use { input ->
            val magic = ByteArray(JOURNAL_MAGIC.size).also(input::readFully)
            if (!magic.contentEquals(JOURNAL_MAGIC)) throw PlaintextMigrationJournalException("Plaintext migration magic is invalid.")
            if (input.readInt() != JOURNAL_FORMAT_VERSION) {
                throw PlaintextMigrationJournalException("Plaintext migration journal version is unsupported.")
            }
            val migrationVersion = input.readInt()
            val stepCount = input.readInt()
            if (stepCount !in 0..PlaintextMigrationStep.entries.size) {
                throw PlaintextMigrationJournalException("Plaintext migration step count is invalid.")
            }
            val steps = buildSet {
                repeat(stepCount) {
                    val name = input.readUTF()
                    val step = PlaintextMigrationStep.entries.firstOrNull { it.name == name }
                        ?: throw PlaintextMigrationJournalException("Plaintext migration step is invalid.")
                    add(step)
                }
            }
            val attemptCount = input.readInt()
            val errorCode = if (input.readBoolean()) input.readUTF() else null
            val revision = input.readInt()
            if (input.available() != 0) throw PlaintextMigrationJournalException("Plaintext migration record has trailing data.")
            PlaintextMigrationState(migrationVersion, steps, attemptCount, errorCode, revision)
        }
        validate(state)
        return state
    }

    private fun validate(state: PlaintextMigrationState) {
        if (state.migrationVersion !in 1..MAX_MIGRATION_VERSION) {
            throw PlaintextMigrationJournalException("Plaintext migration version is invalid.")
        }
        if (state.attemptCount !in 0..MAX_ATTEMPT_COUNT || state.revision !in 0..MAX_REVISION) {
            throw PlaintextMigrationJournalException("Plaintext migration counter is invalid.")
        }
        if (state.lastErrorCode != null && !ERROR_CODE_PATTERN.matches(state.lastErrorCode)) {
            throw PlaintextMigrationJournalException("Plaintext migration error code is invalid.")
        }
    }

    private fun writeNewFile(target: File, bytes: ByteArray) {
        val directory = journalDirectory()
        if (target.exists()) throw PlaintextMigrationJournalException("Plaintext migration target already exists.")
        val partial = File(directory, "$PARTIAL_PREFIX${UUID.randomUUID()}")
        try {
            FileOutputStream(partial).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            if (!partial.renameTo(target)) throw PlaintextMigrationJournalException("Unable to publish plaintext migration record.")
        } catch (failure: IOException) {
            throw PlaintextMigrationJournalException("Unable to persist plaintext migration record.", failure)
        } finally {
            partial.delete()
        }
    }

    private fun journalDirectory(): File {
        val directory = File(secureStorageRoots.persistentRoot(), JOURNAL_DIRECTORY)
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw PlaintextMigrationJournalException("Unable to create plaintext migration journal directory.")
        }
        return directory
    }

    private fun stateFiles(): List<File> = journalDirectory().listFiles().orEmpty().filter { STATE_FILE_PATTERN.matches(it.name) }

    private fun stateRevision(file: File): Int = requireNotNull(STATE_FILE_PATTERN.matchEntire(file.name)).groupValues[1].toInt()

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private companion object {
        const val JOURNAL_DIRECTORY = "plaintext-migration"
        const val JOURNAL_FORMAT_VERSION = 1
        const val STATE_PREFIX = "state-"
        const val COMPLETED_FILE_NAME = "complete.yzpm"
        const val PARTIAL_PREFIX = ".partial-"
        const val RECORD_SUFFIX = ".yzpm"
        const val REVISION_WIDTH = 8
        const val CHECKSUM_BYTES = 32
        const val MAX_PAYLOAD_BYTES = 4 * 1024
        const val MAX_RECORD_BYTES = MAX_PAYLOAD_BYTES + CHECKSUM_BYTES.toLong()
        const val MAX_MIGRATION_VERSION = 1_000_000
        const val MAX_ATTEMPT_COUNT = 1_000_000
        const val MAX_REVISION = 99_999_999
        val JOURNAL_MAGIC = "YZPM0001".encodeToByteArray()
        val ERROR_CODE_PATTERN = Regex("[A-Z0-9_]{1,128}")
        val STATE_FILE_PATTERN = Regex("state-([0-9]{8})\\.yzpm")
    }
}
