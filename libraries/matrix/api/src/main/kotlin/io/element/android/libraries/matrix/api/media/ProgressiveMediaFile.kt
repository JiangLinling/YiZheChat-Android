/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.api.media

/** Viewer-owned reader. It has no plaintext path and must not be used as a save/share file. */
interface ProgressiveMediaFile : MediaFile {
    /** Proven disposal of this reader; an unrelated materialized capability cannot stand in for this proof. */
    override val readQuiescence: kotlinx.coroutines.flow.StateFlow<Boolean>
    val isReadQuiescent: Boolean get() = readQuiescence.value
    val plaintextLength: Long
    val receivedCiphertextBytes: Long
    val totalCiphertextBytes: Long
    /** Local-only delivery admission, also checked while playback is using already buffered samples. */
    fun requireAuthorized()
    /** Explicit save/share requests use the original fully verified download path; the viewer retains its owner. */
    suspend fun materialize(): MediaFile
    /** Synchronous decoder-thread read; each returned byte has passed inner authenticated decryption. */
    fun readAt(position: Long, destination: ByteArray, offset: Int, length: Int): Int
}
