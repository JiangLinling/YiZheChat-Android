/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.contentprotection.impl

import android.content.Context
import android.content.pm.PackageManager
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.security.contentprotection.api.MediaSendAuthorityConfiguration
import io.element.android.security.contentprotection.api.MediaSendAuthorityConfigurationProvider
import java.net.URI
import java.util.Base64

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ManifestMediaSendAuthorityConfigurationProvider(
    @ApplicationContext private val context: Context,
) : MediaSendAuthorityConfigurationProvider {
    @Suppress("DEPRECATION")
    override fun configuration(): MediaSendAuthorityConfiguration? = runCatching {
        val metadata = context.packageManager
            .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
            .metaData ?: return null
        val baseUrl = metadata.getString(BASE_URL)?.trim()?.trimEnd('/').orEmpty()
        val uri = URI(baseUrl)
        if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.rawQuery != null || uri.rawFragment != null) return null
        val pins = metadata.getString(CERTIFICATE_PINS).orEmpty().split(',')
            .map(String::trim).filter(String::isNotEmpty).toSet()
        if (pins.isEmpty() || pins.any { !it.matches(PIN) }) return null
        val root = Base64.getDecoder().decode(metadata.getString(RELEASE_ROOT_DER_B64)?.trim().orEmpty())
        if (root.size !in 64..4096) return null
        val uncertainty = when (val value = metadata.get(MAX_CLOCK_UNCERTAINTY_MS)) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        } ?: return null
        if (uncertainty !in 1..30_000) return null
        MediaSendAuthorityConfiguration(baseUrl, pins, root, uncertainty)
    }.getOrNull()

    private companion object {
        const val BASE_URL = "io.yizhechat.media_send.BASE_URL"
        const val CERTIFICATE_PINS = "io.yizhechat.media_send.CERTIFICATE_PINS_SHA256"
        const val RELEASE_ROOT_DER_B64 = "io.yizhechat.media_send.RELEASE_TRUST_ROOT_DER_B64"
        const val MAX_CLOCK_UNCERTAINTY_MS = "io.yizhechat.media_send.MAX_CLOCK_UNCERTAINTY_MS"
        val PIN = Regex("^sha256/[A-Za-z0-9+/]{43}=$")
    }
}
