/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.mediaviewer.impl.local.pdf

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.localofficeviewer.api.FilePreviewRuntimeCleanup
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PdfLazyLoadingTest {
    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `opening 642 pages reads only first page geometry and cleanup survives owner cancellation`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val owner = CoroutineScope(SupervisorJob() + dispatcher)
        val descriptor = mockk<ParcelFileDescriptor>(relaxed = true)
        val renderer = mockk<PdfRenderer>(relaxed = true)
        val page = mockk<PdfRenderer.Page>(relaxed = true)
        every { descriptor.statSize } returns 6_760_644L
        every { renderer.pageCount } returns 642
        every { renderer.openPage(0) } returns page
        every { page.width } returns 595
        every { page.height } returns 842
        var ready = false
        val cleanup = mutableListOf<FilePreviewRuntimeCleanup>()
        val manager = PdfRendererManager(descriptor, 1080, owner, { ready = true }, { throw AssertionError(it) }, { renderer }, dispatcher, cleanup::add)

        manager.open()
        advanceUntilIdle()

        assertThat(ready).isTrue()
        assertThat(manager.pdfPages.value.dataOrNull()).hasSize(642)
        verify(exactly = 1) { renderer.openPage(0) }
        verify(exactly = 1) { renderer.openPage(any()) }
        owner.cancel()
        manager.close()
        advanceUntilIdle()
        verify(exactly = 1) { renderer.close() }
        verify(atLeast = 1) { descriptor.close() }
        assertThat(cleanup).containsExactly(FilePreviewRuntimeCleanup(true, true, true))
    }

    @Test
    fun `native renderer close failure cannot become a successful cleanup fact`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val descriptor = mockk<ParcelFileDescriptor>(relaxed = true)
        val renderer = mockk<PdfRenderer>(relaxed = true)
        val page = mockk<PdfRenderer.Page>(relaxed = true)
        every { descriptor.statSize } returns 100L
        every { renderer.pageCount } returns 1
        every { renderer.openPage(0) } returns page
        every { page.width } returns 595
        every { page.height } returns 842
        every { renderer.close() } throws IllegalStateException("native close failed")
        val cleanup = mutableListOf<FilePreviewRuntimeCleanup>()
        val manager = PdfRendererManager(descriptor, 1080, this, {}, {}, { renderer }, dispatcher, cleanup::add)
        manager.open()
        advanceUntilIdle()
        manager.close()
        advanceUntilIdle()
        assertThat(cleanup).hasSize(1)
        assertThat(cleanup.single().isComplete).isFalse()
        verify(atLeast = 1) { descriptor.close() }
    }

    @Test
    fun `a page is not opened until visible and a late native result is recycled on close`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val renderer = mockk<PdfRenderer>()
        val nativePage = mockk<PdfRenderer.Page>(relaxed = true)
        val bitmap = mockk<Bitmap>(relaxed = true)
        every { renderer.openPage(641) } returns nativePage
        every { nativePage.width } returns 595
        every { nativePage.height } returns 842
        var failures = 0
        val page = PdfPage(1080, 641, Mutex(), renderer, this, { failures++ }, 1080, 1529, dispatcher, { _, _ -> bitmap })
        verify(exactly = 0) { renderer.openPage(any()) }
        every { nativePage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) } answers {
            page.close()
        }

        page.load()
        advanceUntilIdle()

        assertThat(page.stateFlow.value).isInstanceOf(PdfPage.State.Loading::class.java)
        assertThat(failures).isEqualTo(0)
        verify(exactly = 1) { renderer.openPage(641) }
        verify(exactly = 1) { bitmap.recycle() }
        verify(exactly = 1) { nativePage.close() }
    }
}
