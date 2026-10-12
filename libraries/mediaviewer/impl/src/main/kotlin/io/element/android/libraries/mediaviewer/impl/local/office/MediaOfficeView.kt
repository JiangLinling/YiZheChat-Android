/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaviewer.impl.local.office

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.element.android.compound.theme.ElementTheme
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.mediaviewer.api.local.LocalMedia
import io.element.android.libraries.mediaviewer.impl.R
import io.element.android.libraries.mediaviewer.impl.local.LocalMediaViewState
import io.element.android.libraries.mediaviewer.impl.local.file.MediaFileView
import io.element.android.libraries.mediaviewer.impl.local.pdf.MediaPdfView
import io.element.android.libraries.mediaviewer.impl.viewer.officeDocumentTopBarContentHeight
import io.element.android.libraries.ui.strings.CommonStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayInputStream

private val officeDocumentBackground = Color(0xFFF2F2F2)
private val officeLoadingBackground = Color(0xFFF7F7F7)
private val officePrimaryText = Color(0xFF191919)
private val officeSecondaryText = Color(0xFF606060)
private val officeProgressColor = Color(0xFF4A7D64)
private const val AUTOMATIC_RETRY_COUNT = 1
private const val OFFICE_PREVIEW_BRIDGE = "YiZheOfficePreview"

@Composable
internal fun MediaOfficeView(
    localMediaViewState: LocalMediaViewState,
    localMedia: LocalMedia?,
    officePreviewService: OfficePreviewService,
    accountId: String,
    deviceId: String,
    onClick: () -> Unit,
    onOpenWith: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val topContentPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() +
        officeDocumentTopBarContentHeight

    // The Matrix attachment download is part of the same visible loading step. Avoid showing
    // the generic file placeholder before the secure document pipeline can start.
    if (localMedia == null) {
        localMediaViewState.isReady = true
        OfficePreviewProgressView(
            progress = null,
            onCancel = null,
            topContentPadding = topContentPadding,
            modifier = modifier,
        )
        return
    }

    var cancelled by remember(localMedia.uri) { mutableStateOf(false) }
    var generation by remember(localMedia.uri) { mutableIntStateOf(0) }
    var state by remember(localMedia.uri) { mutableStateOf<OfficePreviewUiState>(OfficePreviewUiState.Waiting) }

    // This surface replaces the generic media thumbnail so the first tap leads to one
    // continuous loading screen until the first native document page is available.
    localMediaViewState.isReady = true

    LaunchedEffect(localMedia.uri, accountId, deviceId, cancelled, generation) {
        if (cancelled) return@LaunchedEffect
        val requestGeneration = generation
        var automaticRetries = 0
        while (true) {
            state = OfficePreviewUiState.Preparing(OfficePreviewProgress(OfficePreviewPhase.HASHING, 0f))
            try {
                val document = officePreviewService.prepare(
                    localMedia = localMedia,
                    accountId = accountId,
                    deviceId = deviceId,
                ) { progress ->
                    if (!cancelled && generation == requestGeneration) {
                        state = OfficePreviewUiState.Preparing(progress)
                    }
                }
                try {
                    currentCoroutineContext().ensureActive()
                    state = OfficePreviewUiState.Ready(document)
                } catch (failure: CancellationException) {
                    document.close()
                    throw failure
                }
                break
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: OfficePreviewException) {
                if (failure.failure.isRetryable() && automaticRetries < AUTOMATIC_RETRY_COUNT) {
                    automaticRetries += 1
                    delay(350)
                    continue
                }
                state = OfficePreviewUiState.Failed(failure.failure)
                break
            } catch (_: Exception) {
                if (automaticRetries < AUTOMATIC_RETRY_COUNT) {
                    automaticRetries += 1
                    delay(350)
                    continue
                }
                state = OfficePreviewUiState.Failed(OfficePreviewFailure.SERVER_REJECTED)
                break
            }
        }
    }

    val activeDocument = (state as? OfficePreviewUiState.Ready)?.document
    DisposableEffect(activeDocument) {
        onDispose {
            activeDocument?.close()
        }
    }

    when {
        cancelled -> MediaFileView(
            localMediaViewState = localMediaViewState,
            uri = localMedia.uri,
            info = localMedia.info,
            onOpenWith = onOpenWith,
            modifier = modifier,
        )
        state is OfficePreviewUiState.Ready -> when (val document = (state as OfficePreviewUiState.Ready).document) {
            is OfficePreviewDocument.Paginated -> MediaPdfView(
                localMediaViewState = localMediaViewState,
                localMedia = document.localMedia,
                onClick = onClick,
                topContentPadding = topContentPadding,
                backgroundColor = officeDocumentBackground,
                showPageIndicator = true,
                manageLocalReadyState = false,
                loadingText = stringResource(R.string.screen_media_office_loading),
                modifier = modifier,
            )
            is OfficePreviewDocument.Spreadsheet -> OfficeSpreadsheetView(
                document = document,
                topContentPadding = topContentPadding,
                onFailure = { state = OfficePreviewUiState.Failed(OfficePreviewFailure.SERVER_REJECTED) },
                modifier = modifier,
            )
        }
        state is OfficePreviewUiState.Failed -> {
            val failure = (state as OfficePreviewUiState.Failed).failure
            OfficePreviewFailureView(
                failure = failure,
                onRetry = if (failure.isRetryable()) {
                    { generation += 1 }
                } else {
                    null
                },
                onOpenWith = onOpenWith,
                topContentPadding = topContentPadding,
                modifier = modifier,
            )
        }
        else -> OfficePreviewProgressView(
            progress = (state as? OfficePreviewUiState.Preparing)?.progress,
            onCancel = { cancelled = true },
            topContentPadding = topContentPadding,
            modifier = modifier,
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun OfficeSpreadsheetView(
    document: OfficePreviewDocument.Spreadsheet,
    topContentPadding: Dp,
    onFailure: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier
            .fillMaxSize()
            .background(officeDocumentBackground)
            .padding(top = topContentPadding),
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setBackgroundColor(android.graphics.Color.WHITE)
                isHorizontalScrollBarEnabled = true
                isVerticalScrollBarEnabled = true
                with(settings) {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowContentAccess = false
                    allowFileAccess = false
                    loadsImagesAutomatically = true
                    cacheMode = WebSettings.LOAD_NO_CACHE
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    javaScriptCanOpenWindowsAutomatically = false
                    setSupportMultipleWindows(false)
                    setGeolocationEnabled(false)
                    @Suppress("DEPRECATION")
                    databaseEnabled = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = true
                }
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                webChromeClient = object : WebChromeClient() {
                    override fun onPermissionRequest(request: PermissionRequest) {
                        request.deny()
                    }
                }
                webViewClient = RestrictedOfficeWebViewClient(
                    allowedOrigin = document.allowedOrigin,
                    onFailure = onFailure,
                )
                addJavascriptInterface(
                    OfficePreviewBridge { post { onFailure() } },
                    OFFICE_PREVIEW_BRIDGE,
                )
                loadUrl(document.viewerUrl)
            }
        },
        update = { webView ->
            if (webView.url?.substringBefore('#') != document.viewerUrl.substringBefore('#')) {
                webView.loadUrl(document.viewerUrl)
            }
        },
        onRelease = { webView ->
            webView.stopLoading()
            webView.removeJavascriptInterface(OFFICE_PREVIEW_BRIDGE)
            webView.clearFormData()
            webView.clearHistory()
            webView.destroy()
        },
    )
}

private class OfficePreviewBridge(
    private val onFailure: () -> Unit,
) {
    @JavascriptInterface
    fun ready() = Unit

    @JavascriptInterface
    fun failed() = onFailure()
}

private class RestrictedOfficeWebViewClient(
    private val allowedOrigin: String,
    private val onFailure: () -> Unit,
) : WebViewClient() {
    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        if (url != null && !url.isAllowedOfficeUrl(allowedOrigin)) onFailure()
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean =
        request.isForMainFrame && !request.url.toString().isAllowedOfficeUrl(allowedOrigin)

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString()
        return if (url.isHttpUrl() && !url.isAllowedOfficeUrl(allowedOrigin)) blockedWebResponse() else null
    }

    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
        if (request?.isForMainFrame == true) onFailure()
    }

    override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
        if (request?.isForMainFrame == true) onFailure()
    }

    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
        handler?.cancel()
        onFailure()
    }
}

private fun String.isHttpUrl(): Boolean = Uri.parse(this).scheme in setOf("http", "https")

private fun String.isAllowedOfficeUrl(allowedOrigin: String): Boolean {
    val candidate = Uri.parse(this)
    val origin = Uri.parse(allowedOrigin)
    if (candidate.scheme != "https" ||
        candidate.host != origin.host ||
        normalizedPort(candidate) != normalizedPort(origin)
    ) {
        return false
    }
    val path = candidate.path.orEmpty()
    return path == "/api/mobile/office-previews/viewer" ||
        path.startsWith("/api/mobile/office-previews/") ||
        path.startsWith("/onlyoffice/")
}

private fun normalizedPort(uri: Uri): Int = if (uri.port == -1) 443 else uri.port

private fun blockedWebResponse(): WebResourceResponse = WebResourceResponse(
    "text/plain",
    "UTF-8",
    403,
    "Forbidden",
    emptyMap(),
    ByteArrayInputStream(ByteArray(0)),
)

@Composable
private fun OfficePreviewProgressView(
    progress: OfficePreviewProgress?,
    onCancel: (() -> Unit)?,
    topContentPadding: Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(officeLoadingBackground)
            .padding(top = topContentPadding),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator(color = officeProgressColor)
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.screen_media_office_loading),
                color = officePrimaryText,
                style = ElementTheme.typography.fontBodyLgRegular,
                textAlign = TextAlign.Center,
            )
            progress?.fraction?.let { fraction ->
                Spacer(modifier = Modifier.height(16.dp))
                LinearProgressIndicator(
                    progress = { fraction },
                    color = officeProgressColor,
                    trackColor = Color(0xFFD9D9D9),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (onCancel != null) {
                Spacer(modifier = Modifier.height(20.dp))
                OfficeOutlinedButton(
                    text = stringResource(CommonStrings.action_cancel),
                    onClick = onCancel,
                )
            }
        }
    }
}

@Composable
private fun OfficePreviewFailureView(
    failure: OfficePreviewFailure,
    onRetry: (() -> Unit)?,
    onOpenWith: (() -> Unit)?,
    topContentPadding: Dp,
    modifier: Modifier = Modifier,
) {
    val message = when (failure) {
        OfficePreviewFailure.FILE_TOO_LARGE -> R.string.screen_media_office_online_too_large
        OfficePreviewFailure.UNSUPPORTED_FORMAT -> R.string.screen_media_office_online_unsupported
        OfficePreviewFailure.SOURCE_UNAVAILABLE -> R.string.screen_media_office_online_source_unavailable
        OfficePreviewFailure.AUTHORIZATION_FAILED,
        OfficePreviewFailure.SERVER_REJECTED -> R.string.screen_media_office_online_error
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(officeLoadingBackground)
            .padding(top = topContentPadding),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(message),
                color = officeSecondaryText,
                style = ElementTheme.typography.fontBodyLgRegular,
                textAlign = TextAlign.Center,
            )
            if (onRetry != null) {
                Spacer(modifier = Modifier.height(16.dp))
                OfficeOutlinedButton(
                    text = stringResource(CommonStrings.action_retry),
                    onClick = onRetry,
                )
            }
            if (onOpenWith != null) {
                Spacer(modifier = Modifier.height(12.dp))
                OfficeOutlinedButton(
                    text = stringResource(CommonStrings.action_open_with),
                    onClick = onOpenWith,
                )
            }
        }
    }
}

@Composable
private fun OfficeOutlinedButton(
    text: String,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        border = BorderStroke(1.dp, Color(0xFF8A8A8A)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = officePrimaryText),
    ) {
        Text(text = text, color = officePrimaryText)
    }
}

private sealed interface OfficePreviewUiState {
    data object Waiting : OfficePreviewUiState
    data class Preparing(val progress: OfficePreviewProgress) : OfficePreviewUiState
    data class Ready(val document: OfficePreviewDocument) : OfficePreviewUiState
    data class Failed(val failure: OfficePreviewFailure) : OfficePreviewUiState
}

private fun OfficePreviewFailure.isRetryable(): Boolean = when (this) {
    OfficePreviewFailure.AUTHORIZATION_FAILED,
    OfficePreviewFailure.SERVER_REJECTED -> true
    OfficePreviewFailure.UNSUPPORTED_FORMAT,
    OfficePreviewFailure.FILE_TOO_LARGE,
    OfficePreviewFailure.SOURCE_UNAVAILABLE -> false
}
