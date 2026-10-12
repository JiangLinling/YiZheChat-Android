/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.designsystem.theme

import com.google.common.truth.Truth.assertThat
import io.element.android.compound.tokens.generated.compoundColorsDark
import io.element.android.compound.tokens.generated.compoundColorsLight
import org.junit.Test

class YiZheThemeColorsTest {
    @Test
    fun `light palette maps the brand surfaces actions and icons`() {
        val colors = compoundColorsLight.withYiZheLightColors()

        assertThat(colors.bgCanvasDefault).isEqualTo(YiZhePalette.paper)
        assertThat(colors.bgActionPrimaryRest).isEqualTo(YiZhePalette.bamboo)
        assertThat(colors.iconAccentPrimary).isEqualTo(YiZhePalette.bamboo)
        assertThat(colors.textPrimary).isEqualTo(YiZhePalette.ink)
        assertThat(colors.isLight).isTrue()
    }

    @Test
    fun `dark palette maps the ink night and celadon colours`() {
        val colors = compoundColorsDark.withYiZheDarkColors()

        assertThat(colors.bgCanvasDefault).isEqualTo(YiZhePalette.inkNight)
        assertThat(colors.bgActionPrimaryRest).isEqualTo(YiZhePalette.celadonMoon)
        assertThat(colors.iconAccentPrimary).isEqualTo(YiZhePalette.celadonMoon)
        assertThat(colors.textPrimary).isEqualTo(YiZhePalette.moonPaper)
        assertThat(colors.isLight).isFalse()
    }
}
