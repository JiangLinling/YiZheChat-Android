/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.home.impl

import android.icu.text.Transliterator
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContactSortKeyTest {
    private val reference = Transliterator.getInstance("Han-Latin/Names; Latin-ASCII")

    @Test fun `plain ASCII shortcut and punctuation fallback match the existing ICU transformation`() {
        val names = (' '..'~').map { it.toString() } + listOf(
            "  ALICE  ", "QA_RTC_H3_1011", "@user:server.org", "123", "", "  ",
            (' '..'~').joinToString(""),
        )
        names.forEach(::assertMatchesExistingTransformation)
        "aAZz09 _".forEach { first ->
            "aAZz09 _".forEach { second -> assertMatchesExistingTransformation("${first}${second}Foo") }
        }
    }

    @Test fun `Chinese accented mixed and non printable names retain ICU ordering`() {
        listOf("张三", "李四", "Élodie", "Åke", "张三 Alice", "用户😀", "\u0001ALICE", "\tBob\n", "İpek")
            .forEach(::assertMatchesExistingTransformation)
    }

    private fun assertMatchesExistingTransformation(name: String) {
        assertThat(contactSortKey(name)).isEqualTo(reference.transliterate(name.trim()).lowercase())
    }
}
