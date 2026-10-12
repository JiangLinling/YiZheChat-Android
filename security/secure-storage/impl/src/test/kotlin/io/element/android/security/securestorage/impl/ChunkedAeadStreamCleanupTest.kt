/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.securestorage.impl

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.OutputStream
import javax.crypto.spec.SecretKeySpec

class ChunkedAeadStreamCleanupTest {
    private val key = SecretKeySpec(ByteArray(32) { 7 }, "AES")
    private val nonce = ByteArray(FILE_NONCE_PREFIX_LENGTH_BYTES) { 3 }
    private val core = "test-core".encodeToByteArray()

    private fun output(sink: OutputStream) = ChunkedAeadOutputStream(DataOutputStream(sink), key, core, nonce, 4)
    private fun input(bytes: ByteArray) = ChunkedAeadInputStream(DataInputStream(ByteArrayInputStream(bytes)), key, core, nonce, 4)

    @Test
    fun `discard clears partial plaintext without publishing a terminal record`() {
        val sink = ByteArrayOutputStream()
        val stream = output(sink)
        stream.write(byteArrayOf(1, 2, 3))
        val retained = buffer(stream, "buffer")
        stream.discard()
        assertThat(retained.all { it == 0.toByte() }).isTrue()
        assertThat(sink.size()).isEqualTo(0)
        assertThat(runCatching { stream.write(4) }.isFailure).isTrue()
        assertThat(runCatching { stream.finish() }.isFailure).isTrue()
    }

    @Test
    fun `ciphertext write failure clears owned plaintext and forbids nonce reuse`() {
        val original = IOException("test-sink-failure")
        val stream = output(object : OutputStream() {
            override fun write(value: Int) { throw original }
        })
        val retained = buffer(stream, "buffer")
        assertThat(runCatching { stream.write(byteArrayOf(1, 2, 3, 4)) }.exceptionOrNull()).isSameInstanceAs(original)
        assertThat(retained.all { it == 0.toByte() }).isTrue()
        assertThat(runCatching { stream.write(5) }.isFailure).isTrue()
        assertThat(runCatching { stream.finish() }.isFailure).isTrue()
    }

    @Test
    fun `successful finish remains idempotent and clears the partial plaintext buffer`() {
        val sink = ByteArrayOutputStream()
        val stream = output(sink)
        stream.write(byteArrayOf(1, 2, 3))
        val retained = buffer(stream, "buffer")
        stream.finish()
        val originalBytes = sink.toByteArray()
        stream.finish()
        assertThat(sink.toByteArray()).isEqualTo(originalBytes)
        assertThat(retained.all { it == 0.toByte() }).isTrue()
        input(originalBytes).use { assertThat(it.readBytes()).isEqualTo(byteArrayOf(1, 2, 3)) }
    }

    @Test
    fun `advancing and closing input zero each owned plaintext generation`() {
        val sink = ByteArrayOutputStream()
        output(sink).apply { write(byteArrayOf(1, 2, 3, 4, 5, 6)); finish() }
        val stream = input(sink.toByteArray())
        assertThat(stream.read()).isEqualTo(1)
        val first = buffer(stream, "plaintext")
        repeat(4) { stream.read() }
        assertThat(first.all { it == 0.toByte() }).isTrue()
        val second = buffer(stream, "plaintext")
        stream.close()
        assertThat(second.all { it == 0.toByte() }).isTrue()
        assertThat(runCatching { stream.read() }.isFailure).isTrue()
        // The store still authenticates any unread tail after a callback closes its borrowed stream.
        stream.verifyToEnd()
        stream.close()
        assertThat(stream.plaintextLength).isEqualTo(6)
    }

    @Test
    fun `invalid following record clears prior plaintext and cannot be read again`() {
        val sink = ByteArrayOutputStream()
        output(sink).apply { write(byteArrayOf(1, 2, 3, 4)); finish() }
        val truncated = sink.toByteArray().dropLast(1).toByteArray()
        val stream = input(truncated)
        repeat(4) { stream.read() }
        val retained = buffer(stream, "plaintext")
        assertThat(runCatching { stream.read() }.isFailure).isTrue()
        assertThat(retained.all { it == 0.toByte() }).isTrue()
        assertThat(runCatching { stream.read() }.isFailure).isTrue()
        assertThat(runCatching { stream.verifyToEnd() }.isFailure).isTrue()
    }

    // Hold the actual owned array across cleanup, rather than merely checking a replacement empty array.
    private fun buffer(owner: Any, field: String): ByteArray = owner.javaClass.getDeclaredField(field).let {
        it.isAccessible = true
        it.get(owner) as ByteArray
    }
}
