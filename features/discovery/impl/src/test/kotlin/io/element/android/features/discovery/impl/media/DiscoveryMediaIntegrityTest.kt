/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.media

import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

internal class DiscoveryMediaIntegrityTest {
    private val bytes = byteArrayOf(1, 2, 3, 4, 5, 6)
    private val reference = DiscoveryMediaReference("1", DiscoveryMediaBinding.COURSE_MEDIA, "2", DiscoveryMediaAccess.PRIVATE,
        DiscoveryMediaKind.VIDEO, "video/mp4", bytes.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })

    @Test fun `ordered complete ranges match the expected full digest`() {
        val verifier = DiscoveryMediaIntegrity(reference)
        verifier.append(reference.range(0, 2), bytes.copyOfRange(0, 2))
        verifier.append(reference.range(2, 4), bytes.copyOfRange(2, 6))
        verifier.finish()
        assertThrows(IllegalStateException::class.java) { verifier.finish() }
    }

    @Test fun `truncation and changed bytes never finish successfully`() {
        val truncated = DiscoveryMediaIntegrity(reference)
        truncated.append(reference.range(0, 2), bytes.copyOfRange(0, 2))
        assertThrows(IllegalArgumentException::class.java) { truncated.finish() }
        val changed = DiscoveryMediaIntegrity(reference)
        changed.append(reference.range(0, 6), bytes.copyOf().apply { this[3] = 99 })
        assertThrows(IllegalArgumentException::class.java) { changed.finish() }
    }

    @Test fun `wrong object skipped ranges and duplicate ranges poison the verifier`() {
        val wrong = DiscoveryMediaIntegrity(reference)
        assertThrows(IllegalArgumentException::class.java) { wrong.append(reference.copy(objectId = "3").range(0, 6), bytes) }
        assertThrows(IllegalStateException::class.java) { wrong.append(reference.range(0, 6), bytes) }
        val skipped = DiscoveryMediaIntegrity(reference)
        assertThrows(IllegalArgumentException::class.java) { skipped.append(reference.range(2, 4), bytes.copyOfRange(2, 6)) }
        val duplicate = DiscoveryMediaIntegrity(reference)
        duplicate.append(reference.range(0, 2), bytes.copyOfRange(0, 2))
        assertThrows(IllegalArgumentException::class.java) { duplicate.append(reference.range(0, 2), bytes.copyOfRange(0, 2)) }
    }
}
