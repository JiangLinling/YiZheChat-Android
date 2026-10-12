/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.voiceplayer.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.preferences.api.store.SessionPreferencesStore
import io.element.android.libraries.voiceplayer.api.VoiceMessageEvent
import io.element.android.libraries.voiceplayer.api.VoiceMessageState
import io.element.android.services.analytics.test.FakeAnalyticsService
import io.element.android.tests.testutils.consumeItemsUntilPredicate
import io.element.android.tests.testutils.test
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class VoicePlayedPresenterTest {
    private val heard = MutableStateFlow(false)
    private val eventId = EventId("\$listened-voice-event")
    private val preferences = mockk<SessionPreferencesStore> {
        every { hasVoiceMessagePlayed(eventId.value) } returns heard
        coEvery { markVoiceMessagePlayed(eventId.value) } answers { heard.value = true }
    }
    private val playback = MutableStateFlow(VoiceMessagePlayer.State(false, false, false, 0, 10_000))
    private val player = mockk<VoiceMessagePlayer>(relaxed = true) {
        every { state } returns playback
        coEvery { prepare() } returns Result.success(Unit)
    }

    @Test fun `preparation alone does not mark listened actual playback persists across presenter recreation`() = runTest {
        fun presenter() = VoiceMessagePresenter(FakeAnalyticsService(), this, InMemoryVoicePlayerStore(), { player }, eventId, 10.seconds, preferences)
        presenter().test {
            consumeItemsUntilPredicate { it.hasBeenPlayed != null }.last().eventSink(VoiceMessageEvent.PlayPause)
            consumeItemsUntilPredicate { it.showCursor }
            playback.value = playback.value.copy(isReady = true)
            runCurrent()
            coVerify(exactly = 0) { preferences.markVoiceMessagePlayed(any()) }
            playback.value = playback.value.copy(isPlaying = true, currentPosition = 50)
            assertThat(consumeItemsUntilPredicate { it.hasBeenPlayed == true }.last().hasBeenPlayed).isTrue()
            coVerify(exactly = 1) { preferences.markVoiceMessagePlayed(eventId.value) }
            cancelAndIgnoreRemainingEvents()
        }
        playback.value = playback.value.copy(isPlaying = false)
        presenter().test {
            assertThat(consumeItemsUntilPredicate { it.hasBeenPlayed == true }.last().hasBeenPlayed).isTrue()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `failed preparation does not mark listened`() = runTest {
        coEvery { player.prepare() } returns Result.failure(IllegalStateException("test download denied"))
        VoiceMessagePresenter(FakeAnalyticsService(), this, InMemoryVoicePlayerStore(), { player }, eventId, 10.seconds, preferences).test {
            consumeItemsUntilPredicate { it.hasBeenPlayed != null }.last().eventSink(VoiceMessageEvent.PlayPause)
            assertThat(consumeItemsUntilPredicate { it.buttonType == VoiceMessageState.ButtonType.Retry }.last().buttonType).isEqualTo(VoiceMessageState.ButtonType.Retry)
            coVerify(exactly = 0) { preferences.markVoiceMessagePlayed(any()) }
            assertThat(heard.value).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
    }
}
