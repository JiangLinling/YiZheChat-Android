/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.call.impl.utils

import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Build
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.net.toUri
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.element.android.libraries.core.extensions.runCatchingExceptions
import kotlinx.coroutines.flow.MutableSharedFlow
import org.json.JSONObject
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.security.SecureRandom

class WebViewWidgetMessageInterceptor(
    private val webView: WebView,
    private val allowedOrigin: String,
    private val onUrlLoaded: (String) -> Unit,
    private val onError: (String?) -> Unit,
) : WidgetMessageInterceptor {
    companion object {
        // We call both the WebMessageListener and the JavascriptInterface objects in JS with this
        // 'listenerName' so they can both receive the data from the WebView when
        // `${LISTENER_NAME}.postMessage(...)` is called
        const val LISTENER_NAME = "elementX"
    }

    // It's important to have extra capacity here to make sure we don't drop any messages
    override val interceptedMessages = MutableSharedFlow<String>(extraBufferCapacity = 10)
    private val bridgeToken = ByteArray(32).also(SecureRandom()::nextBytes).let {
        Base64.encodeToString(it, Base64.NO_WRAP or Base64.URL_SAFE)
    }
    private val webViewVersionName = WebViewCompat.getCurrentWebViewPackage(webView.context)?.versionName.orEmpty()
    private val webViewMajorVersion = webViewVersionName.substringBefore('.').toIntOrNull() ?: 0
    private val needsHuaweiLegacyCallPolyfill = needsHuaweiLegacyCallPolyfill(
        manufacturer = Build.MANUFACTURER,
        webViewMajorVersion = webViewMajorVersion,
    )

    init {
        require(isSameOrigin(allowedOrigin, allowedOrigin)) { "A valid HTTPS call origin is required." }
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/", WebViewAssetLoader.AssetsPathHandler(webView.context))
            .build()

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (!isSameOrigin(url, allowedOrigin)) return

                // We inject this JS code when the page starts loading to attach a message listener to the window.
                // This listener will receive both messages:
                // - EC widget API -> Element X (message.data.api == "fromWidget")
                // - Element X -> EC widget API (message.data.api == "toWidget"), we should ignore these
                view.evaluateJavascript(
                    """
                        window.addEventListener('message', function(event) {
                            if (event.origin !== ${JSONObject.quote(allowedOrigin)}) return;
                            const message = event.data;
                            if (message && (message.response && message.api === "toWidget"
                                || !message.response && message.api === "fromWidget")) {
                                $LISTENER_NAME.postMessage(JSON.stringify({
                                    token: ${JSONObject.quote(bridgeToken)},
                                    payload: message
                                }));
                            }
                        });
                    """.trimIndent(),
                    null
                )
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (isSameOrigin(url, allowedOrigin)) onUrlLoaded(url)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean {
                if (request.isForMainFrame && !isSameOrigin(request.url.toString(), allowedOrigin)) {
                    Timber.w("Blocked cross-origin Element Call navigation.")
                    onError(null)
                    return true
                }
                return false
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                Timber.e("Element Call resource failed with code ${error?.errorCode}.")

                // Only propagate the error if it happens while loading the current page
                if (view?.url == request?.url.toString()) {
                    onError(null)
                }

                super.onReceivedError(view, request, error)
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                Timber.e("Element Call HTTP request failed with status ${errorResponse?.statusCode}.")

                // Only propagate the error if it happens while loading the current page
                if (view?.url == request?.url.toString()) {
                    onError(errorResponse?.statusCode.toString())
                }

                super.onReceivedHttpError(view, request, errorResponse)
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                Timber.e("Element Call TLS validation failed with code ${error?.primaryError}.")

                // Only propagate the error if it happens while loading the current page
                if (view?.url == error?.url.toString()) {
                    onError(null)
                }
                handler?.cancel()
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(request.url)
                    ?.withHuaweiLegacyCallPolyfill(request.url.toString())
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun shouldInterceptRequest(view: WebView?, url: String): WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(url.toUri())
                    ?.withHuaweiLegacyCallPolyfill(url)
            }
        }

        // Always register JavascriptInterface as the baseline message channel.
        // This works on all WebView implementations including Huawei.
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun postMessage(json: String?) {
                onMessageReceived(json)
            }
        }, LISTENER_NAME)

        // Additionally register WebMessageListener on WebViews that reliably support it.
        // Huawei WebView (Chromium < 119) reports WEB_MESSAGE_LISTENER as supported
        // but silently drops messages, so we only trust it on Chromium 119+.
        // See: https://github.com/element-hq/element-x-android/issues/6632
        Timber.d("Using WebView version: $webViewVersionName")

        if (webViewMajorVersion >= 119 &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(
                webView,
                LISTENER_NAME,
                setOf(allowedOrigin),
                WebViewCompat.WebMessageListener { _, message, _, _, _ ->
                    onMessageReceived(message.data)
                }
            )
        }
    }

    override fun sendMessage(message: String) {
        webView.evaluateJavascript("globalThis.postMessage($message, ${JSONObject.quote(allowedOrigin)})", null)
    }

    private fun onMessageReceived(json: String?) {
        val wrapper = runCatchingExceptions { JSONObject(json.orEmpty()) }.getOrNull() ?: return
        if (wrapper.optString("token") != bridgeToken) return
        val payload = wrapper.opt("payload") ?: return
        val encodedPayload = when (payload) {
            is JSONObject -> payload.toString()
            is String -> payload
            else -> return
        }
        runCatchingExceptions { JSONObject(encodedPayload) }.getOrNull()?.let { message ->
            // Log protocol identity only. Never log widget data, SDP, ICE, tokens or user content.
            val action = message.optString("action", "unknown")
            val data = message.optJSONObject("data")
            if (action == "send_event" && data != null) {
                Timber.d(
                    "Element Call widget message action=%s api=%s eventType=%s state=%s delayed=%s parentDelay=%s sticky=%s",
                    action,
                    message.optString("api", "unknown"),
                    data.optString("type", "unknown"),
                    data.has("state_key"),
                    data.has("delay"),
                    data.has("parent_delay_id"),
                    data.has("sticky_duration_ms"),
                )
            } else {
                Timber.d(
                    "Element Call widget message action=%s api=%s",
                    action,
                    message.optString("api", "unknown"),
                )
            }
        }
        interceptedMessages.tryEmit(encodedPayload)
    }

    private fun WebResourceResponse.withHuaweiLegacyCallPolyfill(url: String): WebResourceResponse {
        if (!needsHuaweiLegacyCallPolyfill || !isEmbeddedElementCallIndex(url, allowedOrigin)) return this
        val originalHtml = runCatchingExceptions {
            data.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrElse {
            Timber.e(it, "Unable to read the embedded Element Call document.")
            return this
        }
        val patchedHtml = injectPromiseWithResolversPolyfill(originalHtml) ?: run {
            Timber.e("Unable to install the Huawei legacy WebView compatibility layer.")
            return this
        }
        Timber.i("Installed the Huawei legacy WebView compatibility layer for Element Call.")
        return WebResourceResponse(
            mimeType,
            encoding ?: Charsets.UTF_8.name(),
            ByteArrayInputStream(patchedHtml.toByteArray(Charsets.UTF_8)),
        )
    }
}

internal fun needsHuaweiLegacyCallPolyfill(manufacturer: String, webViewMajorVersion: Int): Boolean =
    manufacturer.trim().equals("huawei", ignoreCase = true) && webViewMajorVersion in 0 until 119

internal fun injectPromiseWithResolversPolyfill(html: String): String? {
    if (HUAWEI_PROMISE_WITH_RESOLVERS_POLYFILL_ID in html) return html
    val moduleScriptIndex = html.indexOf(MODULE_SCRIPT_MARKER)
    if (moduleScriptIndex < 0) return null
    return html.substring(0, moduleScriptIndex) + HUAWEI_PROMISE_WITH_RESOLVERS_POLYFILL + html.substring(moduleScriptIndex)
}

private fun isEmbeddedElementCallIndex(url: String, allowedOrigin: String): Boolean {
    if (!isSameOrigin(url, allowedOrigin)) return false
    return url.toUri().path == "/element-call/index.html"
}

private const val HUAWEI_PROMISE_WITH_RESOLVERS_POLYFILL_ID = "yizhe-promise-with-resolvers-polyfill"
private const val MODULE_SCRIPT_MARKER = "<script type=\"module\""
private val HUAWEI_PROMISE_WITH_RESOLVERS_POLYFILL = """
    <script id="$HUAWEI_PROMISE_WITH_RESOLVERS_POLYFILL_ID">
    if (typeof Promise.withResolvers !== 'function') {
      Promise.withResolvers = function () {
        var resolve;
        var reject;
        var promise = new this(function (resolveValue, rejectValue) {
          resolve = resolveValue;
          reject = rejectValue;
        });
        return { promise: promise, resolve: resolve, reject: reject };
      };
    }
    </script>
""".trimIndent()

internal fun isSameOrigin(url: String, expectedOrigin: String): Boolean {
    val actual = url.toUri()
    val expected = expectedOrigin.toUri()
    if (actual.scheme != "https" || expected.scheme != "https") return false
    if (actual.userInfo != null || expected.userInfo != null) return false
    return actual.host == expected.host && effectivePort(actual) == effectivePort(expected)
}

private fun effectivePort(uri: android.net.Uri): Int = if (uri.port == -1) 443 else uri.port
