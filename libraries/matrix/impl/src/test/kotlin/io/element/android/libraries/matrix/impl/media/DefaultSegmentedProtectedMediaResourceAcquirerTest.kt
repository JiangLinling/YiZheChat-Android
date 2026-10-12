/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.matrix.impl.media

import com.google.common.truth.Truth.assertThat
import io.element.android.security.contentprotection.api.SegmentedMatrixEncryptedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.reflect.KClass

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultSegmentedProtectedMediaResourceAcquirerTest {
    @Test
    fun `outer AES CTR descriptor decrypts exact ciphertext`() {
        val key = ByteArray(32) { (it + 1).toByte() }
        val iv = ByteArray(16) { (it + 17).toByte() }
        val plaintext = "YZMF0001-authenticated-frame".encodeToByteArray()
        val ciphertext = Cipher.getInstance("AES/CTR/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            doFinal(plaintext)
        }
        val descriptor = SegmentedMatrixEncryptedFile(
            version = "v2",
            algorithm = "A256CTR",
            keyType = "oct",
            keyOperations = setOf("encrypt", "decrypt"),
            extractable = true,
            keyBase64Url = Base64.getUrlEncoder().withoutPadding().encodeToString(key),
            ivBase64 = Base64.getEncoder().withoutPadding().encodeToString(iv),
        )

        val result = decryptSegmentedMatrixOuter(ciphertext, descriptor)

        assertThat(result).isEqualTo(plaintext)
        key.fill(0)
        iv.fill(0)
        plaintext.fill(0)
        ciphertext.fill(0)
        result.fill(0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unknown outer algorithm fails closed`() {
        decryptSegmentedMatrixOuter(
            byteArrayOf(1),
            SegmentedMatrixEncryptedFile("v2", "A128CTR", "oct", setOf("encrypt", "decrypt"), true, "AA", "AA"),
        )
    }

    @Test
    fun `media format version one accepts authenticated policy version two`() {
        requireSegmentedPolicyBinding(
            contentContractId = "contract-identifier-0001",
            contentPolicyVersion = 2L,
            inspectedPolicyId = "contract-identifier-0001",
            inspectedPolicyVersion = 2L,
        )
    }

    @Test(expected = SecurityException::class)
    fun `policy version mismatch fails closed`() {
        requireSegmentedPolicyBinding(
            contentContractId = "contract-identifier-0001",
            contentPolicyVersion = 2L,
            inspectedPolicyId = "contract-identifier-0001",
            inspectedPolicyVersion = 3L,
        )
    }

    @Test
    fun `encrypted file diagnostic string redacts key and iv`() {
        val descriptor = SegmentedMatrixEncryptedFile(
            "v2",
            "A256CTR",
            "oct",
            setOf("decrypt"),
            true,
            "secret-key",
            "secret-iv",
        )

        assertThat(descriptor.toString()).doesNotContain("secret-key")
        assertThat(descriptor.toString()).doesNotContain("secret-iv")
    }

    @Test
    fun `hash mismatch clears the owned ciphertext buffer`() {
        var owned: ByteArray? = null

        val failure = runCatching {
            readVerifiedCiphertext(
                input = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)),
                expectedLength = 4L,
                expectedSha256Hex = "0".repeat(64),
                onBufferAllocated = { owned = it },
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(SecurityException::class.java)
        assertThat(requireNotNull(owned).all { it == 0.toByte() }).isTrue()
    }

    @Test
    fun `read failure clears the partially filled ciphertext buffer`() {
        var owned: ByteArray? = null
        val failingInput = object : InputStream() {
            private var first = true
            override fun read(): Int = throw IOException("read failed")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (!first) throw IOException("read failed")
                first = false
                buffer[offset] = 7
                buffer[offset + 1] = 8
                return 2
            }
        }

        val failure = runCatching {
            readVerifiedCiphertext(
                input = failingInput,
                expectedLength = 4L,
                expectedSha256Hex = "0".repeat(64),
                onBufferAllocated = { owned = it },
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IOException::class.java)
        assertThat(requireNotNull(owned).all { it == 0.toByte() }).isTrue()
    }

    @Test
    fun `cancelling after headers closes a blocked response body and call`() = runTest {
        val call = FakeCall()
        val body = BlockingResponseBody()
        val response = response(call.request(), body)
        val consuming = async(Dispatchers.IO) {
            response.consumeCancellable(call) { delivered ->
                requireNotNull(delivered.body).byteStream().read()
                Unit
            }
        }
        assertThat(body.readStarted.await(5, TimeUnit.SECONDS)).isTrue()

        consuming.cancelAndJoin()

        assertThat(call.isCanceled()).isTrue()
        assertThat(body.closed.get()).isTrue()
    }

    @Test
    fun `cancelling a partially filled blocked response clears the same owned buffer`() = runTest {
        val call = FakeCall()
        val body = BlockingResponseBody(prefix = byteArrayOf(7, 8))
        val response = response(call.request(), body)
        val allocated = CountDownLatch(1)
        var owned: ByteArray? = null
        val consuming = async(Dispatchers.IO) {
            response.consumeCancellable(call) { delivered ->
                readVerifiedCiphertext(
                    input = requireNotNull(delivered.body).byteStream(),
                    expectedLength = 4L,
                    expectedSha256Hex = "0".repeat(64),
                    onBufferAllocated = {
                        owned = it
                        allocated.countDown()
                    },
                )
            }
        }
        assertThat(allocated.await(5, TimeUnit.SECONDS)).isTrue()
        assertThat(body.readStarted.await(5, TimeUnit.SECONDS)).isTrue()

        consuming.cancelAndJoin()

        assertThat(call.isCanceled()).isTrue()
        assertThat(body.closed.get()).isTrue()
        assertThat(requireNotNull(owned).all { it == 0.toByte() }).isTrue()
    }

    @Test
    fun `cancelling after onResponse but before continuation resume closes response`() = runTest {
        val call = FakeCall()
        val body = BlockingResponseBody(blockReads = false)
        val waiting = async { call.awaitResponseCancellable() }
        runCurrent()

        call.respond(response(call.request(), body))
        waiting.cancel()
        runCurrent()

        assertThat(call.isCanceled()).isTrue()
        assertThat(body.closed.get()).isTrue()
    }

    private fun response(request: Request, body: ResponseBody) = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body(body)
        .build()

    private class BlockingResponseBody(
        private val blockReads: Boolean = true,
        private val prefix: ByteArray = byteArrayOf(),
    ) : ResponseBody() {
        val readStarted = CountDownLatch(1)
        val closed = AtomicBoolean()
        private val release = CountDownLatch(1)
        private var prefixDelivered = false
        private val bufferedSource: BufferedSource = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (!prefixDelivered && prefix.isNotEmpty()) {
                    prefixDelivered = true
                    sink.write(prefix)
                    return prefix.size.toLong()
                }
                readStarted.countDown()
                if (blockReads) {
                    release.await()
                    throw IOException("response body closed")
                }
                return -1L
            }

            override fun timeout(): Timeout = Timeout.NONE

            override fun close() {
                closed.set(true)
                release.countDown()
            }
        }.buffer()

        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = -1L
        override fun source(): BufferedSource = bufferedSource
    }

    private class FakeCall(
        private val request: Request = Request.Builder().url("https://matrix.example.invalid/media").build(),
    ) : Call {
        private var callback: Callback? = null
        private val executed = AtomicBoolean()
        private val canceled = AtomicBoolean()

        override fun request(): Request = request
        override fun execute(): Response = error("synchronous execution is forbidden")
        override fun enqueue(responseCallback: Callback) {
            check(executed.compareAndSet(false, true))
            callback = responseCallback
        }

        fun respond(response: Response) {
            requireNotNull(callback).onResponse(this, response)
        }

        override fun cancel() {
            canceled.set(true)
        }

        override fun isExecuted(): Boolean = executed.get()
        override fun isCanceled(): Boolean = canceled.get()
        override fun timeout(): Timeout = Timeout.NONE
        override fun <T : Any> tag(type: KClass<T>): T? = null
        override fun <T> tag(type: Class<out T>): T? = null
        override fun <T : Any> tag(type: KClass<T>, computeIfAbsent: () -> T): T = computeIfAbsent()
        override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T = computeIfAbsent()
        override fun clone(): Call = FakeCall(request)
    }
}
