/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaviewer.impl.segmented.bridge

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException

@OptIn(UnstableApi::class)
internal class SecureSegmentDataSource internal constructor(
    private val session: SegmentedPlaybackSession,
    private val generation: Long,
) : BaseDataSource(false) {
    private var opened = false
    private var closed = false
    private var currentUri: Uri? = null
    private var position = 0L
    private var bytesRemaining = 0L

    override fun open(dataSpec: DataSpec): Long {
        if (opened || closed) throw IOException("Segmented DataSource is not reusable.")
        transferInitializing(dataSpec)
        if (dataSpec.uri != session.uri) throw IOException("Segmented DataSource URI does not match its session.")
        val totalLength = session.lengthFor(generation)
        if (dataSpec.position < 0L || dataSpec.position > totalLength) {
            throw IOException("Segmented DataSource position is outside the prepared source.")
        }
        val available = totalLength - dataSpec.position
        val requested = if (dataSpec.length == C.LENGTH_UNSET.toLong()) available else dataSpec.length
        if (requested < 0L || requested > available) {
            throw IOException("Segmented DataSource length is outside the prepared source.")
        }
        currentUri = dataSpec.uri
        position = dataSpec.position
        bytesRemaining = requested
        opened = true
        transferStarted(dataSpec)
        return requested
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!opened || closed) throw IOException("Segmented DataSource is not open.")
        if (length == 0) return 0
        if (offset < 0 || length < 0 || offset > buffer.size - length) {
            throw IOException("Segmented DataSource destination range is invalid.")
        }
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val requestLength = minOf(length.toLong(), bytesRemaining).toInt()
        return try {
            val copied = session.read(generation, position, buffer, offset, requestLength)
            if (copied <= 0 || copied > requestLength) throw IOException("Segmented DataSource returned an invalid count.")
            position += copied
            bytesRemaining -= copied
            bytesTransferred(copied)
            copied
        } catch (failure: IOException) {
            session.requestClose(SegmentedPlaybackCloseCause.READ_FAILURE)
            throw failure
        }
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        if (closed) return
        closed = true
        currentUri = null
        if (opened) {
            opened = false
            transferEnded()
        }
        session.requestClose(SegmentedPlaybackCloseCause.DATA_SOURCE_CLOSED)
    }

    internal class Factory(
        private val session: SegmentedPlaybackSession,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = session.issueDataSource()
    }
}
