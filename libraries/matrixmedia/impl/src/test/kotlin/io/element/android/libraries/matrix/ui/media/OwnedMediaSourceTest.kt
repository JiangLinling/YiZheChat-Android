/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.ui.media

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.media.MediaFile
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.CancellationException

class OwnedMediaSourceTest {
    @Test fun `file remains leased until its accepted source closes`() {
        val file = TrackedFile()
        val source = file.transferToSource { it }
        assertThat(file.fileCloses).isEqualTo(0)
        assertThat(source.readByte().toInt()).isEqualTo(42)
        source.close()
        source.close()
        assertThat(file.streamCloses).isEqualTo(1)
        assertThat(file.fileCloses).isEqualTo(1)
    }

    @Test fun `opening stream failure returns file even when release fails`() {
        val openFailure = IOException("open")
        val closeFailure = IOException("close")
        val file = TrackedFile(openFailure = openFailure, fileCloseFailure = closeFailure)
        val failure = runCatching { file.transferToSource { error("No stream was acquired") } }.exceptionOrNull()
        assertThat(failure).isSameInstanceAs(openFailure)
        assertThat(failure!!.suppressed.toList()).containsExactly(closeFailure)
        assertThat(file.fileCloses).isEqualTo(1)
    }

    @Test fun `consumer construction failure releases stream and file`() {
        val file = TrackedFile()
        val rejected = IllegalStateException("consumer")
        assertThat(runCatching { file.transferToSource { throw rejected } }.exceptionOrNull()).isSameInstanceAs(rejected)
        assertThat(file.streamCloses).isEqualTo(1)
        assertThat(file.fileCloses).isEqualTo(1)
    }

    @Test fun `cancellation preserves its cause while attempting every acquired release`() {
        val streamFailure = IOException("stream")
        val fileFailure = IOException("file")
        val file = TrackedFile(streamCloseFailure = streamFailure, fileCloseFailure = fileFailure)
        val cancelled = CancellationException("cancelled")
        val failure = runCatching { file.transferToSource { throw cancelled } }.exceptionOrNull()
        assertThat(failure).isSameInstanceAs(cancelled)
        assertThat(cancelled.suppressed.toList()).containsExactly(streamFailure)
        assertThat(streamFailure.suppressed.toList()).containsExactly(fileFailure)
        assertThat(file.streamCloses).isEqualTo(1)
        assertThat(file.fileCloses).isEqualTo(1)
    }

    @Test fun `stream release failure does not skip file release after handoff`() {
        val streamFailure = IOException("stream")
        val file = TrackedFile(streamCloseFailure = streamFailure)
        val source = file.transferToSource { it }
        assertThat(runCatching { source.close() }.exceptionOrNull()).isSameInstanceAs(streamFailure)
        assertThat(file.fileCloses).isEqualTo(1)
    }

    private class TrackedFile(
        val openFailure: Throwable? = null,
        val streamCloseFailure: Throwable? = null,
        val fileCloseFailure: Throwable? = null,
    ) : MediaFile {
        var streamCloses = 0
        var fileCloses = 0
        override fun path(): String = error("Source must not materialize plaintext")
        override fun persist(path: String): Boolean = error("Source must not persist plaintext")
        override fun openInputStream(): ByteArrayInputStream {
            openFailure?.let { throw it }
            return object : ByteArrayInputStream(byteArrayOf(42)) {
                override fun close() {
                    streamCloses++
                    streamCloseFailure?.let { throw it }
                    super.close()
                }
            }
        }
        override fun close() {
            fileCloses++
            fileCloseFailure?.let { throw it }
        }
    }
}
