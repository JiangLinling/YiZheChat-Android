/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.timeline

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.impl.media.ProtectedMatrixMedia
import io.element.android.security.contentprotection.api.ContentId
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class PreparedAttachmentCleanupTest {
    private val adapter = mockk<MatrixProtectedContentAdapter>()
    private val first = ProtectedMatrixMedia(File("first.yzm"), ContentId("original-media-one"))
    private val second = ProtectedMatrixMedia(File("second.yzm"), ContentId("original-media-two"))
    private val caption = PreparedProtectedContent("encrypted-fixture", ContentId("original-caption"))

    @Test fun `false media result cannot complete cleanup or block other original revocations`() = runTest {
        coEvery { adapter.destroyMedia(first, any()) } returnsMany listOf(false, true)
        coEvery { adapter.destroyMedia(second, any()) } returns true
        coEvery { adapter.destroyPrepared(caption, any()) } returns true
        val cleanup = PreparedAttachmentCleanup(listOf(first, second), caption, adapter)
        assertThat(runCatching { cleanup.release() }.isFailure).isTrue()
        coVerify(exactly = 1) { adapter.destroyMedia(second, any()) }
        coVerify(exactly = 1) { adapter.destroyPrepared(caption, any()) }
        cleanup.release()
        cleanup.release()
        coVerify(exactly = 2) { adapter.destroyMedia(first, any()) }
        coVerify(exactly = 1) { adapter.destroyMedia(second, any()) }
        coVerify(exactly = 1) { adapter.destroyPrepared(caption, any()) }
    }

    @Test fun `caption denial remains pending without repeating already confirmed media revocation`() = runTest {
        coEvery { adapter.destroyMedia(first, any()) } returns true
        coEvery { adapter.destroyPrepared(caption, any()) } returnsMany listOf(false, true)
        val cleanup = PreparedAttachmentCleanup(listOf(first), caption, adapter)
        assertThat(runCatching { cleanup.release() }.isFailure).isTrue()
        cleanup.release()
        coVerify(exactly = 1) { adapter.destroyMedia(first, any()) }
        coVerify(exactly = 2) { adapter.destroyPrepared(caption, any()) }
    }

    @Test fun `caller mutation cannot replace original compensation resources`() = runTest {
        val media = mutableListOf(first, first)
        val cleanup = PreparedAttachmentCleanup(media, null, adapter)
        media.clear()
        media.add(second)
        coEvery { adapter.destroyMedia(first, any()) } returns true
        cleanup.release()
        cleanup.release()
        coVerify(exactly = 1) { adapter.destroyMedia(first, any()) }
        coVerify(exactly = 0) { adapter.destroyMedia(second, any()) }
    }

    @Test fun `cancellation retains original unfinished item and is never treated as its success`() = runTest {
        val cancelled = CancellationException("authority cancelled")
        var calls = 0
        coEvery { adapter.destroyMedia(first, any()) } coAnswers { if (++calls == 1) throw cancelled; true }
        val cleanup = PreparedAttachmentCleanup(listOf(first), null, adapter)
        assertThat(runCatching { cleanup.release() }.exceptionOrNull()).isSameInstanceAs(cancelled)
        cleanup.release()
        coVerify(exactly = 2) { adapter.destroyMedia(first, any()) }
    }

}
