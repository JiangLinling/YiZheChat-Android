/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.call

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class ElementCallChineseTranslationsTest {
    @Test
    fun `simplified Chinese call handset labels do not fall back to English`() {
        val translations = Json.parseToJsonElement(findTranslationsFile().readText()).jsonObject

        val videoTile = translations.getValue("video_tile").jsonObject
        assertThat(videoTile.getValue("calling").jsonPrimitive.content).isEqualTo("呼叫中…")

        val handset = translations.getValue("handset").jsonObject
        assertThat(handset.getValue("overlay_title").jsonPrimitive.content).isEqualTo("听筒通话")
        assertThat(handset.getValue("overlay_description").jsonPrimitive.content).isEqualTo("贴近耳边即可通话")
        assertThat(handset.getValue("overlay_back_button").jsonPrimitive.content).isEqualTo("切换到扬声器")
    }

    private companion object {
        const val SIMPLIFIED_CHINESE_TRANSLATIONS_PATH =
            "element-call/assets/zh-Hans-app-d-m--oHo.json"

        fun findTranslationsFile(): File {
            return generateSequence(File(requireNotNull(System.getProperty("user.dir"))).canonicalFile) { it.parentFile }
                .map { File(it, "app/src/main/assets/$SIMPLIFIED_CHINESE_TRANSLATIONS_PATH") }
                .firstOrNull(File::isFile)
                ?: error("Missing Element Call Chinese translations asset")
        }
    }
}
