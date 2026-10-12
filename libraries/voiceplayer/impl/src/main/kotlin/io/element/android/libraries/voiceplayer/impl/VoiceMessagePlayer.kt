/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.voiceplayer.impl

import dev.zacsweers.metro.ContributesBinding
import io.element.android.libraries.core.extensions.mapCatchingExceptions
import io.element.android.libraries.core.mimetype.MimeTypes
import io.element.android.libraries.di.RoomScope
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.media.MediaFile
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.libraries.mediaplayer.api.MediaPlayer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import java.util.UUID

/**
 * A media player specialized in playing a single voice message.
 */
interface VoiceMessagePlayer {
    fun interface Factory {
        /**
         * Creates a [VoiceMessagePlayer].
         *
         * NB: Different voice messages can use the same content uri (e.g. in case of
         * a forward of a voice message),
         * therefore the mxc:// uri in [mediaSource] is not enough to uniquely identify
         * a voice message. This is why we must provide the eventId as well.
         *
         * @param eventId The eventId of the voice message event.
         * @param mediaSource The media source of the voice message.
         * @param mimeType The mime type of the voice message.
         * @param filename The filename of the voice message.
         */
        fun create(
            eventId: EventId?,
            mediaSource: MediaSource,
            mimeType: String?,
            filename: String?,
        ): VoiceMessagePlayer
    }

    /**
     * The current state of this player.
     */
    val state: Flow<State>

    /**
     * Acquires control of the underlying [MediaPlayer] and prepares it
     * to play the media file.
     *
     * Will suspend whilst the media file is being downloaded and/or
     * the underlying [MediaPlayer] is loading the media file.
     */
    suspend fun prepare(): Result<Unit>

    /**
     * Play the media.
     */
    fun play()

    /**
     * Pause playback.
     */
    fun pause()

    /**
     * Seek to a specific position.
     *
     * @param positionMs The position in milliseconds.
     */
    fun seekTo(positionMs: Long)

    /**
     * Set the playback speed.
     *
     * @param speed The playback speed (e.g., 0.5f for half speed, 1.0f for normal, 2.0f for double speed)
     */
    fun setPlaybackSpeed(speed: Float)

    /** Pause and revoke the process-bound media handle owned by this player. */
    fun close()

    data class State(
        /**
         * Whether the player is ready to play.
         */
        val isReady: Boolean,
        /**
         * Whether this player is currently playing.
         */
        val isPlaying: Boolean,
        /**
         * Whether the player has reached the end of the media.
         */
        val isEnded: Boolean,
        /**
         * The elapsed time of this player in milliseconds.
         */
        val currentPosition: Long,
        /**
         * The duration of the current content, if available.
         */
        val duration: Long?,
    )
}

/**
 * An implementation of [VoiceMessagePlayer] which is backed by a
 * [VoiceMessageMediaRepo] to fetch and cache the media file and
 * which uses a global [MediaPlayer] instance to play the media.
 */
class DefaultVoiceMessagePlayer(
    private val mediaPlayer: MediaPlayer,
    voiceMessageMediaRepoFactory: VoiceMessageMediaRepo.Factory,
    private val eventId: EventId?,
    mediaSource: MediaSource,
    mimeType: String?,
    filename: String?,
) : VoiceMessagePlayer {
    @ContributesBinding(RoomScope::class) // Scoped types can't use @Inject.
    class Factory(
        private val mediaPlayer: MediaPlayer,
        private val voiceMessageMediaRepoFactory: VoiceMessageMediaRepo.Factory,
    ) : VoiceMessagePlayer.Factory {
        override fun create(
            eventId: EventId?,
            mediaSource: MediaSource,
            mimeType: String?,
            filename: String?,
        ): DefaultVoiceMessagePlayer = DefaultVoiceMessagePlayer(
            mediaPlayer = mediaPlayer,
            voiceMessageMediaRepoFactory = voiceMessageMediaRepoFactory,
            eventId = eventId,
            mediaSource = mediaSource,
            mimeType = mimeType,
            filename = filename,
        )
    }

    private val repo = voiceMessageMediaRepoFactory.create(
        mediaSource = mediaSource,
        mimeType = mimeType,
        filename = filename,
    )
    private val sourceOwnerPrefix = UUID.randomUUID().toString()
    private var currentSourceOwnerId: String? = null
    private var activeMediaFile: MediaFile? = null
    private var loadGeneration: Long = 0L
    private var closed: Boolean = false

    private var internalState = MutableStateFlow(
        VoiceMessagePlayer.State(
            isReady = false,
            isPlaying = false,
            isEnded = false,
            currentPosition = 0L,
            duration = null
        )
    )

    override val state: Flow<VoiceMessagePlayer.State> = combine(mediaPlayer.state, internalState) { mediaPlayerState, internalState ->
        if (!closed && mediaPlayerState.isMyTrack) {
            if (mediaPlayerState.isEnded) {
                releaseActiveMediaFile()
            }
            this.internalState.update {
                it.copy(
                    isReady = mediaPlayerState.isReady && activeMediaFile != null,
                    isPlaying = mediaPlayerState.isPlaying,
                    isEnded = mediaPlayerState.isEnded,
                    currentPosition = mediaPlayerState.currentPosition,
                    duration = mediaPlayerState.duration,
                )
            }
        } else {
            this.internalState.update {
                it.copy(
                    isReady = false,
                    isPlaying = false,
                )
            }
        }
        VoiceMessagePlayer.State(
            isReady = internalState.isReady,
            isPlaying = internalState.isPlaying,
            isEnded = internalState.isEnded,
            currentPosition = internalState.currentPosition,
            duration = internalState.duration,
        )
    }.distinctUntilChanged()

    override suspend fun prepare(): Result<Unit> = if (eventId != null) {
        val generation = synchronized(this) {
            if (closed) return Result.failure(IllegalStateException("Voice message player is closed"))
            (++loadGeneration).also { currentSourceOwnerId = "$sourceOwnerPrefix:$it" }
        }
        val sourceOwnerId = "$sourceOwnerPrefix:$generation"
        repo.getMediaFile().mapCatchingExceptions<Unit, MediaFile> { mediaFile ->
            var ownershipTransferred = false
            try {
                check(acceptsLoad(generation)) { "Voice media load completed after player close or replacement" }
                val state = internalState.value
                releaseActiveMediaFile()
                mediaPlayer.setMedia(
                    uri = mediaFile.uriString(),
                    mediaId = eventId.value,
                    mimeType = MimeTypes.Ogg,
                    startPositionMs = if (state.isEnded) 0L else state.currentPosition,
                    sourceOwnerId = sourceOwnerId,
                )
                val accepted = synchronized(this) {
                    if (closed || generation != loadGeneration || !mediaPlayer.state.value.isMyTrack) {
                        false
                    } else {
                        activeMediaFile = mediaFile
                        true
                    }
                }
                check(accepted) { "Voice media load completed after player close or replacement" }
                ownershipTransferred = true
                internalState.update { it.copy(isReady = true) }
            } finally {
                if (!ownershipTransferred) {
                    if (mediaPlayer.state.value.sourceOwnerId == sourceOwnerId) mediaPlayer.clear()
                    synchronized(this) {
                        if (activeMediaFile === mediaFile) activeMediaFile = null
                    }
                    mediaFile.close()
                }
            }
        }
    } else {
        Result.failure(IllegalStateException("Cannot acquireControl on a voice message with no eventId"))
    }

    override fun play() {
        if (inControl()) {
            mediaPlayer.play()
        }
    }

    override fun pause() {
        if (inControl()) {
            mediaPlayer.pause()
        }
    }

    override fun seekTo(positionMs: Long) {
        if (inControl()) {
            mediaPlayer.seekTo(positionMs)
        } else {
            internalState.update {
                it.copy(currentPosition = positionMs)
            }
        }
    }

    override fun setPlaybackSpeed(speed: Float) {
        mediaPlayer.setPlaybackSpeed(speed)
    }

    override fun close() {
        val mediaFile = synchronized(this) {
            closed = true
            loadGeneration++
            activeMediaFile.also { activeMediaFile = null }
        }
        if (mediaPlayer.state.value.sourceOwnerId?.startsWith("$sourceOwnerPrefix:") == true) mediaPlayer.clear()
        mediaFile?.close()
        internalState.update {
            it.copy(isReady = false, isPlaying = false)
        }
    }

    /**
     * Pausing or losing audio focus keeps the bounded lease so playback can resume without another
     * download. End-of-playback and page disposal release it; the lease TTL remains the hard limit.
     */
    private fun releaseActiveMediaFile() {
        val mediaFile = synchronized(this) {
            activeMediaFile.also { activeMediaFile = null }
        }
        mediaFile?.close()
    }

    @Synchronized
    private fun acceptsLoad(generation: Long): Boolean = !closed && generation == loadGeneration

    private val MediaPlayer.State.isMyTrack: Boolean
        get() = eventId != null && currentSourceOwnerId != null && this.mediaId == eventId.value && this.sourceOwnerId == currentSourceOwnerId

    private fun inControl(): Boolean = mediaPlayer.state.value.let {
        !closed && activeMediaFile != null && it.isMyTrack && (it.isReady || it.isEnded)
    }
}
