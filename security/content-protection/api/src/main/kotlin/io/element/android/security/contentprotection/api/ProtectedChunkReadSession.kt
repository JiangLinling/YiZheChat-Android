/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.contentprotection.api

import java.io.Closeable

/** Opaque, process-local permission to authenticate individual records; never whole-object verification evidence. */
interface ProtectedChunkReadSession : Closeable {
    /** Local expiry, tombstone and device binding checks. This does not repeat remote key release. */
    suspend fun requireAuthorized()
    /** Live local delivery check for an already authenticated decoder staging buffer. */
    suspend fun requireBufferedReadAuthorized() = requireAuthorized()

    /** Returns at most 64 KiB, only after authenticating the header, index, final flag and exact record length. */
    suspend fun decryptChunk(index: Long, record: ByteArray, expectedPlainLength: Int, finalChunk: Boolean): ByteArray
}
