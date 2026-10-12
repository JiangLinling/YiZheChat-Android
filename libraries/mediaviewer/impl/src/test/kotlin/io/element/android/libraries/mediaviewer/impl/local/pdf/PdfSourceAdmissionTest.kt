/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.mediaviewer.impl.local.pdf

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.localofficeviewer.api.FilePreviewLeaseReleaseReason
import io.element.android.libraries.localofficeviewer.api.FilePreviewRuntimeCleanup
import io.element.android.libraries.mediaviewer.impl.R
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PdfSourceAdmissionTest {
    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `source check rejects unavailable changed or unknown metadata without changing geometry limits`() {
        listOf(-1L, 0L, 2971L, 2973L).forEach { size ->
            assertThrows(PdfSourceUnavailableException::class.java) {
                PdfProtectedSourceAccess({ 2972L }, { size }).check()
            }
        }
        assertThrows(PdfSourceUnavailableException::class.java) {
            PdfProtectedSourceAccess({ 2972L }, { throw SecurityException("synthetic denial") }).check()
        }
        assertThrows(PdfSourceUnavailableException::class.java) {
            PdfProtectedSourceAccess({ throw SecurityException("descriptor unavailable") }, { 2972L }).check()
        }
        PdfProtectedSourceAccess({ 2972L }, { 2972L }).check()
        assertThat(PdfSourceUnavailableException().pdfFailureMessageResource()).isEqualTo(R.string.local_pdf_source_unavailable)
        assertThat(PdfSourceUnavailableException().toFilePreviewLeaseReleaseReason())
            .isEqualTo(FilePreviewLeaseReleaseReason.UNDERLYING_UNAVAILABLE)
    }

    @Test
    fun `current source still rejects invalid document geometry with exact diagnostic parameters`() {
        val error = assertThrows(PdfPreviewPolicyException.InvalidPageGeometry::class.java) {
            checkedPdfPagePlan(0, 842, 1080, 1, "visible_geometry") {}
        }
        assertThat(error.pageWidth).isEqualTo(0)
        assertThat(error.pageHeight).isEqualTo(842)
        assertThat(error.requestedWidth).isEqualTo(1080)
        assertThat(error.reason).isEqualTo("non_positive")
        assertThrows(PdfPreviewPolicyException.InvalidPageGeometry::class.java) {
            checkedPdfPagePlan(1, 101, 1080, 1, "visible_geometry") {}
        }
    }

    @Test
    fun `source rejection after native open takes precedence over zero geometry`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val renderer = mockk<PdfRenderer>()
        val nativePage = mockk<PdfRenderer.Page>(relaxed = true)
        var authorized = true
        every { renderer.openPage(1) } answers { authorized = false; nativePage }
        every { nativePage.width } returns 0
        every { nativePage.height } returns 0
        val failures = mutableListOf<Throwable>()
        val page = PdfPage(1080, 1, Mutex(), renderer, this, failures::add, 1080, 1529, dispatcher,
            sourceAccessCheck = { if (!authorized) throw PdfSourceUnavailableException() })
        page.load()
        advanceUntilIdle()
        assertThat(failures.single()).isInstanceOf(PdfSourceUnavailableException::class.java)
        verify(exactly = 1) { nativePage.close() }
        verify(exactly = 0) { nativePage.render(any<Bitmap>(), null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
    }

    @Test
    fun `revocation inside native render recycles bitmap and cannot publish a loaded page`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val renderer = mockk<PdfRenderer>()
        val nativePage = mockk<PdfRenderer.Page>(relaxed = true)
        val bitmap = mockk<Bitmap>(relaxed = true)
        every { renderer.openPage(1) } returns nativePage
        every { nativePage.width } returns 595
        every { nativePage.height } returns 842
        var authorized = true
        every { nativePage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) } answers { authorized = false }
        val failures = mutableListOf<Throwable>()
        val page = PdfPage(1080, 1, Mutex(), renderer, this, failures::add, 1080, 1529, dispatcher,
            bitmapFactory = { _, _ -> bitmap }, sourceAccessCheck = { if (!authorized) throw PdfSourceUnavailableException() })
        page.load()
        advanceUntilIdle()
        assertThat(failures.single()).isInstanceOf(PdfSourceUnavailableException::class.java)
        assertThat(page.stateFlow.value).isInstanceOf(PdfPage.State.Loading::class.java)
        verify(exactly = 1) { bitmap.recycle() }
        verify(exactly = 1) { nativePage.close() }
    }

    @Test
    fun `revoked source is rejected before opening a renderer and descriptor cleanup completes`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val descriptor = mockk<ParcelFileDescriptor>(relaxed = true)
        val renderer = mockk<PdfRenderer>(relaxed = true)
        val failures = mutableListOf<Throwable>()
        val cleanup = mutableListOf<FilePreviewRuntimeCleanup>()
        val manager = PdfRendererManager(descriptor, 1080, this, {}, failures::add, { renderer }, dispatcher, cleanup::add,
            sourceAccessCheck = { throw PdfSourceUnavailableException() })
        manager.open()
        advanceUntilIdle()
        assertThat(failures.single()).isInstanceOf(PdfSourceUnavailableException::class.java)
        verify(exactly = 0) { renderer.openPage(any()) }
        verify(atLeast = 1) { descriptor.close() }
        assertThat(cleanup.single().isComplete).isTrue()
    }

    @Test
    fun `ten minutes of valid source access retains lazy three page geometry and renders middle page`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val descriptor = mockk<ParcelFileDescriptor>(relaxed = true)
        val renderer = mockk<PdfRenderer>(relaxed = true)
        val nativePage = mockk<PdfRenderer.Page>(relaxed = true)
        every { descriptor.statSize } returns 2972L
        every { renderer.pageCount } returns 3
        every { renderer.openPage(any()) } returns nativePage
        every { nativePage.width } returns 595
        every { nativePage.height } returns 842
        val cleanup = mutableListOf<FilePreviewRuntimeCleanup>()
        var checks = 0
        val manager = PdfRendererManager(descriptor, 1080, this, {}, { throw AssertionError(it) }, { renderer }, dispatcher, cleanup::add,
            sourceAccessCheck = { checks++ })
        manager.open()
        runCurrent()
        advanceTimeBy(600_000L)
        runCurrent()
        val pages = checkNotNull(manager.pdfPages.value.dataOrNull())
        assertThat(pages).hasSize(3)
        assertThat(checks).isAtLeast(602)
        verify(exactly = 1) { renderer.openPage(any()) }
        // Exercise deferred page loading with a mocked bitmap allocation; the native parser is mocked.
        val bitmap = mockk<Bitmap>(relaxed = true)
        val page = PdfPage(1080, 1, Mutex(), renderer, this, { throw AssertionError(it) }, 1080, 1529, dispatcher,
            bitmapFactory = { width, height ->
                assertThat(width.toLong() * height * 4L).isAtMost(PdfRenderPolicy.MAX_PAGE_BITMAP_BYTES)
                bitmap
            }, sourceAccessCheck = { checks++ })
        page.load()
        runCurrent()
        assertThat(page.stateFlow.value).isInstanceOf(PdfPage.State.Loaded::class.java)
        verify(exactly = 1) { renderer.openPage(1) }
        page.close()
        manager.close()
        advanceUntilIdle()
        assertThat(cleanup.single().isComplete).isTrue()
        verify(exactly = 1) { bitmap.recycle() }
    }

    @Test
    fun `idle source revocation fails closed and releases renderer without waiting for a swipe`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val descriptor = mockk<ParcelFileDescriptor>(relaxed = true)
        val renderer = mockk<PdfRenderer>(relaxed = true)
        val nativePage = mockk<PdfRenderer.Page>(relaxed = true)
        every { descriptor.statSize } returns 2972L
        every { renderer.pageCount } returns 3
        every { renderer.openPage(0) } returns nativePage
        every { nativePage.width } returns 595
        every { nativePage.height } returns 842
        var authorized = true
        val failures = mutableListOf<Throwable>()
        val cleanup = mutableListOf<FilePreviewRuntimeCleanup>()
        val manager = PdfRendererManager(descriptor, 1080, this, {}, failures::add, { renderer }, dispatcher, cleanup::add,
            sourceAccessCheck = { if (!authorized) throw PdfSourceUnavailableException() })
        manager.open()
        runCurrent()
        advanceTimeBy(290_000L)
        runCurrent()
        authorized = false
        advanceTimeBy(1_000L)
        runCurrent()
        assertThat(failures.single()).isInstanceOf(PdfSourceUnavailableException::class.java)
        verify(exactly = 1) { renderer.openPage(any()) }
        verify(exactly = 1) { renderer.close() }
        assertThat(cleanup.single().isComplete).isTrue()
        assertThat(manager.pdfPages.value).isEqualTo(AsyncData.Uninitialized)
        manager.close()
        advanceUntilIdle()
        assertThat(failures).hasSize(1)
        assertThat(cleanup).hasSize(1)
    }

    @Test
    fun `cancelled native page geometry cannot become the current generation failure`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val renderer = mockk<PdfRenderer>()
        val nativePage = mockk<PdfRenderer.Page>(relaxed = true)
        every { renderer.openPage(1) } returns nativePage
        val failures = mutableListOf<Throwable>()
        val page = PdfPage(1080, 1, Mutex(), renderer, this, failures::add, 1080, 1529, dispatcher)
        every { nativePage.width } answers { page.close(); 0 }
        every { nativePage.height } returns 0
        page.load()
        advanceUntilIdle()
        assertThat(failures).isEmpty()
        verify(exactly = 1) { nativePage.close() }
    }
}
