/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.androidutils.text

import android.text.Spannable
import android.text.SpannableString
import android.text.style.URLSpan
import android.text.util.Linkify
import androidx.core.text.getSpans
import androidx.core.text.toSpannable
import androidx.core.text.util.LinkifyCompat
import io.element.android.libraries.core.extensions.runCatchingExceptions
import timber.log.Timber

/**
 * Helper class to linkify text while preserving existing URL spans.
 *
 * It also checks the linkified results to make sure URLs spans are not including trailing punctuation.
 */
object LinkifyHelper {
    fun linkify(
        text: CharSequence,
        @LinkifyCompat.LinkifyMask linkifyMask: Int = Linkify.WEB_URLS or Linkify.PHONE_NUMBERS or Linkify.EMAIL_ADDRESSES,
    ): CharSequence {
        // Convert the text to a Spannable to be able to add URL spans, return the original text if it's not possible (in tests, i.e.)
        @Suppress("USELESS_ELVIS")
        val spannable = text.toSpannable() ?: return text

        // Get all URL spans, as they will be removed by LinkifyCompat.addLinks
        val oldURLSpans = spannable.getSpans<URLSpan>(0, text.length).associateWith {
            val start = spannable.getSpanStart(it)
            val end = spannable.getSpanEnd(it)
            Pair(start, end)
        }
        // Find and set as URLSpans any links present in the text
        val addedNewLinks = addBoundedLinks(spannable, linkifyMask)

        // Process newly added URL spans
        if (addedNewLinks) {
            val newUrlSpans = spannable.getSpans<URLSpan>(0, spannable.length)
            for (urlSpan in newUrlSpans) {
                val start = spannable.getSpanStart(urlSpan)
                val end = spannable.getSpanEnd(urlSpan)

                // Try to avoid including trailing punctuation in the link.
                // Since this might fail in some edge cases, we catch the exception and just use the original end index.
                val newEnd = runCatchingExceptions {
                    adjustLinkifiedUrlSpanEndIndex(spannable, start, end)
                }.onFailure {
                    Timber.e(it, "Failed to adjust end index for link span")
                }.getOrNull() ?: end

                // Adapt the url in the URL span to the new end index too if needed
                if (end != newEnd) {
                    val diff = end - newEnd
                    val url = urlSpan.url.substring(0, urlSpan.url.length - diff)
                    spannable.removeSpan(urlSpan)
                    spannable.setSpan(URLSpan(url), start, newEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                } else {
                    spannable.setSpan(urlSpan, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }

        // Product identity links are explicit app targets, including an opaque group capability.
        // WEB_URLS does not recognize custom schemes and can otherwise linkify only an inner domain.
        Regex("yizhe://(?:friend|group)/v1\\?[^\\s<>\"']{1,4096}").findAll(spannable).forEach { match ->
            val start = match.range.first
            val end = match.range.last + 1
            spannable.getSpans<URLSpan>(start, end).forEach(spannable::removeSpan)
            spannable.setSpan(URLSpan(match.value), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        // Restore old spans, remove new ones if there is a conflict
        for ((urlSpan, location) in oldURLSpans) {
            val (start, end) = location
            val addedConflictingSpans = spannable.getSpans<URLSpan>(start, end)
            if (addedConflictingSpans.isNotEmpty()) {
                for (span in addedConflictingSpans) {
                    spannable.removeSpan(span)
                }
            }

            spannable.setSpan(urlSpan, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return spannable
    }

    private fun addBoundedLinks(text: Spannable, mask: Int): Boolean {
        var added = false
        var cursor = 0
        // Never send an entire message to ICU's WEB_URL matcher. Its native backtracking
        // stack can grow without Java-heap pressure, including on long non-link text.
        val webMask = mask and (Linkify.WEB_URLS or Linkify.EMAIL_ADDRESSES)
        while (cursor < text.length) {
            if (text[cursor].isWhitespace()) { cursor++; continue }
            val start = cursor
            var possibleLink = false
            while (cursor < text.length && !text[cursor].isWhitespace()) {
                if (text[cursor] == '.' || text[cursor] == '@' || text[cursor] == ':') possibleLink = true
                cursor++
            }
            if (webMask != 0 && possibleLink && cursor - start <= MAX_LINKIFY_TOKEN_LENGTH) {
                added = addLinksInRange(text, start, cursor, webMask) || added
            }
        }
        if (mask and Linkify.PHONE_NUMBERS != 0) {
            cursor = 0
            while (cursor < text.length) {
                if (!text[cursor].isDigit() && text[cursor] != '+' && text[cursor] != '(') { cursor++; continue }
                val start = cursor
                var digits = 0
                while (cursor < text.length && (text[cursor].isDigit() || text[cursor] in "+()- .\u00a0")) {
                    if (text[cursor].isDigit()) digits++
                    cursor++
                }
                if (digits >= 5 && cursor - start <= MAX_LINKIFY_TOKEN_LENGTH) {
                    added = addLinksInRange(text, start, cursor, Linkify.PHONE_NUMBERS) || added
                }
            }
        }
        return added
    }

    private fun addLinksInRange(text: Spannable, start: Int, end: Int, mask: Int): Boolean {
        val candidate = SpannableString(text.subSequence(start, end))
        if (!LinkifyCompat.addLinks(candidate, mask)) return false
        for (span in candidate.getSpans<URLSpan>()) {
            val spanStart = start + candidate.getSpanStart(span)
            val spanEnd = start + candidate.getSpanEnd(span)
            // A phone match inside a URL must not replace or overlap the URL.
            if (text.getSpans<URLSpan>(spanStart, spanEnd).isEmpty()) {
                text.setSpan(URLSpan(span.url), spanStart, spanEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return true
    }

    private const val MAX_LINKIFY_TOKEN_LENGTH = 512

    private fun adjustLinkifiedUrlSpanEndIndex(spannable: Spannable, start: Int, end: Int): Int {
        var end = end

        // Trailing punctuation found, adjust the end index
        while (end > start && spannable[end - 1] in sequenceOf('.', ',', ';', ':', '!', '?', '…')) {
            end--
        }

        // If the last character is a closing parenthesis, check if it's part of a pair
        if (end > start && spannable[end - 1] == ')') {
            val linkifiedTextLastPath = spannable.substring(start, end).substringAfterLast('/')
            val closingParenthesisCount = linkifiedTextLastPath.count { it == ')' }
            val openingParenthesisCount = linkifiedTextLastPath.count { it == '(' }
            // If it's not part of a pair, remove it from the link span by adjusting the end index
            end -= (closingParenthesisCount - openingParenthesisCount).coerceAtLeast(0)
        }
        return end
    }
}

/**
 * Linkify the text with the default mask (WEB_URLS, PHONE_NUMBERS, EMAIL_ADDRESSES).
 */
fun CharSequence.safeLinkify(): CharSequence {
    return LinkifyHelper.linkify(this, Linkify.WEB_URLS or Linkify.PHONE_NUMBERS or Linkify.EMAIL_ADDRESSES)
}
