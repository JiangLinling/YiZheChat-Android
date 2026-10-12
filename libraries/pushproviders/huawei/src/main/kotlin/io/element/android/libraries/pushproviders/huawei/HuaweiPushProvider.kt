/*
 * Copyright 2026 YiZheChat
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.pushproviders.huawei

import android.content.Context
import dev.zacsweers.metro.Inject
import io.element.android.libraries.core.log.logger.LoggerTag
import io.element.android.libraries.di.annotations.ApplicationContext
import timber.log.Timber
import java.security.MessageDigest
import java.util.HexFormat

private const val TOKEN_STATE_PREFERENCES = "huawei_push_token_state"
private const val TOKEN_MASK_KEY = "token_mask"
private const val TOKEN_SHA256_KEY = "token_sha256"
private const val TOKEN_RECEIVED_AT_KEY = "token_received_at_epoch_ms"
private val loggerTag = LoggerTag("HuaweiPushProvider", LoggerTag.PushLoggerTag)

/**
 * Stores only non-reversible token diagnostics. The raw HMS token is never logged or persisted.
 */
@Inject
class HuaweiPushProvider(
    @ApplicationContext private val context: Context,
) {
    fun recordTokenRefresh(token: String?) {
        if (token.isNullOrBlank()) {
            Timber.tag(loggerTag.value).w("HMS Push returned an empty token")
            return
        }

        val maskedToken = token.maskHuaweiPushToken()
        val tokenSha256 = token.sha256Hex()
        context.getSharedPreferences(TOKEN_STATE_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(TOKEN_MASK_KEY, maskedToken)
            .putString(TOKEN_SHA256_KEY, tokenSha256)
            .putLong(TOKEN_RECEIVED_AT_KEY, System.currentTimeMillis())
            .apply()
        Timber.tag(loggerTag.value).i("HMS Push token refreshed")
    }
}

internal fun String.maskHuaweiPushToken(): String = when {
    length <= 12 -> "****"
    else -> "${take(8)}...${takeLast(4)}"
}

private fun String.sha256Hex(): String = HexFormat.of().formatHex(
    MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8)),
)
