/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.voiceplayer.impl

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.architecture.runUpdatingState
import io.element.android.libraries.core.extensions.flatMap
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.preferences.api.store.SessionPreferencesStore
import io.element.android.libraries.ui.utils.time.formatShort
import io.element.android.libraries.voiceplayer.api.VoiceMessageEvent
import io.element.android.libraries.voiceplayer.api.VoiceMessageException
import io.element.android.libraries.voiceplayer.api.VoiceMessageState
import io.element.android.services.analytics.api.AnalyticsService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.distinctUntilChanged
import timber.log.Timber
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class VoiceMessagePresenter(
    private val analyticsService: AnalyticsService,
    private val sessionCoroutineScope: CoroutineScope,
    private val voicePlayerStore: VoicePlayerStore,
    private val playerFactory: () -> VoiceMessagePlayer,
    private val eventId: EventId?,
    private val duration: Duration,
    private val sessionPreferencesStore: SessionPreferencesStore,
) : Presenter<VoiceMessageState> {
    // A cached presenter retains only a non-sensitive cursor, never a disposed player/lease.
    private var resumePositionMs: Long = 0L

    @Composable
    override fun present(): VoiceMessageState {
        val localCoroutineScope = rememberCoroutineScope()
        val player = remember { playerFactory().also { it.seekTo(resumePositionMs) } }
        val play = remember(player) { mutableStateOf<AsyncData<Unit>>(AsyncData.Uninitialized) }
        var playRequestedAtNanos by remember(player) { mutableStateOf<Long?>(null) }
        val hasBeenPlayed by remember(eventId) {
            eventId?.let { sessionPreferencesStore.hasVoiceMessagePlayed(it.value).distinctUntilChanged() } ?: flowOf(false)
        }.collectAsState(initial = null)
        val playerState by player.state.collectAsState(
            VoiceMessagePlayer.State(
                isReady = false,
                isPlaying = false,
                isEnded = false,
                currentPosition = 0L,
                duration = null
            )
        )

        val playbackSpeedIndex by remember {
            voicePlayerStore.playBackSpeedIndex().map { it.takeIf { index -> index in VoicePlayerConfig.availablePlaybackSpeeds.indices } ?: 0 }
        }.collectAsState(0)

        DisposableEffect(player) {
            onDispose(player::close)
        }

        LaunchedEffect(playerState.currentPosition, playerState.isEnded) {
            resumePositionMs = if (playerState.isEnded) 0L else playerState.currentPosition
        }

        LaunchedEffect(playerState.isPlaying) {
            if (playerState.isPlaying) {
                playRequestedAtNanos?.let {
                    Timber.d("Voice playback started: eventId=%s elapsedMs=%s", eventId?.value, (System.nanoTime() - it) / 1_000_000)
                    playRequestedAtNanos = null
                }
                if (eventId != null && hasBeenPlayed != true) sessionCoroutineScope.launch {
                    runCatchingExceptions { sessionPreferencesStore.markVoiceMessagePlayed(eventId.value) }
                        .onFailure { Timber.w("Voice listened marker write failed: type=%s", it.javaClass.simpleName) }
                }
            }
        }

        LaunchedEffect(playbackSpeedIndex) {
            player.setPlaybackSpeed(VoicePlayerConfig.availablePlaybackSpeeds[playbackSpeedIndex])
        }

        val buttonType by remember {
            derivedStateOf {
                when {
                    eventId == null -> VoiceMessageState.ButtonType.Disabled
                    playerState.isPlaying -> VoiceMessageState.ButtonType.Pause
                    play.value is AsyncData.Loading -> VoiceMessageState.ButtonType.Downloading
                    play.value is AsyncData.Failure -> VoiceMessageState.ButtonType.Retry
                    else -> VoiceMessageState.ButtonType.Play
                }
            }
        }
        val duration by remember {
            derivedStateOf { playerState.duration ?: duration.inWholeMilliseconds }
        }
        val progress by remember {
            derivedStateOf {
                playerState.currentPosition / duration.toFloat()
            }
        }
        val time by remember {
            derivedStateOf {
                when {
                    playerState.isReady && !playerState.isEnded -> playerState.currentPosition
                    playerState.currentPosition > 0 -> playerState.currentPosition
                    else -> duration
                }.milliseconds.formatShort()
            }
        }
        val showCursor by remember {
            derivedStateOf {
                !play.value.isUninitialized() && !playerState.isEnded
            }
        }

        fun handleEvent(event: VoiceMessageEvent) {
            when (event) {
                is VoiceMessageEvent.PlayPause -> {
                    if (play.value is AsyncData.Loading) return
                    if (!playerState.isPlaying) {
                        playRequestedAtNanos = System.nanoTime()
                        Timber.d("Voice playback requested: eventId=%s ready=%s", eventId?.value, playerState.isReady)
                    }
                    if (playerState.isPlaying) {
                        player.pause()
                    } else if (playerState.isReady) {
                        player.play()
                    } else {
                        play.value = AsyncData.Loading()
                        // Accepted LC16 work and receipts retain the existing session owner.
                        // Disposal closes this player; late media is rejected and released.
                        sessionCoroutineScope.launch {
                            try {
                                play.runUpdatingState(
                                    errorTransform = {
                                        // Do not log media locations, content or exception messages.
                                        Timber.w("Voice playback preparation failed: type=%s cause=%s at=%s",
                                            it.javaClass.simpleName, it.cause?.javaClass?.simpleName,
                                            it.stackTrace.firstOrNull()?.toString())
                                        analyticsService.trackError(
                                            VoiceMessageException.PlayMessageError("Error while trying to play voice message", it)
                                        )
                                        it
                                    },
                                ) {
                                    player.prepare().flatMap {
                                        runCatchingExceptions { player.play() }
                                    }
                                }
                            } finally {
                                // Real owner cancellation stays cancellation, but cannot strand a cached Loading state.
                                if (play.value is AsyncData.Loading) play.value = AsyncData.Uninitialized
                            }
                        }
                    }
                }
                is VoiceMessageEvent.Seek -> {
                    val targetPositionMs = (event.percentage * duration).toLong()
                    Timber.d("Voice seek requested: percentage=%s targetMs=%s", event.percentage, targetPositionMs)
                    player.seekTo(targetPositionMs)
                }
                is VoiceMessageEvent.ChangePlaybackSpeed -> localCoroutineScope.launch {
                    voicePlayerStore.setPlayBackSpeedIndex(
                        (playbackSpeedIndex + 1) % VoicePlayerConfig.availablePlaybackSpeeds.size
                    )
                }
            }
        }

        return VoiceMessageState(
            buttonType = buttonType,
            progress = progress,
            time = time,
            showCursor = showCursor,
            playbackSpeed = VoicePlayerConfig.availablePlaybackSpeeds[playbackSpeedIndex],
            eventSink = ::handleEvent,
            hasBeenPlayed = hasBeenPlayed,
        )
    }
}
