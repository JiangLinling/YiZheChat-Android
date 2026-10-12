/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaupload.impl

import android.net.Uri
import dev.zacsweers.metro.ContributesBinding
import io.element.android.libraries.androidutils.hash.hash
import io.element.android.libraries.androidutils.file.safeDelete
import io.element.android.libraries.core.extensions.flatMap
import io.element.android.libraries.core.extensions.flatMapCatching
import io.element.android.libraries.di.RoomScope
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.featureflag.api.FeatureFlagService
import io.element.android.libraries.featureflag.api.FeatureFlags
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.media.MediaUploadHandler
import io.element.android.libraries.matrix.api.media.MediaSendCancellationContext
import io.element.android.libraries.matrix.api.room.CreateTimelineParams
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.matrix.api.timeline.item.event.ProtectedMediaAlbum
import io.element.android.libraries.mediaupload.api.MediaOptimizationConfig
import io.element.android.libraries.mediaupload.api.MediaOptimizationConfigProvider
import io.element.android.libraries.mediaupload.api.MediaPreProcessor
import io.element.android.libraries.mediaupload.api.MediaSender
import io.element.android.libraries.mediaupload.api.MediaSenderFactory
import io.element.android.libraries.mediaupload.api.MediaSenderRoomFactory
import io.element.android.libraries.mediaupload.api.MediaUploadInfo
import io.element.android.libraries.mediaupload.api.allFiles
import io.element.android.libraries.mediaupload.api.PreparedSegmentedMediaSend
import io.element.android.libraries.mediaupload.api.SegmentedMediaSendOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap

@ContributesBinding(RoomScope::class)
class DefaultMediaSenderFactory(
    private val preProcessor: MediaPreProcessor,
    private val room: JoinedRoom,
    private val mediaOptimizationConfigProvider: MediaOptimizationConfigProvider,
    private val nextSegmentedMediaSender: NextSegmentedMediaSender,
    private val featureFlagService: FeatureFlagService,
) : MediaSenderFactory {
    override fun create(
        timelineMode: Timeline.Mode,
    ): MediaSender {
        return DefaultMediaSender(
            preProcessor = preProcessor,
            room = room,
            timelineMode = timelineMode,
            mediaOptimizationConfigProvider = mediaOptimizationConfigProvider,
            nextSegmentedMediaSender = nextSegmentedMediaSender,
            featureFlagService = featureFlagService,
        )
    }
}

@ContributesBinding(SessionScope::class)
class DefaultMediaSenderRoomFactory(
    private val preProcessor: MediaPreProcessor,
    private val mediaOptimizationConfigProvider: MediaOptimizationConfigProvider,
    private val nextSegmentedMediaSender: NextSegmentedMediaSender,
    private val featureFlagService: FeatureFlagService,
) : MediaSenderRoomFactory {
    override fun create(
        room: JoinedRoom,
    ): MediaSender {
        return DefaultMediaSender(
            preProcessor = preProcessor,
            room = room,
            timelineMode = Timeline.Mode.Live,
            mediaOptimizationConfigProvider = mediaOptimizationConfigProvider,
            nextSegmentedMediaSender = nextSegmentedMediaSender,
            featureFlagService = featureFlagService,
        )
    }
}

class DefaultMediaSender(
    private val preProcessor: MediaPreProcessor,
    private val room: JoinedRoom,
    private val timelineMode: Timeline.Mode,
    private val mediaOptimizationConfigProvider: MediaOptimizationConfigProvider,
    private val nextSegmentedMediaSender: NextSegmentedMediaSender = UnavailableNextSegmentedMediaSender(),
    private val featureFlagService: FeatureFlagService? = null,
) : MediaSender {
    private val ongoingUploadJobs = ConcurrentHashMap<Job, MediaUploadHandler>()
    private val ownedMediaFiles = mutableSetOf<File>()
    private val activeMediaFiles = mutableMapOf<File, Int>()
    private val pendingCleanupFiles = mutableSetOf<File>()
    val hasOngoingMediaUploads get() = ongoingUploadJobs.isNotEmpty()

    override suspend fun preProcessMedia(
        uri: Uri,
        mimeType: String,
        mediaOptimizationConfig: MediaOptimizationConfig,
        onProgress: (Float) -> Unit,
    ): Result<MediaUploadInfo> {
        Timber.d("Pre-processing media | uri: ${mediaId(uri)} | mimeType: $mimeType")
        return preProcessor
            .process(
                uri = uri,
                mimeType = mimeType,
                deleteOriginal = false,
                mediaOptimizationConfig = mediaOptimizationConfig,
                onProgress = onProgress,
            )
            .onSuccess { info -> synchronized(ownedMediaFiles) { ownedMediaFiles.addAll(info.allFiles()) } }
    }

    override suspend fun mediaSubmissionFact(transportName: String) =
        getTimeline().getOrThrow().mediaSubmissionFact(transportName)

    override suspend fun sendPreProcessedMedia(
        mediaUploadInfo: MediaUploadInfo,
        caption: String?,
        formattedCaption: String?,
        inReplyToEventId: EventId?,
        burnAfterRead: Boolean,
        mediaAlbum: ProtectedMediaAlbum?,
        onProgress: (Float) -> Unit,
        onUploadStarted: () -> Unit,
        onUploadIdentity: (String) -> Unit,
    ): Result<Unit> {
        val mediaLogId = mediaId(mediaUploadInfo.file)
        return withActiveMediaFiles(mediaUploadInfo) { getTimeline().flatMap {
            Timber.d("Started sending media $mediaLogId using timeline: ${it.mode}")
            it.sendMedia(
                uploadInfo = mediaUploadInfo,
                caption = caption,
                formattedCaption = formattedCaption,
                inReplyToEventId = inReplyToEventId,
                burnAfterRead = burnAfterRead,
                mediaAlbum = mediaAlbum,
                onProgress = onProgress,
                onUploadStarted = onUploadStarted,
                onUploadIdentity = onUploadIdentity,
            )
        }
            .handleSendResult(mediaLogId) }
    }

    override suspend fun sendMedia(
        uri: Uri,
        mimeType: String,
        caption: String?,
        formattedCaption: String?,
        inReplyToEventId: EventId?,
        mediaOptimizationConfig: MediaOptimizationConfig,
        burnAfterRead: Boolean,
        onUploadStarted: () -> Unit,
    ): Result<Unit> {
        return preProcessMedia(
                uri = uri,
                mimeType = mimeType,
                mediaOptimizationConfig = mediaOptimizationConfig,
            )
            .flatMapCatching { info ->
                withActiveMediaFiles(info) { getTimeline().getOrThrow().sendMedia(
                    uploadInfo = info,
                    caption = caption,
                    formattedCaption = formattedCaption,
                    inReplyToEventId = inReplyToEventId,
                    burnAfterRead = burnAfterRead,
                    onUploadStarted = onUploadStarted,
                ) }
            }
            .handleSendResult(mediaId(uri))
    }

    override suspend fun sendVoiceMessage(
        uri: Uri,
        mimeType: String,
        waveForm: List<Float>,
        onUploadIdentity: (String) -> Unit,
        inReplyToEventId: EventId?,
        burnAfterRead: Boolean,
        onUploadStarted: () -> Unit,
    ): Result<Unit> {
        return preProcessor
            .processProtectedVoice(
                uri = uri,
                mimeType = mimeType,
            )
            .flatMapCatching { info ->
                getTimeline().getOrThrow().sendProtectedVoiceMessage(
                    source = info.source,
                    audioInfo = info.audioInfo,
                    waveform = waveForm,
                    inReplyToEventId = inReplyToEventId,
                    burnAfterRead = burnAfterRead,
                )
                    .awaitAcceptedUpload(onUploadStarted = onUploadStarted, onUploadIdentity = onUploadIdentity)
            }
            .handleSendResult(mediaId(uri))
    }

    override suspend fun sendPreparedSegmentedMedia(
        request: PreparedSegmentedMediaSend,
    ): Result<SegmentedMediaSendOutcome> {
        if (featureFlagService?.isFeatureEnabled(FeatureFlags.SegmentedProtectedVideoSending) != true) {
            return Result.failure(SecurityException("SEGMENTED_VIDEO_SENDING_NOT_ENABLED"))
        }
        // A rollout flag is not authority. The dedicated sender still requires its PREPARED journal,
        // current room/device facts and signed ALLOW/SEND_BOUND proofs before any Matrix side effect.
        return getTimeline().flatMap { timeline ->
            nextSegmentedMediaSender.send(timeline = timeline, roomId = room.roomId.value, request = request)
        }
    }

    override suspend fun sendSticker(
        uri: Uri,
        mimeType: String,
        mediaOptimizationConfig: MediaOptimizationConfig,
    ): Result<Unit> = preProcessMedia(
        uri = uri,
        mimeType = mimeType,
        mediaOptimizationConfig = mediaOptimizationConfig,
    ).flatMapCatching { info ->
        withActiveMediaFiles(info) {
            val image = info as? MediaUploadInfo.Image ?: error("Only images can be sent as stickers.")
            val handler = getTimeline().getOrThrow().sendSticker(image.file, image.thumbnailFile, image.imageInfo).getOrThrow()
            transferMediaFilesToUploadHandler(info)
            val ownerJob = currentCoroutineContext()[Job]
            if (ownerJob != null) ongoingUploadJobs[ownerJob] = handler
            try { handler.await() } finally { if (ownerJob != null) ongoingUploadJobs.remove(ownerJob, handler) }
        }
    }.handleSendResult(mediaId(uri))

    private fun Result<Unit>.handleSendResult(mediaId: String) = this
        .onFailure { error -> Timber.e(error, "Sending media %s failed", mediaId) }
        .onSuccess { Timber.d("Sent media %s successfully", mediaId) }

    private suspend fun Timeline.sendMedia(
        uploadInfo: MediaUploadInfo,
        caption: String?,
        formattedCaption: String?,
        inReplyToEventId: EventId?,
        burnAfterRead: Boolean,
        mediaAlbum: ProtectedMediaAlbum? = null,
        onProgress: (Float) -> Unit = {},
        onUploadStarted: () -> Unit = {},
        onUploadIdentity: (String) -> Unit = {},
    ): Result<Unit> {
        val handler = when (uploadInfo) {
            is MediaUploadInfo.Image -> {
                sendImage(
                    file = uploadInfo.file,
                    thumbnailFile = uploadInfo.thumbnailFile,
                    imageInfo = uploadInfo.imageInfo,
                    caption = caption,
                    formattedCaption = formattedCaption,
                    inReplyToEventId = inReplyToEventId,
                    burnAfterRead = burnAfterRead,
                    mediaAlbum = mediaAlbum,
                )
            }
            is MediaUploadInfo.Video -> {
                sendVideo(
                    file = uploadInfo.file,
                    thumbnailFile = uploadInfo.thumbnailFile,
                    videoInfo = uploadInfo.videoInfo,
                    caption = caption,
                    formattedCaption = formattedCaption,
                    inReplyToEventId = inReplyToEventId,
                    burnAfterRead = burnAfterRead,
                    mediaAlbum = mediaAlbum,
                )
            }
            is MediaUploadInfo.Audio -> {
                require(mediaAlbum == null) { "Media albums currently support images only." }
                sendAudio(
                    file = uploadInfo.file,
                    audioInfo = uploadInfo.audioInfo,
                    caption = caption,
                    formattedCaption = formattedCaption,
                    inReplyToEventId = inReplyToEventId,
                    burnAfterRead = burnAfterRead,
                )
            }
            is MediaUploadInfo.VoiceMessage -> {
                require(mediaAlbum == null) { "Media albums currently support images only." }
                sendVoiceMessage(
                    file = uploadInfo.file,
                    audioInfo = uploadInfo.audioInfo,
                    waveform = uploadInfo.waveform,
                    inReplyToEventId = inReplyToEventId,
                    burnAfterRead = burnAfterRead,
                )
            }
            is MediaUploadInfo.AnyFile -> {
                require(mediaAlbum == null) { "Media albums currently support images only." }
                sendFile(
                    file = uploadInfo.file,
                    fileInfo = uploadInfo.fileInfo,
                    caption = caption,
                    formattedCaption = formattedCaption,
                    inReplyToEventId = inReplyToEventId,
                    burnAfterRead = burnAfterRead,
                )
            }
        }

        // The accepted handler owns these files through queue completion/cancellation, even if
        // the caller stops awaiting it. Its existing filesToUpload cleanup is authoritative.
        handler.onSuccess { transferMediaFilesToUploadHandler(uploadInfo) }
        // We handle the cancellations here manually, so we suppress the warning
        @Suppress("RunCatchingNotAllowed")
        return handler.awaitAcceptedUpload(onProgress, onUploadStarted, onUploadIdentity)
    }

    @Suppress("RunCatchingNotAllowed")
    private suspend fun Result<MediaUploadHandler>.awaitAcceptedUpload(
        onProgress: (Float) -> Unit = {},
        onUploadStarted: () -> Unit = {},
        onUploadIdentity: (String) -> Unit = {},
    ): Result<Unit> = mapCatching { uploadHandler ->
        Timber.d("Added ongoing upload job, total: ${ongoingUploadJobs.size + 1}")
        val job = currentCoroutineContext()[Job]
        currentCoroutineContext()[MediaSendCancellationContext]?.attach(uploadHandler)
        if (job != null) ongoingUploadJobs[job] = uploadHandler
        uploadHandler.protectedMediaTransportName?.let { identity ->
            // Presentation bookkeeping must never turn an accepted upload into a failed send.
            runCatching { onUploadIdentity(identity) }
                .onFailure { Timber.w("Unable to bind local media presentation identity") }
        }
        // The SDK has created its optimistic local event before returning the handler.
        // Callers may now release composer-only source ownership while the send queue owns retry.
        onUploadStarted()
        try {
            uploadHandler.await().also { result ->
                if (result.isSuccess) onProgress(1f)
            }.getOrThrow()
        } catch (failure: Exception) {
            if (failure !is CancellationException) uploadHandler.cancel()
            throw failure
        } finally {
            if (job != null) ongoingUploadJobs.remove(job, uploadHandler)
        }
    }

    private suspend fun getTimeline(): Result<Timeline> {
        return when (timelineMode) {
            is Timeline.Mode.Thread -> {
                room.createTimeline(CreateTimelineParams.Threaded(threadRootEventId = timelineMode.threadRootId))
            }
            else -> Result.success(room.liveTimeline)
        }
    }

    /**
     * Clean up any temporary files or resources used during the media processing.
     */
    override fun cleanUp() = synchronized(ownedMediaFiles) {
        // Other previews and background sends use the same application cache. A sender can
        // retire only its own outputs, never sweep another transfer's in-progress files.
        pendingCleanupFiles.addAll(ownedMediaFiles)
        cleanUpInactiveFiles()
    }

    private fun cleanUpInactiveFiles() {
        val retired = pendingCleanupFiles.filter { it !in activeMediaFiles }
        retired.forEach { it.safeDelete() }
        ownedMediaFiles.removeAll(retired.toSet())
        pendingCleanupFiles.removeAll(retired.toSet())
    }

    private fun transferMediaFilesToUploadHandler(info: MediaUploadInfo) = synchronized(ownedMediaFiles) {
        val files = info.allFiles().toSet()
        ownedMediaFiles.removeAll(files)
        pendingCleanupFiles.removeAll(files)
    }

    private suspend fun <T> withActiveMediaFiles(info: MediaUploadInfo, block: suspend () -> T): T {
        val files = info.allFiles().toSet()
        synchronized(ownedMediaFiles) {
            ownedMediaFiles.addAll(files)
            files.forEach { activeMediaFiles[it] = (activeMediaFiles[it] ?: 0) + 1 }
        }
        return try {
            room.withSendLifetime(block)
        } finally {
            synchronized(ownedMediaFiles) {
                files.forEach { file ->
                    val remaining = requireNotNull(activeMediaFiles[file]) - 1
                    if (remaining == 0) activeMediaFiles.remove(file) else activeMediaFiles[file] = remaining
                }
                cleanUpInactiveFiles()
            }
        }
    }
}

private fun mediaId(uri: Uri?): String = uri?.path.orEmpty().hash()
private fun mediaId(file: File): String = file.path.orEmpty().hash()
