/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.localofficeviewer.impl

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.webkit.WebViewAssetLoader
import io.element.android.libraries.localofficeviewer.api.FilePreviewBackingCleanupStatus
import io.element.android.libraries.localofficeviewer.api.FilePreviewLease
import io.element.android.libraries.localofficeviewer.api.FilePreviewLeaseReleaseReason
import io.element.android.libraries.localofficeviewer.api.FilePreviewLeaseReleaseResult
import io.element.android.libraries.localofficeviewer.api.FilePreviewLeaseState
import io.element.android.libraries.localofficeviewer.api.FilePreviewResourceLimits
import io.element.android.libraries.localofficeviewer.api.FilePreviewRuntime
import io.element.android.libraries.localofficeviewer.api.FilePreviewRuntimeCleanup
import io.element.android.libraries.localofficeviewer.api.LocalOfficeCleanupResult
import io.element.android.libraries.localofficeviewer.api.LocalOfficeCloseReason
import io.element.android.libraries.localofficeviewer.api.LocalOfficeFormat
import io.element.android.libraries.localofficeviewer.api.LocalOfficePreviewRequest
import io.element.android.libraries.localofficeviewer.api.LocalOfficePreviewState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@Composable
fun LocalOfficePreviewView(
    request: LocalOfficePreviewRequest,
    onState: (LocalOfficePreviewState) -> Unit,
    onClose: (LocalOfficeCloseReason, LocalOfficeCleanupResult) -> Unit,
    modifier: Modifier = Modifier,
    hostLease: FilePreviewLease? = null,
    requireHostLease: Boolean = false,
) {
    var attempt by remember(request.operationId) { mutableIntStateOf(0) }
    val leaseAuthorized = !requireHostLease || (
        hostLease?.operationId == request.operationId &&
            hostLease.verification.isSatisfied &&
            hostLease.state.value == FilePreviewLeaseState.Available
        )
    DisposableEffect(hostLease) {
        onDispose {
            hostLease?.release(FilePreviewLeaseReleaseReason.HOST_DISPOSED)
        }
    }
    if (!leaseAuthorized) {
        LaunchedEffect(request.operationId, hostLease) {
            onState(LocalOfficePreviewState.SECURITY_BLOCKED)
            onState(LocalOfficePreviewState.CLOSED)
            onClose(
                LocalOfficeCloseReason.ACCESS_REVOKED,
                LocalOfficeCleanupResult(
                    webRuntimeReleased = true,
                    sourceClosed = true,
                    validatedPayloadCleared = true,
                    hostLeaseReleased = false,
                    hostLeaseTokenMatched = false,
                    hostActiveStreamsClosed = false,
                    hostBackingCleanupStatus = FilePreviewBackingCleanupStatus.NOT_OBSERVABLE,
                    temporaryPlaintextRemoved = true,
                    pendingRecovery = false,
                )
            )
            onState(LocalOfficePreviewState.RESOURCES_RELEASED)
        }
        LocalOfficeMessage(
            text = stringResource(R.string.local_office_session_ended),
            modifier = modifier,
        )
        return
    }
    key(request.operationId, hostLease?.leaseToken, attempt) {
        LocalOfficePreviewAttempt(
            request = request,
            hostLease = hostLease,
            onState = onState,
            onClose = onClose,
            onRetry = { attempt++ },
            modifier = modifier,
        )
    }
}

@Composable
private fun LocalOfficePreviewAttempt(
    request: LocalOfficePreviewRequest,
    hostLease: FilePreviewLease?,
    onState: (LocalOfficePreviewState) -> Unit,
    onClose: (LocalOfficeCloseReason, LocalOfficeCleanupResult) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier,
) {
    val runtime = remember(request.operationId, hostLease) { hostLease?.beginRuntime() }
    if (hostLease?.tracksRuntimeCleanup == true && runtime == null) {
        LocalOfficeMessage(text = stringResource(R.string.local_office_session_ended), modifier = modifier)
        return
    }
    val token = remember(request.operationId) { UUID.randomUUID().toString() }
    val currentOnState by rememberUpdatedState(onState)
    val currentOnClose by rememberUpdatedState(onClose)
    var detection by remember(request.operationId) { mutableStateOf<LocalOfficeDetection?>(null) }
    var failureCode by remember(request.operationId) { mutableStateOf<String?>(null) }
    var ready by remember(request.operationId) { mutableStateOf(false) }
    var terminatedByLock by remember(request.operationId) { mutableStateOf(false) }
    var leaseRevoked by remember(request.operationId) { mutableStateOf(false) }
    var leaseReleaseReason by remember(request.operationId) { mutableStateOf<FilePreviewLeaseReleaseReason?>(null) }
    var preserveHostLeaseOnDispose by remember(request.operationId) { mutableStateOf(false) }
    val retryWithCurrentHostLease = {
        preserveHostLeaseOnDispose = true
        onRetry()
    }
    val validationRunner = remember(request.operationId) { LocalOfficeValidationRunner(request) }
    val sourceHandler = remember(request.operationId) { SessionContentPathHandler(token) }
    val runtimeGate = remember(request.operationId) {
        LocalOfficeRuntimeGate(
            sourceHandler = sourceHandler,
            hostLease = hostLease,
            validationRunner = validationRunner,
            runtime = runtime,
            onState = { currentOnState(it) },
            onClose = { reason, cleanup -> currentOnClose(reason, cleanup) },
        )
    }

    LaunchedEffect(hostLease) {
        hostLease?.state?.collect { state ->
            if (state is FilePreviewLeaseState.Released) {
                validationRunner.cancel()
                leaseRevoked = true
                leaseReleaseReason = state.reason
                currentOnState(LocalOfficePreviewState.SECURITY_BLOCKED)
                if (runtimeGate.beginTermination(LocalOfficeCloseReason.ACCESS_REVOKED) && !runtimeGate.hasWebRuntime()) {
                    runtimeGate.finishWithoutRuntime(LocalOfficeCloseReason.ACCESS_REVOKED)
                }
            }
        }
    }

    LaunchedEffect(request.operationId) {
        currentOnState(LocalOfficePreviewState.PREPARING)
        currentOnState(LocalOfficePreviewState.VALIDATING)
        val result = validationRunner.await()
        if (!runtimeGate.isAcceptingResults()) {
            result.clearValidatedPayload()
            return@LaunchedEffect
        }
        if (result is LocalOfficeDetection.Accepted && !sourceHandler.install(result.payload, mimeFor(result.summary.format))) {
            return@LaunchedEffect
        }
        detection = result
        when (result) {
            LocalOfficeDetection.Unsupported -> {
                currentOnState(LocalOfficePreviewState.UNSUPPORTED)
                runtimeGate.finishWithoutRuntime(LocalOfficeCloseReason.UNSUPPORTED)
            }
            is LocalOfficeDetection.SecurityBlocked -> {
                Timber.w("LC-16 local Office validation blocked code=%s", result.reason.name)
                val retryable = result.reason.isRetryableValidationFailure()
                currentOnState(
                    if (retryable) LocalOfficePreviewState.VALIDATION_FAILED else LocalOfficePreviewState.SECURITY_BLOCKED
                )
                runtimeGate.finishWithoutRuntime(
                    if (retryable) LocalOfficeCloseReason.VALIDATION_FAILED else LocalOfficeCloseReason.SECURITY_BLOCKED
                )
            }
            is LocalOfficeDetection.Accepted -> currentOnState(LocalOfficePreviewState.RENDERING)
        }
    }

    LaunchedEffect(detection, ready, failureCode) {
        if (detection is LocalOfficeDetection.Accepted && !ready && failureCode == null) {
            delay(RENDER_TIMEOUT_MILLIS)
            if (!ready && failureCode == null) {
                if (runtimeGate.beginTermination(LocalOfficeCloseReason.RENDER_TIMEOUT)) {
                    failureCode = RENDER_TIMEOUT_CODE
                    currentOnState(LocalOfficePreviewState.RENDER_FAILED)
                }
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, runtimeGate) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                validationRunner.cancel()
                if (runtimeGate.beginTermination(LocalOfficeCloseReason.APP_LOCKED)) {
                    terminatedByLock = true
                    if (!runtimeGate.hasWebRuntime()) {
                        runtimeGate.finishWithoutRuntime(LocalOfficeCloseReason.APP_LOCKED)
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            validationRunner.cancel()
            runtimeGate.disposeAttempt(preserveHostLease = preserveHostLeaseOnDispose)
        }
    }

    if (leaseRevoked) {
        LocalOfficeMessage(
            text = stringResource(leaseReleaseReason.officeLeaseReleaseMessageResource()),
            modifier = modifier,
        )
    } else {
        when (val result = detection) {
        null -> LocalOfficeMessage(
            text = stringResource(R.string.local_office_validating),
            loading = true,
            modifier = modifier,
        )
        LocalOfficeDetection.Unsupported -> LocalOfficeMessage(
            text = stringResource(R.string.local_office_unsupported),
            modifier = modifier,
        )
        is LocalOfficeDetection.SecurityBlocked -> {
            val retryable = result.reason.isRetryableValidationFailure()
            LocalOfficeMessage(
                text = stringResource(
                    result.reason.messageResource()
                ),
                actionLabel = stringResource(R.string.local_office_retry).takeIf { retryable },
                onAction = retryWithCurrentHostLease.takeIf { retryable },
                modifier = modifier,
            )
        }
        is LocalOfficeDetection.Accepted -> when {
            terminatedByLock -> LocalOfficeMessage(
                text = stringResource(R.string.local_office_session_locked),
                modifier = modifier,
            )
            failureCode == null -> Column(modifier = modifier) {
                if (result.summary.format in setOf(LocalOfficeFormat.DOC, LocalOfficeFormat.XLS, LocalOfficeFormat.PPT)) {
                    Text(
                        text = stringResource(R.string.local_office_legacy_fidelity_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                IsolatedOfficeWebView(
                    request = request,
                    validation = result.summary,
                    token = token,
                    sourceHandler = sourceHandler,
                    runtimeGate = runtimeGate,
                    onReady = {
                        if (runtimeGate.isAcceptingResults()) {
                            ready = true
                            currentOnState(LocalOfficePreviewState.READY)
                        }
                    },
                    onFailure = { code ->
                        val reason = if (code == RENDER_TIMEOUT_CODE) {
                            LocalOfficeCloseReason.RENDER_TIMEOUT
                        } else {
                            LocalOfficeCloseReason.RENDER_FAILED
                        }
                        if (runtimeGate.beginTermination(reason)) {
                            failureCode = code
                            currentOnState(LocalOfficePreviewState.RENDER_FAILED)
                        }
                    },
                    modifier = Modifier.weight(1f).fillMaxSize(),
                )
            }
            else -> LocalOfficeMessage(
                text = stringResource(
                    if (failureCode == RENDER_TIMEOUT_CODE) R.string.local_office_render_timeout else R.string.local_office_render_failed
                ),
                actionLabel = stringResource(R.string.local_office_retry),
                onAction = retryWithCurrentHostLease,
                modifier = modifier,
            )
        }
    }
}
}

@Composable
private fun LocalOfficeMessage(
    text: String,
    modifier: Modifier,
    loading: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            if (loading) CircularProgressIndicator()
            Text(text)
            if (actionLabel != null && onAction != null) {
                Button(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun IsolatedOfficeWebView(
    request: LocalOfficePreviewRequest,
    validation: LocalOfficeValidationSummary,
    token: String,
    sourceHandler: SessionContentPathHandler,
    runtimeGate: LocalOfficeRuntimeGate,
    onReady: () -> Unit,
    onFailure: (String) -> Unit,
    modifier: Modifier,
) {
    val indexUrl = remember(request.operationId) {
        Uri.Builder()
            .scheme("https")
            .authority(WebViewAssetLoader.DEFAULT_DOMAIN)
            .path("/assets/local-office-viewer/index.html")
            .appendQueryParameter("token", token)
            .appendQueryParameter("name", request.displayName)
            .appendQueryParameter("type", request.declaredMimeType ?: mimeFor(validation.format))
            .build()
            .toString()
    }
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            val loader = WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
                .addPathHandler("/local-office-viewer/content/", sourceHandler)
                .build()
            WebView(context).apply {
                runtimeGate.markCreated()
                with(settings) {
                    javaScriptEnabled = true
                    domStorageEnabled = false
                    databaseEnabled = false
                    allowContentAccess = false
                    allowFileAccess = false
                    javaScriptCanOpenWindowsAutomatically = false
                    setSupportMultipleWindows(false)
                    setGeolocationEnabled(false)
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    cacheMode = WebSettings.LOAD_NO_CACHE
                    blockNetworkLoads = true
                    setSupportZoom(false)
                    builtInZoomControls = false
                    displayZoomControls = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = true
                }
                CookieManager.getInstance().setAcceptCookie(false)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                setNetworkAvailable(false)
                setDownloadListener { _, _, _, _, _ -> onFailure("download-blocked") }
                webChromeClient = object : WebChromeClient() {
                    override fun onPermissionRequest(request: PermissionRequest) = request.deny()
                    override fun onCreateWindow(
                        view: WebView?,
                        isDialog: Boolean,
                        isUserGesture: Boolean,
                        resultMsg: android.os.Message?,
                    ): Boolean = false
                }
                webViewClient = IsolatedOfficeWebViewClient(loader, indexUrl, onFailure)
                addJavascriptInterface(
                    OfficeBridge(
                        onReady = { post { onReady() } },
                        onFailure = { code -> post { onFailure(code) } },
                    ),
                    BRIDGE_NAME,
                )
                loadUrl(indexUrl)
            }
        },
        update = { webView -> if (webView.url != indexUrl) webView.loadUrl(indexUrl) },
        onRelease = { webView ->
            webView.evaluateJavascript("globalThis.YiZheOffice?.destroy?.()", null)
            webView.stopLoading()
            webView.removeJavascriptInterface(BRIDGE_NAME)
            webView.clearFormData()
            webView.clearHistory()
            webView.loadUrl("about:blank")
            webView.destroy()
            runtimeGate.finishAfterWebRuntimeReleased()
        },
    )
}

internal class LocalOfficeRuntimeGate(
    private val sourceHandler: SessionContentPathHandler,
    private val hostLease: FilePreviewLease? = null,
    private val validationRunner: LocalOfficeValidationRunner? = null,
    private val runtime: FilePreviewRuntime? = hostLease?.beginRuntime(),
    private val onState: (LocalOfficePreviewState) -> Unit,
    private val onClose: (LocalOfficeCloseReason, LocalOfficeCleanupResult) -> Unit,
) {
    private val runtimeCreated = AtomicBoolean(false)
    private val acceptingResults = AtomicBoolean(true)
    private val finished = AtomicBoolean(false)
    private val reason = AtomicReference(LocalOfficeCloseReason.HOST_DISPOSED)
    private val hostRelease = AtomicReference<FilePreviewLeaseReleaseResult?>()

    fun markCreated() {
        runtimeCreated.set(true)
    }

    fun hasWebRuntime(): Boolean = runtimeCreated.get()

    fun isAcceptingResults(): Boolean = acceptingResults.get()

    fun beginTermination(value: LocalOfficeCloseReason): Boolean {
        if (value.requiresImmediateHostLeaseRelease()) releaseHostLease(value)
        if (!acceptingResults.compareAndSet(true, false)) return false
        reason.set(value)
        sourceHandler.revoke()
        return true
    }

    fun finishWithoutRuntime(closeReason: LocalOfficeCloseReason) {
        beginTermination(closeReason)
        finish(webRuntimeReleased = true)
    }

    fun disposeAttempt(preserveHostLease: Boolean = false) {
        if (!preserveHostLease) releaseHostLease(LocalOfficeCloseReason.HOST_DISPOSED)
        beginTermination(LocalOfficeCloseReason.HOST_DISPOSED)
        if (!runtimeCreated.get()) finish(webRuntimeReleased = true)
    }

    fun finishAfterWebRuntimeReleased() {
        beginTermination(reason.get())
        finish(webRuntimeReleased = true)
    }

    private fun finish(webRuntimeReleased: Boolean) {
        if (!finished.compareAndSet(false, true)) return
        onState(LocalOfficePreviewState.CLOSED)
        val deliver = { validationClosed: Boolean ->
        val release = hostRelease.get()
        val sourceCleared = sourceHandler.isCleanupComplete()
        runtime?.complete(FilePreviewRuntimeCleanup(webRuntimeReleased, validationClosed, sourceCleared))
        onClose(
            reason.get(),
            LocalOfficeCleanupResult(
                webRuntimeReleased = webRuntimeReleased,
                sourceClosed = validationClosed && sourceCleared,
                validatedPayloadCleared = validationClosed && sourceCleared,
                hostLeaseReleased = release?.hostHandleCloseCompleted == true,
                hostLeaseTokenMatched = release?.leaseTokenMatched == true,
                hostActiveStreamsClosed = release?.activeStreamsClosed == true,
                hostBackingCleanupStatus = release?.backingCleanupStatus
                    ?: FilePreviewBackingCleanupStatus.NOT_OBSERVABLE,
                temporaryPlaintextRemoved = validationClosed && sourceCleared,
                pendingRecovery = !validationClosed || !sourceCleared,
            ),
        )
        onState(LocalOfficePreviewState.RESOURCES_RELEASED)
        }
        if (validationRunner == null) {
            deliver(true)
        } else {
            // This observer survives Compose cancellation and checks the worker's actual termination.
            CoroutineScope(Dispatchers.Main.immediate).launch {
                deliver(validationRunner.awaitCleanup())
            }
        }
    }

    private fun releaseHostLease(closeReason: LocalOfficeCloseReason) {
        synchronized(hostRelease) {
            if (hostRelease.get() != null) return
            val lease = hostLease ?: return
            hostRelease.set(lease.release(closeReason.toLeaseReleaseReason()))
        }
    }
}

internal fun FilePreviewLeaseReleaseReason?.officeLeaseReleaseMessageResource(): Int = when (this) {
    FilePreviewLeaseReleaseReason.SECURITY_BLOCKED -> R.string.local_office_security_blocked
    FilePreviewLeaseReleaseReason.APP_LOCKED -> R.string.local_office_session_locked
    FilePreviewLeaseReleaseReason.SESSION_EXPIRED -> R.string.local_office_session_expired
    else -> R.string.local_office_session_ended
}

private fun LocalOfficeCloseReason.requiresImmediateHostLeaseRelease(): Boolean = when (this) {
    LocalOfficeCloseReason.USER_CLOSE,
    LocalOfficeCloseReason.CANCELLED,
    LocalOfficeCloseReason.ATTACHMENT_SWITCHED,
    LocalOfficeCloseReason.APP_LOCKED,
    LocalOfficeCloseReason.ACCESS_REVOKED,
    LocalOfficeCloseReason.ACCOUNT_EXITED,
    LocalOfficeCloseReason.UNSUPPORTED,
    LocalOfficeCloseReason.SECURITY_BLOCKED -> true
    LocalOfficeCloseReason.VALIDATION_FAILED,
    LocalOfficeCloseReason.RENDER_TIMEOUT,
    LocalOfficeCloseReason.RENDER_FAILED,
    LocalOfficeCloseReason.HOST_DISPOSED -> false
}

private fun LocalOfficeCloseReason.toLeaseReleaseReason(): FilePreviewLeaseReleaseReason = when (this) {
    LocalOfficeCloseReason.USER_CLOSE -> FilePreviewLeaseReleaseReason.USER_CLOSE
    LocalOfficeCloseReason.CANCELLED -> FilePreviewLeaseReleaseReason.CANCELLED
    LocalOfficeCloseReason.ATTACHMENT_SWITCHED -> FilePreviewLeaseReleaseReason.ATTACHMENT_SWITCHED
    LocalOfficeCloseReason.APP_LOCKED -> FilePreviewLeaseReleaseReason.APP_LOCKED
    LocalOfficeCloseReason.ACCESS_REVOKED -> FilePreviewLeaseReleaseReason.ACCESS_REVOKED
    LocalOfficeCloseReason.ACCOUNT_EXITED -> FilePreviewLeaseReleaseReason.ACCOUNT_EXITED
    LocalOfficeCloseReason.SECURITY_BLOCKED,
    LocalOfficeCloseReason.UNSUPPORTED -> FilePreviewLeaseReleaseReason.SECURITY_BLOCKED
    LocalOfficeCloseReason.HOST_DISPOSED,
    LocalOfficeCloseReason.VALIDATION_FAILED,
    LocalOfficeCloseReason.RENDER_TIMEOUT,
    LocalOfficeCloseReason.RENDER_FAILED -> FilePreviewLeaseReleaseReason.HOST_DISPOSED
}

internal class SessionContentPathHandler(
    private val token: String,
) : WebViewAssetLoader.PathHandler {
    private val revoked = AtomicBoolean(false)
    private val served = AtomicBoolean(false)
    private val payload = AtomicReference<InstalledPayload?>()
    private val activeStreams = ConcurrentHashMap.newKeySet<InputStream>()
    private val cleanupFailed = AtomicBoolean(false)

    @Synchronized
    fun install(value: LocalOfficeValidatedPayload, mimeType: String): Boolean {
        val installed = InstalledPayload(value, mimeType)
        if (revoked.get() || !payload.compareAndSet(null, installed)) {
            value.clear()
            return false
        }
        if (revoked.get() && payload.compareAndSet(installed, null)) {
            value.clear()
            return false
        }
        return true
    }

    override fun handle(path: String): WebResourceResponse? {
        val streamAndMime = openPayloadStream(path) ?: return blockedResponse()
        return WebResourceResponse(
            streamAndMime.mimeType,
            null,
            200,
            "OK",
            mapOf(
                "Cache-Control" to "no-store",
                "Content-Security-Policy" to "default-src 'none'",
                "X-Content-Type-Options" to "nosniff",
            ),
            streamAndMime.stream,
        )
    }

    @Synchronized
    internal fun openPayloadStream(path: String): OpenPayload? {
        if (revoked.get() || path != token || !served.compareAndSet(false, true)) return null
        val installed = payload.get() ?: return null
        val rawStream = runCatching { installed.payload.openInputStream() }.getOrElse { return null }
        lateinit var trackedStream: InputStream
        trackedStream = object : FilterInputStream(rawStream) {
            private val closed = AtomicBoolean(false)

            override fun read(): Int {
                ensureReadable()
                return super.read()
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                ensureReadable()
                return super.read(buffer, offset, length)
            }

            override fun close() {
                if (!closed.compareAndSet(false, true)) return
                try {
                    super.close()
                } finally {
                    activeStreams.remove(trackedStream)
                }
            }

            private fun ensureReadable() {
                if (closed.get() || revoked.get()) throw IOException("Local Office payload revoked")
            }
        }
        activeStreams.add(trackedStream)
        return OpenPayload(installed.mimeType, trackedStream)
    }

    @Synchronized
    fun revoke() {
        if (!revoked.compareAndSet(false, true)) return
        activeStreams.toList().forEach { stream ->
            if (runCatching { stream.close() }.isFailure) cleanupFailed.set(true)
        }
        activeStreams.clear()
        payload.getAndSet(null)?.payload?.let { value ->
            value.clear()
            if (!value.isCleared()) cleanupFailed.set(true)
        }
    }

    @Synchronized
    fun isCleanupComplete(): Boolean = revoked.get() && !cleanupFailed.get() && activeStreams.isEmpty() && payload.get() == null

    private data class InstalledPayload(
        val payload: LocalOfficeValidatedPayload,
        val mimeType: String,
    )

    internal data class OpenPayload(
        val mimeType: String,
        val stream: InputStream,
    )
}

private class OfficeBridge(
    private val onReady: () -> Unit,
    private val onFailure: (String) -> Unit,
) {
    private val readyDelivered = AtomicBoolean(false)
    private val failureDelivered = AtomicBoolean(false)

    @JavascriptInterface
    fun ready(value: String) {
        if (readyDelivered.compareAndSet(false, true)) onReady()
    }

    @JavascriptInterface fun progress(value: String) = Unit
    @JavascriptInterface fun warning(value: String) = Unit

    @JavascriptInterface
    fun failed(value: String) {
        if (!failureDelivered.compareAndSet(false, true)) return
        val code = stableRendererCode(value)
        Timber.w("LC-16 local Office render failed code=%s", code)
        onFailure(code)
    }
}

private class IsolatedOfficeWebViewClient(
    private val loader: WebViewAssetLoader,
    private val indexUrl: String,
    private val onFailure: (String) -> Unit,
) : WebViewClient() {
    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse =
        loader.shouldInterceptRequest(request.url) ?: blockedResponse()

    @Deprecated("Deprecated in Java")
    override fun shouldInterceptRequest(view: WebView?, url: String): WebResourceResponse =
        loader.shouldInterceptRequest(Uri.parse(url)) ?: blockedResponse()

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean =
        request.isForMainFrame && request.url.toString() != indexUrl

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        if (url != indexUrl && url != "about:blank") onFailure("navigation-blocked")
    }

    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
        if (request?.isForMainFrame == true) onFailure("page-error")
    }

    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: android.net.http.SslError?) {
        handler?.cancel()
        onFailure("ssl-error")
    }

    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
        onFailure("renderer-process-gone")
        return true
    }
}

private fun stableRendererCode(value: String): String {
    val candidate = value.substringBefore(':')
        .lowercase()
        .filter { it in 'a'..'z' || it in '0'..'9' || it == '-' }
        .take(MAX_RENDERER_CODE_LENGTH)
    return candidate.ifBlank { "render-failed" }
}

private fun LocalOfficeBlockReason.isRetryableValidationFailure(): Boolean =
    this == LocalOfficeBlockReason.VALIDATION_TIMEOUT || this == LocalOfficeBlockReason.READ_FAILED

private fun LocalOfficeBlockReason.messageResource(): Int = when (this) {
    LocalOfficeBlockReason.INPUT_SIZE_LIMIT -> R.string.local_office_input_size_limit
    LocalOfficeBlockReason.ARCHIVE_ENTRY_LIMIT,
    LocalOfficeBlockReason.ARCHIVE_ENTRY_SIZE_LIMIT,
    LocalOfficeBlockReason.ARCHIVE_EXPANDED_SIZE_LIMIT,
    LocalOfficeBlockReason.DOCUMENT_UNIT_LIMIT,
    LocalOfficeBlockReason.CSV_STRUCTURE_LIMIT -> R.string.local_office_complexity_limit
    LocalOfficeBlockReason.MEMORY_UNAVAILABLE -> R.string.local_office_memory_unavailable
    LocalOfficeBlockReason.VALIDATION_TIMEOUT -> R.string.local_office_validation_timeout
    LocalOfficeBlockReason.READ_FAILED -> R.string.local_office_validation_failed
    else -> R.string.local_office_security_blocked
}

private fun blockedResponse() = WebResourceResponse(
    "text/plain",
    "UTF-8",
    403,
    "Forbidden",
    mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff"),
    ByteArrayInputStream(ByteArray(0)),
)

private fun mimeFor(format: LocalOfficeFormat): String = when (format) {
    LocalOfficeFormat.DOC -> "application/msword"
    LocalOfficeFormat.DOCX -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    LocalOfficeFormat.DOCM -> "application/vnd.ms-word.document.macroEnabled.12"
    LocalOfficeFormat.XLS -> "application/vnd.ms-excel"
    LocalOfficeFormat.XLSX -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    LocalOfficeFormat.XLSM -> "application/vnd.ms-excel.sheet.macroEnabled.12"
    LocalOfficeFormat.PPT -> "application/vnd.ms-powerpoint"
    LocalOfficeFormat.PPTX -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    LocalOfficeFormat.CSV -> "text/csv"
}

private const val BRIDGE_NAME = "YiZheOfficeBridge"
private const val RENDER_TIMEOUT_CODE = "render-timeout"
private const val RENDER_TIMEOUT_MILLIS = FilePreviewResourceLimits.RENDER_TIMEOUT_MILLIS
private const val MAX_RENDERER_CODE_LENGTH = 64
