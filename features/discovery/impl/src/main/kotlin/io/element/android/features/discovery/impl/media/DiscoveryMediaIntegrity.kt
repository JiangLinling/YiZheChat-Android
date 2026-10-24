/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.media

import java.security.MessageDigest

/** Whole-file integrity inside encrypted staging. A successful check is not a local preview grant. */
internal class DiscoveryMediaIntegrity(private val reference: DiscoveryMediaReference) : AutoCloseable {
    private val digest = MessageDigest.getInstance("SHA-256")
    private var received = 0L
    private var finished = false

    fun append(range: DiscoveryMediaRange, bytes: ByteArray) {
        check(!finished)
        try {
            require(range.reference == reference && range.start == received && bytes.size == range.length)
            digest.update(bytes)
            received = Math.addExact(received, bytes.size.toLong())
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    fun finish() {
        check(!finished)
        try {
            require(received == reference.byteLength)
            val actual = digest.digest()
            val expected = reference.sha256.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            try { require(MessageDigest.isEqual(actual, expected)) }
            finally { actual.fill(0); expected.fill(0) }
        } finally { close() }
    }

    override fun close() { finished = true; digest.reset() }
}
