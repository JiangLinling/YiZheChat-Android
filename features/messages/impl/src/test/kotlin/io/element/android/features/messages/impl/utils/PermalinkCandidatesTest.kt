/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.messages.impl.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PermalinkCandidatesTest {
    @Test fun `ordinary CJK and load test messages have no candidates`() {
        assertThat(permalinkCandidates("QA LOAD1000B13 13 0136 会议资料已整理，稍后发文件。").toList()).isEmpty()
    }

    @Test fun `preserves exact room user event query and percent encoded link ranges`() {
        val urls = listOf("https://matrix.to/#/@alice:example.org", "https://matrix.to/#/!room:example.org/\$event?via=example.org",
            "https://client.example/#/room/%23room:example.org", "client.example/#/user/@alice:example.org")
        val text = urls.joinToString("\n")
        assertThat(permalinkCandidates(text).map { text.substring(it) }.toList()).containsExactlyElementsIn(urls).inOrder()
    }

    @Test fun `punctuation around links is not included`() {
        val url = "https://matrix.to/#/@alice:example.org"
        val text = "（$url）， <$url> ($url)."
        assertThat(permalinkCandidates(text).map { text.substring(it) }.toList()).containsExactly(url, url, url)
    }

    @Test fun `oversized tokens are scanned linearly without copying or hiding later links`() {
        val url = "https://matrix.to/#/@alice:example.org"
        val raw = "https://" + "a".repeat(200_000) + " " + url
        val text = CountingText(raw)
        val ranges = permalinkCandidates(text).toList()
        assertThat(ranges.map { raw.substring(it) }).containsExactly(url)
        assertThat(text.reads).isLessThan(raw.length * 4)
        assertThat(text.largestCopy).isAtMost(4096)
    }

    @Test fun `empty and long linkless text do not allocate matcher state`() {
        assertThat(permalinkCandidates("").toList()).isEmpty()
        val text = CountingText("资料".repeat(100_000))
        assertThat(permalinkCandidates(text).toList()).isEmpty()
        assertThat(text.reads).isLessThan(text.length * 4)
        assertThat(text.largestCopy).isEqualTo(0)
    }

    private class CountingText(private val value: String) : CharSequence {
        var reads = 0
        var largestCopy = 0
        override val length: Int get() = value.length
        override fun get(index: Int): Char { reads++; return value[index] }
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
            largestCopy = maxOf(largestCopy, endIndex - startIndex)
            check(largestCopy <= 4096)
            return value.subSequence(startIndex, endIndex)
        }
    }
}
