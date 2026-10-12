/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.location.impl.share

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.element.android.features.location.api.Location
import io.element.android.features.location.impl.R
import io.element.android.features.location.impl.common.rememberUserLocationState
import io.element.android.libraries.architecture.AsyncAction
import io.element.android.libraries.ui.strings.CommonStrings
import kotlinx.coroutines.launch
import org.json.JSONObject
import android.graphics.Color as AndroidColor

private data class AmapSelection(val location: Location, val name: String, val address: String)

private class LocationSelectionBridge(
    private val onSelected: (AmapSelection?) -> Unit,
    private val onRequestLocation: () -> Unit,
) {
    @JavascriptInterface
    fun selected(raw: String) {
        if (raw.length > 4096) return
        runCatching {
            val json = JSONObject(raw)
            val lat = json.getDouble("lat")
            val lon = json.getDouble("lon")
            require(lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0)
            AmapSelection(MapCoordinates.toWgs84(Location(lat, lon)), json.optString("name").take(200), json.optString("address").take(500))
        }.getOrNull()?.let(onSelected)
    }
    @JavascriptInterface fun clearSelection() = onSelected(null)
    @JavascriptInterface fun requestLocation() = onRequestLocation()
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun AmapShareLocationView(state: ShareLocationState, navigateUp: () -> Unit, modifier: Modifier = Modifier) {
    val config = state.mapProvider
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<AmapSelection?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var webReady by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val currentState by rememberUpdatedState(state)
    val user = rememberUserLocationState(state.hasLocationPermission).location
    val cancel = stringResource(CommonStrings.action_cancel)
    val send = stringResource(CommonStrings.action_send)
    val currentLocation = stringResource(R.string.location_current_point)
    val sendError = stringResource(R.string.location_send_failed)
    val bridge = remember {
        LocationSelectionBridge(
            onSelected = { point -> scope.launch { selected = point } },
            onRequestLocation = { scope.launch { currentState.eventSink(ShareLocationEvent.StartTrackingUserLocation) } },
        )
    }
    LaunchedEffect(user, webReady, state.hasLocationPermission) {
        if (webReady && state.hasLocationPermission) {
            user?.position?.value?.let {
            val gcj = MapCoordinates.toGcj02(Location(it.latitude, it.longitude))
            webView?.evaluateJavascript("setUserLocation(${gcj.lon},${gcj.lat});", null)
        }
        }
    }
    LaunchedEffect(state.shareStaticLocationAction, state.startLiveLocationAction) {
        if (state.shareStaticLocationAction is AsyncAction.Success || state.startLiveLocationAction is AsyncAction.Success) navigateUp()
    }
    ShareLocationDialogs(state)
    Box(modifier.fillMaxSize().background(Color.White).systemBarsPadding()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                WebView(context).apply {
                    setBackgroundColor(AndroidColor.WHITE)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    addJavascriptInterface(bridge, "YizheLocation")
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?) = true
                        override fun onPageFinished(view: WebView, url: String?) {
                            val payload = JSONObject().put("jsKey", config.jsKey).put("serviceHost", config.serviceHost)
                            view.evaluateJavascript("startMap($payload);", null)
                            webReady = true
                        }
                    }
                    val html = context.assets.open("amap-location-picker.html").bufferedReader().use { it.readText() }
                    loadDataWithBaseURL(config.serviceHost.substringBefore("/v1/location/") + "/", html, "text/html", "UTF-8", null)
                    webView = this
                }
            },
            onRelease = { view ->
                view.stopLoading()
                view.removeJavascriptInterface("YizheLocation")
                view.destroy()
                webView = null
            },
        )
        Row(
            Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.8f)).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = navigateUp) { Text(cancel, color = Color(0xFF191919)) }
            if (state.canShareLiveLocation && state.pausedLiveLocationShare == null) {
                Box {
                TextButton(onClick = { menuOpen = true }) { Text(stringResource(R.string.location_more)) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = {
                            Text(stringResource(R.string.location_share_live))
                        },
                        onClick = {
                            menuOpen = false
                            state.eventSink(ShareLocationEvent.InitiateLiveLocationShare)
                        },
                    )
                }
            }
            }

            Button(
                enabled = selected != null && state.shareStaticLocationAction !is AsyncAction.Loading,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF07C160)),
                onClick = {
                    selected?.let { point ->
                        state.eventSink(ShareLocationEvent.ShareStaticLocation(
                        point.location,
                            true,
                            point.name.ifBlank { currentLocation },
                            point.address,
                    ))
                    }
                },
            ) { Text(send) }
        }
        if (state.shareStaticLocationAction is AsyncAction.Failure) {
            Text(sendError, Modifier.align(Alignment.BottomCenter).background(Color.White).padding(12.dp), color = Color(0xFFD93A49))
        }
    }
}
