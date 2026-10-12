/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.mediaviewer.impl.local.video

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import io.element.android.libraries.matrix.api.media.ProgressiveMediaFile
import java.io.IOException
import java.io.InterruptedIOException
import kotlinx.coroutines.CancellationException
import timber.log.Timber

@UnstableApi
internal class ProgressiveVideoDataSource(private val file: ProgressiveMediaFile) : BaseDataSource(true) {
    private var uri: Uri? = null
    private var position = 0L
    private var remaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        try { file.requireAuthorized() } catch (failure: Exception) { recordFailure("open", failure); throw IOException("Protected video permission is unavailable.") }
        require(dataSpec.position in 0..file.plaintextLength)
        position = dataSpec.position
        remaining = file.plaintextLength - position
        if (dataSpec.length != C.LENGTH_UNSET.toLong()) remaining = minOf(remaining, dataSpec.length)
        uri = dataSpec.uri
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val started = android.os.SystemClock.elapsedRealtime()
        val count = try { file.readAt(position, buffer, offset, minOf(length.toLong(), remaining).toInt()) }
        catch (failure: Exception) {
            if (failure is InterruptedException || failure is InterruptedIOException || failure is CancellationException) {
                // Media3 retires its loader on seek. The replacement still belongs to this viewer.
                throw InterruptedIOException("Protected video decoder read was cancelled.").also { it.initCause(failure) }
            }
            recordFailure("read", failure)
            file.close()
            throw IOException("Protected video read was rejected.", failure)
        }
        if (count <= 0) { file.close(); throw IOException("Protected video ended before its declared length.") }
        val elapsed = android.os.SystemClock.elapsedRealtime() - started
        if (elapsed >= 100) Timber.tag("YiZheProgressiveVideo").i("read_timing elapsed_ms=%d bytes=%d", elapsed, count)
        position += count
        remaining -= count
        bytesTransferred(count)
        return count
    }

    private fun recordFailure(phase: String, failure: Exception) {
        // Only fixed stages and exception classes; never log a URI, message, identity or key.
        Timber.tag("YiZheProgressiveVideo").w("read_failure phase=%s type=%s cause_type=%s",
            phase, failure.javaClass.simpleName, failure.cause?.javaClass?.simpleName ?: "none")
    }

    override fun getUri(): Uri? = uri
    override fun close() {
        uri = null
        if (opened) { opened = false; transferEnded() }
        // ExoPlayer closes/reopens a data source when seeking. Only the existing viewer owner closes the file capability.
    }
}
