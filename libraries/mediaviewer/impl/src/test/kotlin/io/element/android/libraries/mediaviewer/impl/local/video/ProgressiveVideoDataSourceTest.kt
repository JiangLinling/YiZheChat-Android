/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.mediaviewer.impl.local.video

import android.net.Uri
import androidx.media3.datasource.DataSpec
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.media.ProgressiveMediaFile
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.InterruptedIOException

@RunWith(RobolectricTestRunner::class)
class ProgressiveVideoDataSourceTest {
    @Test fun `cancelled loader can seek using the original viewer capability`() {
        for (failure in listOf(InterruptedException(), InterruptedIOException(), CancellationException())) {
            val file = mockk<ProgressiveMediaFile>(relaxed = true)
            every { file.plaintextLength } returns 100L
            every { file.readAt(any(), any(), any(), any()) } throws failure
            val old = ProgressiveVideoDataSource(file)
            old.open(DataSpec(Uri.parse("yizhe-progressive-video://fixture")))
            assertThat(runCatching { old.read(ByteArray(4), 0, 4) }.exceptionOrNull())
                .isInstanceOf(InterruptedIOException::class.java)
            old.close()
            every { file.readAt(40L, any(), 0, 4) } returns 4
            val replacement = ProgressiveVideoDataSource(file)
            replacement.open(DataSpec.Builder().setUri("yizhe-progressive-video://fixture").setPosition(40).build())
            assertThat(replacement.read(ByteArray(4), 0, 4)).isEqualTo(4)
            replacement.close()
            verify(exactly = 0) { file.close() }
        }
    }

    @Test fun `authentication rejection still closes the viewer capability`() {
        val file = mockk<ProgressiveMediaFile>(relaxed = true)
        every { file.plaintextLength } returns 100L
        every { file.readAt(any(), any(), any(), any()) } throws SecurityException()
        val source = ProgressiveVideoDataSource(file)
        source.open(DataSpec(Uri.parse("yizhe-progressive-video://fixture")))
        assertThat(runCatching { source.read(ByteArray(4), 0, 4) }.isFailure).isTrue()
        verify(exactly = 1) { file.close() }
    }
}
