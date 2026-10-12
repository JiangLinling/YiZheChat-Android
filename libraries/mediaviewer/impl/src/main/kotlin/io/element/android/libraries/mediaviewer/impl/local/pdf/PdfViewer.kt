/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaviewer.impl.local.pdf

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.text.roundToPx
import io.element.android.libraries.designsystem.text.toDp
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.mediaviewer.impl.R
import io.element.android.libraries.mediaviewer.impl.viewer.topAppBarHeight
import io.element.android.libraries.ui.strings.CommonStrings
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.zoomable
import java.io.IOException

@Composable
fun PdfViewer(
    pdfViewerState: PdfViewerState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    topContentPadding: Dp = topAppBarHeight,
    backgroundColor: Color = Color.Transparent,
    showPageIndicator: Boolean = false,
    loadingText: String? = null,
) {
    BoxWithConstraints(
        modifier = modifier
            .background(backgroundColor)
            .zoomable(
                state = pdfViewerState.zoomableState,
                onClick = { onClick() }
            ),
        contentAlignment = Alignment.Center
    ) {
        val maxWidthInPx = maxWidth.roundToPx()
        DisposableEffect(pdfViewerState) {
            pdfViewerState.openForWidth(maxWidthInPx)
            onDispose {
                pdfViewerState.close()
            }
        }
        val pdfPages = pdfViewerState.getPages()
        Box(modifier = Modifier.fillMaxSize()) {
            PdfPagesView(
                pdfPages = pdfPages,
                lazyListState = pdfViewerState.lazyListState,
                topContentPadding = topContentPadding,
                backgroundColor = backgroundColor,
                loadingText = loadingText,
            )
            if (showPageIndicator && pdfPages is AsyncData.Success && pdfPages.data.isNotEmpty()) {
                var showJump by remember(pdfViewerState) { mutableStateOf(false) }
                var pageInput by remember(pdfViewerState) { mutableStateOf("1") }
                val scope = rememberCoroutineScope()
                val currentPage by remember(pdfPages.data, pdfViewerState.lazyListState) {
                    derivedStateOf {
                        val layout = pdfViewerState.lazyListState.layoutInfo
                        layout.visibleItemsInfo
                            .filter { it.index in 1..pdfPages.data.size }
                            .maxByOrNull { item ->
                                minOf(item.offset + item.size, layout.viewportEndOffset) -
                                    maxOf(item.offset, layout.viewportStartOffset)
                            }
                            ?.index ?: 1
                    }
                }
                Text(
                    text = "$currentPage/${pdfPages.data.size}",
                    color = Color.White,
                    style = ElementTheme.typography.fontBodyMdRegular,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 16.dp, top = topContentPadding + 12.dp)
                        .background(Color(0x99000000), RoundedCornerShape(6.dp))
                        .clickable {
                            pageInput = currentPage.toString()
                            showJump = true
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
                if (showJump) {
                    val targetPage = pageInput.toIntOrNull()?.takeIf { it in 1..pdfPages.data.size }
                    AlertDialog(
                        onDismissRequest = { showJump = false },
                        title = { Text(stringResource(R.string.local_pdf_jump_to_page)) },
                        text = {
                            OutlinedTextField(
                                value = pageInput,
                                onValueChange = { value -> if (value.length <= 5 && value.all(Char::isDigit)) pageInput = value },
                                label = { Text(stringResource(R.string.local_pdf_page_number, pdfPages.data.size)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                            )
                        },
                        confirmButton = {
                            TextButton(enabled = targetPage != null, onClick = {
                                showJump = false
                                targetPage?.let { page -> scope.launch { pdfViewerState.lazyListState.scrollToItem(page) } }
                            }) { Text(stringResource(R.string.local_pdf_jump)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showJump = false }) { Text(stringResource(R.string.local_pdf_cancel)) }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PdfPagesView(
    pdfPages: AsyncData<ImmutableList<PdfPage>>,
    lazyListState: LazyListState,
    topContentPadding: Dp,
    backgroundColor: Color,
    loadingText: String?,
    modifier: Modifier = Modifier,
) {
    when (pdfPages) {
        is AsyncData.Uninitialized,
        is AsyncData.Loading -> PdfPagesLoadingView(
            loadingText = loadingText,
            topContentPadding = topContentPadding,
            backgroundColor = backgroundColor,
            modifier = modifier,
        )
        is AsyncData.Failure -> PdfPagesErrorView(
            error = pdfPages.error,
            textColor = if (loadingText == null) Color.Unspecified else Color(0xFF606060),
            modifier = modifier
                .background(backgroundColor)
                .padding(top = topContentPadding),
        )
        is AsyncData.Success -> PdfPagesContentView(
            pdfPages = pdfPages.data,
            lazyListState = lazyListState,
            topContentPadding = topContentPadding,
            backgroundColor = backgroundColor,
            modifier = modifier
        )
    }
}

@Composable
private fun PdfPagesLoadingView(
    loadingText: String?,
    topContentPadding: Dp,
    backgroundColor: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
            .padding(top = topContentPadding),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (loadingText == null) {
                CircularProgressIndicator()
            } else {
                CircularProgressIndicator(color = Color(0xFF4A7D64))
            }
            if (loadingText != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = loadingText,
                    textAlign = TextAlign.Center,
                    color = Color(0xFF191919),
                    style = ElementTheme.typography.fontBodyLgRegular,
                )
            }
        }
    }
}

@Composable
private fun PdfPagesErrorView(
    error: Throwable,
    textColor: Color = Color.Unspecified,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = buildString {
                append(stringResource(id = CommonStrings.error_unknown))
                append("\n\n")
                append(error.localizedMessage)
            },
            color = textColor,
            textAlign = TextAlign.Center,
            style = ElementTheme.typography.fontBodyLgRegular,
        )
    }
}

@Composable
private fun PdfPagesContentView(
    pdfPages: ImmutableList<PdfPage>,
    lazyListState: LazyListState,
    topContentPadding: Dp,
    backgroundColor: Color,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor),
        state = lazyListState,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)
    ) {
        // Add a fake item to the top so that the first item is not at the top of the screen.
        item {
            Spacer(modifier = Modifier.height(topContentPadding))
        }
        items(pdfPages.size) { index ->
            val pdfPage = pdfPages[index]
            PdfPageView(pdfPage)
        }
    }
}

@Composable
private fun PdfPageView(
    pdfPage: PdfPage,
) {
    val pdfPageState by pdfPage.stateFlow.collectAsState()
    DisposableEffect(pdfPage) {
        pdfPage.load()
        onDispose {
            pdfPage.close()
        }
    }
    when (val state = pdfPageState) {
        is PdfPage.State.Loaded -> {
            Image(
                bitmap = state.bitmap.asImageBitmap(),
                contentDescription = stringResource(id = CommonStrings.a11y_page_n, pdfPage.pageIndex + 1),
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth()
            )
        }
        is PdfPage.State.Loading -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(state.height.toDp())
                    .background(color = Color.White)
            )
        }
    }
}

@PreviewsDayNight
@Composable
internal fun PdfPagesErrorViewPreview() = ElementPreview {
    PdfPagesErrorView(
        error = IOException("file not in PDF format or corrupted"),
    )
}
