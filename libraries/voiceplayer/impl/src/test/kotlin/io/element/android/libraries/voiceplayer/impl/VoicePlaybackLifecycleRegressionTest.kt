/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.voiceplayer.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.core.mimetype.MimeTypes
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.libraries.matrix.test.AN_EVENT_ID
import io.element.android.libraries.matrix.test.media.FakeMediaFile
import io.element.android.libraries.mediaplayer.test.FakeMediaPlayer
import io.element.android.libraries.voiceplayer.api.VoiceMessageEvent
import io.element.android.libraries.voiceplayer.api.VoiceMessageState
import io.element.android.tests.testutils.consumeItemsUntilPredicate
import io.element.android.tests.testutils.test
import kotlinx.coroutines.test.runTest
import org.junit.Test

class VoicePlaybackLifecycleRegressionTest {
    @Test
    fun `cached presenter can play after leaving and returning to composition`() = runTest {
        val presenter = createVoiceMessagePresenter()
        presenter.test {
            consumeItemsUntilPredicate { it.hasBeenPlayed != null }
            cancelAndIgnoreRemainingEvents()
        }
        presenter.test {
            consumeItemsUntilPredicate { it.hasBeenPlayed != null }.last().eventSink(VoiceMessageEvent.PlayPause)
            val result = consumeItemsUntilPredicate {
                it.buttonType == VoiceMessageState.ButtonType.Pause || it.buttonType == VoiceMessageState.ButtonType.Retry
            }.last()
            assertThat(result.buttonType).isEqualTo(VoiceMessageState.ButtonType.Pause)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `closing former player does not pause new source of the same Matrix event`() = runTest {
        val mediaPlayer = FakeMediaPlayer()
        fun player() = DefaultVoiceMessagePlayer(
            mediaPlayer = mediaPlayer,
            voiceMessageMediaRepoFactory = { _, _, _ ->
                FakeVoiceMessageMediaRepo().apply { mediaFile = FakeMediaFile("qa-voice") }
            },
            eventId = AN_EVENT_ID,
            mediaSource = MediaSource("mxc://qa.invalid/synthetic-voice"),
            mimeType = MimeTypes.Ogg,
            filename = "qa.ogg",
        )
        val former = player()
        val current = player()
        assertThat(former.prepare().isSuccess).isTrue()
        former.play()
        assertThat(current.prepare().isSuccess).isTrue()
        current.play()
        former.close()
        assertThat(mediaPlayer.state.value.mediaId).isEqualTo(AN_EVENT_ID.value)
        assertThat(mediaPlayer.state.value.isPlaying).isTrue()
        current.close()
        assertThat(mediaPlayer.state.value.isPlaying).isFalse()
    }
}
