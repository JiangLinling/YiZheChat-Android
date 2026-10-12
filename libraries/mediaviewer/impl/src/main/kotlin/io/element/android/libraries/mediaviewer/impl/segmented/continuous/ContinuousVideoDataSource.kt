/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.mediaviewer.impl.segmented.continuous

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.io.InterruptedIOException

/** Presents init+all fragments as one virtual fMP4. Media3 never opens the network or an unverified resource. */
internal class ContinuousVideoDataSource(
    private val expectedUri: Uri,
    private val createBuffer: () -> ContinuousVideoBuffer,
    private val onFailure: () -> Unit,
    private val onReleased: (ContinuousVideoBuffer) -> Unit,
    private val retainAuthorityAtEnd: Boolean = false,
) : BaseDataSource(false) {
    constructor(expectedUri: Uri, buffer: ContinuousVideoBuffer, onFailure: () -> Unit, onReleased: (ContinuousVideoBuffer) -> Unit) :
        this(expectedUri, { buffer }, onFailure, onReleased)

    private var buffer: ContinuousVideoBuffer? = null
    private var retainedAtEnd: ContinuousVideoBuffer? = null
    private var opened = false
    private var position = 0L
    private var remaining = 0L

    override fun getUri(): Uri? = expectedUri.takeIf { opened }

    override fun open(dataSpec: DataSpec): Long {
        if (opened || buffer != null || dataSpec.uri != expectedUri) {
            throw IOException("Invalid continuous video source or position.")
        }
        transferInitializing(dataSpec)
        // ProgressiveMediaPeriod reuses its DataSource across seeks. Reclaim the old window before
        // acquiring the replacement, rather than reusing a closed reader or accumulating retained EOF windows.
        retainedAtEnd?.let { old ->
            runBlocking { old.closeAndAwait() }
            onReleased(old)
            retainedAtEnd = null
        }
        val buffer = createBuffer().also { this.buffer = it }
        if (dataSpec.position !in 0..buffer.layout.length) throw IOException("Invalid continuous video position.")
        position = dataSpec.position
        val available = buffer.layout.length - position
        if (dataSpec.length != C.LENGTH_UNSET.toLong() && dataSpec.length !in 0..available) {
            throw IOException("Continuous video range exceeds the authenticated manifest.")
        }
        remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) available else dataSpec.length
        try {
            if (remaining > 0) runBlocking { buffer.prepare(position) }
            opened = true
            transferStarted(dataSpec)
            return remaining
        } catch (failure: Throwable) {
            buffer.beginClose()
            reportFailure(failure)
            throw IOException("Continuous video preparation failed.", failure)
        }
    }

    override fun read(destination: ByteArray, offset: Int, length: Int): Int {
        if (!opened) throw IOException("Continuous video source is not open.")
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        return try {
            val copied = runBlocking { requireNotNull(buffer).read(position, destination, offset, minOf(length.toLong(), remaining).toInt()) }
            if (copied <= 0) throw IOException("Continuous video ended before the declared length.")
            position = Math.addExact(position, copied.toLong())
            remaining -= copied
            bytesTransferred(copied)
            copied
        } catch (failure: Throwable) {
            buffer?.beginClose()
            reportFailure(failure)
            throw IOException("Continuous authenticated video read failed.", failure)
        }
    }

    private fun reportFailure(failure: Throwable) {
        // Media3 interrupts its old loader when seeking/releasing. That closes this reader generation;
        // it must not revoke the shared owner needed by the replacement reader.
        if (failure !is InterruptedException && failure !is InterruptedIOException && !Thread.currentThread().isInterrupted) {
            onFailure()
        }
    }

    override fun close() {
        val current = buffer ?: return
        // EOF ends loading before buffered samples finish playing. Keep the window's authority probes
        // owned by the shared owner until a new reader replaces it or the decoder is stopped.
        if (retainAuthorityAtEnd && opened && remaining == 0L && !current.isClosed) {
            retainedAtEnd = current
            buffer = null
            opened = false
            transferEnded()
            return
        }
        current.beginClose()
        try {
            runBlocking { current.closeAndAwait() }
            onReleased(current)
            buffer = null
        } catch (failure: Throwable) {
            onFailure()
            throw failure
        } finally {
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }
}
