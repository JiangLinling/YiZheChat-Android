/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.network

import android.content.Context
import android.content.pm.PackageManager
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.di.annotations.ApplicationContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.Base64

internal data class DiscoveryEndpoint(val origin: HttpUrl, val certificatePin: String) {
    init {
        require(origin.isHttps && origin.username.isEmpty() && origin.password.isEmpty() &&
            '%' !in origin.encodedPath && ".." !in origin.encodedPath && "//" !in origin.encodedPath &&
            origin.query == null && origin.fragment == null)
        require(certificatePin.startsWith("sha256/") && Base64.getDecoder().decode(certificatePin.removePrefix("sha256/")).size == 32)
    }
}

internal data class DiscoveryEndpoints(val host: DiscoveryEndpoint, val guest: DiscoveryEndpoint)

@Inject
@SingleIn(AppScope::class)
internal class DiscoveryConfiguration(@ApplicationContext context: Context) {
    // Invalid/missing guest configuration hides discovery; it cannot fail host startup.
    val endpoints: DiscoveryEndpoints? = runCatching {
        @Suppress("DEPRECATION")
        val metadata = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA).metaData
        if (metadata?.getBoolean("io.element.android.yizhe.discovery.ENABLED", false) != true) return@runCatching null
        fun text(key: String) = metadata.getString(key).orEmpty()
        DiscoveryEndpoints(
            host = DiscoveryEndpoint(text("io.element.android.yizhe.control.BASE_URL").toHttpUrl(),
                text("io.element.android.yizhe.control.CERTIFICATE_PIN")),
            guest = DiscoveryEndpoint(text("io.element.android.yizhe.discovery.GATEWAY_URL").toHttpUrl(),
                text("io.element.android.yizhe.discovery.GATEWAY_PIN")),
        ).also { require(it.host.origin != it.guest.origin && it.guest.origin.encodedPath == "/") }
    }.getOrNull()
}
