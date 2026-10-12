/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.dataprotection.impl

import android.content.Context
import android.util.AtomicFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal enum class NextPolicyRequestPhase { ARTIFACT, CHALLENGE, RECEIPT }

/** Transport memo only. Every returned body must be independently revalidated before sending.
 * Neither this local journal nor a consumed HTTP memo authorizes policy or clears cleanup debt.
 */
internal class NextPolicyRequestJournal(private val file: NextPolicyJournalFile) {
    constructor(context: Context) : this(AndroidNextPolicyJournalFile(context))

    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true; explicitNulls = true }

    /** Invoke only after verifying current authority. Persist before the first network attempt.
     * The validator also runs on the ORIGINAL stored bytes during an ambiguous retry.
     */
    fun prepare(
        jobId: String,
        authorityDigestSha256: String,
        phase: NextPolicyRequestPhase,
        proposedBytes: ByteArray,
        validateOriginal: (ByteArray) -> Unit,
    ): ByteArray = file.locked {
        check(jobId.matches(ID) && authorityDigestSha256.matches(SHA256))
        check(proposedBytes.size in 1..MAX_BODY_BYTES)
        val state = read()
        val job = state.jobs.firstOrNull { it.jobId == jobId }
            ?: Job(jobId, authorityDigestSha256, emptyList())
        check(job.authorityDigestSha256 == authorityDigestSha256) { "Pending request authority changed." }
        val original = job.requests.firstOrNull { it.phase == phase.name }
        if (original != null) {
            val bytes = decode(original)
            check(bytes.contentEquals(proposedBytes)) { "An existing request cannot be regenerated." }
            validateOriginal(bytes.copyOf())
            return@locked bytes
        }
        check(job.requests.size == phase.ordinal) { "Request phases must be prepared in order." }
        check(job.requests.none { it.consumedAcknowledgementB64 != null }) { "A consumed job cannot acquire another request." }
        val owned = proposedBytes.copyOf()
        validateOriginal(owned.copyOf())
        val updated = job.copy(requests = job.requests + Request(phase.name, encode(owned), digest(owned)))
        check(state.jobs.size < MAX_JOBS || state.jobs.any { it.jobId == jobId }) { "Journal capacity reached; preserve pending history." }
        write(state.copy(jobs = state.jobs.filterNot { it.jobId == jobId } + updated))
        owned
    }

    /** Read exact durable bytes without manufacturing a new nonce, timestamp or signature.
     * A caller must verify the complete current account/boot/APK/policy/DAK context in validator.
     */
    fun pending(
        jobId: String,
        authorityDigestSha256: String,
        phase: NextPolicyRequestPhase,
        validateOriginal: (ByteArray) -> Unit,
    ): ByteArray? = file.locked {
        val job = read().jobs.firstOrNull { it.jobId == jobId } ?: return@locked null
        check(job.authorityDigestSha256 == authorityDigestSha256) { "Pending request authority changed." }
        val request = job.requests.firstOrNull { it.phase == phase.name } ?: return@locked null
        val bytes = decode(request)
        validateOriginal(bytes.copyOf())
        bytes
    }

    /** Retain the original receipt and the exact acknowledgement even after successful transport.
     * Validation must include authenticated transport, current authority and the five-field ACK.
     * This memo MUST NOT be used to skip server reconciliation after a process restart.
     */
    fun recordConsumed(
        jobId: String,
        authorityDigestSha256: String,
        acknowledgementBytes: ByteArray,
        validateAuthenticatedAcknowledgement: (receiptBytes: ByteArray, ackBytes: ByteArray) -> Unit,
    ) = file.locked {
        check(acknowledgementBytes.size in 1..MAX_BODY_BYTES)
        val ownedAck = acknowledgementBytes.copyOf()
        val state = read()
        val job = checkNotNull(state.jobs.firstOrNull { it.jobId == jobId })
        check(job.authorityDigestSha256 == authorityDigestSha256)
        val receipt = checkNotNull(job.requests.firstOrNull { it.phase == NextPolicyRequestPhase.RECEIPT.name })
        validateAuthenticatedAcknowledgement(decode(receipt), ownedAck.copyOf())
        receipt.consumedAcknowledgementB64?.let {
            check(Base64.getDecoder().decode(it).contentEquals(ownedAck)) { "Consumed acknowledgement changed." }
            return@locked
        }
        val updated = job.copy(requests = job.requests.map {
            if (it == receipt) it.copy(consumedAcknowledgementB64 = encode(ownedAck)) else it
        })
        write(state.copy(jobs = state.jobs.map { if (it.jobId == jobId) updated else it }))
    }

    private fun read(): State {
        val bytes = file.read() ?: return State()
        check(bytes.size in 1..MAX_FILE_BYTES)
        val text = bytes.decodeToString(throwOnInvalidSequence = true)
        val state = json.decodeFromString<State>(text)
        check(json.encodeToString(state) == text) { "Journal encoding or schema changed." }
        check(state.schemaVersion == 1 && state.jobs.size <= MAX_JOBS)
        check(state.jobs.map { it.jobId }.distinct().size == state.jobs.size)
        state.jobs.forEach { job ->
            check(job.jobId.matches(ID) && job.authorityDigestSha256.matches(SHA256))
            check(job.requests.size in 1..NextPolicyRequestPhase.entries.size)
            job.requests.forEachIndexed { index, request ->
                check(request.phase == NextPolicyRequestPhase.entries[index].name)
                decode(request)
                request.consumedAcknowledgementB64?.let { ack ->
                    check(request.phase == NextPolicyRequestPhase.RECEIPT.name)
                    check(ack.length <= MAX_ENCODED_BODY_BYTES)
                    check(Base64.getDecoder().decode(ack).size in 1..MAX_BODY_BYTES)
                }
            }
        }
        return state
    }

    private fun write(state: State) {
        val bytes = json.encodeToString(state).encodeToByteArray()
        check(bytes.size <= MAX_FILE_BYTES) { "Journal capacity reached; preserve pending history." }
        file.write(bytes)
    }

    private fun decode(request: Request): ByteArray {
        check(request.bodyB64.length <= MAX_ENCODED_BODY_BYTES && request.bodyDigestSha256.matches(SHA256))
        val bytes = Base64.getDecoder().decode(request.bodyB64)
        check(bytes.size in 1..MAX_BODY_BYTES && digest(bytes) == request.bodyDigestSha256) { "Request journal is corrupt." }
        return bytes
    }

    @Serializable private data class State(val schemaVersion: Int = 1, val jobs: List<Job> = emptyList())
    @Serializable private data class Job(val jobId: String, val authorityDigestSha256: String, val requests: List<Request>)
    @Serializable private data class Request(
        val phase: String, val bodyB64: String, val bodyDigestSha256: String, val consumedAcknowledgementB64: String? = null,
    )

    private fun encode(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object {
        val ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}")
        val SHA256 = Regex("[0-9a-f]{64}")
        const val MAX_JOBS = 256
        const val MAX_BODY_BYTES = 256 * 1024
        const val MAX_ENCODED_BODY_BYTES = (MAX_BODY_BYTES + 2) / 3 * 4
        const val MAX_FILE_BYTES = 4 * 1024 * 1024
    }
}

internal interface NextPolicyJournalFile {
    fun <T> locked(block: () -> T): T
    fun read(): ByteArray?
    fun write(bytes: ByteArray)
}

/** Separate no-backup file; never touches legacy control-state-v1 or its pending operations. */
internal enum class NextPolicyJournalKind(val fileName: String) {
    REQUESTS("next-policy-request-journal-v1.json"),
    LIFECYCLE("next-policy-lifecycle-intents-v1.json"),
}

internal class AndroidNextPolicyJournalFile(
    context: Context,
    kind: NextPolicyJournalKind = NextPolicyJournalKind.REQUESTS,
) : NextPolicyJournalFile {
    private val base = File(context.applicationContext.noBackupFilesDir, "security/management/${kind.fileName}")
    private val atomic = AtomicFile(base)
    private val lockFile = File(base.parentFile, base.name + ".lock")
    private val processLock = locks.getOrPut(base.canonicalPath) { ReentrantLock() }

    override fun <T> locked(block: () -> T): T = processLock.withLock {
        check(base.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
        RandomAccessFile(lockFile, "rw").use { lock -> lock.channel.lock().use { block() } }
    }

    override fun read(): ByteArray? {
        // Only genuine initial absence is empty. Corrupt, unreadable or interrupted files fail closed.
        if (!base.exists() && !File(base.path + ".bak").exists() && !File(base.path + ".new").exists()) return null
        return atomic.openRead().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                check(size > 0 && output.size() + size <= 4 * 1024 * 1024)
                output.write(buffer, 0, size)
            }
            output.toByteArray()
        }
    }

    override fun write(bytes: ByteArray) {
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            output.fd.sync()
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
    }

    private companion object { val locks = ConcurrentHashMap<String, ReentrantLock>() }
}
