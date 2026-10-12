/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaviewer.impl.local.pdf

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import io.element.android.libraries.localofficeviewer.api.FilePreviewRuntime
import io.element.android.libraries.mediaviewer.api.local.LocalMedia
import io.element.android.libraries.mediaviewer.impl.local.LocalMediaViewState
import io.element.android.libraries.mediaviewer.impl.viewer.topAppBarHeight

@Composable
fun MediaPdfView(
    localMediaViewState: LocalMediaViewState,
    localMedia: LocalMedia?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    topContentPadding: Dp = topAppBarHeight,
    backgroundColor: Color = Color.Transparent,
    showPageIndicator: Boolean = false,
    manageLocalReadyState: Boolean = true,
    loadingText: String? = null,
    onSourceFailure: (Throwable) -> Unit = {},
    runtimeFactory: (() -> FilePreviewRuntime?)? = null,
) {
    val currentOnSourceFailure = rememberUpdatedState(onSourceFailure)
    val pdfViewerState = rememberPdfViewerState(
        model = localMedia?.uri,
        zoomableState = localMediaViewState.zoomableState,
        onFailure = { currentOnSourceFailure.value(it) },
        runtimeFactory = runtimeFactory,
    )
    if (manageLocalReadyState) {
        localMediaViewState.isReady = pdfViewerState.isLoaded
    }
    PdfViewer(
        pdfViewerState = pdfViewerState,
        onClick = onClick,
        topContentPadding = topContentPadding,
        backgroundColor = backgroundColor,
        showPageIndicator = showPageIndicator,
        loadingText = loadingText,
        modifier = modifier,
    )
}
