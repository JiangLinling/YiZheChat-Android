/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.dataprotection.impl

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.di.annotations.ApplicationContext

@Inject
@SingleIn(AppScope::class)
class ManagementControlConfiguration(
    @ApplicationContext context: Context,
) {
    private val metadata = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.packageManager.getApplicationInfo(
            context.packageName,
            PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
        ).metaData
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA).metaData
    }

    val baseUrl: String = metadata?.getString(METADATA_BASE_URL).orEmpty().trimEnd('/')
    val signingPublicKeyDerBase64: String = metadata?.getString(METADATA_SIGNING_KEY).orEmpty()
    val signingKeyId: String = metadata?.getString(METADATA_SIGNING_KEY_ID).orEmpty()
    val certificatePin: String = metadata?.getString(METADATA_CERTIFICATE_PIN).orEmpty()
    val updateChannel: String = metadata?.getString(METADATA_UPDATE_CHANNEL).orEmpty()
    val updateCdnBaseUrl: String = metadata?.getString(METADATA_UPDATE_CDN_BASE_URL).orEmpty().trimEnd('/')
    val updateFallbackBaseUrl: String = metadata?.getString(METADATA_UPDATE_FALLBACK_BASE_URL).orEmpty().trimEnd('/')

    val isNextDeployment: Boolean = updateChannel.startsWith("next-")

    val isConfigured: Boolean = baseUrl.startsWith("https://") &&
        signingPublicKeyDerBase64.isNotBlank() &&
        signingKeyId.isNotBlank() &&
        certificatePin.startsWith("sha256/")

    companion object {
        const val METADATA_BASE_URL = "io.element.android.yizhe.control.BASE_URL"
        const val METADATA_SIGNING_KEY = "io.element.android.yizhe.control.SIGNING_KEY_DER_B64"
        const val METADATA_SIGNING_KEY_ID = "io.element.android.yizhe.control.SIGNING_KEY_ID"
        const val METADATA_CERTIFICATE_PIN = "io.element.android.yizhe.control.CERTIFICATE_PIN"
        const val METADATA_UPDATE_CHANNEL = "io.element.android.yizhe.update.CHANNEL"
        const val METADATA_UPDATE_CDN_BASE_URL = "io.element.android.yizhe.update.CDN_BASE_URL"
        const val METADATA_UPDATE_FALLBACK_BASE_URL = "io.element.android.yizhe.update.FALLBACK_BASE_URL"
    }
}
