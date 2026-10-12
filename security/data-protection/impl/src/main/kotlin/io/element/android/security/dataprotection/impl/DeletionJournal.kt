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
import io.element.android.security.dataprotection.api.DeletionOperationId
import io.element.android.security.dataprotection.api.DeletionReason
import io.element.android.security.dataprotection.api.DeletionRequest
import io.element.android.security.dataprotection.api.DeletionScope
import io.element.android.security.dataprotection.api.DeletionStep
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

data class DeletionJournalEntry(
    val request: DeletionRequest,
    val completedSteps: Set<DeletionStep>,
    val attemptCount: Int,
    val lastErrorCode: String?,
    val revision: Int,
)

data class DeletionJournalBeginResult(
    val entry: DeletionJournalEntry,
    val alreadyComplete: Boolean,
)

data class PendingDeletionEntries(
    val entries: List<DeletionJournalEntry>,
    val unreadableEntries: Int,
)

interface DeletionJournal {
    fun begin(request: DeletionRequest): DeletionJournalBeginResult
    fun update(entry: DeletionJournalEntry): DeletionJournalEntry
    fun complete(entry: DeletionJournalEntry): DeletionJournalEntry
    fun loadPending(): PendingDeletionEntries
}

class DeletionJournalException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Append-only deletion journal stored below noBackupFilesDir.
 *
 * Progress is never overwritten in place. Every revision is fsynced to a new file and then renamed into view, so a
 * killed process leaves either the previous valid revision or the next valid revision. Records contain only opaque
 * identifiers and deletion metadata; user content, access tokens and encryption keys are forbidden.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class FileDeletionJournal(
    private val secureStorageRoots: SecureStorageRoots,
) : DeletionJournal {
    override fun begin(request: DeletionRequest): DeletionJournalBeginResult {
        validateRequest(request)
        val operationHash = operationHash(request.operationId)
        readAudit(operationHash)?.let { completed ->
            requireSameStableRequest(completed.request, request)
            return DeletionJournalBeginResult(completed, alreadyComplete = true)
        }

        latestValidPending(operationHash)?.let { pending ->
            requireSameStableRequest(pending.request, request)
            val confirmedPreconditions = pending.request.confirmedPreconditions + request.confirmedPreconditions
            if (confirmedPreconditions == pending.request.confirmedPreconditions) {
                return DeletionJournalBeginResult(pending, alreadyComplete = false)
            }
            val updated = writePending(
                pending.copy(
                    request = pending.request.copy(confirmedPreconditions = confirmedPreconditions),
                    completedSteps = pending.completedSteps + confirmedPreconditions,
                )
            )
            return DeletionJournalBeginResult(updated, alreadyComplete = false)
        }

        val initial = DeletionJournalEntry(
            request = request,
            completedSteps = setOf(DeletionStep.REQUESTED) + request.confirmedPreconditions,
            attemptCount = 0,
            lastErrorCode = null,
            revision = 0,
        )
        return DeletionJournalBeginResult(writePending(initial), alreadyComplete = false)
    }

    override fun update(entry: DeletionJournalEntry): DeletionJournalEntry {
        validateEntry(entry)
        check(DeletionStep.COMPLETE !in entry.completedSteps) { "Completed deletion records are immutable." }
        return writePending(entry)
    }

    override fun complete(entry: DeletionJournalEntry): DeletionJournalEntry {
        validateEntry(entry)
        val completed = entry.copy(
            completedSteps = entry.completedSteps + DeletionStep.WROTE_NON_CONTENT_AUDIT + DeletionStep.COMPLETE,
            lastErrorCode = null,
        )
        val operationHash = operationHash(entry.request.operationId)
        val existingAudit = readAudit(operationHash)
        if (existingAudit != null) {
            requireSameStableRequest(existingAudit.request, completed.request)
            deletePending(operationHash)
            return existingAudit
        }

        val target = File(journalDirectory(), "$AUDIT_PREFIX$operationHash$RECORD_SUFFIX")
        writeNewFile(target, encode(completed))
        deletePending(operationHash)
        pruneCompletedAudits()
        return completed
    }

    override fun loadPending(): PendingDeletionEntries {
        val directory = journalDirectory()
        directory.listFiles().orEmpty()
            .filter { it.name.startsWith(PARTIAL_PREFIX) }
            .forEach(File::delete)

        val completedAudits = mutableMapOf<String, DeletionJournalEntry>()
        var unreadableEntries = 0
        directory.listFiles().orEmpty()
            .filter { AUDIT_FILE_PATTERN.matches(it.name) }
            .forEach { file ->
                val operationHash = auditOperationHash(file)
                runCatchingExceptions { decode(file, operationHash) }
                    .onSuccess { completedAudits[operationHash] = it }
                    .onFailure { unreadableEntries += 1 }
            }

        val entries = mutableListOf<DeletionJournalEntry>()
        pendingFiles()
            .groupBy(::pendingOperationHash)
            .forEach { (operationHash, files) ->
                val completed = completedAudits[operationHash]
                if (completed != null) {
                    files.forEach(File::delete)
                    return@forEach
                }

                var selected: DeletionJournalEntry? = null
                files.sortedByDescending(::pendingRevision).forEach { file ->
                    if (selected != null) return@forEach
                    runCatchingExceptions { decode(file, operationHash) }
                        .onSuccess { entry -> selected = entry }
                        .onFailure { unreadableEntries += 1 }
                }
                selected?.let(entries::add)
            }
        return PendingDeletionEntries(
            entries = entries.sortedBy { it.request.requestedAtEpochMillis },
            unreadableEntries = unreadableEntries,
        )
    }

    private fun writePending(entry: DeletionJournalEntry): DeletionJournalEntry {
        val operationHash = operationHash(entry.request.operationId)
        val nextRevision = pendingFiles(operationHash).maxOfOrNull(::pendingRevision)?.plus(1) ?: 1
        val updated = entry.copy(revision = nextRevision)
        val target = File(
            journalDirectory(),
            "$PENDING_PREFIX$operationHash-${nextRevision.toString().padStart(REVISION_WIDTH, '0')}$RECORD_SUFFIX",
        )
        writeNewFile(target, encode(updated))
        return updated
    }

    private fun latestValidPending(operationHash: String): DeletionJournalEntry? = pendingFiles(operationHash)
        .sortedByDescending(::pendingRevision)
        .firstNotNullOfOrNull { file -> runCatchingExceptions { decode(file, operationHash) }.getOrNull() }

    private fun readAudit(operationHash: String): DeletionJournalEntry? {
        val file = File(journalDirectory(), "$AUDIT_PREFIX$operationHash$RECORD_SUFFIX")
        return file.takeIf(File::isFile)?.let { decode(it, operationHash) }
    }

    private fun encode(entry: DeletionJournalEntry): ByteArray {
        val payload = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(JOURNAL_MAGIC)
                output.writeInt(JOURNAL_FORMAT_VERSION)
                output.writeUTF(entry.request.operationId.value)
                when (val scope = entry.request.scope) {
                    is DeletionScope.Account -> {
                        output.writeUTF(SCOPE_ACCOUNT)
                        output.writeUTF(scope.accountIdHash)
                        output.writeUTF(scope.vaultId)
                        output.writeUTF(scope.localSessionId)
                        output.writeUTF(scope.sessionFilePath)
                        output.writeUTF(scope.sessionCachePath)
                    }
                    is DeletionScope.Content -> {
                        output.writeUTF(SCOPE_CONTENT)
                        output.writeUTF(scope.accountIdHash)
                        output.writeUTF(scope.vaultId)
                        output.writeUTF(scope.contentId)
                    }
                    is DeletionScope.Asset -> {
                        output.writeUTF(SCOPE_ASSET)
                        output.writeUTF(scope.accountIdHash)
                        output.writeUTF(scope.vaultId)
                        output.writeUTF(scope.assetId)
                        output.writeUTF(scope.contentId)
                    }
                }
                output.writeUTF(entry.request.reason.name)
                output.writeLong(entry.request.policyVersion)
                output.writeLong(entry.request.requestedAtEpochMillis)
                output.writeInt(entry.completedSteps.size)
                entry.completedSteps.sortedBy(DeletionStep::ordinal).forEach { output.writeUTF(it.name) }
                output.writeInt(entry.attemptCount)
                output.writeBoolean(entry.lastErrorCode != null)
                entry.lastErrorCode?.let(output::writeUTF)
                output.writeInt(entry.revision)
            }
            bytes.toByteArray()
        }
        if (payload.size > MAX_PAYLOAD_BYTES) throw DeletionJournalException("Deletion journal record is too large.")
        return payload + sha256(payload)
    }

    private fun decode(file: File, expectedOperationHash: String): DeletionJournalEntry {
        if (file.length() !in CHECKSUM_BYTES + 1L..MAX_RECORD_BYTES) {
            throw DeletionJournalException("Deletion journal record has an invalid length.")
        }
        val record = file.readBytes()
        val payload = record.copyOfRange(0, record.size - CHECKSUM_BYTES)
        val checksum = record.copyOfRange(record.size - CHECKSUM_BYTES, record.size)
        if (!MessageDigest.isEqual(checksum, sha256(payload))) {
            throw DeletionJournalException("Deletion journal checksum verification failed.")
        }

        val entry = DataInputStream(ByteArrayInputStream(payload)).use { input ->
            val magic = ByteArray(JOURNAL_MAGIC.size).also(input::readFully)
            if (!magic.contentEquals(JOURNAL_MAGIC)) throw DeletionJournalException("Deletion journal magic is invalid.")
            val formatVersion = input.readInt()
            if (formatVersion !in MIN_SUPPORTED_JOURNAL_FORMAT_VERSION..JOURNAL_FORMAT_VERSION) {
                throw DeletionJournalException("Deletion journal version is unsupported.")
            }
            val operationId = DeletionOperationId(input.readUTF())
            val scope = if (formatVersion == 1) {
                DeletionScope.Account(
                    accountIdHash = input.readUTF(),
                    vaultId = input.readUTF(),
                    localSessionId = input.readUTF(),
                    sessionFilePath = input.readUTF(),
                    sessionCachePath = input.readUTF(),
                )
            } else {
                when (input.readUTF()) {
                    SCOPE_ACCOUNT -> DeletionScope.Account(
                        accountIdHash = input.readUTF(),
                        vaultId = input.readUTF(),
                        localSessionId = input.readUTF(),
                        sessionFilePath = input.readUTF(),
                        sessionCachePath = input.readUTF(),
                    )
                    SCOPE_CONTENT -> DeletionScope.Content(
                        accountIdHash = input.readUTF(),
                        vaultId = input.readUTF(),
                        contentId = input.readUTF(),
                    )
                    SCOPE_ASSET -> DeletionScope.Asset(
                        accountIdHash = input.readUTF(),
                        vaultId = input.readUTF(),
                        assetId = input.readUTF(),
                        contentId = input.readUTF(),
                    )
                    else -> throw DeletionJournalException("Deletion scope is invalid.")
                }
            }
            val reason = enumValueOrNull<DeletionReason>(input.readUTF())
                ?: throw DeletionJournalException("Deletion reason is invalid.")
            val policyVersion = input.readLong()
            val requestedAtEpochMillis = input.readLong()
            val stepCount = input.readInt()
            if (stepCount !in 1..DeletionStep.entries.size) throw DeletionJournalException("Deletion step count is invalid.")
            val steps = buildSet {
                repeat(stepCount) {
                    val step = enumValueOrNull<DeletionStep>(input.readUTF())
                        ?: throw DeletionJournalException("Deletion step is invalid.")
                    add(step)
                }
            }
            val attemptCount = input.readInt()
            val errorCode = if (input.readBoolean()) input.readUTF() else null
            val revision = input.readInt()
            if (input.available() != 0) throw DeletionJournalException("Deletion journal has trailing data.")
            DeletionJournalEntry(
                request = DeletionRequest(
                    operationId = operationId,
                    scope = scope,
                    reason = reason,
                    policyVersion = policyVersion,
                    requestedAtEpochMillis = requestedAtEpochMillis,
                    confirmedPreconditions = steps.intersect(ALLOWED_CONFIRMED_PRECONDITIONS),
                ),
                completedSteps = steps,
                attemptCount = attemptCount,
                lastErrorCode = errorCode,
                revision = revision,
            )
        }
        validateEntry(entry)
        if (operationHash(entry.request.operationId) != expectedOperationHash) {
            throw DeletionJournalException("Deletion journal operation hash does not match its filename.")
        }
        return entry
    }

    private fun writeNewFile(target: File, bytes: ByteArray) {
        val directory = journalDirectory()
        if (target.exists()) throw DeletionJournalException("Deletion journal target already exists.")
        val partial = File(directory, "$PARTIAL_PREFIX${UUID.randomUUID()}")
        try {
            FileOutputStream(partial).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            if (!partial.renameTo(target)) throw DeletionJournalException("Unable to publish deletion journal record.")
        } catch (failure: IOException) {
            throw DeletionJournalException("Unable to persist deletion journal record.", failure)
        } finally {
            partial.delete()
        }
    }

    private fun journalDirectory(): File {
        val directory = File(secureStorageRoots.persistentRoot(), JOURNAL_DIRECTORY)
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw DeletionJournalException("Unable to create deletion journal directory.")
        }
        return directory
    }

    private fun pendingFiles(operationHash: String? = null): List<File> = journalDirectory().listFiles().orEmpty()
        .filter { file ->
            val match = PENDING_FILE_PATTERN.matchEntire(file.name) ?: return@filter false
            operationHash == null || match.groupValues[1] == operationHash
        }

    private fun deletePending(operationHash: String) {
        pendingFiles(operationHash).forEach(File::delete)
    }

    private fun pruneCompletedAudits() {
        journalDirectory().listFiles().orEmpty()
            .filter { AUDIT_FILE_PATTERN.matches(it.name) }
            .sortedByDescending(File::lastModified)
            .drop(MAX_COMPLETED_AUDITS)
            .forEach(File::delete)
    }

    private fun pendingOperationHash(file: File): String = requireNotNull(PENDING_FILE_PATTERN.matchEntire(file.name)).groupValues[1]

    private fun pendingRevision(file: File): Int = requireNotNull(PENDING_FILE_PATTERN.matchEntire(file.name)).groupValues[2].toInt()

    private fun auditOperationHash(file: File): String = requireNotNull(AUDIT_FILE_PATTERN.matchEntire(file.name)).groupValues[1]

    private fun operationHash(operationId: DeletionOperationId): String = sha256(operationId.value.encodeToByteArray()).toHex()

    private fun validateEntry(entry: DeletionJournalEntry) {
        validateRequest(entry.request)
        if (DeletionStep.REQUESTED !in entry.completedSteps) throw DeletionJournalException("Deletion request step is missing.")
        if (entry.attemptCount !in 0..MAX_ATTEMPT_COUNT) throw DeletionJournalException("Deletion attempt count is invalid.")
        if (entry.revision !in 0..MAX_REVISION) throw DeletionJournalException("Deletion journal revision is invalid.")
        if (entry.lastErrorCode != null && !ERROR_CODE_PATTERN.matches(entry.lastErrorCode)) {
            throw DeletionJournalException("Deletion error code is invalid.")
        }
    }

    private fun validateRequest(request: DeletionRequest) {
        if (request.operationId.value.length !in 1..MAX_OPERATION_ID_LENGTH) {
            throw DeletionJournalException("Deletion operation identifier is invalid.")
        }
        when (val scope = request.scope) {
            is DeletionScope.Account -> {
                validateAccountAndVault(scope.accountIdHash, scope.vaultId)
                if (scope.localSessionId.length !in 1..MAX_SESSION_ID_LENGTH) {
                    throw DeletionJournalException("Local session identifier is invalid.")
                }
                if (scope.sessionFilePath.length !in 1..MAX_PATH_LENGTH) throw DeletionJournalException("Session file path is invalid.")
                if (scope.sessionCachePath.length !in 1..MAX_PATH_LENGTH) throw DeletionJournalException("Session cache path is invalid.")
            }
            is DeletionScope.Content -> {
                validateAccountAndVault(scope.accountIdHash, scope.vaultId)
                validateOpaqueIdentifier(scope.contentId, "Content")
            }
            is DeletionScope.Asset -> {
                validateAccountAndVault(scope.accountIdHash, scope.vaultId)
                validateOpaqueIdentifier(scope.assetId, "Asset")
                validateOpaqueIdentifier(scope.contentId, "Content")
            }
        }
        if (request.policyVersion < 0 || request.requestedAtEpochMillis < 0) throw DeletionJournalException("Deletion metadata is invalid.")
        if (!ALLOWED_CONFIRMED_PRECONDITIONS.containsAll(request.confirmedPreconditions)) {
            throw DeletionJournalException("Deletion request contains coordinator-owned preconditions.")
        }
    }

    private fun validateAccountAndVault(accountIdHash: String, vaultId: String) {
        if (!SHA256_PATTERN.matches(accountIdHash)) throw DeletionJournalException("Account hash is invalid.")
        if (vaultId.length !in 1..MAX_VAULT_ID_LENGTH) throw DeletionJournalException("Vault identifier is invalid.")
    }

    private fun validateOpaqueIdentifier(value: String, label: String) {
        if (value.length !in 1..MAX_SCOPED_IDENTIFIER_LENGTH || value.any(Char::isISOControl)) {
            throw DeletionJournalException("$label identifier is invalid.")
        }
    }

    private fun requireSameStableRequest(existing: DeletionRequest, requested: DeletionRequest) {
        if (existing.copy(confirmedPreconditions = emptySet()) != requested.copy(confirmedPreconditions = emptySet())) {
            throw DeletionJournalException("Deletion operation identifier is already used by another request.")
        }
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }

    private inline fun <reified T : Enum<T>> enumValueOrNull(value: String): T? = enumValues<T>().firstOrNull { it.name == value }

    private companion object {
        const val JOURNAL_DIRECTORY = "deletion-journal"
        const val MIN_SUPPORTED_JOURNAL_FORMAT_VERSION = 1
        const val JOURNAL_FORMAT_VERSION = 2
        const val SCOPE_ACCOUNT = "ACCOUNT"
        const val SCOPE_CONTENT = "CONTENT"
        const val SCOPE_ASSET = "ASSET"
        const val PENDING_PREFIX = "pending-"
        const val AUDIT_PREFIX = "audit-"
        const val PARTIAL_PREFIX = ".partial-"
        const val RECORD_SUFFIX = ".yzdj"
        const val REVISION_WIDTH = 8
        const val CHECKSUM_BYTES = 32
        const val MAX_PAYLOAD_BYTES = 16 * 1024
        const val MAX_RECORD_BYTES = MAX_PAYLOAD_BYTES + CHECKSUM_BYTES.toLong()
        const val MAX_OPERATION_ID_LENGTH = 128
        const val MAX_VAULT_ID_LENGTH = 1024
        const val MAX_SESSION_ID_LENGTH = 1024
        const val MAX_PATH_LENGTH = 4096
        const val MAX_SCOPED_IDENTIFIER_LENGTH = 1024
        const val MAX_ATTEMPT_COUNT = 1_000_000
        const val MAX_REVISION = 99_999_999
        const val MAX_COMPLETED_AUDITS = 128
        val JOURNAL_MAGIC = "YZDJ0001".encodeToByteArray()
        val SHA256_PATTERN = Regex("[0-9a-f]{64}")
        val ERROR_CODE_PATTERN = Regex("[A-Z0-9_]{1,128}")
        val PENDING_FILE_PATTERN = Regex("pending-([0-9a-f]{64})-([0-9]{8})\\.yzdj")
        val AUDIT_FILE_PATTERN = Regex("audit-([0-9a-f]{64})\\.yzdj")
        val ALLOWED_CONFIRMED_PRECONDITIONS = setOf(
            DeletionStep.STOPPED_SYNC_AND_UI,
            DeletionStep.PURGED_MEMORY,
            DeletionStep.CLOSED_DATABASES,
        )
    }
}
