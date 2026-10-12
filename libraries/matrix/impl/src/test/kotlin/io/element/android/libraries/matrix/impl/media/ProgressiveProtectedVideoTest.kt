/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.media

import com.google.common.truth.Truth.assertThat
import io.element.android.security.contentprotection.api.SegmentedMatrixEncryptedFile
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.buffer
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import org.junit.Test
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProgressiveProtectedVideoTest {
    @Test fun `tiny decoder reads authenticate once per record and staging is zeroed on advance and close`() {
        var decryptions = 0
        val staged = mutableListOf<ByteArray>()
        val session = object : io.element.android.security.contentprotection.api.ProtectedChunkReadSession {
            override suspend fun requireAuthorized() = Unit
            override suspend fun decryptChunk(index: Long, record: ByteArray, expectedPlainLength: Int, finalChunk: Boolean): ByteArray =
                ByteArray(expectedPlainLength) { 7 }.also { decryptions++; staged.add(it) }
            override fun close() = Unit
        }
        val transport = ProgressiveMatrixTransport(descriptor, ciphertext.size.toLong(), "https://homeserver.example", "test-token", http(mutableListOf()))
        val file = ProgressiveProtectedMediaFile(transport, session, 0, ProgressiveChunkLayout(ciphertext.size.toLong()),
            fullDownload = { error("No export") })
        val buffer = ByteArray(5)
        repeat(100) { assertThat(file.readAt(it.toLong(), buffer, 0, buffer.size)).isEqualTo(5) }
        assertThat(decryptions).isEqualTo(1)
        assertThat(buffer).isEqualTo(ByteArray(5) { 7 })
        file.readAt(65_536, buffer, 0, 5)
        assertThat(decryptions).isEqualTo(2)
        assertThat(staged.first().all { it == 0.toByte() }).isTrue()
        file.close()
        assertThat(staged.last().all { it == 0.toByte() }).isTrue()
        assertThat(file.isReadQuiescent).isTrue()
    }

    @Test fun `live denial before copying staged bytes closes and zeros the retained record`() {
        var denied = false
        var staged: ByteArray? = null
        val session = object : io.element.android.security.contentprotection.api.ProtectedChunkReadSession {
            override suspend fun requireAuthorized() { if (denied) throw SecurityException() }
            override suspend fun decryptChunk(index: Long, record: ByteArray, expectedPlainLength: Int, finalChunk: Boolean): ByteArray =
                ByteArray(expectedPlainLength) { 7 }.also { staged = it }
            override fun close() = Unit
        }
        val transport = ProgressiveMatrixTransport(descriptor, ciphertext.size.toLong(), "https://homeserver.example", "test-token", http(mutableListOf()))
        val file = ProgressiveProtectedMediaFile(transport, session, 0, ProgressiveChunkLayout(ciphertext.size.toLong()),
            fullDownload = { error("No export") })
        file.readAt(0, ByteArray(5), 0, 5)
        denied = true
        val target = ByteArray(5)
        assertThat(runCatching { file.readAt(5, target, 0, 5) }.isFailure).isTrue()
        assertThat(target).isEqualTo(ByteArray(5))
        assertThat(requireNotNull(staged).all { it == 0.toByte() }).isTrue()
        assertThat(file.isReadQuiescent).isTrue()
    }

    @Test fun `materialized reader release is observed without inventing a completed export`() = kotlinx.coroutines.test.runTest {
        val transport = ProgressiveMatrixTransport(descriptor, ciphertext.size.toLong(), "https://homeserver.example", "test-token", http(mutableListOf()))
        val session = object : io.element.android.security.contentprotection.api.ProtectedChunkReadSession {
            override suspend fun requireAuthorized() = Unit
            override suspend fun decryptChunk(index: Long, record: ByteArray, expectedPlainLength: Int, finalChunk: Boolean): ByteArray =
                error("This test observes ownership only.")
            override fun close() = Unit
        }
        val exportReleased = kotlinx.coroutines.flow.MutableStateFlow(false)
        val exported = io.mockk.mockk<io.element.android.libraries.matrix.api.media.MediaFile>(relaxed = true)
        io.mockk.every { exported.readQuiescence } returns exportReleased
        val file = ProgressiveProtectedMediaFile(transport, session, 0, ProgressiveChunkLayout(65_557), fullDownload = { exported })
        assertThat(file.materialize()).isSameInstanceAs(exported)
        file.close()
        assertThat(file.readQuiescence.value).isFalse()
        assertThat(file.isReadQuiescent).isFalse()
        val observed = async { file.readQuiescence.first { it } }
        runCurrent()
        assertThat(observed.isCompleted).isFalse()
        exportReleased.value = true
        runCurrent()
        assertThat(observed.await()).isTrue()
        assertThat(file.isReadQuiescent).isTrue()
        io.mockk.verify(exactly = 1) { exported.close() }
    }

    @Test fun `failed reader close retries real disposal before proving quiescence`() {
        val ranges = mutableListOf<String>()
        val transport = ProgressiveMatrixTransport(descriptor, ciphertext.size.toLong(), "https://homeserver.example", "test-token", http(ranges))
        transport.read(0, 1)
        var closes = 0
        val session = object : io.element.android.security.contentprotection.api.ProtectedChunkReadSession {
            override suspend fun requireAuthorized() = Unit
            override suspend fun decryptChunk(index: Long, record: ByteArray, expectedPlainLength: Int, finalChunk: Boolean): ByteArray =
                error("No decoder read is part of this close test.")
            override fun close() { if (++closes == 1) throw java.io.IOException("First close could not complete.") }
        }
        val file = ProgressiveProtectedMediaFile(transport, session, 0, ProgressiveChunkLayout(65_557),
            fullDownload = { error("No export is part of this reader test.") })
        assertThat(file.isReadQuiescent).isFalse()
        assertThat(runCatching { file.close() }.isFailure).isTrue()
        assertThat(file.isReadQuiescent).isFalse()
        assertThat(runCatching { file.readAt(0, ByteArray(1), 0, 1) }.isFailure).isTrue()
        file.close()
        assertThat(closes).isEqualTo(2)
        assertThat(file.isReadQuiescent).isTrue()
        file.close()
        assertThat(closes).isEqualTo(2)
        assertThat(runCatching { transport.read(0, 1) }.isFailure).isTrue()
    }

    private val key = ByteArray(32) { 7 }
    private val iv = ByteArray(16) { 0 }.also { it[7] = 9 }
    private val plaintext = ByteArray(4 * 1024 * 1024 + 173) { (it % 251).toByte() }
    private val ciphertext = Cipher.getInstance("AES/CTR/NoPadding").run {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(this@ProgressiveProtectedVideoTest.iv))
        doFinal(plaintext)
    }
    private val descriptor = EncryptedMatrixSourceDescriptor(
        "mxc://media.example/video", "0".repeat(64),
        SegmentedMatrixEncryptedFile(
            version = "v2", algorithm = "A256CTR", keyType = "oct", keyOperations = setOf("encrypt", "decrypt"), extractable = true,
            keyBase64Url = Base64.getUrlEncoder().withoutPadding().encodeToString(key),
            ivBase64 = Base64.getEncoder().withoutPadding().encodeToString(iv),
        ),
    )
    private fun http(ranges: MutableList<String>, responseCode: Int = 206, wrongRange: Boolean = false, truncate: Boolean = false) =
        OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).addInterceptor { chain ->
            val request = chain.request()
            assertThat(request.url.toString()).isEqualTo("https://homeserver.example/_matrix/client/v1/media/download/media.example/video")
            assertThat(request.header("Authorization")).isEqualTo("Bearer test-token")
            assertThat(request.header("Accept-Encoding")).isEqualTo("identity")
            val range = requireNotNull(request.header("Range"))
            ranges.add(range)
            val (start, end) = range.removePrefix("bytes=").split('-').map(String::toInt)
            val bytes = if (responseCode == 200) ciphertext else ciphertext.copyOfRange(start, end + 1)
            val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(responseCode).message("fixture")
                .body(object : ResponseBody() {
                    override fun contentType() = null
                    override fun contentLength() = bytes.size.toLong()
                    override fun source() = Buffer().write(if (truncate) bytes.copyOf(17) else bytes)
                })
            if (responseCode == 206) response.header("Content-Range", if (wrongRange) "bytes 0-9/10" else "bytes $start-$end/${ciphertext.size}")
            response.build()
        }.build()

    @Test fun `first data arrives before complete download and sequential reads reuse one response`() {
        val ranges = mutableListOf<String>()
        val transport = ProgressiveMatrixTransport(descriptor, ciphertext.size.toLong(), "https://homeserver.example", "test-token", http(ranges))
        transport.use {
            assertThat(it.read(33, 1000)).isEqualTo(plaintext.copyOfRange(33, 1033))
            assertThat(it.receivedBytes.get()).isEqualTo(65_536)
            assertThat(it.receivedBytes.get()).isLessThan(ciphertext.size.toLong())
            assertThat(it.read(65_520, 1000)).isEqualTo(plaintext.copyOfRange(65_520, 66_520))
            assertThat(ranges).hasSize(1)
            val tail = ciphertext.size - 90
            assertThat(it.read(tail.toLong(), 90)).isEqualTo(plaintext.copyOfRange(tail, plaintext.size))
            assertThat(ranges).hasSize(2)
        }
        assertThat(runCatching { transport.read(0, 1) }.isFailure).isTrue()
    }

    @Test fun `recent ciphertext seek avoids replaying a range ignorant response`() {
        val ranges = mutableListOf<String>()
        ProgressiveMatrixTransport(descriptor, ciphertext.size.toLong(), "https://homeserver.example", "test-token", http(ranges, 200)).use {
            var offset = 0
            while (offset < ciphertext.size) {
                val size = minOf(65_536, ciphertext.size - offset)
                it.read(offset.toLong(), size).fill(0)
                offset += size
            }
            val before = it.receivedBytes.get()
            assertThat(it.read(1_048_579, 71)).isEqualTo(plaintext.copyOfRange(1_048_579, 1_048_650))
            assertThat(it.receivedBytes.get()).isEqualTo(before)
            assertThat(ranges).hasSize(1)
        }
    }

    @Test fun `cancel does not drain a socket on caller and close awaits IO disposal`() {
        val caller = Thread.currentThread()
        var disposalThread: Thread? = null
        val source = object : okio.ForwardingSource(Buffer().write(ciphertext)) {
            override fun close() { disposalThread = Thread.currentThread(); super.close() }
        }
        val buffered = source.buffer()
        val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(object : ResponseBody() {
                    override fun contentType() = null
                    override fun contentLength() = ciphertext.size.toLong()
                    override fun source() = buffered
                }).build()
        }.build()
        val transport = ProgressiveMatrixTransport(descriptor, ciphertext.size.toLong(), "https://homeserver.example", "test-token", client)
        transport.read(0, 1)
        transport.cancel()
        assertThat(disposalThread).isNull()
        transport.close()
        assertThat(disposalThread).isNotNull()
        assertThat(disposalThread).isNotSameInstanceAs(caller)
        assertThat(runCatching { transport.read(0, 1) }.isFailure).isTrue()
    }

    @Test fun `range ignorant server still streams before full response without a second request`() {
        val ranges = mutableListOf<String>()
        ProgressiveMatrixTransport(descriptor, ciphertext.size.toLong(), "https://homeserver.example", "test-token", http(ranges, 200)).use {
            assertThat(it.read(0, 23)).isEqualTo(plaintext.copyOfRange(0, 23))
            assertThat(it.receivedBytes.get()).isEqualTo(65_536)
            assertThat(it.read(65_540, 3)).isEqualTo(plaintext.copyOfRange(65_540, 65_543))
            assertThat(ranges).hasSize(1)
        }
    }

    @Test fun `wrong response range truncated body and redirects fail closed`() {
        val cases = listOf(http(mutableListOf(), wrongRange = true), http(mutableListOf(), truncate = true), http(mutableListOf(), 302))
        for (client in cases) {
            ProgressiveMatrixTransport(descriptor, ciphertext.size.toLong(), "https://homeserver.example", "test-token", client).use {
                assertThat(runCatching { it.read(0, 1) }.isFailure).isTrue()
            }
        }
    }

    @Test fun `CTR random offsets match complete decryption across block and counter boundaries`() {
        for (offset in listOf(0, 1, 15, 16, 17, 4095, 65_535, 65_536, 1_048_579)) {
            val slice = ciphertext.copyOfRange(offset, offset + 71)
            assertThat(decryptMatrixRange(slice, key, iv, offset.toLong())).isEqualTo(plaintext.copyOfRange(offset, offset + 71))
        }
    }

    @Test fun `canonical final record length rejects impossible totals and out of bounds indices`() {
        val layout = ProgressiveChunkLayout(65_557L * 2 + 37)
        assertThat(layout.count).isEqualTo(3)
        assertThat(layout.plaintextLength).isEqualTo(65_536L * 2 + 16)
        assertThat(layout.recordOffset(2)).isEqualTo(65_557L * 2)
        assertThat(layout.isFinal(2)).isTrue()
        assertThat(layout.recordLength(2)).isEqualTo(37)
        for (length in listOf(0L, 1L, 20L, 21L, 65_558L)) {
            assertThat(runCatching { ProgressiveChunkLayout(length) }.isFailure).isTrue()
        }
        assertThat(runCatching { layout.recordLength(3) }.isFailure).isTrue()
    }
}
