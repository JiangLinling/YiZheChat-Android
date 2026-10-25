/*
 * Copyright (c) 2026 YiZheChat.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.mediaviewer.impl.local.archive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.libraries.designsystem.theme.components.CircularProgressIndicator
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.mediaviewer.api.local.LocalMedia
import io.element.android.libraries.mediaviewer.impl.R
import io.element.android.libraries.mediaviewer.impl.local.LocalMediaViewState
import io.element.android.libraries.mediaviewer.impl.viewer.topAppBarHeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun MediaArchiveView(
    localMediaViewState: LocalMediaViewState,
    localMedia: LocalMedia?,
    modifier: Modifier = Modifier,
    onFileDisplayStateChanged: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    var result by remember(localMedia?.uri) { mutableStateOf<Result<List<SafeArchiveEntry>>?>(null) }
    LaunchedEffect(localMedia?.uri) {
        localMediaViewState.isReady = false
        result = localMedia?.uri?.let { uri ->
            withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "Unable to read the protected archive." }
                        ZipArchiveEntryListExtractor.list(input)
                    }
                }
            }
        }
        localMediaViewState.isReady = result?.isSuccess == true
    }
    val currentOnFileDisplayed = androidx.compose.runtime.rememberUpdatedState(onFileDisplayStateChanged)
    LaunchedEffect(result, localMedia?.uri) {
        currentOnFileDisplayed.value(false)
        if (result?.isSuccess == true) {
            androidx.compose.runtime.withFrameNanos { }
            currentOnFileDisplayed.value(true)
        }
    }
    when {
        result == null -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        result?.isFailure == true -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.screen_media_archive_secure_preview_error),
                color = ElementTheme.colors.textPrimary,
            )
        }
        else -> {
            val entries = result?.getOrNull().orEmpty()
            if (entries.isEmpty()) {
                Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.screen_media_archive_secure_preview_empty),
                        color = ElementTheme.colors.textPrimary,
                    )
                }
            } else {
                LazyColumn(
                    modifier = modifier
                        .fillMaxSize()
                        .padding(top = topAppBarHeight, start = 20.dp, end = 20.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(entries) { entry ->
                        Column {
                            Text(
                                text = entry.displayName,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                color = ElementTheme.colors.textPrimary,
                            )
                            Text(
                                text = when {
                                    entry.isDirectory -> stringResource(R.string.screen_media_archive_directory)
                                    entry.uncompressedBytes != null -> stringResource(
                                        R.string.screen_media_archive_entry_bytes,
                                        entry.uncompressedBytes,
                                    )
                                    else -> stringResource(R.string.screen_media_archive_entry_size_unknown)
                                },
                                color = ElementTheme.colors.textSecondary,
                            )
                        }
                    }
                }
            }
        }
    }
}
