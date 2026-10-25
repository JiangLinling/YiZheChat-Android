/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaviewer.impl.local.txt

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewParameter
import io.element.android.features.viewfolder.api.TextFileViewer
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.CircularProgressIndicator
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.mediaviewer.api.local.LocalMedia
import io.element.android.libraries.mediaviewer.impl.viewer.topAppBarHeight
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.element.android.libraries.mediaviewer.impl.R

@Composable
fun TextFileView(
    localMedia: LocalMedia?,
    textFileViewer: TextFileViewer,
    modifier: Modifier = Modifier,
    onFileDisplayStateChanged: (Boolean) -> Unit = {},
) {
    val data = remember(localMedia?.uri) { mutableStateOf<AsyncData<ImmutableList<String>>>(AsyncData.Uninitialized) }
    val context = LocalContext.current
    DisposableEffect(localMedia?.uri) {
        onDispose { data.value = AsyncData.Uninitialized }
    }
    LaunchedEffect(localMedia?.uri) {
        data.value = AsyncData.Loading()
        if (localMedia?.uri != null) {
            // Load the file content
            val result = runCatchingExceptions { withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(localMedia.uri).use {
                    requireNotNull(it) { "Protected text source is unavailable." }.bufferedReader().readLines().toList()
                }
            } }
            data.value = if (result.isSuccess) {
                AsyncData.Success(result.getOrNull().orEmpty().toImmutableList())
            } else {
                AsyncData.Failure(result.exceptionOrNull() ?: Exception("An error occurred"))
            }
        }
    }
    val currentOnFileDisplayed = androidx.compose.runtime.rememberUpdatedState(onFileDisplayStateChanged)
    LaunchedEffect(data.value, localMedia?.uri) {
        currentOnFileDisplayed.value(false)
        if (data.value is AsyncData.Success) {
            androidx.compose.runtime.withFrameNanos { }
            currentOnFileDisplayed.value(true)
        }
    }
    TextFileContentView(
        data = data.value,
        textFileViewer = textFileViewer,
        modifier = modifier,
    )
}

@Composable
private fun TextFileContentView(
    data: AsyncData<ImmutableList<String>>,
    textFileViewer: TextFileViewer,
    modifier: Modifier = Modifier,
) {
    when (data) {
        AsyncData.Uninitialized,
        is AsyncData.Loading -> Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
        is AsyncData.Failure -> Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(text = stringResource(id = R.string.error_protected_media_read_unavailable))
        }
        is AsyncData.Success -> {
            textFileViewer.Render(
                lines = data.data,
                modifier = modifier
                    .fillMaxSize()
                    .padding(top = topAppBarHeight),
            )
        }
    }
}

@PreviewsDayNight
@Composable
internal fun TextFileContentViewPreview(
    @PreviewParameter(TextFileContentProvider::class) text: AsyncData<ImmutableList<String>>,
) = ElementPreview {
    TextFileContentView(
        data = text,
        textFileViewer = { lines, modifier ->
            Text(
                modifier = modifier,
                text = lines.firstOrNull() ?: "File content"
            )
        }
    )
}
