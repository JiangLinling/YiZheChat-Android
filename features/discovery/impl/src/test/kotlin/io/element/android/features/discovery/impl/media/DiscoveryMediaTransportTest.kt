/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.media

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

internal class DiscoveryMediaTransportTest {
    private val reference = DiscoveryMediaReference("1", DiscoveryMediaBinding.ARTICLE_MEDIA, "2", DiscoveryMediaAccess.PRIVATE,
        DiscoveryMediaKind.IMAGE, "image/png", 8, "a".repeat(64))
    private val range = reference.range(2, 3)
    private fun response(body: ByteArray = byteArrayOf(3, 4, 5)): Response.Builder = Response.Builder()
        .request(Request.Builder().url("https://example.invalid/v1/discovery/business/media/read").build())
        .protocol(Protocol.HTTP_1_1).code(206).message("Partial Content")
        .header("Content-Type", "application/octet-stream").header("Content-Length", "3")
        .header("Content-Range", "bytes 2-4/8").header("Accept-Ranges", "bytes")
        .header("X-Content-SHA256", reference.sha256).header("Cache-Control", "no-store, private")
        .body(body.toResponseBody())

    @Test fun `valid exact range returns only its bytes`() {
        response().build().use { assertArrayEquals(byteArrayOf(3, 4, 5), readDiscoveryMediaRange(it, range)) }
    }

    @Test fun `partial and excessive bytes are rejected`() {
        listOf(byteArrayOf(3, 4), byteArrayOf(3, 4, 5, 6)).forEach { body ->
            response(body).build().use { value ->
                assertThrows(IllegalArgumentException::class.java) { readDiscoveryMediaRange(value, range) }
            }
        }
    }

    @Test fun `content version range type compression and duplicate headers cannot be accepted`() {
        listOf(
            response().header("X-Content-SHA256", "b".repeat(64)),
            response().header("Content-Range", "bytes 2-4/9"),
            response().header("Content-Type", "application/json"),
            response().header("Content-Encoding", "gzip"),
            response().addHeader("Content-Length", "3"),
            response().header("Cache-Control", "public, max-age=60"),
        ).forEach { builder -> builder.build().use { value ->
            assertThrows(IllegalArgumentException::class.java) { readDiscoveryMediaRange(value, range) }
        } }
    }

    @Test fun `redirect and whole file success are not valid range responses`() {
        listOf(200, 302).forEach { code -> response().code(code).build().use { value ->
            assertThrows(io.element.android.features.discovery.impl.session.DiscoveryFailure::class.java) { readDiscoveryMediaRange(value, range) }
        } }
    }

    @Test fun `range bounds cannot overflow or exceed cap`() {
        assertThrows(IllegalArgumentException::class.java) { reference.range(-1, 1) }
        assertThrows(IllegalArgumentException::class.java) { reference.range(8, 1) }
        assertThrows(IllegalArgumentException::class.java) { reference.range(7, 2) }
        val large = reference.copy(byteLength = Long.MAX_VALUE)
        assertThrows(IllegalArgumentException::class.java) { large.range(Long.MAX_VALUE - 1, 2) }
        assertThrows(IllegalArgumentException::class.java) { large.range(0, 262145) }
        assertEquals(Long.MAX_VALUE - 1, large.range(Long.MAX_VALUE - 1, 1).endInclusive)
    }
}
