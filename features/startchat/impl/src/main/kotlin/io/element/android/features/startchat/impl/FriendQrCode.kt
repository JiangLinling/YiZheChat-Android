/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.startchat.impl

import io.element.android.libraries.matrix.api.core.UserId
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal object FriendQrCode {
    fun encode(userId: UserId): String = "yizhe://friend/v1?user_id=" +
        URLEncoder.encode(userId.value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    fun decode(value: String): UserId? {
        val text = value.trim()
        val rawId = when {
            text.startsWith("@") -> text
            text.startsWith("yizhe://friend/v1?") -> text.substringAfter("user_id=", "").substringBefore('&')
                .takeIf(String::isNotBlank)?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }
            text.startsWith("https://matrix.to/#/") -> text.substringAfter("https://matrix.to/#/").substringBefore('?')
                .let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }
            else -> null
        }
        return rawId?.takeIf { it.startsWith("@") && ':' in it && it.length <= 255 }?.let(::UserId)
    }
}
