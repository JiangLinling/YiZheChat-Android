/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaviewer.impl.local.pdf

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.localofficeviewer.api.FilePreviewRuntimeCleanup
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class PdfRendererManager(
    private val parcelFileDescriptor: ParcelFileDescriptor,
    private val width: Int,
    private val coroutineScope: CoroutineScope,
    private val onReady: () -> Unit,
    private val onFailure: (Throwable) -> Unit,
    private val rendererFactory: (ParcelFileDescriptor) -> PdfRenderer = ::PdfRenderer,
    private val renderDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onCleanup: (FilePreviewRuntimeCleanup) -> Unit = {},
    private val sourceAccessCheck: (() -> Unit)? = null,
) {
    private val mutex = Mutex()
    private val closeRequested = AtomicBoolean(false)
    private val failureReported = AtomicBoolean(false)
    private val opened = AtomicBoolean(false)
    private val cleanupReported = AtomicBoolean(false)
    private val pageCleanupFailed = AtomicBoolean(false)
    private var pdfRenderer: PdfRenderer? = null
    private var sourceMonitor: Job? = null
    @Volatile private var ownedPages: ImmutableList<PdfPage>? = null
    private val mutablePdfPages = MutableStateFlow<AsyncData<ImmutableList<PdfPage>>>(AsyncData.Uninitialized)
    val pdfPages: StateFlow<AsyncData<ImmutableList<PdfPage>>> = mutablePdfPages

    fun open() {
        if (!opened.compareAndSet(false, true)) return
        coroutineScope.launch {
            mutex.withLock {
                var retained = false
                try {
                    val result = withContext(renderDispatcher) {
                        runCatchingExceptions {
                            if (closeRequested.get()) return@runCatchingExceptions
                            try {
                            checkPdfSourceAccess(sourceAccessCheck, "document_admission", 0, width)
                            PdfRenderPolicy.validateEncodedSize(parcelFileDescriptor.statSize)
                            val renderer = rendererFactory(parcelFileDescriptor)
                            var installed = false
                            try {
                                PdfRenderPolicy.validatePageCount(renderer.pageCount)
                                // Only the first page provides the initial placeholder aspect ratio.
                                val plan = renderer.openPage(0).use { page ->
                                    checkedPdfPagePlan(page.width, page.height, width, 0, "initial_geometry", sourceAccessCheck)
                                }
                                val pages = (0 until renderer.pageCount).map { index ->
                                    PdfPage(
                                        width,
                                        index,
                                        mutex,
                                        renderer,
                                        coroutineScope,
                                        ::reportFailure,
                                        plan.width,
                                        plan.height,
                                        renderDispatcher,
                                        sourceAccessCheck = sourceAccessCheck,
                                    )
                                }.toImmutableList()
                                if (!closeRequested.get()) {
                                    // Install ownership before returning across a cancellable dispatcher boundary.
                                    pdfRenderer = renderer
                                    ownedPages = pages
                                    mutablePdfPages.value = AsyncData.Success(pages)
                                    installed = true
                                    retained = true
                                }
                            } finally {
                                if (!installed) renderer.close()
                            }
                            } catch (_: OutOfMemoryError) {
                                throw PdfPreviewPolicyException.MemoryUnavailable()
                            }
                        }
                    }
                    result.fold(
                        onSuccess = {
                            if (retained && !closeRequested.get()) {
                                onReady()
                                monitorSourceAccess()
                            }
                        },
                        onFailure = ::reportFailure,
                    )
                } finally {
                    if (!retained || closeRequested.get() || !currentCoroutineContext().isActive) {
                        withContext(NonCancellable + renderDispatcher) { releaseLocked() }
                    }
                }
            }
        }
    }

    fun close() {
        if (!closeRequested.compareAndSet(false, true)) return
        sourceMonitor?.cancel()
        // Invalidate page jobs immediately, including a render already inside the native call.
        invalidatePages()
        // Cleanup must finish even if the owning Compose scope has already been cancelled.
        CoroutineScope(renderDispatcher).launch {
            mutex.withLock { releaseLocked() }
        }
    }

    private fun monitorSourceAccess() {
        val check = sourceAccessCheck ?: return
        sourceMonitor = coroutineScope.launch {
            while (isActive && !closeRequested.get()) {
                delay(1_000L)
                val result = withContext(renderDispatcher) {
                    mutex.withLock {
                        runCatchingExceptions {
                            if (!closeRequested.get()) checkPdfSourceAccess(check, "idle_admission", -1, width)
                        }
                    }
                }
                if (result.isFailure) {
                    reportFailure(checkNotNull(result.exceptionOrNull()))
                    close()
                    return@launch
                }
            }
        }
    }

    private fun releaseLocked() {
        // Holding the renderer mutex proves every native page render has left its critical section.
        invalidatePages()
        val pagesCleared = !pageCleanupFailed.get()
        ownedPages = null
        val rendererClosed = runCatching { pdfRenderer?.close() }.isSuccess
        pdfRenderer = null
        val descriptorClosed = runCatching { parcelFileDescriptor.close() }.isSuccess
        mutablePdfPages.value = AsyncData.Uninitialized
        if (cleanupReported.compareAndSet(false, true)) {
            onCleanup(FilePreviewRuntimeCleanup(rendererClosed, descriptorClosed, pagesCleared))
        }
    }

    private fun invalidatePages() {
        ownedPages?.forEach { page ->
            if (runCatching { page.close() }.isFailure) pageCleanupFailed.set(true)
        }
    }

    private fun reportFailure(failure: Throwable) {
        if (!failureReported.compareAndSet(false, true) || closeRequested.get()) return
        mutablePdfPages.value = AsyncData.Failure(failure)
        onFailure(failure)
    }
}
