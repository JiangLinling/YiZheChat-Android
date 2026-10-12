/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.mediaplayer.impl

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.mediaplayer.test.FakeAudioFocus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MediaPreparationRegressionTest {
    private fun source() = FakeSimplePlayer(
        pauseLambda = {},
        clearMediaItemsLambda = {},
        setMediaItemLambda = { _, _ -> },
        prepareLambda = {},
    )

    @Test
    fun `secure source taking more than one second can still prepare`() = runTest {
        val source = source()
        val sut = DefaultMediaPlayer(source, backgroundScope, FakeAudioFocus())
        launch {
            delay(1_500)
            source.simulateMediaItemTransition(MediaItem.Builder().setMediaId("qa-event").build())
            source.simulatePlaybackStateChanged(Player.STATE_READY)
        }
        val ready = sut.setMedia("content://qa.invalid/leased", "qa-event", "audio/ogg", sourceOwnerId = "qa-owner")
        assertThat(ready.isReady).isTrue()
        assertThat(ready.sourceOwnerId).isEqualTo("qa-owner")
        assertThat(testScheduler.currentTime).isEqualTo(1_500)
    }

    @Test
    fun `real parent cancellation stays cancellation and clears its own source`() = runTest {
        val sut = DefaultMediaPlayer(source(), backgroundScope, FakeAudioFocus())
        val pending = async { sut.setMedia("content://qa.invalid/leased", "qa-event", "audio/ogg", sourceOwnerId = "qa-owner") }
        runCurrent()
        pending.cancelAndJoin()
        assertThat(pending.isCancelled).isTrue()
        assertThat(sut.state.value.mediaId).isNull()
        assertThat(sut.state.value.sourceOwnerId).isNull()
    }

    @Test
    fun `former prepare cannot clear new source of the same event`() = runTest {
        val source = source()
        val sut = DefaultMediaPlayer(source, backgroundScope, FakeAudioFocus())
        val former = async { runCatchingExceptions {
            sut.setMedia("content://qa.invalid/former", "qa-event", "audio/ogg", sourceOwnerId = "former")
        } }
        runCurrent()
        val current = async {
            sut.setMedia("content://qa.invalid/current", "qa-event", "audio/ogg", sourceOwnerId = "current")
        }
        runCurrent()
        assertThat(former.await().isFailure).isTrue()
        assertThat(sut.state.value.sourceOwnerId).isEqualTo("current")
        source.simulateMediaItemTransition(MediaItem.Builder().setMediaId("qa-event").build())
        source.simulatePlaybackStateChanged(Player.STATE_READY)
        assertThat(current.await().isReady).isTrue()
        assertThat(sut.state.value.sourceOwnerId).isEqualTo("current")
    }
}
