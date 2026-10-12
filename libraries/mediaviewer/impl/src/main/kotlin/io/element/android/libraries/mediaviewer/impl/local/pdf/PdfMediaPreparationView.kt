/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.mediaviewer.impl.local.pdf

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.designsystem.components.async.AsyncLoading
import io.element.android.libraries.mediaviewer.api.local.LocalMedia
import io.element.android.libraries.mediaviewer.impl.local.LocalMediaViewState

/** Preparation is not a missing or released lease. Only fresh success enters the protected PDF adapter. */
@Composable
internal fun PdfMediaPreparationView(
    downloadedMedia: AsyncData<LocalMedia>,
    localMediaViewState: LocalMediaViewState,
    modifier: Modifier = Modifier,
    content: @Composable (LocalMedia) -> Unit,
) {
    when (downloadedMedia) {
        is AsyncData.Success -> content(downloadedMedia.data)
        is AsyncData.Loading, AsyncData.Uninitialized -> {
            localMediaViewState.isReady = false
            Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AsyncLoading()
            }
        }
        is AsyncData.Failure -> {
            // The host renders its existing download error and retry policy. Never reuse prevData after a denial.
            localMediaViewState.isReady = false
        }
    }
}
