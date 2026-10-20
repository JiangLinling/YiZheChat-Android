/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.call.impl.ui

import android.content.Context
import android.annotation.SuppressLint
import android.os.Build
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.viewinterop.AndroidView
import io.element.android.features.call.impl.R
import io.element.android.features.call.impl.pip.PictureInPictureEvent
import io.element.android.features.call.impl.pip.PictureInPictureState
import io.element.android.features.call.impl.pip.aPictureInPictureState
import io.element.android.features.call.impl.utils.InvalidAudioDeviceReason
import io.element.android.features.call.impl.utils.WebViewAudioManager
import io.element.android.features.call.impl.utils.WebViewPipController
import io.element.android.features.call.impl.utils.WebViewWidgetMessageInterceptor
import io.element.android.features.call.impl.utils.isSameOrigin
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.designsystem.components.ProgressDialog
import io.element.android.libraries.designsystem.components.dialogs.ErrorDialog
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.ui.strings.CommonStrings
import timber.log.Timber

typealias RequestPermissionCallback = (Array<String>) -> Unit

/** Call surfaces always use a dark theme, including system icons and native preparation. */
@Composable
internal fun CallTheme(
    appPreferencesStore: io.element.android.libraries.preferences.api.store.AppPreferencesStore,
    featureFlagService: io.element.android.libraries.featureflag.api.FeatureFlagService,
    compoundLight: io.element.android.compound.tokens.generated.SemanticColors,
    compoundDark: io.element.android.compound.tokens.generated.SemanticColors,
    buildMeta: io.element.android.libraries.core.meta.BuildMeta,
    content: @Composable () -> Unit,
) {
    if (io.element.android.appconfig.NextDeploymentConfig.isNextDeployment) {
        io.element.android.compound.theme.ElementTheme(
            theme = io.element.android.compound.theme.Theme.Dark,
            compoundDark = compoundDark,
            materialColorsDark = androidx.compose.material3.darkColorScheme(background = Color(0xFF252A2E)),
            content = content,
        )
    } else {
        io.element.android.libraries.designsystem.theme.ElementThemeApp(
            appPreferencesStore = appPreferencesStore, featureFlagService = featureFlagService,
            compoundLight = compoundLight, compoundDark = compoundDark, buildMeta = buildMeta, content = content,
        )
    }
}

interface CallScreenNavigator {
    fun close()
}

@Composable
internal fun CallScreenView(
    state: CallScreenState,
    pipState: PictureInPictureState,
    onConsoleMessage: (ConsoleMessage) -> Unit,
    requestPermissions: (Array<String>, RequestPermissionCallback) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentState by rememberUpdatedState(state)
    val currentPipState by rememberUpdatedState(pipState)
    var callWebView by remember { mutableStateOf<WebView?>(null) }
    var rtcErrorPresented by remember { mutableStateOf(false) }
    SideEffect { callWebView?.installYiZheCallTheme(currentState) }

    fun handleBack(fromNative: Boolean = false) {
        if (io.element.android.appconfig.NextDeploymentConfig.isNextDeployment && !currentState.isCallActive) {
            currentState.eventSink(CallScreenEvent.Hangup)
            return
        }
        when (CallScreenBackPressPolicy.resolve(supportPip = currentPipState.supportPip, hasWebView = callWebView != null, fromNative)) {
            CallScreenBackPressAction.EnterPictureInPicture ->
                currentPipState.eventSink(PictureInPictureEvent.EnterPictureInPicture)
            CallScreenBackPressAction.DispatchEscapeToWebView ->
                callWebView?.dispatchEscKeyEvent()
            null -> Timber.d("Back press with unsupported pip is a no-op")
        }
    }

    BackHandler {
        handleBack(fromNative = true)
    }
    if (state.webViewError != null) {
        ErrorDialog(
            content = buildString {
                append(stringResource(CommonStrings.error_unknown))
                state.webViewError.takeIf { it.isNotEmpty() }?.let { append("\n\n").append(it) }
            },
            onSubmit = { state.eventSink(CallScreenEvent.Hangup) },
        )
    } else {
        var webViewAudioManager by remember { mutableStateOf<WebViewAudioManager?>(null) }
        LaunchedEffect(state.urlState, callWebView, webViewAudioManager) {
            if (state.urlState is AsyncData.Success && callWebView != null &&
                webViewAudioManager?.isInCallMode?.get() == false
            ) webViewAudioManager?.onCallStarted()
        }
        val coroutineScope = rememberCoroutineScope()

        var invalidAudioDeviceReason by remember { mutableStateOf<InvalidAudioDeviceReason?>(null) }
        invalidAudioDeviceReason?.let {
            InvalidAudioDeviceDialog(invalidAudioDeviceReason = it) {
                invalidAudioDeviceReason = null
            }
        }

        val renderUrl = state.renderCallUrl(io.element.android.appconfig.NextDeploymentConfig.isNextDeployment)
        if (renderUrl != null) {
            Box(modifier = modifier.fillMaxSize()) {
                CallWebView(
                    modifier = Modifier.consumeWindowInsets(WindowInsets.systemBars).fillMaxSize(),
                    url = renderUrl,
                    userAgent = state.userAgent,
                    onPermissionsRequest = { request ->
                        if (currentState.urlState !is AsyncData.Success || currentState.isTerminating) {
                            request.deny()
                        } else {
                            val androidPermissions = mapWebkitPermissions(request.resources)
                            val callback: RequestPermissionCallback = { request.grant(it) }
                            requestPermissions(androidPermissions.toTypedArray(), callback)
                        }
                    },
                    onConsoleMessage = onConsoleMessage,
                    onCreateWebView = { webView, allowedOrigin ->
                        callWebView = webView
                        rtcErrorPresented = false
                        webView.addBackHandler(onBackPressed = ::handleBack)
                        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                            WebViewCompat.addWebMessageListener(webView, "yizheCallUi", setOf(allowedOrigin)) { view, message, origin, mainFrame, _ ->
                                if (acceptCallUiTerminalError(message.data, origin.toString(), allowedOrigin, mainFrame)) {
                                    view.post {
                                        if (callWebView === view) rtcErrorPresented = true
                                    }
                                }
                                if (acceptCallUiHangup(message.data, origin.toString(), allowedOrigin, mainFrame)) {
                                    view.post {
                                        if (callWebView === view) currentState.eventSink(CallScreenEvent.Hangup)
                                    }
                                }
                                if (acceptCallUiToggleSpeaker(message.data, origin.toString(), allowedOrigin, mainFrame)) {
                                    view.post {
                                        if (callWebView === view) webViewAudioManager?.toggleSpeaker()
                                    }
                                }
                            }
                        }
                        val interceptor = WebViewWidgetMessageInterceptor(
                            webView = webView,
                            allowedOrigin = allowedOrigin,
                            onUrlLoaded = {
                                Timber.tag("RTCSetup").d("phase=page_loaded")
                                webViewAudioManager?.onWidgetReady()
                                webView.installYiZheCallTheme(currentState)
                                webView.evaluateJavascript("window.controls.onBackButtonPressed = () => { backHandler.onBackPressed() }", null)
                                if (currentState.urlState is AsyncData.Success && webViewAudioManager?.isInCallMode?.get() == false) {
                                    Timber.d("Element Call loaded; starting in-call audio mode")
                                    webViewAudioManager?.onCallStarted()
                                } else {
                                    Timber.d("Can't start in-call audio mode since the app is already in it.")
                                }
                            },
                            onError = { currentState.eventSink(CallScreenEvent.OnWebViewError(null)) },
                        )
                        webViewAudioManager = WebViewAudioManager(
                            webView = webView,
                            coroutineScope = coroutineScope,
                            onInvalidAudioDeviceAdded = { invalidAudioDeviceReason = it },
                            preferSpeaker = !currentState.isAudioCall,
                            startOnSpeaker = !io.element.android.appconfig.NextDeploymentConfig.isNextDeployment && !currentState.isAudioCall,
                        )
                        state.eventSink(CallScreenEvent.SetupMessageChannels(interceptor))
                        val pipController = WebViewPipController(webView)
                        pipState.eventSink(PictureInPictureEvent.SetPipController(pipController))
                    },
                    onDestroyWebView = {
                        callWebView = null
                        // Reset audio mode
                        webViewAudioManager?.onCallStopped()
                    }
                )
                if (state.isCallActive && state.isNetworkDisconnected && !rtcErrorPresented && !pipState.isInPictureInPicture) {
                    Text(
                        text = stringResource(R.string.call_network_disconnected),
                        color = Color.White,
                        modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 52.dp)
                            .background(Color(0xDD292D2A), RoundedCornerShape(8.dp)).padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
        }
        if (io.element.android.appconfig.NextDeploymentConfig.isNextDeployment && (!state.isTerminating || state.urlState is AsyncData.Failure) &&
            (state.urlState !is AsyncData.Success || !state.isCallActive)
        ) {
            CallPreparationView(state)
        }
        if (!io.element.android.appconfig.NextDeploymentConfig.isNextDeployment) when (state.urlState) {
            AsyncData.Uninitialized,
            is AsyncData.Loading ->
                ProgressDialog(text = stringResource(id = CommonStrings.common_please_wait))
            is AsyncData.Failure -> {
                Timber.e("Element Call initialization failed; details are redacted.")
                ErrorDialog(
                    content = stringResource(CommonStrings.error_unknown),
                    onSubmit = { state.eventSink(CallScreenEvent.Hangup) },
                )
            }
            is AsyncData.Success -> Unit
        }
    }
}

@Composable
private fun InvalidAudioDeviceDialog(
    invalidAudioDeviceReason: InvalidAudioDeviceReason,
    onDismiss: () -> Unit,
) {
    ErrorDialog(
        content = when (invalidAudioDeviceReason) {
            InvalidAudioDeviceReason.BT_AUDIO_DEVICE_DISABLED -> {
                stringResource(R.string.call_invalid_audio_device_bluetooth_devices_disabled)
            }
        },
        onSubmit = onDismiss,
    )
}

@Composable
private fun CallWebView(
    url: String,
    userAgent: String,
    onPermissionsRequest: (PermissionRequest) -> Unit,
    onConsoleMessage: (ConsoleMessage) -> Unit,
    onCreateWebView: (WebView, String) -> Unit,
    onDestroyWebView: (WebView) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (LocalInspectionMode.current) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text("WebView - can't be previewed")
        }
    } else {
        val allowedOrigin = remember(url) { callOrigin(url) }
        if (allowedOrigin == null) {
            ErrorDialog(
                content = stringResource(CommonStrings.error_unknown),
                onSubmit = {},
            )
            return
        }
        AndroidView(
            modifier = modifier,
            factory = { context ->
                CallContentWebView(context).apply {
                    setup(
                        userAgent = userAgent,
                        allowedOrigin = allowedOrigin,
                        onPermissionsRequested = onPermissionsRequest,
                        onConsoleMessage = onConsoleMessage,
                    )
                    onCreateWebView(this, allowedOrigin)
                }
            },
            update = { webView ->
                // WebView.url remains unset until the asynchronous navigation commits.
                // Native preparation/receipt recompositions must not restart that navigation.
                if (webView.requestedUrl != url) {
                    webView.requestedUrl = url
                    Timber.tag("RTCSetup").d("phase=page_requested")
                    webView.loadUrl(url)
                }
            },
            onRelease = { webView ->
                onDestroyWebView(webView)
                webView.stopLoading()
                webView.removeJavascriptInterface("backHandler")
                if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                    WebViewCompat.removeWebMessageListener(webView, "yizheCallUi")
                }
                webView.removeJavascriptInterface(WebViewWidgetMessageInterceptor.LISTENER_NAME)
                webView.clearFormData()
                webView.clearHistory()
                webView.clearCache(true)
                WebStorage.getInstance().deleteOrigin(allowedOrigin)
                webView.destroy()
            }
        )
    }
}

private class CallContentWebView(context: Context) : WebView(context) {
    var requestedUrl: String? = null
}

@SuppressLint("SetJavaScriptEnabled")
private fun WebView.setup(
    userAgent: String,
    allowedOrigin: String,
    onPermissionsRequested: (PermissionRequest) -> Unit,
    onConsoleMessage: (ConsoleMessage) -> Unit,
) {
    layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
    )

    with(settings) {
        javaScriptEnabled = true
        allowContentAccess = false
        allowFileAccess = false
        domStorageEnabled = true
        mediaPlaybackRequiresUserGesture = false
        @Suppress("DEPRECATION")
        databaseEnabled = false
        loadsImagesAutomatically = true
        cacheMode = WebSettings.LOAD_NO_CACHE
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        setGeolocationEnabled(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            safeBrowsingEnabled = true
        }
        userAgentString = userAgent
    }
    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
    isVerticalScrollBarEnabled = false
    isHorizontalScrollBarEnabled = false

    webChromeClient = object : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest) {
            val allowedResources = request.resources.filter {
                it == PermissionRequest.RESOURCE_AUDIO_CAPTURE || it == PermissionRequest.RESOURCE_VIDEO_CAPTURE
            }
            if (isSameOrigin(request.origin.toString(), allowedOrigin) &&
                allowedResources.isNotEmpty() &&
                allowedResources.size == request.resources.size
            ) {
                onPermissionsRequested(request)
            } else {
                Timber.w("Blocked an untrusted Element Call media permission request.")
                request.deny()
            }
        }

        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
            onConsoleMessage(consoleMessage)
            return true
        }
    }
}

internal fun callOrigin(url: String): String? {
    val uri = android.net.Uri.parse(url)
    if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null) return null
    val port = if (uri.port == -1 || uri.port == 443) "" else ":${uri.port}"
    return "https://${uri.host}$port"
}

private fun WebView.addBackHandler(onBackPressed: () -> Unit) {
    addJavascriptInterface(
        // JavascriptInterface runs on WebView's bridge thread. Compose events and
        // all subsequent WebView/Activity operations belong to the UI thread.
        JavascriptBackHandlerBridge(callback = { post { onBackPressed() } }),
        "backHandler"
    )
}

private fun WebView.dispatchEscKeyEvent() {
    dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ESCAPE))
    dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ESCAPE))
}

internal fun acceptCallUiHangup(message: String?, sourceOrigin: String, allowedOrigin: String, isMainFrame: Boolean): Boolean =
    isMainFrame && message == "hangup" && isSameOrigin(sourceOrigin, allowedOrigin)

/** Presentation only: the embedded error screen owns its close action and cleanup. */
internal fun acceptCallUiTerminalError(message: String?, sourceOrigin: String, allowedOrigin: String, isMainFrame: Boolean): Boolean =
    message == "terminal-error" && acceptCallUiHangup("hangup", sourceOrigin, allowedOrigin, isMainFrame)

internal fun acceptCallUiToggleSpeaker(message: String?, sourceOrigin: String, allowedOrigin: String, isMainFrame: Boolean): Boolean =
    message == "toggle-speaker" && acceptCallUiHangup("hangup", sourceOrigin, allowedOrigin, isMainFrame)

private fun WebView.installYiZheCallTheme(state: CallScreenState) {
    val labels = JSONObject()
    mapOf(
        "micOn" to R.string.call_control_mic_on, "micOff" to R.string.call_control_mic_off,
        "cameraOn" to R.string.call_control_camera_on, "cameraOff" to R.string.call_control_camera_off,
        "speakerOn" to R.string.call_control_speaker_on, "speakerOff" to R.string.call_control_speaker_off,
        "hangup" to R.string.call_control_hangup, "more" to R.string.call_control_more,
        "flip" to R.string.call_control_flip,
        "groupReturn" to R.string.call_group_return, "groupFocus" to R.string.call_group_focus,
        "lobbyReady" to R.string.call_lobby_ready,
    ).forEach { (key, resource) -> labels.put(key, context.getString(resource)) }
    val presentation = JSONObject().put("isAudioCall", state.isAudioCall)
        .put("isGroupCall", state.isGroupCall)
        .put("isMediaConnected", state.isMediaConnected).put("isNetworkDisconnected", state.isNetworkDisconnected)
        .put("peerName", state.peerName ?: JSONObject.NULL).put("labels", labels)
        .put("status", when {
            state.isNetworkDisconnected -> context.getString(R.string.call_network_disconnected)
            state.isMediaConnected -> "%02d:%02d".format(java.util.Locale.ROOT, state.connectedSeconds / 60, state.connectedSeconds % 60)
            state.isIncoming -> context.getString(R.string.call_accept_connecting)
            state.isGroupCall -> context.getString(R.string.call_waiting_members)
            else -> context.getString(R.string.call_waiting_peer)
        })
    evaluateJavascript("window.yizheCallPresentation = $presentation;" + YIZHE_CALL_THEME_SCRIPT, null)
}

internal val YIZHE_CALL_THEME_SCRIPT = """
    (() => {
      if (window.yizheRefreshCallPresentation) { window.yizheRefreshCallPresentation(); return; }
      const style = document.createElement('style');
      style.id = 'yizhe-call-theme';
      style.textContent = `
        html, body, ._inRoom_15f4n_8 { background: #252a2e !important; color: white; }
        html, body { overflow: hidden !important; overscroll-behavior: none; }
        ._inRoom_15f4n_8 { background: #252a2e !important; overflow: hidden; --video-tile-background: transparent; }
        ._header_15f4n_17, ._bar_1lobl_1 { color: white !important; background: transparent !important; }
        ._bar_1lobl_1::before { display: none !important; }
        ._bar_1lobl_1 svg path { fill: white !important; }
        [data-yizhe-call-media] ._bar_1lobl_1 h1 { visibility: hidden; }
        ._header_15f4n_17 h1, ._header_15f4n_17 h2, ._header_15f4n_17 button, header h1 { color: white !important; }
        [data-yizhe-settings-source] { visibility: hidden; }
        ._inRoom_15f4n_8[data-yizhe-media="voice"] ._header_15f4n_17 h1 { visibility: hidden; }
        ._overlay_kh5c3_1 { display: none !important; }
        .yizhe-call-identity { position: fixed; z-index: 25; top: calc(25% + 64px); left: 8%; right: 8%; color: white; text-align: center; pointer-events: none; }
        .yizhe-call-identity strong { display: block; font-size: 23px; font-weight: 500; line-height: 1.4; overflow-wrap: anywhere; }
        .yizhe-call-status { margin-top: 12px; color: #ffffffb8; font-size: 14px; line-height: 1.5; font-variant-numeric: tabular-nums; }
        ._inRoom_15f4n_8[data-yizhe-media="video"] .yizhe-call-identity { top: 48px; left: 15%; right: 15%; text-shadow: 0 1px 3px #000; }
        ._inRoom_15f4n_8[data-yizhe-media="video"] .yizhe-call-identity strong { font-size: 18px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
        ._inRoom_15f4n_8[data-yizhe-media="video"] .yizhe-call-status { margin-top: 8px; }
        ._inRoom_15f4n_8[data-yizhe-direct="true"][data-yizhe-media="voice"] ._avatar_yelmg_51 { position: fixed !important; left: 50% !important; top: 25% !important; inline-size: 88px !important; block-size: 88px !important; border-radius: 14px !important; display: flex !important; align-items: center; justify-content: center; overflow: hidden; font-size: 36px !important; line-height: 1 !important; }
        ._inRoom_15f4n_8[data-yizhe-direct="true"][data-yizhe-media="voice"] ._avatar_yelmg_51 > * { font-size: 36px !important; line-height: 1 !important; }
        ._nameTag_yelmg_104 { color: white !important; background: #171b1970; }
        ._status_yelmg_121 { display: none !important; }
        ._tile_1jqw7_8:not([data-yizhe-preview]) ._avatar_yelmg_51 { --cpd-avatar-size: 88px !important; inline-size: 88px !important; block-size: 88px !important; border-radius: 14px !important; font-size: 36px !important; line-height: 1 !important; opacity: 1 !important; mix-blend-mode: normal !important; }
        ._tile_1jqw7_8:not([data-yizhe-preview]) ._avatar_yelmg_51[style*="display: initial"] { display: flex !important; align-items: center; justify-content: center; }
        ._inRoom_15f4n_8 ._tile_1jqw7_8 ._avatar_yelmg_51[style*="display: none"] { display: none !important; }
        [data-yizhe-preview] { position: fixed !important; left: var(--yizhe-preview-x) !important; top: var(--yizhe-preview-y) !important; right: auto !important; bottom: auto !important; transform: none !important; translate: none !important; scale: none !important; width: var(--yizhe-preview-width) !important; height: var(--yizhe-preview-height) !important; z-index: 30 !important; border-radius: 12px !important; overflow: hidden; touch-action: none; box-shadow: 0 2px 12px #0005; }
        [data-yizhe-stage="call"] [data-testid="footer-container"] { position: fixed !important; inset: auto 0 0 !important; padding: 18px 16px calc(env(safe-area-inset-bottom) + 30px) !important; background: transparent !important; display: grid !important; grid-template-columns: 1fr !important; grid-template-areas: "buttons" !important; opacity: 1 !important; pointer-events: auto !important; }
        [data-yizhe-stage="call"] [data-yizhe-media="voice"] [data-testid="footer-container"] { background: transparent !important; }
        [data-yizhe-stage="call"] [data-testid="footer-container"] > ._buttons_1iwp6_72 { display: grid; grid-template-columns: repeat(3, minmax(64px, 1fr)); column-gap: 20px; row-gap: 18px; width: min(100%, 360px); align-items: center; justify-items: center; }
        [data-yizhe-stage="call"] [data-testid="footer-container"] button[data-yizhe-label] { position: relative; display: inline-flex !important; align-items: center !important; justify-content: center !important; padding: 0 !important; font-size: 22px; line-height: 1; min-width: 56px !important; min-height: 56px !important; width: 56px !important; height: 56px !important; border-radius: 50%; margin-bottom: 24px; background: #ffffff20 !important; color: white !important; border: 1px solid #ffffff26 !important; }
        [data-yizhe-stage="call"] [data-testid="footer-container"] button[data-yizhe-label][data-testid="incall_mute"][aria-checked="false"],
        [data-yizhe-stage="call"] [data-testid="footer-container"] button[data-yizhe-output][aria-checked="true"] { background: white !important; color: #202522 !important; border-color: white !important; }
        [data-yizhe-stage="call"] [data-testid="footer-container"] button[data-yizhe-label][data-testid="incall_leave"] { min-width: 64px !important; min-height: 64px !important; width: 64px !important; height: 64px !important; background: #ed4657 !important; color: white !important; border: 0 !important; }
        [data-yizhe-stage="call"] [data-testid="footer-container"] button[data-yizhe-label][aria-disabled="true"], [data-yizhe-stage="call"] [data-testid="footer-container"] button[data-yizhe-label]:disabled { opacity: .45; }
        [data-yizhe-stage="call"] [data-testid="footer-container"] button[data-yizhe-label]::after { content: attr(data-yizhe-label); position: absolute; top: calc(100% + 8px); left: 50%; transform: translateX(-50%); width: 100px; text-align: center; font-size: 12px; font-weight: 400; line-height: 16px; color: white; pointer-events: none; }
        [data-yizhe-stage="call"] [data-testid="footer-container"] > ._buttons_1iwp6_72 > [data-yizhe-control="auxiliary"] { display: none !important; }
        [data-yizhe-stage="call"] [data-testid="footer-container"] [data-yizhe-expanded="true"] > [data-yizhe-control="auxiliary"] { display: flex !important; }
        [data-yizhe-stage="call"] [data-yizhe-media="voice"] [data-testid="footer-container"] button[data-testid="yizhe-more"] { position: fixed !important; top: 48px; right: 16px; width: 48px !important; height: 48px !important; min-width: 48px !important; min-height: 48px !important; background: transparent !important; border: 0 !important; }
        [data-yizhe-media="voice"] [data-testid="yizhe-more"]::after { display: none; }
        [data-yizhe-stage="call"] [data-testid="footer-container"] button[data-yizhe-label][data-yizhe-hidden="true"] { display: none !important; }
        [data-yizhe-stage="call"] ._switchCamera_ghafa_91 { visibility: hidden !important; pointer-events: none !important; }
        @media (max-height: 480px) {
          [data-yizhe-stage="call"] [data-testid="footer-container"] { padding-block: 6px !important; }
          [data-yizhe-stage="call"] [data-testid="footer-container"] > ._buttons_1iwp6_72 { row-gap: 3px; }
          [data-yizhe-stage="call"] [data-testid="footer-container"] button[data-yizhe-label] { margin-bottom: 18px; }
          [data-yizhe-stage="call"] [data-testid="footer-container"] button[data-yizhe-label] { min-width: 48px !important; min-height: 48px !important; width: 48px !important; height: 48px !important; }
          [data-yizhe-stage="call"] [data-testid="footer-container"] button[data-yizhe-label][data-testid="incall_leave"] { min-width: 56px !important; min-height: 56px !important; width: 56px !important; height: 56px !important; }
          .yizhe-call-identity { top: 32%; } .yizhe-call-identity strong { font-size: 18px; }
          ._inRoom_15f4n_8[data-yizhe-direct="true"][data-yizhe-media="voice"] ._avatar_yelmg_51 { top: 20% !important; inline-size: 56px !important; block-size: 56px !important; }
        }
        @media (max-height: 400px) and (max-width: 400px) {
          [data-yizhe-stage="call"] [data-testid="footer-container"], .yizhe-call-identity { display: none !important; }
        }
        [data-yizhe-stage="lobby"] .yizhe-call-identity, [data-yizhe-group="true"] .yizhe-call-identity { top: 48px; left: 15%; right: 15%; text-shadow: 0 1px 3px #000; }
        [data-yizhe-stage="lobby"] .yizhe-call-identity strong, [data-yizhe-group="true"] .yizhe-call-identity strong { font-size: 18px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
        [data-yizhe-stage="lobby"] .yizhe-call-status, [data-yizhe-group="true"] .yizhe-call-status { margin-top: 8px; }
        [data-yizhe-stage="call"] [data-yizhe-media="video"] [data-testid="footer-container"] { inset: auto 12px 12px !important; padding: 16px 12px 14px !important; background: transparent !important; border: 0 !important; backdrop-filter: none !important; box-shadow: none !important; }
        [data-yizhe-stage="call"] [data-yizhe-controls-hidden="true"] button,
        html[data-yizhe-stage="call"]:has([data-yizhe-controls-hidden="true"]) ._bar_1lobl_1 button,
        [data-yizhe-stage="call"] [data-yizhe-controls-hidden="true"] [data-testid="footer-container"] { visibility: hidden !important; pointer-events: none !important; }
        [data-yizhe-stage="call"] [data-testid="footer-container"] button { transition: background-color .15s, opacity .15s, transform .15s; -webkit-tap-highlight-color: transparent; }
        [data-yizhe-stage="call"] [data-testid="footer-container"] button:active:not(:disabled) { transform: scale(.94); }
        [data-yizhe-stage="call"] [data-testid="footer-container"] button:focus-visible { outline: 2px solid #9ecfff; outline-offset: 4px; }
        [data-yizhe-stage="call"] [data-testid="footer-container"] button svg { width: 24px; height: 24px; }
        [data-yizhe-stage="call"] [data-testid="footer-container"] button[data-testid="incall_leave"] { box-shadow: 0 4px 14px #ed465730; }
        [data-yizhe-group="true"] video[data-testid="video"] { border-radius: 16px; }
        [data-yizhe-group-tile] { position: fixed !important; left: var(--yizhe-group-x) !important; top: var(--yizhe-group-y) !important; width: var(--yizhe-group-width) !important; height: var(--yizhe-group-height) !important; transform: none !important; translate: none !important; scale: none !important; z-index: 20 !important; border-radius: 12px; overflow: hidden; cursor: pointer; touch-action: manipulation; }
        [data-yizhe-group-tile="hidden"] { visibility: hidden !important; pointer-events: none !important; }
        [data-testid="yizhe-group-return"] { position: fixed; top: 108px; left: 16px; z-index: 40; border: 1px solid #ffffff50; border-radius: 20px; background: #252a2ecc; color: white; padding: 10px 16px; }
        ._nameTag_yelmg_104 { border-radius: 10px; backdrop-filter: blur(10px); }
        @media (max-height: 480px) { [data-yizhe-stage="call"] [data-yizhe-media="video"] [data-testid="footer-container"] { inset: auto 8px 8px !important; padding: 6px 10px !important; border-radius: 18px; } }
      `;
      document.head.appendChild(style);
      const accessibleName = button => {
        const ids = button.getAttribute('aria-labelledby');
        return button.getAttribute('aria-label') || button.getAttribute('title') ||
          (ids && ids.split(' ').map(id => document.getElementById(id)?.textContent || '').join(' ').trim()) || '';
      };
      const disabled = button => !button || button.disabled || button.getAttribute('aria-disabled') === 'true' || button.tabIndex < 0;
      const createControl = (group, id, label, glyph, action) => {
        let button = group.querySelector('[data-testid="' + id + '"]');
        if (!button) {
          button = document.createElement('button'); button.type = 'button'; button.dataset.testid = id;
          button.textContent = glyph; button.setAttribute('aria-label', label); button.dataset.yizheLabel = label;
          if (id === 'yizhe-more' || id === 'yizhe-flip' || id === 'yizhe-output') {
            const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
            svg.setAttribute('viewBox', '0 0 24 24'); svg.setAttribute('aria-hidden', 'true');
            if (id === 'yizhe-more') {
              [5, 12, 19].forEach(x => { const dot = document.createElementNS(svg.namespaceURI, 'circle');
                dot.setAttribute('cx', String(x)); dot.setAttribute('cy', '12'); dot.setAttribute('r', '2'); dot.setAttribute('fill', 'currentColor'); svg.append(dot); });
            } else {
              const path = document.createElementNS(svg.namespaceURI, 'path');
              path.setAttribute('d', id === 'yizhe-output' ? 'M11 5 6 9H3v6h3l5 4V5Zm4 3a6 6 0 0 1 0 8m3-11a10 10 0 0 1 0 14' : 'M20 7h-6m6 0V1m0 6a9 9 0 0 0-15-2M4 17h6m-6 0v6m0-6a9 9 0 0 0 15 2');
              path.setAttribute('fill', 'none'); path.setAttribute('stroke', 'currentColor'); path.setAttribute('stroke-width', '2');
              path.setAttribute('stroke-linecap', 'round'); path.setAttribute('stroke-linejoin', 'round'); svg.append(path);
            }
            button.replaceChildren(svg);
          }
          button.addEventListener('click', event => { event.stopPropagation(); action(button, event); }); group.appendChild(button);
        }
        return button;
      };
      const place = (node, row, column) => { if (node) { node.style.gridRow = String(row); node.style.gridColumn = String(column); } };
      const previews = new WeakMap();
      let focusedGroupTile = null;
      const groupTileListeners = new WeakSet();
      const updateGroupTiles = (room, labels, lobby) => {
        const tiles = [...room.querySelectorAll('._tile_1jqw7_8')];
        const enabled = !lobby && room.dataset.yizheGroup === 'true' && room.dataset.yizheMedia === 'video';
        let back = room.querySelector('[data-testid="yizhe-group-return"]');
        if (!enabled) {
          tiles.forEach(tile => { delete tile.dataset.yizheGroupTile; });
          focusedGroupTile = null; back?.remove(); return;
        }
        if (!tiles.includes(focusedGroupTile)) focusedGroupTile = null;
        const footer = room.querySelector('[data-testid="footer-container"]');
        const top = innerHeight <= 480 ? 48 : 104;
        const bottom = Math.max(top + 48, (footer?.getBoundingClientRect().top || innerHeight - 16) - 12);
        const width = Math.max(1, (innerWidth - 32) / 3);
        const rows = Math.max(1, Math.ceil(tiles.length / 3));
        const height = Math.max(1, Math.min(width * 1.5, (bottom - top - (rows - 1) * 8) / rows));
        tiles.forEach((tile, index) => {
          delete tile.dataset.yizhePreview;
          tile.dataset.yizheGroupTile = focusedGroupTile && focusedGroupTile !== tile ? 'hidden' : 'visible';
          // Project the original tile, not a copied video/stream. Fixed-position
          // containing blocks vary between SDK layouts; retain their measured origin.
          const bounds = tile.getBoundingClientRect();
          const oldX = parseFloat(tile.style.getPropertyValue('--yizhe-group-x')) || 0;
          const oldY = parseFloat(tile.style.getPropertyValue('--yizhe-group-y')) || 0;
          const x = focusedGroupTile === tile ? 12 : 8 + (index % 3) * (width + 8);
          const y = focusedGroupTile === tile ? top : top + Math.floor(index / 3) * (height + 8);
          tile.style.setProperty('--yizhe-group-x', x - (bounds.left - oldX) + 'px');
          tile.style.setProperty('--yizhe-group-y', y - (bounds.top - oldY) + 'px');
          tile.style.setProperty('--yizhe-group-width', (focusedGroupTile === tile ? innerWidth - 24 : width) + 'px');
          tile.style.setProperty('--yizhe-group-height', (focusedGroupTile === tile ? bottom - top : height) + 'px');
          if (!groupTileListeners.has(tile)) {
            groupTileListeners.add(tile);
            tile.addEventListener('click', event => {
              if (!tile.hasAttribute('data-yizhe-group-tile') || event.target.closest('button, a, input')) return;
              event.preventDefault(); event.stopImmediatePropagation();
              focusedGroupTile = focusedGroupTile === tile ? null : tile;
              delete room.dataset.yizheControlsHidden; schedule();
            }, true);
          }
        });
        if (focusedGroupTile && !back) {
          back = document.createElement('button'); back.type = 'button'; back.dataset.testid = 'yizhe-group-return';
          back.addEventListener('click', event => { event.stopPropagation(); focusedGroupTile = null; schedule(); });
          room.appendChild(back);
        }
        if (back) {
          back.textContent = labels.groupReturn || ''; back.hidden = !focusedGroupTile;
          back.setAttribute('aria-label', labels.groupReturn || '');
        }
      };
      const positionPreview = (tile, position) => {
        const footer = document.querySelector('[data-testid="footer-container"]');
        const compact = innerHeight <= 480;
        const top = compact ? 12 : 96;
        const bottom = footer && !compact ? footer.getBoundingClientRect().top - 12 : innerHeight - 12;
        position.x = Math.max(12, Math.min(innerWidth - position.width - 12, position.x));
        position.y = Math.max(top, Math.min(Math.max(top, bottom - position.height), position.y));
        tile.style.setProperty('--yizhe-preview-width', position.width + 'px');
        tile.style.setProperty('--yizhe-preview-height', position.height + 'px');
        if (!tile.style.getPropertyValue('--yizhe-preview-x')) tile.style.setProperty('--yizhe-preview-x', '0px');
        if (!tile.style.getPropertyValue('--yizhe-preview-y')) tile.style.setProperty('--yizhe-preview-y', '0px');
        // Grid containment can establish a fixed-position containing block. Project
        // viewport coordinates into that origin so header/footer animation cannot move the preview.
        const currentX = parseFloat(tile.style.getPropertyValue('--yizhe-preview-x')) || 0;
        const currentY = parseFloat(tile.style.getPropertyValue('--yizhe-preview-y')) || 0;
        const bounds = tile.getBoundingClientRect();
        const originX = bounds.left - currentX, originY = bounds.top - currentY;
        tile.style.setProperty('--yizhe-preview-x', position.x - originX + 'px');
        tile.style.setProperty('--yizhe-preview-y', position.y - originY + 'px');
      };
      const updatePreviews = room => {
        // Only the small, draggable tile of a one-to-one portrait layout is projected.
        // Keep the original media node and click callbacks for camera and big/small switching.
        const tiles = [...room.querySelectorAll('._tile_1jqw7_8')];
        tiles.forEach(tile => {
          const width = parseFloat(tile.style.width), height = parseFloat(tile.style.height);
          const small = tiles.length <= 2 && tile.classList.contains('_draggable_1jqw7_8') &&
            width > 0 && width < innerWidth * .6 && height > 0 && height < innerHeight * .6;
          if (!small || room.dataset.yizheMedia !== 'video') { delete tile.dataset.yizhePreview; return; }
          let position = previews.get(tile);
          if (!position) {
            position = { x: innerWidth - Math.min(width, 112) - 12, y: innerHeight <= 480 ? 12 : 112, width: Math.min(width, 112), height: Math.min(height, 176), moved: false };
            previews.set(tile, position);
            let drag;
            tile.addEventListener('pointerdown', event => {
              if (!tile.hasAttribute('data-yizhe-preview') || event.target.closest('button') || !event.isPrimary) return;
              event.stopImmediatePropagation();
              drag = { id: event.pointerId, startX: event.clientX, startY: event.clientY, x: position.x, y: position.y, moved: false };
              tile.setPointerCapture(event.pointerId);
            }, true);
            tile.addEventListener('pointermove', event => {
              if (!drag || event.pointerId !== drag.id) return;
              event.stopImmediatePropagation();
              const dx = event.clientX - drag.startX, dy = event.clientY - drag.startY;
              if (!drag.moved && Math.hypot(dx, dy) < 6) return;
              drag.moved = true; event.preventDefault();
              position.x = drag.x + dx; position.y = drag.y + dy; position.moved = true;
              positionPreview(tile, position);
            }, true);
            const finishDrag = event => {
              if (!drag || event.pointerId !== drag.id) return;
              event.stopImmediatePropagation();
              tile.dataset.yizheDragged = drag.moved ? 'true' : 'false'; drag = null;
              if (tile.hasPointerCapture(event.pointerId)) tile.releasePointerCapture(event.pointerId);
            };
            tile.addEventListener('pointerup', finishDrag, true);
            tile.addEventListener('pointercancel', finishDrag, true);
            tile.addEventListener('click', event => {
              if (tile.dataset.yizheDragged !== 'true') return;
              event.preventDefault(); event.stopImmediatePropagation(); delete tile.dataset.yizheDragged;
            }, true);
          }
          tile.dataset.yizhePreview = 'true';
          if (!position.moved) position.x = innerWidth - position.width - 12;
          positionPreview(tile, position);
        });
      };
      let terminalErrorReported = false;
      const update = () => {
        // This is the ErrorView class from the verified embedded RTC asset manifest.
        // Report only presentation state; its original Close button still owns teardown.
        if (!terminalErrorReported && document.querySelector('._error_17zfc_1 > h1')) {
          window.yizheCallUi?.postMessage('terminal-error'); terminalErrorReported = true;
        }
        const config = window.yizheCallPresentation || {}, labels = config.labels || {};
        const room = document.querySelector('._inRoom_15f4n_8'); if (!room) return;
        // Lobby and in-call views share the same room/footer classes. Only the
        // actual Join button identifies the preview; it must keep its native controls.
        const lobby = !!room.querySelector('[data-testid="lobby_joinCall"]');
        document.documentElement.dataset.yizheStage = lobby ? 'lobby' : 'call';
        room.dataset.yizheDirect = String(!config.isGroupCall && !lobby);
        room.dataset.yizheGroup = String(!!config.isGroupCall);
        const camera = room.querySelector('[data-testid="incall_videomute"]');
        const remoteVideo = [...room.querySelectorAll('video[data-testid="video"]')].some(node => getComputedStyle(node).display !== 'none');
        const voice = config.isAudioCall && camera?.getAttribute('aria-checked') !== 'true' && !remoteVideo;
        room.dataset.yizheMedia = voice ? 'voice' : 'video';
        document.documentElement.dataset.yizheCallMedia = voice ? 'voice' : 'video';
        let identity = room.querySelector('.yizhe-call-identity');
        if (!identity) {
          identity = document.createElement('div'); identity.className = 'yizhe-call-identity';
          const name = document.createElement('strong'), status = document.createElement('div'); status.className = 'yizhe-call-status';
          identity.append(name, status); room.appendChild(identity);
        }
        identity.querySelector('strong').textContent = config.peerName || '';
        const rtcStatus = room.querySelector('._status_yelmg_121')?.textContent?.trim();
        const isRinging = /^(Calling|正在呼叫|呼叫中|Ringing)/i.test(rtcStatus || '');
        identity.querySelector('.yizhe-call-status').textContent =
          lobby && !config.isNetworkDisconnected ? labels.lobbyReady || '' :
          !config.isMediaConnected && !config.isNetworkDisconnected && rtcStatus && !isRinging ? rtcStatus : config.status || '';
        if (lobby) {
          updateGroupTiles(room, labels, true);
          delete room.dataset.yizheControlsHidden;
          // React may reuse a footer after leaving a call. Remove presentation-only
          // decorations while preserving its media controls and their original handlers.
          room.querySelectorAll('[data-testid="yizhe-more"], [data-testid="yizhe-flip"], [data-testid="yizhe-settings"], [data-testid="yizhe-output"]')
            .forEach(node => node.remove());
          room.querySelectorAll('[data-yizhe-control]').forEach(node => {
            delete node.dataset.yizheControl; node.style.removeProperty('grid-row'); node.style.removeProperty('grid-column');
          });
          room.querySelectorAll('[data-yizhe-label]').forEach(node => { delete node.dataset.yizheLabel; });
          room.querySelectorAll('[data-yizhe-preview]').forEach(node => { delete node.dataset.yizhePreview; });
          room.querySelectorAll('[data-yizhe-expanded]').forEach(node => { delete node.dataset.yizheExpanded; });
          document.querySelectorAll('[data-yizhe-settings-source]').forEach(node => { delete node.dataset.yizheSettingsSource; });
          return;
        }
        room.querySelectorAll('[data-testid="footer-container"] > ._buttons_1iwp6_72').forEach(group => {
          const original = [...group.children].filter(child => !['yizhe-more', 'yizhe-flip', 'yizhe-settings', 'yizhe-output'].includes(child.dataset.testid));
          const auxiliary = []; let mic, cameraNode, output, leave;
          original.forEach(child => {
            const button = child.matches('button') ? child : child.querySelector('button'); if (!button) return;
            const id = button.dataset.testid, enabled = button.getAttribute('aria-checked') === 'true';
            let label = accessibleName(button); delete button.dataset.yizheOutput;
            if (id === 'incall_mute') { mic = child; label = enabled ? labels.micOn : labels.micOff; }
            else if (id === 'incall_videomute') { cameraNode = child; label = enabled ? labels.cameraOn : labels.cameraOff; }
            else if (!id && button.hasAttribute('aria-checked')) { output = child; button.dataset.yizheOutput = 'true'; label = enabled ? labels.speakerOn : labels.speakerOff; }
            else if (id === 'incall_leave') { leave = child; label = labels.hangup; }
            else auxiliary.push(child);
            button.dataset.yizheLabel = label || accessibleName(button); child.dataset.yizheControl = 'primary';
          });
          if (window.yizheCallAudio && !voice) {
            if (output) output.dataset.yizheHidden = 'true';
            const audio = window.yizheCallAudio;
            const label = audio.speakerOn ? labels.speakerOn : labels.speakerOff;
            output = createControl(group, 'yizhe-output', label, '', (_, event) => {
              if (event.isTrusted) window.yizheCallUi?.postMessage('toggle-speaker');
            });
            output.dataset.yizheControl = 'primary'; output.dataset.yizheOutput = 'true';
            output.dataset.yizheLabel = label; output.setAttribute('aria-label', label);
            output.setAttribute('role', 'switch'); output.setAttribute('aria-checked', String(audio.speakerOn));
            output.disabled = !audio.canToggle;
          } else group.querySelector('[data-testid="yizhe-output"]')?.remove();
          if (voice && cameraNode) auxiliary.unshift(cameraNode);
          const settingsSource = document.querySelector('[data-testid="settings-app-bar"]');
          if (settingsSource) settingsSource.dataset.yizheSettingsSource = 'true';
          const bottomSettings = original.some(child => child.matches('[data-testid="settings-bottom-center"]') || child.querySelector('[data-testid="settings-bottom-center"]'));
          if (settingsSource && !bottomSettings) {
            settingsSource.dataset.yizheSettingsSource = 'true';
            const settings = createControl(group, 'yizhe-settings', accessibleName(settingsSource) || labels.more, '⚙', () => {
              const target = document.querySelector('[data-testid="settings-app-bar"]'); if (!disabled(target)) target.click();
            });
            settings.disabled = disabled(settingsSource); auxiliary.push(settings);
          } else group.querySelector('[data-testid="yizhe-settings"]')?.remove();
          const more = createControl(group, 'yizhe-more', labels.more || 'More', '···', button => {
            const expanded = group.dataset.yizheExpanded !== 'true'; group.dataset.yizheExpanded = String(expanded);
            button.setAttribute('aria-expanded', String(expanded)); schedule();
          });
          more.setAttribute('aria-expanded', group.dataset.yizheExpanded === 'true' ? 'true' : 'false');
          more.dataset.yizheHidden = String(auxiliary.length === 0);
          const originalFlip = room.querySelector('button._switchCamera_ghafa_91');
          const flip = createControl(group, 'yizhe-flip', labels.flip || 'Switch camera', '⇄', () => {
            const target = room.querySelector('button._switchCamera_ghafa_91'); if (!disabled(target)) target.click();
          });
          flip.dataset.yizheHidden = String(voice || !originalFlip); flip.disabled = disabled(originalFlip);
          const rows = group.dataset.yizheExpanded === 'true' ? Math.ceil(auxiliary.length / 3) : 0;
          auxiliary.forEach((child, index) => { child.dataset.yizheControl = 'auxiliary'; place(child, 1 + Math.floor(index / 3), 1 + index % 3); });
          const row = rows + 1;
          if (voice) {
            place(mic, row, 1); place(leave, row, 2); place(output, row, 3);
          } else {
            place(mic, row, 1); place(output, row, 2); place(cameraNode, row, 3);
            place(more, row + 1, 1); place(leave, row + 1, 2); place(flip, row + 1, 3);
          }
        });
        if (room.dataset.yizheGroup === 'true') updateGroupTiles(room, labels, false);
        else { updateGroupTiles(room, labels, false); updatePreviews(room); }
      };
      let pending = false;
      const schedule = () => {
        if (pending) return; pending = true;
        requestAnimationFrame(() => { observer.disconnect(); update(); pending = false; observe(); });
      };
      const observer = new MutationObserver(schedule);
      const observe = () => observer.observe(document.body, { childList: true, subtree: true, characterData: true,
        attributes: true, attributeFilter: ['aria-label', 'aria-labelledby', 'aria-checked', 'aria-disabled', 'title', 'style', 'disabled', 'tabindex'] });
      document.addEventListener('click', event => {
        const button = event.target instanceof Element && event.target.closest('[data-testid="incall_leave"]');
        if (event.isTrusted && button && !disabled(button) && window.yizheCallUi?.postMessage) window.yizheCallUi.postMessage('hangup');
      }, true);
      // Keep media running while hiding chrome. Buttons, dialogs and preview dragging
      // retain their original interactions; a tap on the scene restores all controls.
      let scenePointer;
      document.addEventListener('pointerdown', event => {
        scenePointer = { x: event.clientX, y: event.clientY, moved: false };
      }, true);
      document.addEventListener('pointermove', event => {
        if (scenePointer && Math.hypot(event.clientX - scenePointer.x, event.clientY - scenePointer.y) > 8) scenePointer.moved = true;
      }, true);
      document.addEventListener('click', event => {
        const target = event.target instanceof Element ? event.target : null;
        const room = target?.closest('._inRoom_15f4n_8');
        if (!room || document.documentElement.dataset.yizheStage !== 'call' || scenePointer?.moved ||
            target.closest('button, a, input, select, textarea, [role="dialog"], [role="menu"], [data-yizhe-preview], [data-yizhe-group-tile]') ||
            document.querySelector('[role="dialog"], ._error_17zfc_1 > h1')) return;
        room.dataset.yizheControlsHidden = String(room.dataset.yizheControlsHidden !== 'true');
      }, true);
      document.addEventListener('keydown', event => {
        if (event.key === 'Tab' || event.key === 'Escape') {
          document.querySelector('._inRoom_15f4n_8')?.removeAttribute('data-yizhe-controls-hidden');
        }
      }, true);
      window.addEventListener('resize', schedule);
      window.yizheRefreshCallPresentation = schedule;
      update(); observe();
    })();
    """.trimIndent()


@PreviewsDayNight
@Composable
internal fun CallScreenViewPreview(
    @PreviewParameter(CallScreenStateProvider::class) state: CallScreenState,
) = ElementPreview {
    CallScreenView(
        state = state,
        pipState = aPictureInPictureState(),
        requestPermissions = { _, _ -> },
        onConsoleMessage = {},
    )
}

@PreviewsDayNight
@Composable
internal fun InvalidAudioDeviceDialogPreview() = ElementPreview {
    InvalidAudioDeviceDialog(invalidAudioDeviceReason = InvalidAudioDeviceReason.BT_AUDIO_DEVICE_DISABLED) {}
}

internal class JavascriptBackHandlerBridge(
    private val callback: () -> Unit,
) {
    @JavascriptInterface
    fun onBackPressed() {
        callback()
    }
}
