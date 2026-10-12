/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.login.impl.screens.loginpassword

import android.icu.text.Transliterator
import android.os.Build
import java.security.MessageDigest

internal object OnboardingUsernameStemFactory {
    private val numberedName = Regex("^(.*?)([0-9]+)号$")

    fun fromDisplayName(displayName: String): String {
        val trimmed = displayName.trim()
        val numbered = numberedName.matchEntire(trimmed)
        val baseName = numbered?.groupValues?.get(1)?.takeIf(String::isNotBlank) ?: trimmed
        val base = transliterate(baseName).filter(Char::isLetterOrDigit).lowercase()
            .filter { it in 'a'..'z' || it in '0'..'9' }
            .take(40)
            .ifBlank { "user" + sha256Hex(trimmed).take(8) }
        val number = numbered?.groupValues?.get(2)?.toIntOrNull()
        return when {
            number == null || number <= 1 -> base
            else -> (base + number.toString().padStart(2, '0')).take(48)
        }
    }

    private fun transliterate(value: String): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return value
        return runCatching {
            Transliterator.getInstance("Han-Latin/Names; Latin-ASCII; Lower()").transliterate(value)
        }.getOrDefault(value)
    }

    private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray())
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
