/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.mediaupload.impl

import android.net.Uri
import androidx.media3.transformer.Effects
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.mediaupload.api.MediaOptimizationConfig
import io.element.android.libraries.preferences.api.store.VideoCompressionPreset
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VideoEditExportTest {
    private val config = MediaOptimizationConfig(false, VideoCompressionPreset.STANDARD)

    @Test
    fun `export receives both clip boundaries and removes the audio track`() {
        val edited = buildEditedVideoInput(
            Uri.parse("content://test/video"),
            config.copy(videoStartMs = 1250, videoEndMs = 5500, muteAudio = true),
            30,
            Effects(emptyList(), emptyList())
        )
        assertThat(edited.mediaItem.clippingConfiguration.startPositionMs).isEqualTo(1250)
        assertThat(edited.mediaItem.clippingConfiguration.endPositionMs).isEqualTo(5500)
        assertThat(edited.removeAudio).isTrue()
    }

    @Test
    fun `failed export cannot send the untrimmed source or restore audio`() {
        listOf(config.copy(videoStartMs = 500), config.copy(videoEndMs = 1000), config.copy(muteAudio = true)).forEach { edited ->
            assertThat(runCatching { requireUneditedVideoFallback(edited) }.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        }
        requireUneditedVideoFallback(config)
    }
}
