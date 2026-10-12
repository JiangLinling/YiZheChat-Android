/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaviewer.impl.local.video

import android.annotation.SuppressLint
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.common.Player
import androidx.media3.common.Player.STATE_READY
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import io.element.android.compound.theme.ElementTheme
import io.element.android.libraries.audio.api.AudioFocus
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.text.toDp
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.utils.KeepScreenOn
import io.element.android.libraries.designsystem.utils.OnLifecycleEvent
import io.element.android.libraries.mediaviewer.api.local.LocalMedia
import io.element.android.libraries.mediaviewer.impl.R
import io.element.android.libraries.mediaviewer.impl.local.LocalMediaViewState
import io.element.android.libraries.mediaviewer.impl.local.PlayableState
import io.element.android.libraries.mediaviewer.impl.local.player.MediaPlayerControllerState
import io.element.android.libraries.mediaviewer.impl.local.player.MediaPlayerControllerView
import io.element.android.libraries.mediaviewer.impl.local.player.rememberExoPlayer
import io.element.android.libraries.mediaviewer.impl.local.player.seekToEnsurePlaying
import io.element.android.libraries.mediaviewer.impl.local.player.togglePlay
import io.element.android.libraries.mediaviewer.impl.local.rememberLocalMediaViewState
import io.element.android.libraries.mediaviewer.impl.segmented.player.SegmentedVideoSeekStart
import io.element.android.libraries.mediaviewer.impl.segmented.player.SegmentedVideoAdvanceStart
import io.element.android.libraries.mediaviewer.impl.segmented.player.SegmentedVideoTimelineSnapshot
import io.element.android.libraries.mediaviewer.impl.segmented.product.SegmentedVideoAttachment
import io.element.android.libraries.mediaviewer.impl.segmented.product.SegmentedVideoPlaybackRegistry
import io.element.android.libraries.ui.utils.a11y.isTalkbackActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.saket.telephoto.zoomable.zoomable
import timber.log.Timber
import kotlin.time.Duration.Companion.seconds

@SuppressLint("UnsafeOptInUsageError")
@Composable
fun MediaVideoView(
    isDisplayed: Boolean,
    localMediaViewState: LocalMediaViewState,
    bottomPaddingInPixels: Int,
    localMedia: LocalMedia?,
    autoplay: Boolean,
    audioFocus: AudioFocus?,
    onPlaybackEnded: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val continuous = localMedia?.uri?.let(SegmentedVideoPlaybackRegistry::resolve)?.continuous == true
    val exoPlayer = rememberExoPlayer(forAudioOnly = false, continuousProtectedVideo = continuous, progressiveProtectedVideo = localMedia?.progressiveVideo != null)
    key(exoPlayer) {
        ExoPlayerMediaVideoView(
            isDisplayed = isDisplayed,
            localMediaViewState = localMediaViewState,
            bottomPaddingInPixels = bottomPaddingInPixels,
            exoPlayer = exoPlayer,
            localMedia = localMedia,
            autoplay = autoplay,
            audioFocus = audioFocus,
            onPlaybackEnded = onPlaybackEnded,
            modifier = modifier,
        )
    }
}

@SuppressLint("UnsafeOptInUsageError")
@Composable
private fun ExoPlayerMediaVideoView(
    isDisplayed: Boolean,
    localMediaViewState: LocalMediaViewState,
    bottomPaddingInPixels: Int,
    exoPlayer: ExoPlayer,
    localMedia: LocalMedia?,
    autoplay: Boolean,
    audioFocus: AudioFocus?,
    onPlaybackEnded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var mediaPlayerControllerState: MediaPlayerControllerState by remember {
        mutableStateOf(
            MediaPlayerControllerState(
                isVisible = true,
                isPlaying = false,
                isReady = false,
                progressInMillis = 0,
                durationInMillis = 0,
                canMute = true,
                isMuted = false,
                seekingToMillis = null,
            )
        )
    }

    val playableState: PlayableState.Playable by remember {
        derivedStateOf {
            PlayableState.Playable(
                isShowingControls = mediaPlayerControllerState.isVisible,
            )
        }
    }

    localMediaViewState.playableState = playableState

    val currentOnPlaybackEnded by rememberUpdatedState(onPlaybackEnded)
    var hasPreparedPlayableMedia by remember { mutableStateOf(false) }
    var segmentedPlaybackFailedClosed by remember { mutableStateOf(false) }
    LaunchedEffect(localMedia?.uri) {
        hasPreparedPlayableMedia = false
        segmentedPlaybackFailedClosed = false
    }
    var autoHideController by remember { mutableIntStateOf(0) }
    var segmentedAttachment by remember(localMedia?.uri) { mutableStateOf<SegmentedVideoAttachment?>(null) }
    val playbackScope = rememberCoroutineScope()
    val currentSegmentedAttachment by rememberUpdatedState(segmentedAttachment)
    val currentProgressiveVideo by rememberUpdatedState(localMedia?.progressiveVideo)
    val playerListener = remember {
        object : Player.Listener {
            override fun onRenderedFirstFrame() {
                localMediaViewState.isReady = true
                currentProgressiveVideo?.let {
                    Timber.tag("YiZheProgressiveVideo").i("first_frame received_cipher_bytes=%d total_cipher_bytes=%d",
                        it.receivedCiphertextBytes, it.totalCiphertextBytes)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                currentProgressiveVideo?.let {
                    val rootCause = generateSequence<Throwable>(error) { it.cause }.take(16).last()
                    Timber.tag("YiZheProgressiveVideo").w("player_error code=%d root_type=%s",
                        error.errorCode, rootCause.javaClass.simpleName)
                    it.close()
                    exoPlayer.pause()
                    exoPlayer.clearMediaItems()
                    segmentedPlaybackFailedClosed = true
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                currentProgressiveVideo?.let {
                    Timber.tag("YiZheProgressiveVideo").i("playing_state playing=%s position_ms=%d buffered_ms=%d play_when_ready=%s",
                        isPlaying, exoPlayer.currentPosition, (exoPlayer.bufferedPosition - exoPlayer.currentPosition).coerceAtLeast(0), exoPlayer.playWhenReady)
                }
                mediaPlayerControllerState = mediaPlayerControllerState.copy(
                    isPlaying = isPlaying,
                )
            }

            override fun onVolumeChanged(volume: Float) {
                mediaPlayerControllerState = mediaPlayerControllerState.copy(
                    isMuted = volume == 0f,
                )
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                if (reason == Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE) {
                    val durationMillis = currentSegmentedAttachment?.timelineSnapshot()?.assetDurationUs?.div(1_000L)
                        ?: exoPlayer.duration.takeIf { it >= 0 }
                    durationMillis?.let {
                            mediaPlayerControllerState = mediaPlayerControllerState.copy(
                                durationInMillis = it,
                            )
                    }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                mediaPlayerControllerState = mediaPlayerControllerState.copy(
                    isReady = playbackState == STATE_READY,
                )
                if (playbackState == STATE_READY) {
                    hasPreparedPlayableMedia = true
                }
                // ExoPlayer can report ENDED while it is still empty, before the downloaded
                // media source has been prepared. Treating that transient state as a real end
                // immediately closes the viewer (especially visible for one-time videos).
                if (shouldNotifyPlaybackEnded(playbackState, hasPreparedPlayableMedia)) {
                    val attachment = currentSegmentedAttachment
                    if (attachment == null) {
                        currentOnPlaybackEnded()
                    } else {
                        when (val advance = attachment.onFragmentEnded(exoPlayer.currentMediaItem?.mediaId)) {
                            is SegmentedVideoAdvanceStart.Accepted -> playbackScope.launch {
                                val receipt = advance.terminal.await()
                                if (!receipt.succeeded) {
                                    segmentedPlaybackFailedClosed = true
                                    Timber.w("Segmented fragment advance failed closed.")
                                }
                            }
                            SegmentedVideoAdvanceStart.FinalFragment -> currentOnPlaybackEnded()
                            SegmentedVideoAdvanceStart.IgnoredStaleOrDuplicate -> Unit
                            is SegmentedVideoAdvanceStart.Rejected -> if (advance.failedClosed) {
                                segmentedPlaybackFailedClosed = true
                                Timber.w(advance.failure, "Segmented fragment advance was rejected and failed closed.")
                            }
                        }
                    }
                }
            }
        }
    }

    val isTalkbackActive = isTalkbackActive()
    LaunchedEffect(autoHideController, isTalkbackActive) {
        if (isTalkbackActive) {
            // Ensure that the controller is always visible when talkback is active
            mediaPlayerControllerState = mediaPlayerControllerState.copy(
                isVisible = true,
            )
        } else {
            delay(5.seconds)
            if (exoPlayer.isPlaying) {
                mediaPlayerControllerState = mediaPlayerControllerState.copy(
                    isVisible = false,
                )
            }
        }
    }

    val segmentedOwner = localMedia?.uri?.let(SegmentedVideoPlaybackRegistry::resolve)
    LaunchedEffect(localMedia?.progressiveVideo) {
        val file = localMedia?.progressiveVideo ?: return@LaunchedEffect
        while (true) {
            delay(250)
            try { withContext(Dispatchers.IO) { file.requireAuthorized() } }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) {
                val denial = failure as? io.element.android.security.contentprotection.api.StageThreeGateDenied
                Timber.tag("YiZheProgressiveVideo").w("gate_failure type=%s code=%s",
                    failure.javaClass.simpleName, denial?.reason?.name ?: "LOCAL_READ_DENIED")
                exoPlayer.pause()
                exoPlayer.clearMediaItems()
                withContext(Dispatchers.IO) { file.close() }
                segmentedPlaybackFailedClosed = true
                break
            }
        }
    }
    LaunchedEffect(segmentedAttachment) {
        segmentedAttachment?.failureFlow()?.collect { failure ->
            if (failure != null) segmentedPlaybackFailedClosed = true
        }
    }
    if (localMedia?.uri != null) {
        LaunchedEffect(localMedia.uri, segmentedOwner) {
            if (segmentedOwner != null) {
                segmentedAttachment = segmentedOwner.attach(exoPlayer)
                mediaPlayerControllerState = mediaPlayerControllerState.copy(
                    durationInMillis = segmentedAttachment?.timelineSnapshot()?.assetDurationUs?.div(1_000L) ?: 0L,
                )
            } else if (localMedia.progressiveVideo != null) {
                val file = checkNotNull(localMedia.progressiveVideo)
                val source = ProgressiveMediaSource.Factory(DataSource.Factory { ProgressiveVideoDataSource(file) })
                    .createMediaSource(MediaItem.fromUri(localMedia.uri))
                exoPlayer.setMediaSource(source)
            } else {
                val mediaItem = MediaItem.fromUri(localMedia.uri)
                exoPlayer.setMediaItem(mediaItem)
            }
        }
    } else {
        exoPlayer.setMediaItems(emptyList())
    }
    KeepScreenOn(mediaPlayerControllerState.isPlaying)
    Box(
        modifier = modifier
            .background(ElementTheme.colors.bgSubtlePrimary),
    ) {
        val context = LocalContext.current
        if (segmentedPlaybackFailedClosed) {
            Text(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp),
                text = stringResource(
                    if (localMedia?.progressiveVideo != null) R.string.error_protected_media_read_unavailable
                    else R.string.screen_media_segmented_playback_failed_closed,
                ),
            )
        } else if (LocalInspectionMode.current) {
            Text(
                modifier = Modifier
                    .background(ElementTheme.colors.bgSubtlePrimary)
                    .align(Alignment.Center),
                text = "A Video Player will render here",
            )
        } else {
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .zoomable(
                        state = localMediaViewState.zoomableState,
                        onClick = {
                            autoHideController++
                            mediaPlayerControllerState = mediaPlayerControllerState.copy(
                                isVisible = !mediaPlayerControllerState.isVisible,
                            )
                        }
                    ),
                factory = {
                    PlayerView(context).apply {
                        player = exoPlayer
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                        useController = false
                    }
                },
                onRelease = { playerView ->
                    playerView.player = null
                },
            )
        }
        if (!segmentedPlaybackFailedClosed) MediaPlayerControllerView(
            state = mediaPlayerControllerState,
            onTogglePlay = {
                autoHideController++
                exoPlayer.togglePlay()
            },
            onSeekChange = {
                autoHideController++
                mediaPlayerControllerState = mediaPlayerControllerState.copy(
                    seekingToMillis = it.toLong(),
                )
                val targetMillis = it.toLong()
                val attachment = segmentedAttachment
                if (attachment == null) {
                    exoPlayer.seekToEnsurePlaying(targetMillis)
                } else {
                    when (val start = attachment.seek(Math.multiplyExact(targetMillis, 1_000L))) {
                        is SegmentedVideoSeekStart.Accepted -> playbackScope.launch {
                            val receipt = start.terminal.await()
                            if (!receipt.succeeded) {
                                segmentedPlaybackFailedClosed = true
                                Timber.w("Segmented seek failed closed.")
                            }
                        }
                        is SegmentedVideoSeekStart.Rejected -> Unit
                    }
                }
            },
            onToggleMute = {
                autoHideController++
                exoPlayer.volume = if (exoPlayer.volume == 1f) 0f else 1f
            },
            audioFocus = audioFocus,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(bottom = bottomPaddingInPixels.toDp()),
        )
    }

    LaunchedEffect(exoPlayer.isPlaying) {
        if (exoPlayer.isPlaying) {
            while (true) {
                val position = globalPlaybackPositionMillis(exoPlayer.currentPosition, currentSegmentedAttachment?.timelineSnapshot())
                val seekingTo = mediaPlayerControllerState.seekingToMillis
                mediaPlayerControllerState = mediaPlayerControllerState.copy(
                    progressInMillis = position,
                    seekingToMillis = if (seekingTo != null && position >= seekingTo) null else seekingTo,
                )
                delay(200)
            }
        } else {
            // Ensure we render the final state
            val position = globalPlaybackPositionMillis(exoPlayer.currentPosition, currentSegmentedAttachment?.timelineSnapshot())
            val seekingTo = mediaPlayerControllerState.seekingToMillis
            mediaPlayerControllerState = mediaPlayerControllerState.copy(
                progressInMillis = position,
                seekingToMillis = if (seekingTo != null && position >= seekingTo) null else seekingTo,
            )
        }
    }

    ExoPlayerLifecycleHelper(
        exoPlayer = exoPlayer,
        autoplay = autoplay,
        isDisplayed = isDisplayed,
        playerListener = playerListener,
        mediaPlayerControllerState = mediaPlayerControllerState,
        segmentedAttachment = segmentedAttachment,
    )
}

internal fun globalPlaybackPositionMillis(
    localPositionMillis: Long,
    segmentedTimeline: SegmentedVideoTimelineSnapshot?,
): Long = segmentedTimeline?.globalPositionUs(Math.multiplyExact(localPositionMillis, 1_000L))?.div(1_000L)
    ?: localPositionMillis

internal fun closeOwnerThenReleasePlayer(
    closeOwner: () -> Unit,
    releasePlayer: () -> Unit,
) {
    val cleanupFailure = runCatching(closeOwner).exceptionOrNull()
    val releaseFailure = runCatching(releasePlayer).exceptionOrNull()
    if (cleanupFailure != null) {
        releaseFailure?.let(cleanupFailure::addSuppressed)
        throw cleanupFailure
    }
    releaseFailure?.let { throw it }
}

internal fun removeListenerThenReleasePlayer(
    removeListener: () -> Unit,
    releasePlayer: () -> Unit,
) {
    val removalFailure = runCatching(removeListener).exceptionOrNull()
    val releaseFailure = runCatching(releasePlayer).exceptionOrNull()
    if (removalFailure != null) {
        releaseFailure?.let(removalFailure::addSuppressed)
        throw removalFailure
    }
    releaseFailure?.let { throw it }
}

internal fun shouldNotifyPlaybackEnded(playbackState: Int, hasPreparedPlayableMedia: Boolean): Boolean =
    playbackState == Player.STATE_ENDED && hasPreparedPlayableMedia

@OptIn(UnstableApi::class)
@Composable
private fun ExoPlayerLifecycleHelper(
    exoPlayer: ExoPlayer,
    autoplay: Boolean,
    isDisplayed: Boolean,
    playerListener: Player.Listener,
    mediaPlayerControllerState: MediaPlayerControllerState,
    segmentedAttachment: SegmentedVideoAttachment?,
) {
    // Prepare and release the exoPlayer with the composable lifecycle
    val currentSegmentedAttachment by rememberUpdatedState(segmentedAttachment)
    DisposableEffect(Unit) {
        Timber.d("ExoPlayerMediaVideoView DisposableEffect: initializing exoPlayer")
        exoPlayer.addListener(playerListener)
        exoPlayer.prepare()

        onDispose {
            Timber.d("Disposing exoplayer")
            if (!exoPlayer.isReleased) {
                closeOwnerThenReleasePlayer(
                    closeOwner = { runBlocking { currentSegmentedAttachment?.closeAndAwait() } },
                    releasePlayer = {
                        removeListenerThenReleasePlayer(
                            removeListener = { exoPlayer.removeListener(playerListener) },
                            releasePlayer = exoPlayer::release,
                        )
                    },
                )
            }
        }
    }

    var needsAutoPlay by remember { mutableStateOf(autoplay) }
    LaunchedEffect(needsAutoPlay, isDisplayed, mediaPlayerControllerState.isReady) {
        val isReadyAndNotPlaying = mediaPlayerControllerState.isReady && !mediaPlayerControllerState.isPlaying
        if (needsAutoPlay && isDisplayed && isReadyAndNotPlaying) {
            // When displayed, start autoplaying
            exoPlayer.play()
            needsAutoPlay = false
        } else if (!isDisplayed && mediaPlayerControllerState.isPlaying) {
            // If not displayed, make sure to pause the video
            exoPlayer.pause()
        }
    }

    // Pause playback when lifecycle is paused
    OnLifecycleEvent { _, event ->
        if (event == Lifecycle.Event.ON_PAUSE && exoPlayer.isPlaying) {
            exoPlayer.pause()
        }
    }
}

@PreviewsDayNight
@Composable
internal fun MediaVideoViewPreview() = ElementPreview {
    MediaVideoView(
        isDisplayed = true,
        modifier = Modifier.fillMaxSize(),
        bottomPaddingInPixels = 0,
        localMediaViewState = rememberLocalMediaViewState(),
        localMedia = null,
        audioFocus = null,
        autoplay = false,
    )
}
