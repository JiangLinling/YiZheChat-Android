/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.tests.konsist

import com.google.common.truth.Truth.assertThat
import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.Test

class KonsistLicenseTest {
    private val publicCopyright = """ \* Copyright \(c\) 20\d\d((, |-)20\d\d)? Element Creations Ltd\.""".toRegex()
    private val publicLicenseSuffix = listOf(
        " *",
        " * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.",
        " * Please see LICENSE files in the repository root for full details.",
        " */",
    )

    private fun firstLicenseHeader(text: String): String {
        val source = text.trimStart { it.isWhitespace() || it == '\uFEFF' }
        if (!source.startsWith("/*")) return ""
        val end = source.indexOf("*/", startIndex = 2)
        return if (end < 0) "" else source.substring(0, end + 2)
    }

    private fun hasPublicLicenseHeader(text: String): Boolean {
        val lines = firstLicenseHeader(text).split('\n')
        return lines.size >= 6 && lines.first() == "/*" && lines.takeLast(4) == publicLicenseSuffix &&
            lines.drop(1).dropLast(4).any(publicCopyright::matches)
    }

    private val enterpriseLicense = """
        /\*
         \* © 20\d\d((, |-)20\d\d)? Element Creations Ltd\.
        (?:.*\n)* \*
         \* Element Creations Ltd, Element Software SARL, Element Software Inc\.,
         \* and Element Software GmbH \(the "Element Group"\) only make this file available
         \* under a proprietary license model\.
         \*
         \* Without a proprietary license with us, you cannot use this file\. The terms of
         \* the proprietary license agreement between you and any member of the Element Group
         \* shall always apply to your use of this file\. Unauthorised use, copying, distribution,
         \* or modification of this file, via any medium, is strictly prohibited\.
         \*
         \* For details about the licensing terms, you must either visit our website or contact
         \* a member of our sales team\.
         \*/
        """.trimIndent().toRegex()

    @Test
    fun `assert that FOSS files have the correct license header`() {
        Konsist
            .scopeFromProject()
            .files
            .filter {
                it.moduleName.startsWith("enterprise").not() &&
                    it.moduleName != "libraries/rustls-tls" &&
                    it.nameWithExtension != "locales.kt" &&
                    it.name.startsWith("Template ").not()
            }
            .also {
                assertThat(it).isNotEmpty()
            }
            .assertTrue {
                hasPublicLicenseHeader(it.text)
            }
    }

    @Test
    fun `assert that Enterprise files have the correct license header`() {
        Konsist
            .scopeFromProject()
            .files
            .filter {
                it.moduleName.startsWith("enterprise")
            }
            .assertTrue {
                enterpriseLicense.containsMatchIn(firstLicenseHeader(it.text))
            }
    }

    @Test
    fun `assert that files do not have double license header`() {
        Konsist
            .scopeFromProject()
            .files
            .filter {
                it.moduleName.endsWith("rustls-tls").not() &&
                it.nameWithExtension != "locales.kt" &&
                it.nameWithExtension != "KonsistLicenseTest.kt" &&
                    it.name.startsWith("Template ").not()
            }
            .assertTrue {
                it.text.count("Element Creations Ltd.") == 1
            }
    }

    @Test
    fun `license checking is bounded to the actual header even for a large source body`() {
        val header = "/*\n * Copyright (c) 2025 Element Creations Ltd.\n" + publicLicenseSuffix.joinToString("\n")
        assertThat(hasPublicLicenseHeader(header + "\n" + "val body = 0\n".repeat(10_000))).isTrue()
        assertThat(hasPublicLicenseHeader("/*\n" + " * documentation\n".repeat(5_000) + " */")).isFalse()
    }

    @Test
    fun `a later source body license cannot substitute for a missing leading license header`() {
        val header = "/*\n * Copyright (c) 2025 Element Creations Ltd.\n" + publicLicenseSuffix.joinToString("\n")
        assertThat(hasPublicLicenseHeader("/* module documentation */\n" + header)).isFalse()
        assertThat(hasPublicLicenseHeader(header.replace(" * SPDX-License-Identifier:", " * Missing-License-Identifier:"))).isFalse()
    }
}

private fun String.count(subString: String): Int {
    var count = 0
    var index = 0
    while (true) {
        index = indexOf(subString, index)
        if (index == -1) return count
        count++
        index += subString.length
    }
}
