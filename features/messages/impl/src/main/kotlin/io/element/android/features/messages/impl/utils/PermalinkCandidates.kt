/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.messages.impl.utils

/**
 * Finds candidates for the existing permalink parser without Android's backtracking WEB_URL
 * expression. This is presentation only: an oversized token stays readable as ordinary text.
 * Each token is visited a bounded number of times and never copied before the size check.
 */
internal fun permalinkCandidates(text: CharSequence): Sequence<IntRange> = sequence {
    var cursor = 0
    while (cursor < text.length) {
        if (text[cursor].isPermalinkBoundary()) {
            cursor++
            continue
        }
        val tokenStart = cursor
        while (cursor < text.length && !text[cursor].isPermalinkBoundary()) cursor++
        val tokenEnd = cursor
        if (tokenEnd - tokenStart > MAX_PERMALINK_CANDIDATE_LENGTH) continue
        var start = tokenStart
        while (start < tokenEnd && text[start] in "([{") start++
        // The converter also accepts scheme-less client links containing /#/room/ or /#/user/.
        val token = text.subSequence(start, tokenEnd).toString()
        if (!token.startsWith("https://", ignoreCase = true) &&
            !token.startsWith("http://", ignoreCase = true) && !token.contains("/#/")
        ) continue
        var end = tokenEnd
        while (end > start && text[end - 1] in ").,;!?]}") end--
        if (end > start) yield(start until end)
    }
}

private const val MAX_PERMALINK_CANDIDATE_LENGTH = 4096

private fun Char.isPermalinkBoundary(): Boolean = isWhitespace() || isISOControl() || this in "<>\"`，。；！？（）【】"
