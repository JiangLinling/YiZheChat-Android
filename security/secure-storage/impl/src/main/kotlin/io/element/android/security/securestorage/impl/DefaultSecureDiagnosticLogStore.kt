/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.securestorage.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.security.securestorage.api.DeviceRootKeyService
import io.element.android.security.securestorage.api.DeviceWrappedKey
import io.element.android.security.securestorage.api.DiagnosticCollectionState
import io.element.android.security.securestorage.api.DiagnosticMigrationResult
import io.element.android.security.securestorage.api.SecureDiagnosticEvent
import io.element.android.security.securestorage.api.SecureDiagnosticLogStore
import io.element.android.security.securestorage.api.SecureDiagnosticSnapshot
import io.element.android.security.securestorage.api.SecureStorageKeyUnavailableException
import io.element.android.security.securestorage.api.SecureStorageRoots
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The existing Secure Storage authority owns the installation-bound LOG domain. Never writes plaintext. */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DefaultSecureDiagnosticLogStore(
    private val roots: SecureStorageRoots,
    private val deviceRootKeyService: DeviceRootKeyService,
) : SecureDiagnosticLogStore {
    private val random = SecureRandom()
    private val suppressed = ThreadLocal<Boolean>()
    private val dropped = AtomicLong()
    private val committed = AtomicLong()
    @Volatile private var lastFailure: String? = null
    @Volatile private var keyUnavailable = false
    @Volatile private var retentionCleanupFailed = false
    @Volatile private var migrationFailed = false
    @Volatile private var collectionFailure: String? = null
    @Volatile private var workerThread: Thread? = null
    private var segment: File? = null
    private var sequence = 0L
    internal var now: () -> Long = System::currentTimeMillis
    internal var maximumSegmentBytes: Long = 1024 * 1024
    internal var maximumTotalBytes: Long = 16 * 1024 * 1024
    internal var maximumSegments: Int = 16
    internal var maximumAgeMillis: Long = TimeUnit.DAYS.toMillis(7)
    internal var deleteSegment: (File) -> Boolean = File::delete
    internal var appendEncryptedFrame: (File, ByteArray) -> Unit = { file, frame ->
        FileOutputStream(file, true).use { stream ->
            stream.write(frame)
            stream.flush()
            stream.fd.sync()
        }
    }
    private val executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(512),
        { task ->
            Thread(task, "YiZheSecureDiagnostics").apply {
            isDaemon = true
            workerThread = this
        }
        },
        ThreadPoolExecutor.AbortPolicy(),
    )

    private fun directory(): File = File(roots.persistentRoot(), "diagnostics/v1").apply {
        if (!isDirectory && !mkdirs()) throw IOException("DIAGNOSTIC_DIRECTORY_UNAVAILABLE")
    }

    override fun reportFailure(code: String) {
        require(code.matches(Regex("[A-Z][A-Z0-9_]{0,79}")))
        collectionFailure = code
        lastFailure = code
    }

    override fun append(priority: Int, source: String?, message: String, throwable: Throwable?, location: String?) {
        // DRK itself emits diagnostics and may switch dispatchers. Its context is suppressed below.
        if (suppressed.get() == true) return
        val event = runCatching { DiagnosticLogRedactor.redact(now(), priority, source, message, throwable, location) }.getOrElse {
            dropped.incrementAndGet()
            lastFailure = "REDACTION_FAILED"
            return
        }
        val work = Write(event)
        try {
            executor.execute(work)
        } catch (_: RejectedExecutionException) {
            // Preserve critical evidence preferentially without moving disk/crypto work onto the UI thread.
            val older = executor.queue.firstOrNull { it is Write && it.event.priority < 6 }
            if (event.priority >= 6 && older != null && executor.queue.remove(older)) {
                dropped.incrementAndGet()
                try {
                    executor.execute(work)
                    return
                } catch (_: RejectedExecutionException) {
                    // A concurrent flush/close may have consumed the last slot. Count actual loss.
                }
            }
            dropped.incrementAndGet()
            lastFailure = "QUEUE_OVERFLOW"
        }
    }

    private inner class Write(val event: SecureDiagnosticEvent) : Runnable {
        override fun run() {
            val batch = mutableListOf(event)
            while (batch.size < 32) {
                val next = executor.queue.peek() as? Write ?: break
                if (executor.queue.remove(next)) batch += next.event
            }
            val before = committed.get()
            try {
                guarded { persist(batch) }
            } catch (failure: Exception) {
                dropped.addAndGet((batch.size - (committed.get() - before)).coerceAtLeast(0))
                keyUnavailable = failure is SecureStorageKeyUnavailableException
                lastFailure = when {
                    keyUnavailable -> "DEVICE_LOG_KEY_UNAVAILABLE"
                    retentionCleanupFailed -> "RETENTION_CAPACITY_UNAVAILABLE"
                    else -> "ENCRYPTED_WRITE_FAILED"
                }
            }
        }
    }

    private fun <T> guarded(block: suspend () -> T): T = runBlocking(suppressed.asContextElement(true)) { block() }

    private suspend fun loadDataKey(folder: File): ByteArray {
        val envelope = File(folder, KEY_FILE)
        if (!envelope.exists()) {
            if (segments(folder).isNotEmpty() || File(folder, "$KEY_FILE.partial").exists()) {
                throw SecureStorageKeyUnavailableException()
            }
            val key = ByteArray(32).also(random::nextBytes)
            try {
                val wrapped = deviceRootKeyService.wrapKey(key, KEY_BINDING)
                val temporary = File(folder, "$KEY_FILE.partial")
                FileOutputStream(temporary).use { stream ->
                    DataOutputStream(stream).apply {
                        writeInt(KEY_MAGIC)
                        writeInt(wrapped.formatVersion)
                        writeLong(wrapped.rootKeyVersion)
                        writeBytesWithSize(wrapped.installationIdSha256)
                        writeBytesWithSize(wrapped.initializationVector)
                        writeBytesWithSize(wrapped.ciphertext)
                        flush()
                    }
                    stream.fd.sync()
                }
                if (!temporary.renameTo(envelope)) throw IOException("DIAGNOSTIC_KEY_COMMIT_FAILED")
                return key.copyOf()
            } finally {
                key.fill(0)
            }
        }
        val wrapped = try {
            DataInputStream(envelope.inputStream()).use { input ->
                require(input.readInt() == KEY_MAGIC)
                DeviceWrappedKey(input.readInt(), input.readLong(), input.readBytesWithSize(), input.readBytesWithSize(), input.readBytesWithSize())
                    .also { require(input.read() == -1) }
            }
        } catch (failure: Exception) {
            throw SecureStorageKeyUnavailableException(failure)
        }
        // Never keep an unwrapped derivative across batches or bypass a destroyed/missing installation DRK.
        val key = deviceRootKeyService.unwrapKey(wrapped, KEY_BINDING)
        if (key.size != 32) {
            key.fill(0)
            throw SecureStorageKeyUnavailableException()
        }
        return key
    }

    private suspend fun persist(events: List<SecureDiagnosticEvent>) {
        val folder = directory()
        val key = loadDataKey(folder)
        try {
            events.forEach { event ->
                val plaintext = encode(event)
                try {
                    val frameSize = plaintext.size + 52L
                    require(frameSize <= maximumSegmentBytes && frameSize <= maximumTotalBytes)
                    val newSegment = segment?.let { !it.exists() || it.length() + frameSize > maximumSegmentBytes } ?: true
                    prune(folder, frameSize, if (newSegment) 1 else 0)
                    val file = currentSegment(folder, frameSize)
                    val id = ++sequence
                    val nonce = ByteArray(12).also(random::nextBytes)
                    val ciphertext = cipher(Cipher.ENCRYPT_MODE, key, nonce, aad(file, id, event.timestampEpochMillis)).doFinal(plaintext)
                    val frame = ByteArrayOutputStream().use { bytes ->
                        DataOutputStream(bytes).use { output ->
                            output.writeInt(RECORD_MAGIC)
                            output.writeLong(id)
                            output.writeLong(event.timestampEpochMillis)
                            output.write(nonce)
                            output.writeInt(ciphertext.size)
                            output.write(ciphertext)
                        }
                        bytes.toByteArray()
                    }
                    try {
                        appendEncryptedFrame(file, frame)
                        committed.incrementAndGet()
                    } catch (failure: Exception) {
                        // A partial frame is sealed forever. Subsequent records use a fresh authenticated segment.
                        segment = null
                        throw failure
                    }
                } finally {
                    plaintext.fill(0)
                }
            }
            prune(folder)
            keyUnavailable = false
            if (!retentionCleanupFailed && !migrationFailed && collectionFailure == null && lastFailure != "QUEUE_OVERFLOW") lastFailure = null
        } finally {
            key.fill(0)
        }
    }

    private fun currentSegment(folder: File, frameSize: Long): File {
        val current = segment
        if (current != null && current.exists() && current.length() + frameSize <= maximumSegmentBytes) return current
        return File(folder, "diag-${now().coerceAtLeast(0).toString().padStart(13, '0')}-${UUID.randomUUID().toString().replace("-", "")}.elog")
            .also {
                segment = it
                sequence = 0
            }
    }

    private fun segments(folder: File): List<File> = folder.listFiles().orEmpty()
        .filter { it.isFile && SEGMENT_NAME.matches(it.name) }.sortedBy { it.name }

    private fun prune(folder: File, reserveBytes: Long = 0, reserveSegments: Int = 0) {
        var files = segments(folder)
        files.filter { now() - it.name.removePrefix("diag-").substringBefore('-').toLong() > maximumAgeMillis }.forEach { file ->
            if (file == segment) segment = null
            if (!deleteSegment(file)) {
                retentionCleanupFailed = true
                lastFailure = "RETENTION_CLEANUP_FAILED"
            }
        }
        files = segments(folder)
        var bytes = files.sumOf(File::length)
        var count = files.size
        files.forEach { file ->
            if (count + reserveSegments > maximumSegments || bytes + reserveBytes > maximumTotalBytes) {
                val size = file.length()
                if (deleteSegment(file)) {
                    bytes -= size
                    count--
                    if (file == segment) segment = null
                } else {
                    retentionCleanupFailed = true
                    lastFailure = "RETENTION_CLEANUP_FAILED"
                }
            }
        }
        // If deletion failed, refuse the incoming frame rather than allowing unbounded disk growth.
        if (count + reserveSegments > maximumSegments || bytes + reserveBytes > maximumTotalBytes) {
            retentionCleanupFailed = true
            lastFailure = "RETENTION_CAPACITY_UNAVAILABLE"
            throw IOException("DIAGNOSTIC_STORAGE_BUDGET_EXHAUSTED")
        }
    }

    override fun flushBlocking(timeoutMillis: Long): Boolean {
        if (Thread.currentThread() === workerThread) return executor.queue.none { it is Write }
        val future = CompletableFuture<Boolean>()
        return try {
            executor.execute { future.complete(!keyUnavailable && lastFailure == null) }
            future.get(timeoutMillis.coerceIn(1, 5000), TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            lastFailure = "FLUSH_INCOMPLETE"
            false
        }
    }

    override suspend fun snapshot(): SecureDiagnosticSnapshot = withContext(Dispatchers.IO) {
        val future = CompletableFuture<SecureDiagnosticSnapshot>()
        try {
            executor.execute {
                try {
                    future.complete(guarded { readSnapshot() })
                } catch (failure: Exception) {
                    keyUnavailable = failure is SecureStorageKeyUnavailableException
                    lastFailure = if (keyUnavailable) "DEVICE_LOG_KEY_UNAVAILABLE" else "ENCRYPTED_READ_FAILED"
                    future.complete(unavailableSnapshot())
                }
            }
            future.get(10, TimeUnit.SECONDS)
        } catch (_: Exception) {
            lastFailure = "SNAPSHOT_INCOMPLETE"
            unavailableSnapshot()
        }
    }

    private suspend fun readSnapshot(): SecureDiagnosticSnapshot {
        val folder = directory()
        // Retention failures remain visible, but do not hide already authenticated evidence.
        runCatching { prune(folder) }
        val files = segments(folder)
        if (files.isEmpty()) return unavailableSnapshot(empty = true)
        val key = loadDataKey(folder)
        val events = ArrayDeque<SecureDiagnosticEvent>()
        var characters = 0
        var unreadable = 0
        var omitted = 0L
        try {
            files.forEach { file ->
                try {
                    DataInputStream(file.inputStream().buffered()).use { input ->
                        while (input.available() > 0) {
                            require(input.readInt() == RECORD_MAGIC)
                            val id = input.readLong()
                            val timestamp = input.readLong()
                            val nonce = ByteArray(12).also(input::readFully)
                            val length = input.readInt()
                            require(length in 16..MAX_RECORD_BYTES)
                            val ciphertext = ByteArray(length).also(input::readFully)
                            val plaintext = cipher(Cipher.DECRYPT_MODE, key, nonce, aad(file, id, timestamp)).doFinal(ciphertext)
                            val event = try {
                                decode(plaintext)
                            } finally {
                                plaintext.fill(0)
                            }
                            require(event.timestampEpochMillis == timestamp)
                            events += event
                            characters += event.details.length + event.stack.length + event.source.length + event.location.length + 64
                            while (characters > MAX_SNAPSHOT_CHARACTERS || events.size > MAX_SNAPSHOT_RECORDS) {
                                val removed = events.removeFirst()
                                characters -= removed.details.length + removed.stack.length + removed.source.length + removed.location.length + 64
                                omitted++
                            }
                        }
                    }
                } catch (_: Exception) {
                    unreadable++ // Complete authenticated records before a torn trailing frame remain useful.
                }
            }
        } finally {
            key.fill(0)
        }
        val state = when {
            unreadable > 0 || omitted > 0 || dropped.get() > 0 || lastFailure != null -> DiagnosticCollectionState.PARTIAL
            events.isEmpty() -> DiagnosticCollectionState.EMPTY
            else -> DiagnosticCollectionState.AVAILABLE
        }
        return SecureDiagnosticSnapshot(UUID.randomUUID().toString(), state, events.toList(), dropped.get() + omitted, unreadable, lastFailure)
    }

    private fun unavailableSnapshot(empty: Boolean = false) = SecureDiagnosticSnapshot(
        UUID.randomUUID().toString(),
        when {
            keyUnavailable -> DiagnosticCollectionState.KEY_UNAVAILABLE
            lastFailure != null -> DiagnosticCollectionState.STORAGE_ERROR
            empty -> DiagnosticCollectionState.EMPTY
            else -> DiagnosticCollectionState.STORAGE_ERROR
        },
        emptyList(),
        dropped.get(),
        0,
        lastFailure,
    )

    override suspend fun migrateLegacy(files: List<File>): DiagnosticMigrationResult = withContext(Dispatchers.IO) {
        val future = CompletableFuture<DiagnosticMigrationResult>()
        try {
            executor.execute {
                try {
                    future.complete(guarded { migrateSelectedFiles(files) })
                } catch (failure: Exception) {
                    future.completeExceptionally(failure)
                }
            }
            future.get(30, TimeUnit.SECONDS)
        } catch (_: Exception) {
            migrationFailed = true
            lastFailure = "LEGACY_MIGRATION_INCOMPLETE"
            DiagnosticMigrationResult(0, files.size, 0)
        }
    }

    private suspend fun migrateSelectedFiles(files: List<File>): DiagnosticMigrationResult {
        var migrated = 0
        var failed = 0
        var records = 0
        files.filter { it.isFile }.take(24).forEach { file ->
            val before = committed.get()
            try {
                val source = if (file.extension == "gz") GZIPInputStream(file.inputStream()) else file.inputStream()
                val bytes = ByteArray(1024 * 1024 + 1)
                val count = source.use { input ->
                    var offset = 0
                    while (offset < bytes.size) {
                        val size = input.read(bytes, offset, bytes.size - offset)
                        if (size < 0) break
                        offset += size
                    }
                    offset
                }
                if (count == bytes.size) throw IOException("LEGACY_FILE_EXCEEDS_MIGRATION_BUDGET")
                val events = try {
                    bytes.decodeToString(0, count).lineSequence().filter(String::isNotBlank)
                        .map { DiagnosticLogRedactor.redact(now(), 4, "Legacy", it, null, "legacy-log") }.toList()
                } finally {
                    bytes.fill(0)
                }
                if (events.isEmpty()) throw IOException("LEGACY_FILE_EMPTY")
                events.chunked(32).forEach { persist(it) }
                if (committed.get() - before != events.size.toLong()) throw IOException("LEGACY_COMMIT_COUNT_MISMATCH")
                migrated++
            } catch (_: Exception) {
                failed++
                migrationFailed = true
                lastFailure = "LEGACY_MIGRATION_INCOMPLETE"
            }
            records += (committed.get() - before).toInt()
        }
        if (files.size > 24) {
            failed += files.size - 24
            migrationFailed = true
            lastFailure = "LEGACY_MIGRATION_INCOMPLETE"
        }
        return DiagnosticMigrationResult(migrated, failed, records)
    }

    internal fun closeForTests() {
        flushBlocking(5000)
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }

    private fun cipher(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray): Cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        updateAAD(aad)
    }

    private fun aad(file: File, sequence: Long, timestamp: Long): ByteArray =
        "diagnostic-log:v1:${file.name}:$sequence:$timestamp".encodeToByteArray()

    private fun encode(event: SecureDiagnosticEvent): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeLong(event.timestampEpochMillis)
            output.writeInt(event.priority)
            listOf(event.source, event.details, event.stack, event.location).forEach { output.writeBytesWithSize(it.encodeToByteArray()) }
            output.writeInt(event.redactedFields)
        }
        bytes.toByteArray().also { require(it.size <= MAX_RECORD_BYTES - 16) }
    }

    private fun decode(bytes: ByteArray): SecureDiagnosticEvent = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        SecureDiagnosticEvent(
            input.readLong(),
            input.readInt(),
            input.readBytesWithSize().decodeToString(),
            input.readBytesWithSize().decodeToString(),
            input.readBytesWithSize().decodeToString(),
            input.readBytesWithSize().decodeToString(),
            input.readInt(),
        ).also { require(input.read() == -1) }
    }

    private fun DataOutputStream.writeBytesWithSize(bytes: ByteArray) {
        require(bytes.size <= MAX_RECORD_BYTES)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readBytesWithSize(): ByteArray {
        val size = readInt()
        require(size in 0..MAX_RECORD_BYTES)
        return ByteArray(size).also(::readFully)
    }

    private companion object {
        const val KEY_FILE = "installation-log-key.v1"
        const val KEY_MAGIC = 0x59444B31
        const val RECORD_MAGIC = 0x59444C31
        const val MAX_RECORD_BYTES = 256 * 1024
        const val MAX_SNAPSHOT_CHARACTERS = 2 * 1024 * 1024
        const val MAX_SNAPSHOT_RECORDS = 5000
        val KEY_BINDING = "yizhe:secure-storage:purpose=LOG:installation-diagnostics:v1".encodeToByteArray()
        val SEGMENT_NAME = Regex("diag-[0-9]{13,19}-[0-9a-f]{32}\\.elog")
    }
}
