/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.media

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.media.MediaSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class MatrixEncryptedMediaTransportTest {
    private val key = ByteArray(32) { 7 }
    private val iv = ByteArray(16) { 3 }
    private val frame = "YZMF0001-test-inner-encrypted-frame".encodeToByteArray()
    private val ciphertext = Cipher.getInstance("AES/CTR/NoPadding").run {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(this@MatrixEncryptedMediaTransportTest.iv)); doFinal(frame)
    }
    private fun source(url: String = "mxc://media.example/voice", hash: ByteArray = MessageDigest.getInstance("SHA-256").digest(ciphertext)) = MediaSource(
        url, """{"file":{"url":"$url","v":"v2","key":{"alg":"A256CTR","kty":"oct","ext":true,"key_ops":["encrypt","decrypt"],"k":"${Base64.getUrlEncoder().withoutPadding().encodeToString(key)}"},"iv":"${Base64.getEncoder().withoutPadding().encodeToString(iv)}","hashes":{"sha256":"${Base64.getEncoder().withoutPadding().encodeToString(hash)}"}}}""",
    )

    private fun client(
        bytes: ByteArray = ciphertext,
        length: Long = bytes.size.toLong(),
        code: Int = 200,
        inspect: (okhttp3.Request) -> Unit = {},
    ) = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).addInterceptor { chain ->
        inspect(chain.request())
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("test")
            .header("Location", "https://untrusted.example/never-follow")
            .body(object : ResponseBody() {
                override fun contentType() = null
                override fun contentLength() = length
                override fun source() = Buffer().write(bytes)
            }).build()
    }.build()

    @Test fun `encrypted voice verifies outer bytes and decrypts only in memory on the current origin`() = runTest {
        var requests = 0
        val http = client(inspect = {
            requests++
            assertThat(it.url.toString()).isEqualTo("https://homeserver.example:18443/_matrix/client/v1/media/download/media.example/voice")
            assertThat(it.header("Authorization")).isEqualTo("Bearer test-token")
            assertThat(it.header("Cache-Control")).isEqualTo("no-store")
        })
        val actual = loadUncachedEncryptedMatrixContent(source(), "https://homeserver.example:18443", "test-token", http)
        assertThat(actual).isEqualTo(frame)
        assertThat(requests).isEqualTo(1)
        assertThat(http.cache).isNull()
        actual.fill(0)
    }

    @Test fun `wrong hash truncation extra bytes unknown oversized length and redirect fail closed`() = runTest {
        val cases = listOf(
            source(hash = ByteArray(32)) to client(),
            source() to client(bytes = ciphertext.copyOf(ciphertext.size - 1), length = ciphertext.size.toLong()),
            source() to client(bytes = ciphertext + byteArrayOf(1), length = ciphertext.size.toLong()),
            source() to client(length = -1),
            source() to client(length = 64L * 1024 * 1024 + 1),
            source() to client(code = 302),
        )
        for ((source, http) in cases) {
            assertThat(runCatching { loadUncachedEncryptedMatrixContent(source, "https://homeserver.example", "test-token", http) }.isFailure).isTrue()
        }
    }

    @Test fun `plain mismatched or invalid locator never issues a network request`() = runTest {
        var requests = 0
        val http = client(inspect = { requests++ })
        val invalid = listOf(
            MediaSource("mxc://media.example/plain"),
            MediaSource("mxc://media.example/different", source().json),
            source("mxc://media.example/voice#fragment"),
            source("mxc://media.example/voice?query"),
            source("https://media.example/voice"),
        )
        for (source in invalid) {
            assertThat(runCatching { loadUncachedEncryptedMatrixContent(source, "https://homeserver.example", "test-token", http) }.isFailure).isTrue()
        }
        assertThat(runCatching { loadUncachedEncryptedMatrixContent(source(), "http://homeserver.example", "test-token", http) }.isFailure).isTrue()
        assertThat(requests).isEqualTo(0)
    }

    @Test fun `segment exact length and original maximum remain enforced`() = runTest {
        val hash = MessageDigest.getInstance("SHA-256").digest(ciphertext).joinToString("") { "%02x".format(it) }
        assertThat(runCatching { fetchVerifiedMatrixCiphertext(client(), source().safeUrl, "https://homeserver.example", "test-token", hash, ciphertext.size + 1L, 2_200_000L) }.isFailure).isTrue()
        assertThat(runCatching { fetchVerifiedMatrixCiphertext(client(), source().safeUrl, "https://homeserver.example", "test-token", hash, 2_200_001L, 2_200_000L) }.isFailure).isTrue()
    }

    @Test fun `prompt cancellation while returning ciphertext or frame clears the undispatched result`() = runTest {
        for (value in listOf(ciphertext.copyOf(), frame.copyOf())) {
            val ioQueue = ArrayDeque<Runnable>()
            val ioDispatcher = object : CoroutineDispatcher() {
                override fun dispatch(context: CoroutineContext, block: Runnable) { ioQueue.addLast(block) }
            }
            var delivered = false
            val job = async {
                withOwnedMediaIo(ioDispatcher, cleanup = { it.fill(0) }) { value }
                delivered = true
            }
            runCurrent()
            assertThat(ioQueue.size).isEqualTo(1)
            ioQueue.removeFirst().run() // Return is queued on caller dispatcher, which has not resumed.
            job.cancel()
            runCurrent()
            assertThat(delivered).isFalse()
            assertThat(value).isEqualTo(ByteArray(value.size))
        }
    }
}
