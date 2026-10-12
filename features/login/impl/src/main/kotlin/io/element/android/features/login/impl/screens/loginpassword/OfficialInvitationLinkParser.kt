/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.login.impl.screens.loginpassword

import java.net.URI

internal object OfficialInvitationLinkParser {
    private val publicIdPattern = Regex("[A-Za-z0-9_-]{20,64}")
    private val officialHosts = setOf("www.yizhesy.cn", "yizhesy.cn")

    fun publicIdOrNull(clipboardText: String?): String? {
        val value = clipboardText?.trim()?.takeIf { it.length <= 512 } ?: return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host?.lowercase() !in officialHosts) return null
        if (uri.port != -1 || uri.userInfo != null || uri.query != null || uri.fragment != null) return null
        val match = Regex("^/i/(${publicIdPattern.pattern})/?$").matchEntire(uri.path.orEmpty()) ?: return null
        return match.groupValues[1]
    }
}
