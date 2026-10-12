/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaviewer.impl.local.pdf

import android.content.Context
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.localofficeviewer.api.FilePreviewRuntime
import io.element.android.libraries.localofficeviewer.api.FilePreviewRuntimeCleanup
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.CoroutineScope
import me.saket.telephoto.zoomable.ZoomableState
import me.saket.telephoto.zoomable.rememberZoomableState

@Stable
class PdfViewerState(
    private val model: Any?,
    private val coroutineScope: CoroutineScope,
    private val context: Context,
    val zoomableState: ZoomableState,
    val lazyListState: LazyListState,
    private val onFailure: (Throwable) -> Unit,
    private val runtimeFactory: (() -> FilePreviewRuntime?)? = null,
) {
    var isLoaded by mutableStateOf(false)
    private var pdfRendererManager by mutableStateOf<PdfRendererManager?>(null)

    @Composable
    fun getPages(): AsyncData<ImmutableList<PdfPage>> {
        return pdfRendererManager?.run {
            pdfPages.collectAsState().value
        } ?: AsyncData.Uninitialized
    }

    fun openForWidth(maxWidth: Int) {
        pdfRendererManager?.close()
        val runtime = runtimeFactory?.invoke()
        if (runtimeFactory != null && runtime == null) {
            onFailure(IllegalStateException("The local-preview runtime owner is already released."))
            return
        }
        val descriptorFactory = ParcelFileDescriptorFactory(context)
        descriptorFactory.create(model)
            .onSuccess { parcelFileDescriptor ->
                pdfRendererManager = PdfRendererManager(
                    parcelFileDescriptor = parcelFileDescriptor,
                    width = maxWidth,
                    coroutineScope = coroutineScope,
                    onReady = { isLoaded = true },
                    onFailure = {
                        isLoaded = false
                        onFailure(it)
                    },
                    onCleanup = { runtime?.complete(it) },
                    sourceAccessCheck = descriptorFactory.sourceAccessCheck(model) { parcelFileDescriptor.statSize },
                ).apply {
                    open()
                }
            }
            .onFailure {
                runtime?.complete(FilePreviewRuntimeCleanup(true, true, true))
                onFailure(it)
            }
    }

    fun close() {
        pdfRendererManager?.close()
        isLoaded = false
    }
}

@Composable
fun rememberPdfViewerState(
    model: Any?,
    zoomableState: ZoomableState = rememberZoomableState(),
    lazyListState: LazyListState = rememberLazyListState(),
    context: Context = LocalContext.current,
    coroutineScope: CoroutineScope = rememberCoroutineScope(),
    onFailure: (Throwable) -> Unit = {},
    runtimeFactory: (() -> FilePreviewRuntime?)? = null,
): PdfViewerState {
    return remember(model) {
        PdfViewerState(
            model = model,
            coroutineScope = coroutineScope,
            context = context,
            zoomableState = zoomableState,
            lazyListState = lazyListState,
            onFailure = onFailure,
            runtimeFactory = runtimeFactory,
        )
    }
}
