/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.location.api.internal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OpenFreeMapTileServerStyleUriBuilderTest {
    private val builder = OpenFreeMapTileServerStyleUriBuilder(
        lightStyleUrl = "https://maps.example/light",
        darkStyleUrl = "https://maps.example/dark",
    )

    @Test
    fun `light map uri uses light fallback style`() {
        assertThat(builder.build(customMapStyleUrl = null, darkMode = false))
            .isEqualTo("https://maps.example/light")
    }

    @Test
    fun `dark map uri uses dark fallback style`() {
        assertThat(builder.build(customMapStyleUrl = null, darkMode = true))
            .isEqualTo("https://maps.example/dark")
    }

    @Test
    fun `custom map uri is preserved`() {
        assertThat(builder.build(customMapStyleUrl = "https://custom.example/style.json?token=value", darkMode = true))
            .isEqualTo("https://custom.example/style.json?token=value")
    }

    @Test
    fun `blank custom map uri uses fallback`() {
        assertThat(builder.build(customMapStyleUrl = "  ", darkMode = false))
            .isEqualTo("https://maps.example/light")
    }
}
