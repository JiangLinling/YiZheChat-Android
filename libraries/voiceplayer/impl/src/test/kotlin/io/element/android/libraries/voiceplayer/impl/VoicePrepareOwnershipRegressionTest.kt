/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.voiceplayer.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.core.mimetype.MimeTypes
import io.element.android.libraries.matrix.api.media.MediaFile
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.libraries.matrix.test.AN_EVENT_ID
import io.element.android.libraries.mediaplayer.test.FakeMediaPlayer
import io.element.android.libraries.voiceplayer.api.VoiceMessageEvent
import io.element.android.libraries.voiceplayer.api.VoiceMessageState
import io.element.android.tests.testutils.consumeItemsUntilPredicate
import io.element.android.tests.testutils.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoicePrepareOwnershipRegressionTest {
    private class Lease : MediaFile {
        var closes = 0
        override fun path() = "qa-voice.ogg"
        override fun persist(path: String) = false
        override fun close() { closes++ }
    }

    @Test
    fun `rapid repeated clicks create only one active voice download`() = runTest {
        var downloads = 0
        val repo = object : VoiceMessageMediaRepo {
            override suspend fun getMediaFile(): Result<MediaFile> {
                downloads++
                delay(2_000)
                return Result.success(Lease())
            }
        }
        createVoiceMessagePresenter(voiceMessageMediaRepo = repo).test {
            val state = consumeItemsUntilPredicate { it.hasBeenPlayed != null }.last()
            state.eventSink(VoiceMessageEvent.PlayPause)
            state.eventSink(VoiceMessageEvent.PlayPause)
            consumeItemsUntilPredicate { it.buttonType == VoiceMessageState.ButtonType.Pause }
            assertThat(downloads).isEqualTo(1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `superseded preparation closes its lease without clearing a newer generation`() = runTest {
        val mediaPlayer = FakeMediaPlayer(fakePrepareDelayMs = 1_000)
        val leases = mutableListOf<Lease>()
        val repo = object : VoiceMessageMediaRepo {
            override suspend fun getMediaFile(): Result<MediaFile> = Result.success(Lease().also { leases += it })
        }
        val player = DefaultVoiceMessagePlayer(
            mediaPlayer, { _, _, _ -> repo }, AN_EVENT_ID,
            MediaSource("mxc://qa.invalid/synthetic-voice"), MimeTypes.Ogg, "qa.ogg",
        )
        val former = async { player.prepare() }
        runCurrent()
        advanceTimeBy(100)
        val current = async { player.prepare() }
        runCurrent()
        assertThat(former.await().isFailure).isTrue()
        assertThat(current.await().isSuccess).isTrue()
        player.play()
        assertThat(mediaPlayer.state.value.isPlaying).isTrue()
        assertThat(leases[0].closes).isEqualTo(1)
        assertThat(leases[1].closes).isEqualTo(0)
        player.close()
        assertThat(leases[1].closes).isEqualTo(1)
    }
}
