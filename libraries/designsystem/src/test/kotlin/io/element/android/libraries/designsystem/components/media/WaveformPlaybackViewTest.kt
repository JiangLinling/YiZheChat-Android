/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.designsystem.components.media

import com.google.common.truth.Truth.assertThat
import kotlinx.collections.immutable.persistentListOf
import org.junit.Test

class WaveformPlaybackViewTest {
    @Test
    fun `short waveform is stretched across the available sample count`() {
        val result = persistentListOf(0f, 1f).normalisedData(maxSamplesCount = 5)

        assertThat(result).containsExactly(0f, 0.25f, 0.5f, 0.75f, 1f).inOrder()
    }

    @Test
    fun `long waveform is condensed while preserving both ends`() {
        val result = persistentListOf(0f, 1f, 2f, 3f, 4f).normalisedData(maxSamplesCount = 3)

        assertThat(result).containsExactly(0f, 2f, 4f).inOrder()
    }

    @Test
    fun `empty waveform remains empty`() {
        val result = persistentListOf<Float>().normalisedData(maxSamplesCount = 20)

        assertThat(result).isEmpty()
    }
}
