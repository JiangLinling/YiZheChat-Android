/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.media

import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.libraries.matrix.api.media.MediaFile
import io.element.android.libraries.matrix.api.media.ProgressiveMediaFile
import io.element.android.security.contentprotection.api.ProtectedChunkReadSession
import io.element.android.security.securestorage.api.AccountVaultId
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.math.BigInteger
import java.net.URI
import java.util.Base64
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal suspend fun openProgressiveProtectedVideo(
    source: MediaSource,
    wireLength: Long,
    homeserverUrl: String,
    accessToken: String,
    httpClient: OkHttpClient,
    service: MatrixProtectedMediaService,
    vaultId: AccountVaultId,
    accountIdHash: String,
    fullDownload: suspend () -> MediaFile,
): ProgressiveMediaFile = withOwnedMediaIo(cleanup = { it.close() }) {
    require(wireLength in 22L..MAX_VIDEO_BYTES)
    val origin = requireNotNull(source.eventOrigin)
    require(origin.eventId != null)
    val descriptor = parseEncryptedMatrixSource(source)
    val transport = ProgressiveMatrixTransport(descriptor, wireLength, homeserverUrl, accessToken, httpClient)
    var session: ProtectedChunkReadSession? = null
    try {
        val initial = transport.readOpening(0, minOf(PAGE_BYTES.toLong(), wireLength).toInt())
        val prefixLength = try {
            val input = DataInputStream(initial.inputStream())
            require(ByteArray(8).also(input::readFully).contentEquals("YZMF0001".encodeToByteArray()))
            val roomLength = input.readInt()
            require(roomLength in 1..4096)
            require(input.skipBytes(roomLength) == roomLength)
            val headerLength = input.readInt()
            require(headerLength in 1..1024 * 1024)
            Math.addExact(16 + roomLength, headerLength)
        } finally { initial.fill(0) }
        require(prefixLength.toLong() < wireLength)
        val prefix = transport.readOpening(0, prefixLength)
        session = try { service.openChunkReader(prefix, origin.roomId, vaultId, accountIdHash) } finally { prefix.fill(0) }
        val layout = ProgressiveChunkLayout(wireLength - prefixLength)
        val firstRecord = transport.readOpening(prefixLength.toLong(), layout.recordLength(0))
        try { session.decryptChunk(0, firstRecord, layout.plainLength(0), layout.isFinal(0)).fill(0) }
        finally { firstRecord.fill(0) }
        ProgressiveProtectedMediaFile(transport, session, prefixLength.toLong(), layout, fullDownload).also { session = null }
    } catch (failure: Throwable) {
        session?.close()
        transport.close()
        throw failure
    }
}

/** Canonical fixed-record framing, including the authenticated final record. */
internal class ProgressiveChunkLayout(payloadBytes: Long) {
    val count = (payloadBytes + RECORD_BYTES - 1) / RECORD_BYTES
    private val last = payloadBytes - (count - 1) * RECORD_BYTES
    val plaintextLength: Long
    init {
        require(count > 0 && last in 21L..RECORD_BYTES)
        plaintextLength = (count - 1) * PAGE_BYTES + last - 21
        require(plaintextLength > 0)
    }
    fun isFinal(index: Long): Boolean { require(index in 0 until count); return index == count - 1 }
    fun recordLength(index: Long): Int = if (isFinal(index)) last.toInt() else RECORD_BYTES.toInt()
    fun plainLength(index: Long): Int = recordLength(index) - 21
    fun recordOffset(index: Long): Long { require(index in 0 until count); return index * RECORD_BYTES }
}

internal class ProgressiveProtectedMediaFile(
    private val transport: ProgressiveMatrixTransport,
    private val session: ProtectedChunkReadSession,
    private val prefixLength: Long,
    private val layout: ProgressiveChunkLayout,
    private val fullDownload: suspend () -> MediaFile,
) : ProgressiveMediaFile {
    private val closed = AtomicBoolean()
    private val readLock = Any()
    // One decoder staging record, owned by this file and zeroed on advance/close. Never persisted or shared.
    private var stagedIndex = -1L
    private var stagedPlaintext: ByteArray? = null
    private val readQuiescent = kotlinx.coroutines.flow.MutableStateFlow(false)
    private val exportObservation = kotlinx.coroutines.flow.MutableStateFlow<kotlinx.coroutines.flow.StateFlow<Boolean>>(
        kotlinx.coroutines.flow.MutableStateFlow(true),
    )
    // Cold observation: no extra job or plaintext lifetime is introduced by a subscriber.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.coroutines.InternalCoroutinesApi::class)
    override val readQuiescence = object : kotlinx.coroutines.flow.StateFlow<Boolean> {
        override val value: Boolean get() = readQuiescent.value && exportObservation.value.value
        override val replayCache: List<Boolean> get() = listOf(value)
        override suspend fun collect(collector: kotlinx.coroutines.flow.FlowCollector<Boolean>): Nothing {
            kotlinx.coroutines.flow.combine(readQuiescent, exportObservation.flatMapLatest { it }) { own, exported -> own && exported }
                .collect(collector)
            error("Reader release observation unexpectedly completed.")
        }
    }
    private val token = UUID.randomUUID().toString()
    private val materializeMutex = Mutex()
    private var fullFile: MediaFile? = null
    override val isReadQuiescent get() = readQuiescence.value
    override val plaintextLength = layout.plaintextLength
    override val receivedCiphertextBytes get() = transport.receivedBytes.get()
    override val totalCiphertextBytes get() = transport.totalBytes
    override fun path(): String = throw SecurityException("Progressive media has no plaintext path.")
    override fun uriString() = "yizhe-progressive-video://$token"
    override fun openInputStream(): InputStream = throw SecurityException("Progressive media is a viewer-only reader.")
    override fun persist(path: String) = false
    override fun requireAuthorized() {
        check(!closed.get())
        runBlocking { session.requireAuthorized() }
        check(!closed.get())
    }
    override suspend fun materialize(): MediaFile = materializeMutex.withLock {
        session.requireAuthorized()
        synchronized(readLock) { fullFile }?.let { return@withLock it }
        val downloaded = fullDownload()
        try {
            currentCoroutineContext().ensureActive()
            session.requireAuthorized()
            synchronized(readLock) {
                check(!closed.get())
                fullFile = downloaded
                exportObservation.value = downloaded.readQuiescence ?: kotlinx.coroutines.flow.MutableStateFlow(false)
            }
            downloaded
        } catch (failure: Throwable) { downloaded.close(); throw failure }
    }

    override fun readAt(position: Long, destination: ByteArray, offset: Int, length: Int): Int = synchronized(readLock) {
        require(position >= 0 && offset >= 0 && length >= 0 && offset <= destination.size - length)
        check(!closed.get())
        if (length == 0) return@synchronized 0
        if (position >= plaintextLength) return@synchronized -1
        val index = position / PAGE_BYTES
        val within = (position % PAGE_BYTES).toInt()
        try {
            if (stagedIndex != index) {
                stagedPlaintext?.fill(0)
                stagedPlaintext = null
                stagedIndex = -1
                val record = transport.read(prefixLength + layout.recordOffset(index), layout.recordLength(index))
                val decoded = try { runBlocking { session.decryptChunk(index, record, layout.plainLength(index), layout.isFinal(index)) } }
                finally { record.fill(0) }
                stagedPlaintext = decoded
                stagedIndex = index
            }
            val plaintext = requireNotNull(stagedPlaintext)
            val copied = minOf(length, plaintext.size - within)
            runBlocking { session.requireBufferedReadAuthorized() }
            check(!closed.get())
            plaintext.copyInto(destination, offset, within, within + copied)
            copied
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (interrupted: InterruptedException) {
            throw interrupted
        } catch (interrupted: java.io.InterruptedIOException) {
            throw interrupted
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    override fun close() {
        if (readQuiescence.value) return
        closed.set(true)
        // Always cancel before the read barrier; a failed close remains retryable.
        var failure = runCatching { transport.cancel() }.exceptionOrNull()
        synchronized(readLock) {
            var cleanupSucceeded = true
            fun dispose(action: () -> Unit) {
                try { action() } catch (error: Throwable) {
                    cleanupSucceeded = false
                    if (failure == null) failure = error else if (failure !== error) failure?.addSuppressed(error)
                }
            }
            stagedPlaintext?.fill(0)
            stagedPlaintext = null
            stagedIndex = -1
            dispose { session.close() }
            dispose { transport.close() }
            dispose { fullFile?.close() }
            if (cleanupSucceeded) {
                fullFile = null
                // Legacy export capabilities have their own disposal proof and resource owner.
                readQuiescent.value = true
            }
        }
        failure?.let { throw it }
    }
}

/** Only ciphertext pages are retained. One response continues across records; each decoder read authenticates locally. */
internal class ProgressiveMatrixTransport(
    descriptor: EncryptedMatrixSourceDescriptor,
    val totalBytes: Long,
    homeserverUrl: String,
    private var accessToken: String,
    private val client: OkHttpClient,
) : Closeable {
    private val closed = AtomicBoolean()
    val receivedBytes = AtomicLong()
    private val lock = Any()
    @Volatile private var call: Call? = null
    @Volatile private var response: Response? = null
    private var input: InputStream? = null
    private var cursor = 0L
    private var responseEnd = 0L
    private val pages = LinkedHashMap<Long, ByteArray>(16, .75f, true)
    private val key = Base64.getUrlDecoder().decode(descriptor.encryptedFile.keyBase64Url)
    private val iv = Base64.getDecoder().decode(descriptor.encryptedFile.ivBase64)
    private val url = run {
        require(totalBytes in 22L..MAX_VIDEO_BYTES && key.size == 32 && iv.size == 16 && accessToken.isNotBlank())
        check(client.cache == null && !client.followRedirects && !client.followSslRedirects)
        val mxc = URI(descriptor.url)
        require(mxc.scheme == "mxc" && !mxc.rawAuthority.isNullOrBlank() && mxc.userInfo == null && mxc.query == null && mxc.fragment == null)
        val id = mxc.rawPath.orEmpty().removePrefix("/")
        require(id.isNotBlank() && !id.contains('/'))
        val server = requireNotNull(homeserverUrl.toHttpUrlOrNull())
        require(server.isHttps && server.username.isEmpty() && server.password.isEmpty())
        server.newBuilder().encodedPath("/").query(null).fragment(null)
            .addPathSegments("_matrix/client/v1/media/download").addPathSegment(requireNotNull(mxc.rawAuthority)).addPathSegment(id).build()
    }

    suspend fun readOpening(offset: Long, length: Int): ByteArray = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        try {
            continuation.resume(read(offset, length)) { _, bytes, _ -> bytes.fill(0) }
        } catch (failure: Throwable) { continuation.resumeWithException(failure) }
    }

    fun read(offset: Long, length: Int): ByteArray = synchronized(lock) {
        require(offset >= 0 && length in 0..1024 * 1024 + 4112 && offset <= totalBytes - length)
        check(!closed.get())
        val ciphertext = ByteArray(length)
        var copied = 0
        try {
            while (copied < length) {
                val at = offset + copied
                val pageIndex = at / PAGE_BYTES
                val page = pages[pageIndex] ?: readPage(pageIndex).also {
                    pages[pageIndex] = it
                    // 32 MiB ciphertext-only seek window; never stores decoded video bytes.
                    if (pages.size > 512) {
                        val oldest = pages.entries.iterator().next()
                        pages.remove(oldest.key)?.fill(0)
                    }
                }
                val within = (at % PAGE_BYTES).toInt()
                val count = minOf(length - copied, page.size - within)
                page.copyInto(ciphertext, copied, within, within + count)
                copied += count
            }
            decryptMatrixRange(ciphertext, key, iv, offset)
        } catch (interrupted: java.io.InterruptedIOException) {
            // A cancelled decoder read may leave this response partially consumed. Reopen only transport.
            closeResponse()
            throw interrupted
        } finally { ciphertext.fill(0) }
    }

    private fun readPage(index: Long): ByteArray {
        val start = index * PAGE_BYTES
        val length = minOf(PAGE_BYTES.toLong(), totalBytes - start).toInt()
        if (input == null || start < cursor || start + length > responseEnd) openResponse(start)
        val stream = requireNotNull(input)
        val scratch = ByteArray(8192)
        try {
            while (cursor < start) {
                val count = stream.read(scratch, 0, minOf(scratch.size.toLong(), start - cursor).toInt())
                if (count <= 0) throw IOException("Truncated Matrix media response.")
                cursor += count
                receivedBytes.addAndGet(count.toLong())
            }
            val page = ByteArray(length)
            try {
                var read = 0
                while (read < length) {
                    check(!closed.get())
                    val count = stream.read(page, read, length - read)
                    if (count <= 0) throw IOException("Truncated Matrix media response.")
                    read += count
                    cursor += count
                    receivedBytes.addAndGet(count.toLong())
                }
                if (cursor == responseEnd) {
                    if (stream.read() != -1) throw SecurityException("Unexpected Matrix media response bytes.")
                    closeResponse()
                }
                return page
            } catch (failure: Throwable) { page.fill(0); throw failure }
        } finally { scratch.fill(0) }
    }

    private fun openResponse(start: Long) {
        closeResponse()
        val end = minOf(totalBytes, start + 2L * 1024 * 1024) - 1
        val next = client.newCall(Request.Builder().url(url).header("Authorization", "Bearer $accessToken")
            .header("Cache-Control", "no-store").header("Accept-Encoding", "identity").header("Range", "bytes=$start-$end").build())
        call = next
        if (closed.get()) { next.cancel(); throw IOException("Progressive media is closed.") }
        val result = next.execute()
        response = result
        try {
            check(!closed.get())
            if (result.isRedirect) throw SecurityException("Matrix media redirects are forbidden.")
            require(result.header("Content-Encoding") == null || result.header("Content-Encoding") == "identity")
            val body = requireNotNull(result.body)
            when (result.code) {
                206 -> {
                    require(result.header("Content-Range") == "bytes $start-$end/$totalBytes")
                    require(body.contentLength() == end - start + 1)
                    cursor = start
                    responseEnd = end + 1
                }
                200 -> {
                    // Some media servers ignore Range. Still stream continuously, without retaining the whole object.
                    require(result.header("Content-Range") == null && body.contentLength() == totalBytes)
                    cursor = 0
                    responseEnd = totalBytes
                }
                else -> throw IOException("Matrix media request failed with HTTP ${result.code}.")
            }
            input = body.byteStream()
        } catch (failure: Throwable) { closeResponse(); throw failure }
    }

    private fun closeResponse() {
        response?.close()
        response = null
        input = null
        call = null
    }
    // Cancelling a Call is safe on the UI/cancellation thread. Response.close may drain the socket.
    fun cancel() { closed.set(true); call?.cancel() }
    override fun close() {
        try {
            cancel()
        } finally {
            // Retain the synchronous disposal barrier, but perform socket disposal on IO.
            runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            synchronized(lock) {
                try {
                    closeResponse()
                } finally {
                    pages.values.forEach { it.fill(0) }
                    pages.clear()
                    key.fill(0)
                    iv.fill(0)
                    accessToken = ""
                }
            }
            }
        }
    }
}

internal fun decryptMatrixRange(ciphertext: ByteArray, key: ByteArray, iv: ByteArray, offset: Long): ByteArray {
    require(offset >= 0 && key.size == 32 && iv.size == 16)
    val counter = BigInteger(1, iv).add(BigInteger.valueOf(offset / 16))
    require(counter.bitLength() <= 128)
    val counterBytes = counter.toByteArray().takeLast(16).toByteArray()
    val adjustedIv = ByteArray(16)
    counterBytes.copyInto(adjustedIv, 16 - counterBytes.size)
    val padding = (offset % 16).toInt()
    val padded = ByteArray(padding + ciphertext.size)
    ciphertext.copyInto(padded, padding)
    var decrypted: ByteArray? = null
    try {
        decrypted = Cipher.getInstance("AES/CTR/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(adjustedIv))
            doFinal(padded)
        }
        return decrypted.copyOfRange(padding, decrypted.size)
    } finally { counterBytes.fill(0); adjustedIv.fill(0); padded.fill(0); decrypted?.fill(0) }
}

private const val PAGE_BYTES = 65_536
private const val RECORD_BYTES = 65_557L
private const val MAX_VIDEO_BYTES = 2L * 1024 * 1024 * 1024
