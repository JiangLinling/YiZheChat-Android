/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaplayer.impl

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.audio.api.AudioFocus
import io.element.android.libraries.audio.api.AudioSessionRequest
import io.element.android.libraries.audio.api.AudioSessionRole
import io.element.android.libraries.di.RoomScope
import io.element.android.libraries.di.annotations.SessionCoroutineScope
import io.element.android.libraries.mediaplayer.api.MediaPlayer
import io.element.android.libraries.mediaplayer.api.MediaPreparationTimeoutException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.UUID

/**
 * Default implementation of [MediaPlayer] backed by a [SimplePlayer].
 */
@ContributesBinding(RoomScope::class)
@SingleIn(RoomScope::class)
class DefaultMediaPlayer(
    private val player: SimplePlayer,
    @SessionCoroutineScope
    private val sessionCoroutineScope: CoroutineScope,
    private val audioFocus: AudioFocus,
) : MediaPlayer {
    private val listener = object : SimplePlayer.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update {
                it.copy(
                    currentPosition = player.currentPosition,
                    duration = duration,
                    isPlaying = isPlaying,
                )
            }
            if (isPlaying) {
                job = sessionCoroutineScope.launch { updateCurrentPosition() }
            } else {
                job?.cancel()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?) {
            _state.update {
                it.copy(
                    currentPosition = player.currentPosition,
                    duration = duration,
                    mediaId = mediaItem?.mediaId,
                )
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _state.update {
                it.copy(
                    isReady = playbackState == Player.STATE_READY,
                    isEnded = playbackState == Player.STATE_ENDED,
                    currentPosition = player.currentPosition,
                    duration = duration,
                )
            }
            if (playbackState == Player.STATE_ENDED) releaseActiveFocus()
        }

        override fun onPositionDiscontinuity(positionMs: Long, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
                Timber.d("Voice seek applied by player: actualMs=%s reason=%s", positionMs, reason)
                _state.update {
                    it.copy(currentPosition = positionMs)
                }
            } else {
                Timber.d("Ignoring non-seek voice position discontinuity: actualMs=%s reason=%s", positionMs, reason)
            }
        }
    }

    init {
        player.addListener(listener)
    }

    private var job: Job? = null
    private var activeFocusOwnerId: String? = null

    private val _state = MutableStateFlow(
        MediaPlayer.State(
            isReady = false,
            isPlaying = false,
            isEnded = false,
            mediaId = null,
            currentPosition = 0L,
            duration = null,
        )
    )

    override val state: StateFlow<MediaPlayer.State> = _state.asStateFlow()

    override suspend fun setMedia(
        uri: String,
        mediaId: String,
        mimeType: String,
        startPositionMs: Long,
        sourceOwnerId: String?,
    ): MediaPlayer.State {
        pause()
        player.clearMediaItems()
        _state.update {
            it.copy(isReady = false, isPlaying = false, isEnded = false, mediaId = null,
                currentPosition = startPositionMs, duration = null, sourceOwnerId = sourceOwnerId)
        }
        try {
            player.setMediaItem(
                MediaItem.Builder().setUri(uri).setMediaId(mediaId).setMimeType(mimeType).build(),
                startPositionMs,
            )
            player.prepare()
            // A total preparation deadline, not a one-second inter-emission cancellation.
            val ready = withTimeoutOrNull(5_000L) {
                state.first { it.sourceOwnerId != sourceOwnerId || (it.isReady && it.mediaId == mediaId) }
            } ?: throw MediaPreparationTimeoutException()
            check(ready.sourceOwnerId == sourceOwnerId) { "Media preparation was replaced" }
            return ready
        } catch (failure: Exception) {
            // A former owner must never clear a replacement, including the same Matrix event.
            if (state.value.sourceOwnerId == sourceOwnerId && state.value.mediaId in listOf(null, mediaId)) clear()
            throw failure
        }
    }

    override fun play() {
        val ownerId = "voice-playback:${UUID.randomUUID()}"
        val operationId = state.value.mediaId ?: ownerId
        val acquired = audioFocus.acquire(
            AudioSessionRequest(
                ownerId = ownerId,
                role = AudioSessionRole.VoiceMessagePlayback,
                operationId = operationId,
                onOwnershipLost = {
                    val owned = synchronized(this) {
                        if (activeFocusOwnerId == ownerId) {
                            activeFocusOwnerId = null
                            true
                        } else {
                            false
                        }
                    }
                    if (owned && player.isPlaying()) player.pause()
                },
            )
        )
        if (!acquired) return
        synchronized(this) { activeFocusOwnerId = ownerId }
        if (player.playbackState == Player.STATE_ENDED) {
            // There's a bug with some ogg files that somehow report to
            // have no duration.
            // With such files, once playback has ended once, calling
            // player.seekTo(0) and then player.play() results in the
            // player starting and stopping playing immediately effectively
            // playing no sound.
            // This is a workaround which will reload the media file.
            player.getCurrentMediaItem()?.let {
                player.setMediaItem(it, 0)
                player.prepare()
            }
        }
        player.play()
    }

    override fun pause() {
        player.pause()
        releaseActiveFocus()
    }

    override fun clear() {
        pause()
        job?.cancel()
        job = null
        player.clearMediaItems()
        _state.value = MediaPlayer.State(
            isReady = false,
            isPlaying = false,
            isEnded = false,
            mediaId = null,
            currentPosition = 0L,
            duration = null,
        )
    }

    override fun seekTo(positionMs: Long) {
        val targetPositionMs = positionMs.coerceIn(0L, duration ?: Long.MAX_VALUE)
        Timber.d("Voice seek submitted to player: requestedMs=%s targetMs=%s durationMs=%s", positionMs, targetPositionMs, duration)
        player.seekTo(targetPositionMs)
        _state.update {
            // ExoPlayer applies seeks asynchronously. Publish the requested position immediately so a
            // paused voice message does not snap back to the old position while the seek is in flight.
            it.copy(currentPosition = targetPositionMs)
        }
    }

    override fun setPlaybackSpeed(speed: Float) {
        player.setPlaybackSpeed(speed)
    }

    override fun close() {
        releaseActiveFocus()
        player.release()
    }

    private fun releaseActiveFocus() {
        val ownerId = synchronized(this) {
            activeFocusOwnerId.also { activeFocusOwnerId = null }
        }
        ownerId?.let(audioFocus::release)
    }

    private suspend fun updateCurrentPosition() {
        while (true) {
            if (!_state.value.isPlaying) return
            delay(100)
            _state.update {
                it.copy(currentPosition = player.currentPosition)
            }
        }
    }

    private val duration: Long?
        get() = player.duration.let {
            if (it == C.TIME_UNSET) null else it
        }
}
