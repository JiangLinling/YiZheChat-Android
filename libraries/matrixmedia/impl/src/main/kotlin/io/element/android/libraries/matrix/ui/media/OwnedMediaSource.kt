/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.ui.media

import io.element.android.libraries.matrix.api.media.MediaFile
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import okio.source

/** Transfer the file and its stream together, or release both if constructing the consumer fails. */
internal fun <T> MediaFile.transferToSource(accept: (BufferedSource) -> T): T {
    val input = try {
        openInputStream()
    } catch (failure: Throwable) {
        closeAfterFailure(failure) { close() }
        throw failure
    }
    var streamClosed = false
    var fileClosed = false
    // Construction can fail after the consumer has already closed; do not repeat successful releases.
    val release: () -> Unit = {
        var failure: Throwable? = null
        if (!streamClosed) {
            try {
                input.close()
                streamClosed = true
            } catch (error: Throwable) {
                failure = error
            }
        }
        if (!fileClosed) {
            try {
                close()
                fileClosed = true
            } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }
    var source: BufferedSource? = null
    try {
        source = object : ForwardingSource(input.source()) {
            override fun close() = release()
        }.buffer()
        return accept(source)
    } catch (failure: Throwable) {
        // Also covers ImageSource / SourceFetchResult construction, before Coil takes ownership.
        closeAfterFailure(failure) {
            if (source == null) release() else source.close()
        }
        throw failure
    }
}

private inline fun closeAfterFailure(failure: Throwable, release: () -> Unit) {
    try {
        release()
    } catch (cleanup: Throwable) {
        if (failure !== cleanup) failure.addSuppressed(cleanup)
    }
}
