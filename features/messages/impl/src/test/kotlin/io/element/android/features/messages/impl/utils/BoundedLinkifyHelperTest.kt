/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.messages.impl.utils

import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.URLSpan
import android.text.util.Linkify
import androidx.core.text.getSpans
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.androidutils.text.LinkifyHelper
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BoundedLinkifyHelperTest {
    @Test fun `long non-links and oversized URL tokens keep all text`() {
        for (text in listOf("资料".repeat(100_000), "https://" + "a.".repeat(50_000))) {
            val result = LinkifyHelper.linkify(text) as Spanned
            assertThat(result.toString()).isEqualTo(text)
            assertThat(result.getSpans<URLSpan>()).isEmpty()
        }
    }

    @Test fun `ordinary web and email links retain exact ranges and targets`() {
        val text = "访问 https://example.org/path. 邮箱 alice@example.org"
        val result = LinkifyHelper.linkify(text, Linkify.WEB_URLS or Linkify.EMAIL_ADDRESSES) as Spanned
        val spans = result.getSpans<URLSpan>()
        assertThat(spans.map { it.url }).containsExactly("https://example.org/path", "mailto:alice@example.org")
        assertThat(spans.map { result.subSequence(result.getSpanStart(it), result.getSpanEnd(it)).toString() })
            .containsExactly("https://example.org/path", "alice@example.org")
    }

    @Test fun `formatted phone number remains a telephone link`() {
        val result = LinkifyHelper.linkify("Call +1 (650) 555-1234", Linkify.PHONE_NUMBERS) as Spanned
        assertThat(result.getSpans<URLSpan>().map { it.url }).containsExactly("tel:+16505551234")
    }

    @Test fun `old explicit URL span wins over automatic decoration`() {
        val text = SpannableString("example.org")
        val original = URLSpan("https://trusted.example/actual")
        text.setSpan(original, 0, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        val result = LinkifyHelper.linkify(text) as Spanned
        assertThat(result.getSpans<URLSpan>().toList()).containsExactly(original)
        assertThat(result.getSpanStart(original)).isEqualTo(0)
        assertThat(result.getSpanEnd(original)).isEqualTo(text.length)
    }

    @Test fun `group capability link stays whole and later links survive oversized text`() {
        val link = "yizhe://group/v1?room_id=%21room%3Aexample.org&token=" + "a".repeat(64)
        val text = "x".repeat(100_000) + " " + link + " https://example.org"
        val result = LinkifyHelper.linkify(text) as Spanned
        assertThat(result.toString()).isEqualTo(text)
        assertThat(result.getSpans<URLSpan>().map { it.url }).containsExactly(link, "https://example.org")
    }
}
