/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.androidutils.clipboard

import android.content.ClipboardManager
import android.content.Context

/**
 * Allows plain text to enter YiZheChat without exposing YiZheChat content to Android's clipboard.
 *
 * App-local content always wins while it is available. System clipboard items are accepted only when they already
 * contain text; URI and Intent items are deliberately ignored instead of dereferencing external content providers.
 */
object ClipboardIngress {
    fun getPlainText(context: Context): String? {
        InAppClipboard.getPlainText()?.let { return it }
        return runCatching {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return@runCatching null
            val clip = clipboard.primaryClip ?: return@runCatching null
            if (clip.itemCount == 0) return@runCatching null
            clip.getItemAt(0).text
                ?.toString()
                ?.take(MAX_INGRESS_CHARACTERS)
        }.getOrNull()
    }

    fun hasPlainText(context: Context): Boolean = getPlainText(context) != null

    private const val MAX_INGRESS_CHARACTERS = 100_000
}
