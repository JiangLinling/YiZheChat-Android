/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaviewer.impl.local.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import androidx.compose.runtime.Stable
import io.element.android.libraries.core.extensions.runCatchingExceptions
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Stable
class PdfPage(
    private val maxWidth: Int,
    val pageIndex: Int,
    private val mutex: Mutex,
    private val pdfRenderer: PdfRenderer,
    private val coroutineScope: CoroutineScope,
    private val onFailure: (Throwable) -> Unit,
    initialWidth: Int,
    initialHeight: Int,
    private val renderDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val bitmapFactory: (Int, Int) -> Bitmap = ::createWhitePdfBitmap,
    private val sourceAccessCheck: (() -> Unit)? = null,
) {
    sealed interface State {
        data class Loading(val width: Int, val height: Int) : State
        data class Loaded(val bitmap: Bitmap) : State
    }

    // Page construction is cheap. Actual geometry is read only when the page is displayed.
    private val stateLock = Any()
    private var generation = 0L
    private var renderPlan = PdfPageRenderPlan(initialWidth, initialHeight)
    private var loadJob: Job? = null
    private val mutableStateFlow = MutableStateFlow<State>(State.Loading(initialWidth, initialHeight))
    val stateFlow: StateFlow<State> = mutableStateFlow

    fun load() {
        val job = synchronized(stateLock) {
            if (loadJob?.isActive == true || mutableStateFlow.value is State.Loaded) return
            val token = ++generation
            coroutineScope.launch(start = CoroutineStart.LAZY) {
                runCatchingExceptions {
                    mutex.withLock {
                        withContext(renderDispatcher) {
                            val context = currentCoroutineContext()
                            if (!context.isActive || !isCurrent(token)) return@withContext
                            try {
                                checkPdfSourceAccess(sourceAccessCheck, "before_page_open", pageIndex, maxWidth)
                                pdfRenderer.openPage(pageIndex).use { page ->
                                    val pageWidth = page.width
                                    val pageHeight = page.height
                                    if (!context.isActive || !isCurrent(token)) return@use
                                    val plan = checkedPdfPagePlan(
                                        pageWidth, pageHeight, maxWidth, pageIndex, "visible_geometry", sourceAccessCheck,
                                    )
                                    synchronized(stateLock) {
                                        if (token == generation) {
                                            renderPlan = plan
                                            mutableStateFlow.value = State.Loading(plan.width, plan.height)
                                        }
                                    }
                                    if (!context.isActive || !isCurrent(token)) return@use
                                    val bitmap = bitmapFactory(plan.width, plan.height)
                                    var transferred = false
                                    try {
                                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                        checkPdfSourceAccess(sourceAccessCheck, "before_publish", pageIndex, maxWidth)
                                        // Transfer inside IO, before cancellable dispatch back to the UI.
                                        // A close during native rendering invalidates the token and recycles the result.
                                        synchronized(stateLock) {
                                            if (token == generation && context.isActive) {
                                                mutableStateFlow.value = State.Loaded(bitmap)
                                                transferred = true
                                            }
                                        }
                                    } finally {
                                        if (!transferred) bitmap.recycle()
                                    }
                                }
                            } catch (_: OutOfMemoryError) {
                                throw PdfPreviewPolicyException.MemoryUnavailable()
                            }
                        }
                    }
                }.onFailure { failure ->
                    if (isCurrent(token) && currentCoroutineContext().isActive) onFailure(failure)
                }
            }.also { loadJob = it }
        }
        job.start()
    }

    fun close() {
        synchronized(stateLock) {
            generation++
            loadJob?.cancel()
            loadJob = null
            val previous = mutableStateFlow.value
            mutableStateFlow.value = State.Loading(renderPlan.width, renderPlan.height)
            if (previous is State.Loaded) previous.bitmap.recycle()
        }
    }

    private fun isCurrent(token: Long): Boolean = synchronized(stateLock) { token == generation }
}

private fun createWhitePdfBitmap(width: Int, height: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    try {
        Canvas(bitmap).drawColor(Color.WHITE)
        return bitmap
    } catch (failure: Throwable) {
        bitmap.recycle()
        throw failure
    }
}
