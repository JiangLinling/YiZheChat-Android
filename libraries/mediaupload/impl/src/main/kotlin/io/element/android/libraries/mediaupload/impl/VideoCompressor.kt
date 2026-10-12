/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaupload.impl

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Size
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppMp4Muxer
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.TransformationRequest
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import dev.zacsweers.metro.Inject
import io.element.android.libraries.androidutils.file.createTmpFile
import io.element.android.libraries.androidutils.file.safeDelete
import io.element.android.libraries.androidutils.media.runAndRelease
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.mediaupload.api.MediaOptimizationConfig
import io.element.android.libraries.preferences.api.store.VideoCompressionPreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@Inject
class VideoCompressor(
    @ApplicationContext private val context: Context,
) {
    @OptIn(UnstableApi::class)
    fun compress(uri: Uri, videoCompressionPreset: VideoCompressionPreset, edits: MediaOptimizationConfig? = null): Flow<VideoTranscodingEvent> = callbackFlow {
        transcodePermit.acquire()
        var permitOwnedByFlow = true
        var tmpFile: File? = null
        var cleanup: VideoTranscodeCleanup? = null
        var progressJob: kotlinx.coroutines.Job? = null
        try {
            val metadata = getVideoMetadata(uri)

            val videoCompressorConfig = VideoCompressorConfigFactory.create(
                metadata = metadata,
                preset = videoCompressionPreset,
            )

            val outputFile = context.createTmpFile(extension = "mp4")
            tmpFile = outputFile
            val output = VideoTranscodeOutput(outputFile)

            val width = metadata?.width ?: Int.MAX_VALUE
            val height = metadata?.height ?: Int.MAX_VALUE

            val videoResizeEffect = run {
                val outputSize = videoCompressorConfig.videoCompressorHelper.getOutputSize(Size(width, height))
                if (metadata?.rotation == 90 || metadata?.rotation == 270) {
                    // If the video is rotated, we need to swap width and height
                    Presentation.createForWidthAndHeight(
                        outputSize.height,
                        outputSize.width,
                        Presentation.LAYOUT_SCALE_TO_FIT,
                    )
                } else {
                    // Otherwise, we can use the original width and height
                    Presentation.createForWidthAndHeight(
                        outputSize.width,
                        outputSize.height,
                        Presentation.LAYOUT_SCALE_TO_FIT,
                    )
                }
            }

            // If we are resizing, we also want to reduce set frame rate to the default value (30fps)
            val newFrameRate = videoCompressorConfig.newFrameRate

            // If we need to resize the video, we also want to recalculate the bitrate
            val newBitrate = videoCompressorConfig.newBitRate

            // Remove all video metadata
            val removeMetadataMuxer = InAppMp4Muxer.Factory { metadataEntries ->
                metadataEntries.removeAll { true }
            }
            val outputMediaItem = buildEditedVideoInput(uri, edits, newFrameRate, Effects(emptyList(), listOf(videoResizeEffect)))

            val encoderFactory = DefaultEncoderFactory.Builder(context)
                .setRequestedVideoEncoderSettings(
                    VideoEncoderSettings.Builder()
                        // Use VBR which is generally better for quality and compatibility, although slightly worse for file size
                        .setBitrateMode(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                        .setBitrate(newBitrate)
                        .build()
                )
                .build()

            val videoTransformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setPortraitEncodingEnabled(false)
                .setEncoderFactory(encoderFactory)
                .setMuxerFactory(removeMetadataMuxer)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        val completion = VideoTranscodingEvent.Completed(output, onClaim = { close() })
                        if (trySend(completion).isFailure) {
                            output.cleanUp()
                            close()
                        }
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        Timber.e(exportException, "Video transcoding failed")
                        output.cleanUp()
                        close(exportException)
                    }

                    override fun onFallbackApplied(
                        composition: Composition,
                        originalTransformationRequest: TransformationRequest,
                        fallbackTransformationRequest: TransformationRequest
                    ) = Unit
                })
                .build()

            cleanup = VideoTranscodeCleanup(
                mainExecutor = ContextCompat.getMainExecutor(context),
                cancelTransformer = videoTransformer::cancel,
                output = output,
                releasePermit = transcodePermit::release,
            )
            permitOwnedByFlow = false

            progressJob = launch(Dispatchers.Main) {
                val progressHolder = ProgressHolder()
                while (isActive) {
                    val state = videoTransformer.getProgress(progressHolder)
                    if (state != Transformer.PROGRESS_STATE_NOT_STARTED) {
                        channel.send(VideoTranscodingEvent.Progress(progressHolder.progress.toFloat()))
                    }
                    delay(500)
                }
            }

            withContext(Dispatchers.Main) {
                videoTransformer.start(outputMediaItem, outputFile.path)
            }

            awaitClose {
                progressJob?.cancel()
                cleanup?.close()
            }
        } catch (failure: Throwable) {
            progressJob?.cancel()
            cleanup?.close() ?: run {
                tmpFile?.safeDelete()
                if (permitOwnedByFlow) transcodePermit.release()
            }
            throw failure
        }
    }

    private fun getVideoMetadata(uri: Uri): VideoFileMetadata? {
        return runCatchingExceptions {
            MediaMetadataRetriever().runAndRelease {
                setDataSource(context, uri)

                val width = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: -1
                val height = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: -1
                val bitrate = extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: -1
                val frameRate = extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toIntOrNull() ?: -1
                val rotation = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0

                val (actualWidth, actualHeight) = if (width == -1 || height == -1) {
                    // Try getting the first frame instead
                    val bitmap = getFrameAtTime(0) ?: return null
                    bitmap.width to bitmap.height
                } else {
                    width to height
                }

                VideoFileMetadata(
                    width = actualWidth,
                    height = actualHeight,
                    bitrate = bitrate,
                    frameRate = frameRate,
                    rotation = rotation,
                )
            }
        }.onFailure {
            Timber.e(it, "Failed to get video dimensions")
        }.getOrNull()
    }

    private companion object {
        val transcodePermit = VideoTranscodePermit()
    }
}

internal class VideoTranscodePermit(
    private val semaphore: Semaphore = Semaphore(1),
) {
    suspend fun acquire() = semaphore.acquire()

    fun release() = semaphore.release()
}

internal class VideoTranscodeOutput(
    private val file: File,
) {
    private val state = AtomicReference(State.AVAILABLE)

    fun claim(): File {
        check(state.compareAndSet(State.AVAILABLE, State.CLAIMED)) {
            "Video transcode output is no longer available."
        }
        return file
    }

    fun cleanUp() {
        if (state.compareAndSet(State.AVAILABLE, State.CLEANED)) {
            file.safeDelete()
        }
    }

    private enum class State {
        AVAILABLE,
        CLAIMED,
        CLEANED,
    }
}

internal class VideoTranscodeCleanup(
    private val mainExecutor: Executor,
    private val cancelTransformer: () -> Unit,
    private val output: VideoTranscodeOutput,
    private val releasePermit: () -> Unit,
) {
    private val closed = AtomicBoolean(false)

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        mainExecutor.execute {
            try {
                cancelTransformer()
            } finally {
                output.cleanUp()
                releasePermit()
            }
        }
    }
}

internal data class VideoFileMetadata(
    val width: Int,
    val height: Int,
    val bitrate: Long,
    val frameRate: Int,
    val rotation: Int,
)

sealed interface VideoTranscodingEvent {
    data class Progress(val value: Float) : VideoTranscodingEvent

    class Completed internal constructor(
        private val output: VideoTranscodeOutput,
        private val onClaim: () -> Unit = {},
    ) : VideoTranscodingEvent {
        fun claimFile(): File = output.claim().also { onClaim() }
    }
}

@OptIn(UnstableApi::class)
internal fun buildEditedVideoInput(uri: Uri, edits: MediaOptimizationConfig?, frameRate: Int, effects: Effects): EditedMediaItem {
    val clipping = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(edits?.videoStartMs ?: 0)
    edits?.videoEndMs?.let { clipping.setEndPositionMs(it) }
    return EditedMediaItem.Builder(MediaItem.Builder().setUri(uri).setClippingConfiguration(clipping.build()).build())
        .setRemoveAudio(edits?.muteAudio == true)
        .setFrameRate(frameRate)
        .setEffects(effects)
        .build()
}

internal fun requireUneditedVideoFallback(edits: MediaOptimizationConfig) {
    check(edits.videoStartMs == 0L && edits.videoEndMs == null && !edits.muteAudio) {
        "Video edits could not be exported. The unedited source must not be sent."
    }
}
