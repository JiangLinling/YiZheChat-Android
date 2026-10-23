/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaviewer.impl.viewer

import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.ProduceStateScope
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.core.coroutines.SessionOwnedResourceWork
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.localofficeviewer.api.FilePreviewLeaseReleaseReason
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.media.MatrixMediaLoader
import io.element.android.security.contentprotection.api.KmsTransportPriority
import io.element.android.security.contentprotection.api.KmsTransportSchedulingContext
import io.element.android.libraries.matrix.api.media.MediaFile
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.mediaviewer.api.MediaViewerEntryPoint.MediaViewerMode
import io.element.android.libraries.mediaviewer.api.local.LocalMedia
import io.element.android.libraries.mediaviewer.api.local.LocalMediaFactory
import io.element.android.libraries.mediaviewer.impl.datasource.MediaGalleryDataSource
import io.element.android.libraries.mediaviewer.impl.gallery.MediaGalleryMode
import io.element.android.libraries.mediaviewer.impl.local.office.FilePreviewLeaseRegistry
import io.element.android.libraries.mediaviewer.impl.local.office.filePreviewLeaseBinding
import io.element.android.libraries.mediaviewer.impl.local.requiresFilePreviewLease
import io.element.android.libraries.mediaviewer.impl.model.MediaItem
import io.element.android.libraries.mediaviewer.impl.model.eventId
import io.element.android.libraries.mediaviewer.impl.model.id
import io.element.android.libraries.mediaviewer.impl.model.mediaInfo
import io.element.android.libraries.mediaviewer.impl.model.mediaSource
import io.element.android.libraries.mediaviewer.impl.model.thumbnailSource
import io.element.android.libraries.mediaviewer.impl.segmented.product.SegmentedVideoPlaybackOwnerFactory
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.currentLifecycleOperationContext
import io.element.android.security.dataprotection.api.lifecycleOperationRequest
import io.element.android.security.dataprotection.api.perform
import io.element.android.services.toolbox.api.systemclock.SystemClock
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.EmptyCoroutineContext

private fun String.mediaScopeHash(): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(encodeToByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }

class MediaViewerDataSource(
    mode: MediaViewerMode,
    coroutineScope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val galleryDataSource: MediaGalleryDataSource,
    private val mediaLoader: MatrixMediaLoader,
    private val localMediaFactory: LocalMediaFactory,
    private val systemClock: SystemClock,
    private val pagerKeysHandler: PagerKeysHandler,
    private val appOperationalStateCoordinator: AppOperationalStateCoordinator,
    private val accountIdHash: String,
    private val matrixDeviceIdHash: String,
    private val allowReusableOriginalCache: Boolean = true,
    private val segmentedVideoPlaybackOwnerFactory: SegmentedVideoPlaybackOwnerFactory? = null,
    downloadScope: CoroutineScope = coroutineScope,
    private val downloadDispatcher: CoroutineDispatcher = dispatcher,
    private val roomId: String? = null,
) {
    private val downloadOwners = SessionOwnedResourceWork(downloadScope)
    private val mediaLock = Any()
    private val mediaFiles: ConcurrentHashMap<MediaLoadIdentity, MediaFile> = ConcurrentHashMap()
    private val filePreviewLeaseTokens: ConcurrentHashMap<MediaLoadIdentity, String> = ConcurrentHashMap()
    private val mediaGenerations: ConcurrentHashMap<MediaLoadIdentity, Long> = ConcurrentHashMap()
    @Volatile private var disposed = false

    private val galleryMode = when (mode) {
        MediaViewerMode.SingleMedia,
        is MediaViewerMode.TimelineImagesAndVideos -> MediaGalleryMode.Images
        is MediaViewerMode.TimelineFilesAndAudios -> MediaGalleryMode.Files
    }

    // Each event owns its media even if another event reuses an MXC. Segmented media additionally bind
    // the authenticated candidate so a different version cannot reuse its owner.
    private val localMediaStates: ConcurrentHashMap<MediaLoadIdentity, MutableState<AsyncData<LocalMedia>>> =
        ConcurrentHashMap()

    fun setup(coroutineScope: CoroutineScope) {
        galleryDataSource.start(coroutineScope)
    }

    fun dispose() {
        val handlesToClose = synchronized(mediaLock) {
            disposed = true
            mediaGenerations.keys.forEach(::invalidate)
            localMediaStates.values.forEach { it.value = AsyncData.Uninitialized }
            OwnedHandles(
                mediaFiles = mediaFiles.values.toList(),
                filePreviewLeaseTokens = filePreviewLeaseTokens.values.toList(),
            ).also {
                mediaFiles.clear()
                filePreviewLeaseTokens.clear()
                localMediaStates.clear()
            }
        }
        Timber.d(
            "Disposing MediaViewerDataSource, closing files=%s filePreviewLeases=%s",
            handlesToClose.mediaFiles.size,
            handlesToClose.filePreviewLeaseTokens.size,
        )
        handlesToClose.close(FilePreviewLeaseReleaseReason.HOST_DISPOSED)
    }

    /**
     * Helper function to translate the [dataFlow] result to a Compose [State] that can be observed in the UI.
     */
    @Composable
    fun produceState(
        producer: suspend ProduceStateScope<ImmutableList<MediaViewerPageData>>.(StateFlow<ImmutableList<MediaViewerPageData>>) -> Unit
    ): State<ImmutableList<MediaViewerPageData>> {
        val latestProducer by rememberUpdatedState(producer)
        return produceState(initialValue = initialData()) {
            latestProducer(dataFlow)
        }
    }

    /**
     * Find the index of the page corresponding to the given eventId, or null if not found.
     */
    fun findEventIndex(eventId: EventId?): Int? {
        if (eventId == null) return null
        return dataFlow.value.indexOfFirst { (it as? MediaViewerPageData.MediaViewerData)?.eventId == eventId }.takeIf { it >= 0 }
    }

    @VisibleForTesting
    internal val dataFlow: StateFlow<ImmutableList<MediaViewerPageData>> = galleryDataSource.groupedMediaItemsFlow()
        .map { groupedItems ->
            when (groupedItems) {
                AsyncData.Uninitialized,
                is AsyncData.Loading -> {
                    persistentListOf(
                        MediaViewerPageData.Loading(
                            direction = Timeline.PaginationDirection.BACKWARDS,
                            timestamp = systemClock.epochMillis(),
                            pagerKey = Long.MIN_VALUE,
                        )
                    )
                }
                is AsyncData.Failure -> {
                    persistentListOf(
                        MediaViewerPageData.Failure(groupedItems.error),
                    )
                }
                is AsyncData.Success -> {
                    withContext(dispatcher) {
                        val mediaItems = groupedItems.data.getItems(galleryMode)
                        buildMediaViewerPageList(mediaItems)
                    }
                }
            }
        }
        .stateIn(
            scope = CoroutineScope(coroutineScope.coroutineContext + dispatcher),
            started = SharingStarted.Lazily,
            initialValue = initialData(),
        )

    private fun initialData(): ImmutableList<MediaViewerPageData> {
        val initialMediaItems =
            galleryDataSource.getLastData().dataOrNull()?.getItems(galleryMode).orEmpty()
        return buildMediaViewerPageList(initialMediaItems)
    }

    /**
     * Build a list of [MediaViewerPageData] from a list of [MediaItem].
     * In particular, create a mutable state of AsyncData<LocalMedia> for each media item, which
     * will be used to render the downloaded media (see [loadMedia] which will update this value).
     */
    private fun buildMediaViewerPageList(groupedItems: List<MediaItem>) = buildList {
        // Filter out DateSeparator items, we do not need them for the media viewer
        val groupedItemsNoDateSeparator = groupedItems.filterNot { it is MediaItem.DateSeparator }
        pagerKeysHandler.accept(groupedItemsNoDateSeparator)
        groupedItemsNoDateSeparator.forEach { mediaItem ->
            when (mediaItem) {
                is MediaItem.DateSeparator -> Unit
                is MediaItem.Event -> {
                    val mediaSource = mediaItem.mediaSource()
                    val eventId = mediaItem.eventId()
                    val mediaItemId = mediaItem.id()
                    val segmentedVideo = (mediaItem as? MediaItem.Video)?.segmentedVideo
                    val mediaLoadIdentity = mediaLoadIdentity(
                        mediaSource = mediaSource,
                        eventId = eventId,
                        mediaItemId = mediaItemId,
                        segmentedVideo = segmentedVideo,
                    )
                    val localMedia = localMediaStates.getOrPut(mediaLoadIdentity) {
                        mutableStateOf(AsyncData.Uninitialized)
                    }
                    add(
                        MediaViewerPageData.MediaViewerData(
                            eventId = eventId,
                            mediaInfo = mediaItem.mediaInfo(),
                            mediaSource = mediaSource,
                            thumbnailSource = mediaItem.thumbnailSource(),
                            downloadedMedia = localMedia,
                            pagerKey = pagerKeysHandler.getKey(mediaItem),
                            segmentedVideo = segmentedVideo,
                            mediaItemId = mediaItemId,
                        )
                    )
                }
                is MediaItem.LoadingIndicator -> add(
                    MediaViewerPageData.Loading(
                        direction = mediaItem.direction,
                        timestamp = mediaItem.timestamp,
                        pagerKey = pagerKeysHandler.getKey(mediaItem),
                    )
                )
            }
        }
    }.toImmutableList()

    fun clearLoadingError(data: MediaViewerPageData.MediaViewerData) {
        localMediaStates[data.mediaLoadIdentity]?.value = AsyncData.Uninitialized
    }

    suspend fun loadMore(direction: Timeline.PaginationDirection) {
        if (galleryDataSource.isReady) {
            galleryDataSource.loadMore(direction)
        }
    }

    suspend fun loadMedia(data: MediaViewerPageData.MediaViewerData) {
        withContext(downloadDispatcher) { FilePreviewLeaseRegistry.retryPendingReleases() }
        val localMediaState: MutableState<AsyncData<LocalMedia>>
        val generation: Long
        val mediaLoadIdentity = data.mediaLoadIdentity
        synchronized(mediaLock) {
            if (disposed) return
            localMediaState = localMediaStates.getOrPut(mediaLoadIdentity) {
                mutableStateOf(AsyncData.Uninitialized)
            }
            if (localMediaState.value.isLoading() || localMediaState.value.isSuccess()) return
            localMediaState.value = AsyncData.Loading()
            generation = mediaGenerations.getOrPut(mediaLoadIdentity) { 0L }
        }

        Timber.d("loadMedia for ${data.eventId}")
        val origin = roomId?.let { io.element.android.libraries.matrix.api.media.MediaEventOrigin(it, data.eventId?.value) }
            ?: data.mediaSource.eventOrigin
        val request = lifecycleOperationRequest(
            lifecycleId = FullAppLifecycleId.LC_16,
            trigger = "download",
            accountIdHash = accountIdHash,
            matrixDeviceIdHash = matrixDeviceIdHash,
            roomIdHash = origin?.roomId?.mediaScopeHash(),
            eventIdHash = origin?.eventId?.mediaScopeHash(),
        )
        val parentOperation = currentLifecycleOperationContext()
        runCatchingExceptions {
            downloadOwners.execute<ViewerLoadResult>(
                userInitiated = true,
                context = (parentOperation ?: EmptyCoroutineContext) + downloadDispatcher + KmsTransportSchedulingContext(KmsTransportPriority.USER_MEDIA),
                discard = { it.close(FilePreviewLeaseReleaseReason.CANCELLED) },
                onCleanupFailure = { Timber.w("Viewer media cleanup remains pending for operationId=%s", request.operationId) },
            ) { retainUntilReceipt ->
                val result = ViewerLoadResult()
                retainUntilReceipt(result)
                appOperationalStateCoordinator.perform(request) { operationId ->
                    val mediaFile = data.segmentedVideo?.let { candidate ->
                        requireNotNull(segmentedVideoPlaybackOwnerFactory) {
                            "Segmented video owner factory is unavailable."
                        }.create(candidate, data.mediaInfo.filename)
                    } ?: withContext(KmsTransportSchedulingContext(KmsTransportPriority.USER_MEDIA)) {
                        val scopedSource = data.mediaSource.copy(eventOrigin = origin)
                        val progressive = if (data.mediaInfo.mimeType.startsWith("video/")) {
                            mediaLoader.openProgressiveVideo(scopedSource, data.mediaInfo.fileSize).getOrThrow()
                        } else null
                        progressive ?: mediaLoader.downloadMediaFile(
                            source = scopedSource,
                            mimeType = data.mediaInfo.mimeType,
                            filename = data.mediaInfo.filename,
                            useCache = allowReusableOriginalCache,
                        ).getOrThrow()
                    }
                    result.mediaFile = mediaFile
                    if (!isRequestActive(mediaLoadIdentity, generation, localMediaState)) {
                        result.close(FilePreviewLeaseReleaseReason.CANCELLED)
                        return@perform result
                    }
                    val localMedia = localMediaFactory.createFromMediaFile(
                        mediaFile = mediaFile,
                        mediaInfo = data.mediaInfo,
                    ).copy(lifecycleOperationId = operationId)
                    val sourceUri = mediaFile.uriString()
                    val protectedSource = sourceUri.startsWith("content://") && sourceUri.substringAfter("content://").substringBefore('/').endsWith(".securemedia")
                    if (data.mediaInfo.requiresFilePreviewLease() || protectedSource) {
                        val filePreviewLeaseToken = FilePreviewLeaseRegistry.register(
                            localMedia.filePreviewLeaseBinding(),
                            mediaFile,
                        )
                        result.filePreviewLeaseToken = filePreviewLeaseToken
                        result.mediaFile = null
                        result.loaded = LoadedMedia(
                            mediaFile = null,
                            localMedia = localMedia.copy(filePreviewLeaseToken = filePreviewLeaseToken),
                            filePreviewLeaseToken = filePreviewLeaseToken,
                        )
                    } else {
                        result.loaded = LoadedMedia(mediaFile, localMedia, filePreviewLeaseToken = null)
                    }
                    result
                }
            }
        }.onSuccess { result ->
                val loaded = result.loaded ?: return@onSuccess
                val previousHandles = synchronized(mediaLock) {
                    if (!isRequestActiveLocked(mediaLoadIdentity, generation, localMediaState)) {
                        loaded.close(FilePreviewLeaseReleaseReason.CANCELLED)
                        return@synchronized null
                    }
                    localMediaState.value = AsyncData.Success(loaded.localMedia)
                    if (loaded.filePreviewLeaseToken == null) {
                        OwnedHandles(
                            mediaFiles = listOfNotNull(mediaFiles.put(mediaLoadIdentity, checkNotNull(loaded.mediaFile))),
                            filePreviewLeaseTokens = listOfNotNull(filePreviewLeaseTokens.remove(mediaLoadIdentity)),
                        )
                    } else {
                        OwnedHandles(
                            mediaFiles = listOfNotNull(mediaFiles.remove(mediaLoadIdentity)),
                            filePreviewLeaseTokens = listOfNotNull(
                                filePreviewLeaseTokens.put(mediaLoadIdentity, loaded.filePreviewLeaseToken)
                            ),
                        )
                    }
                }
                previousHandles?.close(FilePreviewLeaseReleaseReason.ATTACHMENT_SWITCHED)
            }
            .onFailure { failure ->
                synchronized(mediaLock) {
                    if (isRequestActiveLocked(mediaLoadIdentity, generation, localMediaState)) {
                        localMediaState.value = AsyncData.Failure(failure)
                    }
                }
            }
    }

    fun cancelLoadingMedia(data: MediaViewerPageData.MediaViewerData) {
        releaseMedia(data)
    }

    fun releaseMedia(data: MediaViewerPageData.MediaViewerData) {
        val mediaLoadIdentity = data.mediaLoadIdentity
        val handlesToClose = synchronized(mediaLock) {
            Timber.d("releaseMedia for ${data.eventId}")
            invalidate(mediaLoadIdentity)
            localMediaStates[mediaLoadIdentity]?.value = AsyncData.Uninitialized
            OwnedHandles(
                mediaFiles = listOfNotNull(mediaFiles.remove(mediaLoadIdentity)),
                filePreviewLeaseTokens = listOfNotNull(filePreviewLeaseTokens.remove(mediaLoadIdentity)),
            )
        }
        handlesToClose.close(FilePreviewLeaseReleaseReason.CANCELLED)
    }

    private fun invalidate(identity: MediaLoadIdentity) {
        mediaGenerations[identity] = (mediaGenerations[identity] ?: 0L) + 1L
    }

    private fun isRequestActive(
        identity: MediaLoadIdentity,
        generation: Long,
        state: MutableState<AsyncData<LocalMedia>>,
    ): Boolean = synchronized(mediaLock) {
        isRequestActiveLocked(identity, generation, state)
    }

    private fun isRequestActiveLocked(
        identity: MediaLoadIdentity,
        generation: Long,
        state: MutableState<AsyncData<LocalMedia>>,
    ): Boolean = !disposed &&
        mediaGenerations[identity] == generation &&
        state.value.isLoading()

    private data class LoadedMedia(
        val mediaFile: MediaFile?,
        val localMedia: LocalMedia,
        val filePreviewLeaseToken: String?,
    ) {
        fun close(reason: FilePreviewLeaseReleaseReason) {
            closeEveryHandle(listOfNotNull(mediaFile), listOfNotNull(filePreviewLeaseToken), reason)
        }
    }

    /** Retains the acquired handle across factory/registry/receipt failures until its actual release. */
    private class ViewerLoadResult {
        var mediaFile: MediaFile? = null
        var filePreviewLeaseToken: String? = null
        var loaded: LoadedMedia? = null
        private var mediaClosed = false
        private var previewReleased = false

        fun close(reason: FilePreviewLeaseReleaseReason) {
            var firstFailure: Throwable? = null
            if (!mediaClosed) try { mediaFile?.close(); mediaClosed = true } catch (failure: Throwable) { firstFailure = failure }
            if (!previewReleased) try {
                filePreviewLeaseToken?.let { FilePreviewLeaseRegistry.release(it, reason) }
                previewReleased = true
            } catch (failure: Throwable) {
                firstFailure?.let { if (it !== failure) it.addSuppressed(failure) } ?: run { firstFailure = failure }
            }
            firstFailure?.let { throw it }
        }
    }

    private data class OwnedHandles(
        val mediaFiles: List<MediaFile>,
        val filePreviewLeaseTokens: List<String>,
    ) {
        fun close(reason: FilePreviewLeaseReleaseReason) {
            closeEveryHandle(mediaFiles, filePreviewLeaseTokens, reason)
        }
    }
}

private fun closeEveryHandle(files: List<MediaFile>, previewTokens: List<String>, reason: FilePreviewLeaseReleaseReason) {
    var firstFailure: Throwable? = null
    fun attempt(close: () -> Unit) {
        try { close() } catch (failure: Throwable) {
            firstFailure?.let { if (it !== failure) it.addSuppressed(failure) } ?: run { firstFailure = failure }
        }
    }
    files.forEach { file -> attempt(file::close) }
    previewTokens.forEach { token -> attempt { FilePreviewLeaseRegistry.release(token, reason) } }
    firstFailure?.let { throw it }
}
