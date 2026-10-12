/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.x

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.abs

class FloatingChatWindowBoundsTest {
    @Test
    fun `resize preserves centre and aspect ratio away from screen edges`() {
        for (scale in listOf(.4f, .6f, .8f)) {
            val b = floatingChatWindowBounds(1080, 2200, scale, .5f, .5f, 0)
            assertThat(b.left + b.width / 2).isWithin(1).of(540)
            assertThat(b.top + b.height / 2).isWithin(1).of(1100)
            assertThat(abs(b.width / 1080f - b.height / 2200f)).isLessThan(.002f)
        }
    }

    @Test
    fun `both handles support inward shrink and outward growth with no tap jump`() {
        for (left in listOf(true, false)) {
            val outwardX = if (left) -100f else 100f
            assertThat(floatingChatDragScale(.8f, 1080, 2200, left, 0f, 0f)).isEqualTo(.8f)
            assertThat(floatingChatDragScale(.8f, 1080, 2200, left, outwardX, 200f)).isGreaterThan(.8f)
            assertThat(floatingChatDragScale(.8f, 1080, 2200, left, -outwardX, -200f)).isLessThan(.8f)
        }
    }

    @Test
    fun `growth fits the whole available screen and clamps moved centres`() {
        val b = floatingChatWindowBounds(1080, 2200, 2f, 1.5f, -.2f, 0)
        assertThat(b).isEqualTo(FloatingChatWindowBounds(0, 0, 1080, 2200, 1f))
    }

    @Test
    fun `keyboard fitting shrinks both axes and restoration keeps requested size`() {
        val withIme = floatingChatWindowBounds(1080, 2200, .8f, .5f, .5f, 900)
        assertThat(withIme.top + withIme.height).isAtMost(1300)
        assertThat(abs(withIme.width / 1080f - withIme.height / 2200f)).isLessThan(.002f)
        val restored = floatingChatWindowBounds(1080, 2200, .8f, .5f, .5f, 0)
        assertThat(restored.scale).isEqualTo(.8f)
        assertThat(restored.width).isGreaterThan(withIme.width)
    }
    @Test
    fun `curve is draggable but transparent area over the composer passes through`() {
        assertThat(floatingChatHandleHit(9f, 24f)).isTrue()
        assertThat(floatingChatHandleHit(25f, 39f)).isTrue()
        assertThat(floatingChatHandleHit(25f, 10f)).isFalse()
        assertThat(floatingChatHandleHit(38f, 20f)).isFalse()
    }
    @Test
    fun `full display keyboard boundary fits composer without feedback from floating height`() {
        val occlusion = floatingChatImeOcclusion(3200, 3144, 1229)
        assertThat(occlusion).isEqualTo(1173)
        val fitted = floatingChatWindowBounds(1440, 3000, .8f, .5f, .5f, occlusion)
        assertThat(144 + fitted.top + fitted.height).isEqualTo(1971)
        assertThat(fitted.width).isEqualTo(877)
        // A gap below a small window is not a keyboard. Hiding IME restores the requested size.
        assertThat(floatingChatImeOcclusion(3200, 3144, 0)).isEqualTo(0)
        assertThat(floatingChatWindowBounds(1440, 3000, .8f, .5f, .5f, 0).width).isEqualTo(1152)
    }
}
