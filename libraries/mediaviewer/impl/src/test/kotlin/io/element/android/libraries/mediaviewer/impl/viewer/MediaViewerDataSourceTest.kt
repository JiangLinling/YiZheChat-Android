/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaviewer.impl.viewer

import android.net.Uri
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.localofficeviewer.api.FilePreviewLeaseState
import io.element.android.libraries.matrix.api.core.UniqueId
import io.element.android.libraries.matrix.api.media.MatrixMediaLoader
import io.element.android.libraries.matrix.api.media.MediaFile
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.matrix.api.timeline.item.event.SegmentedVideoCandidate
import io.element.android.libraries.matrix.test.AN_EVENT_ID
import io.element.android.libraries.matrix.test.AN_EVENT_ID_2
import io.element.android.libraries.matrix.test.AN_EXCEPTION
import io.element.android.libraries.matrix.test.media.FakeMatrixMediaLoader
import io.element.android.libraries.mediaviewer.api.MediaViewerEntryPoint.MediaViewerMode
import io.element.android.libraries.mediaviewer.api.local.LocalMediaFactory
import io.element.android.libraries.mediaviewer.impl.datasource.FakeMediaGalleryDataSource
import io.element.android.libraries.mediaviewer.impl.datasource.MediaGalleryDataSource
import io.element.android.libraries.mediaviewer.impl.gallery.aGroupedMediaItems
import io.element.android.libraries.mediaviewer.impl.local.image.ImageValidationException
import io.element.android.libraries.mediaviewer.impl.local.office.FilePreviewLeaseRegistry
import io.element.android.libraries.mediaviewer.impl.model.aMediaItemDateSeparator
import io.element.android.libraries.mediaviewer.impl.model.aMediaItemFile
import io.element.android.libraries.mediaviewer.impl.model.aMediaItemImage
import io.element.android.libraries.mediaviewer.impl.model.aMediaItemLoadingIndicator
import io.element.android.libraries.mediaviewer.impl.model.aMediaItemVideo
import io.element.android.libraries.mediaviewer.test.FakeLocalMediaFactory
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.LifecycleOperationContext
import io.element.android.security.dataprotection.api.currentLifecycleOperationContext
import io.element.android.security.dataprotection.test.FakeAppOperationalStateCoordinator
import io.element.android.services.toolbox.test.systemclock.A_FAKE_TIMESTAMP
import io.element.android.services.toolbox.test.systemclock.FakeSystemClock
import io.element.android.tests.testutils.lambda.lambdaRecorder
import io.element.android.tests.testutils.lambda.value
import io.element.android.tests.testutils.testCoroutineDispatchers
import io.mockk.mockk
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class MediaViewerDataSourceTest {
    private val mockMediaUrl: Uri = mockk("localMediaUri")

    @Test
    fun `outer accepted start keeps original download and receipts after viewer exit`() = runTest {
        val facts = FakeAppOperationalStateCoordinator()
        val coordinator = mockk<AppOperationalStateCoordinator>()
        val accepted = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        coEvery { coordinator.start(any()) } coAnswers {
            val operation = facts.start(firstArg())
            accepted.complete(Unit); response.await(); operation
        }
        coEvery { coordinator.recordClientReceipt(any()) } coAnswers { facts.recordClientReceipt(firstArg()) }
        coEvery { coordinator.persistClientReceipts(any()) } returns Unit
        val file = TrackingMediaFile()
        val loader = object : MatrixMediaLoader by FakeMatrixMediaLoader() {
            override suspend fun downloadMediaFile(source: MediaSource, mimeType: String?, filename: String?, useCache: Boolean): Result<MediaFile> {
                assertThat(currentLifecycleOperationContext()?.operationId).isEqualTo(facts.startedRequests.single().operationId)
                return Result.success(file)
            }
        }
        val gallery = FakeMediaGalleryDataSource()
        val sut = createMediaViewerDataSource(galleryDataSource = gallery, mediaLoader = loader, appOperationalStateCoordinator = coordinator)
        sut.dataFlow.test {
            skipItems(1)
            gallery.emitGroupedMediaItems(AsyncData.Success(aGroupedMediaItems(imageAndVideoItems = listOf(aMediaItemImage()))))
            val page = awaitItem().first() as MediaViewerPageData.MediaViewerData
            val waiter = async { sut.loadMedia(page) }
            accepted.await(); waiter.cancel(); sut.dispose(); response.complete(Unit); runCurrent()
            assertThat(facts.startedRequests).hasSize(1)
            assertThat(facts.clientReceipts).hasSize(2)
            assertThat(facts.clientReceipts.map { it.operationId }.toSet()).containsExactly(facts.startedRequests.single().operationId)
            assertThat(file.closeCount).isEqualTo(1)
            assertThat(page.downloadedMedia.value.isSuccess()).isFalse()
        }
    }

    @Test
    fun `viewer exit while waiting for inner download does not strand the admitted outer operation`() = runTest {
        val facts = FakeAppOperationalStateCoordinator()
        val entered = CompletableDeferred<Unit>()
        val admission = CompletableDeferred<Unit>()
        val file = TrackingMediaFile()
        val loader = object : MatrixMediaLoader by FakeMatrixMediaLoader() {
            override suspend fun downloadMediaFile(source: MediaSource, mimeType: String?, filename: String?, useCache: Boolean): Result<MediaFile> {
                entered.complete(Unit); admission.await(); return Result.success(file)
            }
        }
        val gallery = FakeMediaGalleryDataSource()
        val sut = createMediaViewerDataSource(galleryDataSource = gallery, mediaLoader = loader, appOperationalStateCoordinator = facts)
        sut.dataFlow.test {
            skipItems(1)
            gallery.emitGroupedMediaItems(AsyncData.Success(aGroupedMediaItems(imageAndVideoItems = listOf(aMediaItemImage()))))
            val page = awaitItem().first() as MediaViewerPageData.MediaViewerData
            val waiter = async { sut.loadMedia(page) }
            entered.await(); waiter.cancel(); sut.dispose(); admission.complete(Unit); runCurrent()
            assertThat(facts.startedRequests).hasSize(1)
            assertThat(facts.clientReceipts).hasSize(2)
            assertThat(file.closeCount).isEqualTo(1)
            assertThat(page.downloadedMedia.value.isSuccess()).isFalse()
        }
    }

    @Test
    fun `factory or receipt failure releases acquired handle without inventing client completion`() = runTest {
        for (factoryFails in listOf(true, false)) {
            val facts = FakeAppOperationalStateCoordinator()
            val coordinator = mockk<AppOperationalStateCoordinator>()
            coEvery { coordinator.start(any()) } coAnswers { facts.start(firstArg()) }
            coEvery { coordinator.persistClientReceipts(any()) } returns Unit
            coEvery { coordinator.recordClientReceipt(any()) } throws IllegalStateException("Fixture receipt failure")
            val file = TrackingMediaFile()
            val loader = object : MatrixMediaLoader by FakeMatrixMediaLoader() {
                override suspend fun downloadMediaFile(source: MediaSource, mimeType: String?, filename: String?, useCache: Boolean) = Result.success<MediaFile>(file)
            }
            val factory = if (factoryFails) mockk<LocalMediaFactory>().also {
                coEvery { it.createFromMediaFile(any(), any()) } throws IllegalStateException("Fixture factory failure")
            } else FakeLocalMediaFactory(mockMediaUrl)
            val gallery = FakeMediaGalleryDataSource()
            val sut = createMediaViewerDataSource(galleryDataSource = gallery, mediaLoader = loader,
                localMediaFactory = factory, appOperationalStateCoordinator = coordinator)
            sut.dataFlow.test {
                skipItems(1)
                gallery.emitGroupedMediaItems(AsyncData.Success(aGroupedMediaItems(imageAndVideoItems = listOf(aMediaItemImage()))))
                val page = awaitItem().first() as MediaViewerPageData.MediaViewerData
                sut.loadMedia(page)
                assertThat(page.downloadedMedia.value.isFailure()).isTrue()
                assertThat(file.closeCount).isEqualTo(1)
                assertThat(facts.startedRequests).hasSize(1)
                assertThat(facts.clientReceipts).isEmpty()
                coVerify(exactly = if (factoryFails) 0 else 2) { coordinator.recordClientReceipt(any()) }
            }
        }
    }

    @Test
    fun `setup should start the gallery data source`() = runTest {
        val startLambda = lambdaRecorder<Unit> { }
        val galleryDataSource = FakeMediaGalleryDataSource(
            startLambda = startLambda
        )
        val sut = createMediaViewerDataSource(
            galleryDataSource = galleryDataSource,
        )
        sut.setup(backgroundScope)
        startLambda.assertions().isCalledOnce()
    }

    @Test
    fun `test dispose`() = runTest {
        val sut = createMediaViewerDataSource()
        sut.dispose()
    }

    @Test
    fun `dispose attempts every acquired handle even when an earlier close throws`() = runTest {
        val gallery = FakeMediaGalleryDataSource()
        val files = listOf(TrackingMediaFile(closeFailure = IllegalStateException("Injected close failure")), TrackingMediaFile())
        var nextFile = 0
        val loader = object : MatrixMediaLoader by FakeMatrixMediaLoader() {
            override suspend fun downloadMediaFile(source: MediaSource, mimeType: String?, filename: String?, useCache: Boolean): Result<MediaFile> =
                Result.success(files[nextFile++])
        }
        val sut = createMediaViewerDataSource(galleryDataSource = gallery, mediaLoader = loader)
        sut.dataFlow.test {
            skipItems(1)
            gallery.emitGroupedMediaItems(AsyncData.Success(aGroupedMediaItems(imageAndVideoItems = listOf(
                aMediaItemImage(eventId = AN_EVENT_ID), aMediaItemImage(eventId = AN_EVENT_ID_2),
            ))))
            val data = awaitItem().map { it as MediaViewerPageData.MediaViewerData }
            data.forEach { sut.loadMedia(it) }
            assertThat(data.all { it.downloadedMedia.value.isSuccess() }).isTrue()
            assertThat(runCatching { sut.dispose() }.isFailure).isTrue()
            assertThat(files.map { it.closeCount }).containsExactly(1, 1)
        }
    }

    @Test
    fun `test dataFlow uninitialized, loading and error`() = runTest {
        val galleryDataSource = FakeMediaGalleryDataSource(
            initialData = AsyncData.Uninitialized,
        )
        val sut = createMediaViewerDataSource(
            galleryDataSource = galleryDataSource,
        )
        sut.dataFlow.test {
            // The flow starts with an empty result
            assertThat(awaitItem()).isEmpty()
            galleryDataSource.emitGroupedMediaItems(AsyncData.Uninitialized)
            assertThat(awaitItem().first()).isInstanceOf(MediaViewerPageData.Loading::class.java)
            galleryDataSource.emitGroupedMediaItems(AsyncData.Loading())
            // No items emitted, we were already loading data
            ensureAllEventsConsumed()
            galleryDataSource.emitGroupedMediaItems(AsyncData.Failure(AN_EXCEPTION))
            assertThat(awaitItem().first()).isEqualTo(MediaViewerPageData.Failure(AN_EXCEPTION))
        }
    }

    @Test
    fun `test dataFlow empty`() = runTest {
        val galleryDataSource = FakeMediaGalleryDataSource()
        val sut = createMediaViewerDataSource(
            galleryDataSource = galleryDataSource,
        )
        sut.dataFlow.test {
            galleryDataSource.emitGroupedMediaItems(
                AsyncData.Success(
                    aGroupedMediaItems(
                        imageAndVideoItems = listOf(),
                        fileItems = listOf(),
                    )
                )
            )
            val result = awaitItem()
            assertThat(result).isEmpty()
        }
    }

    @Test
    fun `test dataFlow loading items`() = runTest {
        val galleryDataSource = FakeMediaGalleryDataSource()
        val sut = createMediaViewerDataSource(
            galleryDataSource = galleryDataSource,
        )
        sut.dataFlow.test {
            skipItems(1)
            galleryDataSource.emitGroupedMediaItems(
                AsyncData.Success(
                    aGroupedMediaItems(
                        imageAndVideoItems = listOf(
                            aMediaItemLoadingIndicator(
                                direction = Timeline.PaginationDirection.BACKWARDS,
                            ),
                            aMediaItemLoadingIndicator(
                                direction = Timeline.PaginationDirection.FORWARDS,
                            ),
                        ),
                        fileItems = listOf(),
                    )
                )
            )
            val result = awaitItem()
            assertThat(result).containsExactly(
                MediaViewerPageData.Loading(
                    direction = Timeline.PaginationDirection.BACKWARDS,
                    timestamp = A_FAKE_TIMESTAMP,
                    pagerKey = 0L,
                ),
                MediaViewerPageData.Loading(
                    direction = Timeline.PaginationDirection.FORWARDS,
                    timestamp = A_FAKE_TIMESTAMP,
                    pagerKey = 1L,
                ),
            )
        }
    }

    @Test
    fun `test dataFlow with data galleryMode image`() = runTest {
        val galleryDataSource = FakeMediaGalleryDataSource()
        val sut = createMediaViewerDataSource(
            mode = MediaViewerMode.TimelineImagesAndVideos(timelineMode = Timeline.Mode.Media),
            galleryDataSource = galleryDataSource,
        )
        sut.dataFlow.test {
            skipItems(1)
            galleryDataSource.emitGroupedMediaItems(
                AsyncData.Success(
                    aGroupedMediaItems(
                        imageAndVideoItems = listOf(aMediaItemImage(eventId = AN_EVENT_ID)),
                        fileItems = listOf(aMediaItemFile(eventId = AN_EVENT_ID_2)),
                    )
                )
            )
            val result = awaitItem()
            assertThat(result).hasSize(1)
            assertThat((result.first() as MediaViewerPageData.MediaViewerData).eventId).isEqualTo(AN_EVENT_ID)
        }
    }

    @Test
    fun `all media isolate events and only identical event sources reuse their owner`() = runTest {
        val galleryDataSource = FakeMediaGalleryDataSource()
        val sharedSourceUrl = "mxc://matrix.example/shared-init"
        val sharedSource = MediaSource(sharedSourceUrl)
        val firstCandidate = mockk<SegmentedVideoCandidate>("firstCandidate")
        val secondCandidate = mockk<SegmentedVideoCandidate>("secondCandidate")
        val sut = createMediaViewerDataSource(
            mode = MediaViewerMode.TimelineImagesAndVideos(timelineMode = Timeline.Mode.Media),
            galleryDataSource = galleryDataSource,
        )
        sut.dataFlow.test {
            skipItems(1)
            galleryDataSource.emitGroupedMediaItems(
                AsyncData.Success(
                    aGroupedMediaItems(
                        imageAndVideoItems = listOf(
                            aMediaItemVideo(UniqueId("video-1"), sharedSource).copy(
                                eventId = AN_EVENT_ID,
                                segmentedVideo = firstCandidate,
                            ),
                            aMediaItemVideo(UniqueId("video-2"), sharedSource).copy(
                                eventId = AN_EVENT_ID_2,
                                segmentedVideo = firstCandidate,
                            ),
                            aMediaItemVideo(UniqueId("video-3"), sharedSource).copy(
                                eventId = AN_EVENT_ID,
                                segmentedVideo = secondCandidate,
                            ),
                            aMediaItemVideo(UniqueId("local-video-1"), sharedSource).copy(
                                eventId = null,
                                segmentedVideo = firstCandidate,
                            ),
                            aMediaItemVideo(UniqueId("local-video-2"), sharedSource).copy(
                                eventId = null,
                                segmentedVideo = firstCandidate,
                            ),
                            aMediaItemImage(UniqueId("ordinary-1"), mediaSourceUrl = sharedSourceUrl).copy(eventId = AN_EVENT_ID),
                            aMediaItemImage(UniqueId("ordinary-2"), mediaSourceUrl = sharedSourceUrl).copy(eventId = AN_EVENT_ID),
                            aMediaItemImage(UniqueId("ordinary-3"), mediaSourceUrl = sharedSourceUrl).copy(eventId = AN_EVENT_ID_2),
                        ),
                    )
                )
            )

            val result = awaitItem().map { it as MediaViewerPageData.MediaViewerData }

            assertThat(result).hasSize(8)
            for (left in 0..4) {
                for (right in (left + 1)..4) {
                    assertThat(result[left].downloadedMedia).isNotSameInstanceAs(result[right].downloadedMedia)
                }
            }
            assertThat(result[5].downloadedMedia).isSameInstanceAs(result[6].downloadedMedia)
            assertThat(result[5].downloadedMedia).isNotSameInstanceAs(result[7].downloadedMedia)
        }
    }

    @Test
    fun `test dataFlow with data galleryMode files`() = runTest {
        val galleryDataSource = FakeMediaGalleryDataSource()
        val sut = createMediaViewerDataSource(
            mode = MediaViewerMode.TimelineFilesAndAudios(timelineMode = Timeline.Mode.Media),
            galleryDataSource = galleryDataSource,
        )
        sut.dataFlow.test {
            skipItems(1)
            galleryDataSource.emitGroupedMediaItems(
                AsyncData.Success(
                    aGroupedMediaItems(
                        imageAndVideoItems = listOf(aMediaItemImage(eventId = AN_EVENT_ID)),
                        fileItems = listOf(aMediaItemFile(eventId = AN_EVENT_ID_2)),
                    )
                )
            )
            val result = awaitItem()
            assertThat(result).hasSize(1)
            assertThat((result.first() as MediaViewerPageData.MediaViewerData).eventId).isEqualTo(AN_EVENT_ID_2)
        }
    }

    @Test
    fun `test dataFlow - date separator are filtered out`() = runTest {
        val galleryDataSource = FakeMediaGalleryDataSource()
        val sut = createMediaViewerDataSource(
            galleryDataSource = galleryDataSource,
        )
        sut.dataFlow.test {
            skipItems(1)
            galleryDataSource.emitGroupedMediaItems(
                AsyncData.Success(
                    aGroupedMediaItems(
                        imageAndVideoItems = listOf(aMediaItemDateSeparator(), aMediaItemImage(), aMediaItemDateSeparator()),
                        fileItems = emptyList(),
                    )
                )
            )
            val result = awaitItem()
            assertThat(result).hasSize(1)
        }
    }

    @Test
    fun `loadMore invokes the gallery data source loadMore`() = runTest {
        val loadMoreLambda = lambdaRecorder<Timeline.PaginationDirection, Unit> { }
        val galleryDataSource = FakeMediaGalleryDataSource(
            loadMoreLambda = loadMoreLambda
        )
        val sut = createMediaViewerDataSource(
            galleryDataSource = galleryDataSource,
        )
        sut.loadMore(Timeline.PaginationDirection.BACKWARDS)
        loadMoreLambda.assertions().isCalledOnce().with(value(Timeline.PaginationDirection.BACKWARDS))
    }

    @Test
    fun `test dataFlow with data galleryMode image and load media`() = runTest {
        val galleryDataSource = FakeMediaGalleryDataSource()
        val coordinator = FakeAppOperationalStateCoordinator()
        var observedContext: LifecycleOperationContext? = null
        val delegate = FakeMatrixMediaLoader()
        val mediaLoader = object : MatrixMediaLoader by delegate {
            override suspend fun downloadMediaFile(
                source: io.element.android.libraries.matrix.api.media.MediaSource,
                mimeType: String?,
                filename: String?,
                useCache: Boolean,
            ): Result<MediaFile> {
                observedContext = currentLifecycleOperationContext()
                return delegate.downloadMediaFile(source, mimeType, filename, useCache)
            }
        }
        val sut = createMediaViewerDataSource(
            galleryDataSource = galleryDataSource,
            mediaLoader = mediaLoader,
            appOperationalStateCoordinator = coordinator,
        )
        sut.dataFlow.test {
            skipItems(1)
            galleryDataSource.emitGroupedMediaItems(
                AsyncData.Success(
                    aGroupedMediaItems(
                        imageAndVideoItems = listOf(aMediaItemImage(eventId = AN_EVENT_ID)),
                    )
                )
            )
            val result = awaitItem()
            val mediaViewerData = result.first() as MediaViewerPageData.MediaViewerData
            assertThat(mediaViewerData.downloadedMedia.value).isEqualTo(AsyncData.Uninitialized)
            sut.loadMedia(mediaViewerData)
            assertThat(mediaViewerData.downloadedMedia.value.isSuccess()).isTrue()
            val request = coordinator.startedRequests.single()
            assertThat(request.lifecycleId).isEqualTo(FullAppLifecycleId.LC_16)
            assertThat(request.trigger).isEqualTo("download")
            assertThat(observedContext?.operationId).isEqualTo(request.operationId)
            assertThat(mediaViewerData.downloadedMedia.value.dataOrNull()?.lifecycleOperationId)
                .isEqualTo(request.operationId)
            assertThat(coordinator.clientReceipts.map { it.operationId }).containsExactly(
                request.operationId,
                request.operationId,
            )
        }
    }

    @Test
    fun `test dataFlow with data galleryMode image and load media with failure then success`() = runTest {
        val galleryDataSource = FakeMediaGalleryDataSource()
        val mediaLoader = FakeMatrixMediaLoader()
        val sut = createMediaViewerDataSource(
            galleryDataSource = galleryDataSource,
            mediaLoader = mediaLoader,
        )
        sut.dataFlow.test {
            skipItems(1)
            galleryDataSource.emitGroupedMediaItems(
                AsyncData.Success(
                    aGroupedMediaItems(
                        imageAndVideoItems = listOf(aMediaItemImage(eventId = AN_EVENT_ID)),
                    )
                )
            )
            val result = awaitItem()
            val mediaViewerData = result.first() as MediaViewerPageData.MediaViewerData
            assertThat(mediaViewerData.downloadedMedia.value).isEqualTo(AsyncData.Uninitialized)
            mediaLoader.shouldFail = true
            sut.loadMedia(mediaViewerData)
            assertThat(mediaViewerData.downloadedMedia.value.isFailure()).isTrue()
            // clear the error
            sut.clearLoadingError(mediaViewerData)
            assertThat(mediaViewerData.downloadedMedia.value).isEqualTo(AsyncData.Uninitialized)
            // load again with success
            mediaLoader.shouldFail = false
            sut.loadMedia(mediaViewerData)
            assertThat(mediaViewerData.downloadedMedia.value.isSuccess()).isTrue()
        }
    }

    @Test
    fun `cancel during download closes late media and does not publish it`() = runTest {
        val galleryDataSource = FakeMediaGalleryDataSource()
        val downloadStarted = CompletableDeferred<Unit>()
        val allowDownloadToFinish = CompletableDeferred<Unit>()
        val mediaFile = TrackingMediaFile()
        val mediaLoader = object : MatrixMediaLoader by FakeMatrixMediaLoader() {
            override suspend fun downloadMediaFile(
                source: io.element.android.libraries.matrix.api.media.MediaSource,
                mimeType: String?,
                filename: String?,
                useCache: Boolean,
            ): Result<MediaFile> {
                downloadStarted.complete(Unit)
                allowDownloadToFinish.await()
                return Result.success(mediaFile)
            }
        }
        val sut = createMediaViewerDataSource(
            galleryDataSource = galleryDataSource,
            mediaLoader = mediaLoader,
        )

        sut.dataFlow.test {
            skipItems(1)
            galleryDataSource.emitGroupedMediaItems(
                AsyncData.Success(aGroupedMediaItems(imageAndVideoItems = listOf(aMediaItemImage(eventId = AN_EVENT_ID))))
            )
            val mediaViewerData = awaitItem().first() as MediaViewerPageData.MediaViewerData
            val loadJob = launch { sut.loadMedia(mediaViewerData) }
            downloadStarted.await()

            sut.cancelLoadingMedia(mediaViewerData)
            allowDownloadToFinish.complete(Unit)
            loadJob.join()

            assertThat(mediaViewerData.downloadedMedia.value).isEqualTo(AsyncData.Uninitialized)
            assertThat(mediaFile.closeCount).isEqualTo(1)
        }
    }

    @Test
    fun `one time original download disables reusable loader cache`() = runTest {
        val galleryDataSource = FakeMediaGalleryDataSource()
        var observedUseCache: Boolean? = null
        val mediaLoader = object : MatrixMediaLoader by FakeMatrixMediaLoader() {
            override suspend fun downloadMediaFile(
                source: io.element.android.libraries.matrix.api.media.MediaSource,
                mimeType: String?,
                filename: String?,
                useCache: Boolean,
            ): Result<MediaFile> {
                observedUseCache = useCache
                return Result.success(TrackingMediaFile())
            }
        }
        val sut = createMediaViewerDataSource(
            galleryDataSource = galleryDataSource,
            mediaLoader = mediaLoader,
            allowReusableOriginalCache = false,
        )

        sut.dataFlow.test {
            skipItems(1)
            galleryDataSource.emitGroupedMediaItems(
                AsyncData.Success(aGroupedMediaItems(imageAndVideoItems = listOf(aMediaItemImage(eventId = AN_EVENT_ID))))
            )
            sut.loadMedia(awaitItem().first() as MediaViewerPageData.MediaViewerData)

            assertThat(observedUseCache).isFalse()
        }
    }

    @Test
    fun `image validation failure closes downloaded lease`() = runTest {
        val galleryDataSource = FakeMediaGalleryDataSource()
        val mediaFile = TrackingMediaFile()
        val mediaLoader = object : MatrixMediaLoader by FakeMatrixMediaLoader() {
            override suspend fun downloadMediaFile(
                source: io.element.android.libraries.matrix.api.media.MediaSource,
                mimeType: String?,
                filename: String?,
                useCache: Boolean,
            ): Result<MediaFile> = Result.success(mediaFile)
        }
        val delegateFactory = FakeLocalMediaFactory(mockMediaUrl)
        val rejectingFactory = object : LocalMediaFactory by delegateFactory {
            override fun createFromMediaFile(mediaFile: MediaFile, mediaInfo: io.element.android.libraries.mediaviewer.api.MediaInfo) =
                throw ImageValidationException.PixelCountExceeded()
        }
        val sut = createMediaViewerDataSource(
            galleryDataSource = galleryDataSource,
            mediaLoader = mediaLoader,
            localMediaFactory = rejectingFactory,
        )

        sut.dataFlow.test {
            skipItems(1)
            galleryDataSource.emitGroupedMediaItems(
                AsyncData.Success(aGroupedMediaItems(imageAndVideoItems = listOf(aMediaItemImage(eventId = AN_EVENT_ID))))
            )
            val mediaViewerData = awaitItem().first() as MediaViewerPageData.MediaViewerData

            sut.loadMedia(mediaViewerData)

            assertThat(mediaViewerData.downloadedMedia.value.isFailure()).isTrue()
            assertThat(mediaFile.closeCount).isEqualTo(1)
        }
    }

    @Test
    fun `office media publishes exact secure lease and release closes it once`() = runTest {
        val galleryDataSource = FakeMediaGalleryDataSource()
        val mediaFile = TrackingMediaFile(SECURE_MEDIA_URI)
        val officeItem = anOfficeMediaItem()
        val mediaLoader = object : MatrixMediaLoader by FakeMatrixMediaLoader() {
            override suspend fun downloadMediaFile(
                source: io.element.android.libraries.matrix.api.media.MediaSource,
                mimeType: String?,
                filename: String?,
                useCache: Boolean,
            ): Result<MediaFile> = Result.success(mediaFile)
        }
        val sut = createMediaViewerDataSource(
            mode = MediaViewerMode.TimelineFilesAndAudios(timelineMode = Timeline.Mode.Media),
            galleryDataSource = galleryDataSource,
            mediaLoader = mediaLoader,
        )

        sut.dataFlow.test {
            skipItems(1)
            galleryDataSource.emitGroupedMediaItems(
                AsyncData.Success(aGroupedMediaItems(fileItems = listOf(officeItem)))
            )
            val mediaViewerData = awaitItem().first() as MediaViewerPageData.MediaViewerData

            sut.loadMedia(mediaViewerData)

            val localMedia = checkNotNull(mediaViewerData.downloadedMedia.value.dataOrNull())
            val token = checkNotNull(localMedia.filePreviewLeaseToken)
            val lease = checkNotNull(
                FilePreviewLeaseRegistry.acquire(token, localMedia.lifecycleOperationId, SECURE_MEDIA_AUTHORITY)
            )
            assertThat(lease.state.value).isEqualTo(FilePreviewLeaseState.Available)
            assertThat(mediaFile.closeCount).isEqualTo(0)

            sut.releaseMedia(mediaViewerData)

            assertThat(mediaFile.closeCount).isEqualTo(1)
            assertThat(FilePreviewLeaseRegistry.acquire(token, localMedia.lifecycleOperationId, SECURE_MEDIA_AUTHORITY)).isNull()
        }
    }

    @Test
    fun `office media with unsupported carrier fails closed and closes downloaded handle`() = runTest {
        val galleryDataSource = FakeMediaGalleryDataSource()
        val mediaFile = TrackingMediaFile("file:///tmp/plaintext.pdf")
        val officeItem = anOfficeMediaItem()
        val mediaLoader = object : MatrixMediaLoader by FakeMatrixMediaLoader() {
            override suspend fun downloadMediaFile(
                source: io.element.android.libraries.matrix.api.media.MediaSource,
                mimeType: String?,
                filename: String?,
                useCache: Boolean,
            ): Result<MediaFile> = Result.success(mediaFile)
        }
        val sut = createMediaViewerDataSource(
            mode = MediaViewerMode.TimelineFilesAndAudios(timelineMode = Timeline.Mode.Media),
            galleryDataSource = galleryDataSource,
            mediaLoader = mediaLoader,
        )

        sut.dataFlow.test {
            skipItems(1)
            galleryDataSource.emitGroupedMediaItems(
                AsyncData.Success(aGroupedMediaItems(fileItems = listOf(officeItem)))
            )
            val mediaViewerData = awaitItem().first() as MediaViewerPageData.MediaViewerData

            sut.loadMedia(mediaViewerData)

            assertThat(mediaViewerData.downloadedMedia.value.isFailure()).isTrue()
            assertThat(mediaFile.closeCount).isEqualTo(1)
        }
    }

    @Test
    fun `pdf media publishes exact secure lease and release closes it once`() = runTest {
        val galleryDataSource = FakeMediaGalleryDataSource()
        val mediaFile = TrackingMediaFile(SECURE_MEDIA_URI)
        val pdfItem = aPdfMediaItem()
        val mediaLoader = object : MatrixMediaLoader by FakeMatrixMediaLoader() {
            override suspend fun downloadMediaFile(
                source: io.element.android.libraries.matrix.api.media.MediaSource,
                mimeType: String?,
                filename: String?,
                useCache: Boolean,
            ): Result<MediaFile> = Result.success(mediaFile)
        }
        val sut = createMediaViewerDataSource(
            mode = MediaViewerMode.TimelineFilesAndAudios(timelineMode = Timeline.Mode.Media),
            galleryDataSource = galleryDataSource,
            mediaLoader = mediaLoader,
        )

        sut.dataFlow.test {
            skipItems(1)
            galleryDataSource.emitGroupedMediaItems(
                AsyncData.Success(aGroupedMediaItems(fileItems = listOf(pdfItem)))
            )
            val mediaViewerData = awaitItem().first() as MediaViewerPageData.MediaViewerData

            sut.loadMedia(mediaViewerData)

            val localMedia = checkNotNull(mediaViewerData.downloadedMedia.value.dataOrNull())
            val token = checkNotNull(localMedia.filePreviewLeaseToken)
            val lease = checkNotNull(
                FilePreviewLeaseRegistry.acquire(token, localMedia.lifecycleOperationId, SECURE_MEDIA_AUTHORITY)
            )
            assertThat(lease.carrierUri).isEqualTo(SECURE_MEDIA_URI)
            assertThat(lease.state.value).isEqualTo(FilePreviewLeaseState.Available)
            assertThat(mediaFile.closeCount).isEqualTo(0)

            sut.releaseMedia(mediaViewerData)

            assertThat(mediaFile.closeCount).isEqualTo(1)
            assertThat(FilePreviewLeaseRegistry.acquire(token, localMedia.lifecycleOperationId, SECURE_MEDIA_AUTHORITY)).isNull()
        }
    }

    @Test
    fun `pdf media with unsupported carrier fails closed and closes downloaded handle`() = runTest {
        val galleryDataSource = FakeMediaGalleryDataSource()
        val mediaFile = TrackingMediaFile("file:///tmp/plaintext.pdf")
        val mediaLoader = object : MatrixMediaLoader by FakeMatrixMediaLoader() {
            override suspend fun downloadMediaFile(
                source: io.element.android.libraries.matrix.api.media.MediaSource,
                mimeType: String?,
                filename: String?,
                useCache: Boolean,
            ): Result<MediaFile> = Result.success(mediaFile)
        }
        val sut = createMediaViewerDataSource(
            mode = MediaViewerMode.TimelineFilesAndAudios(timelineMode = Timeline.Mode.Media),
            galleryDataSource = galleryDataSource,
            mediaLoader = mediaLoader,
        )

        sut.dataFlow.test {
            skipItems(1)
            galleryDataSource.emitGroupedMediaItems(
                AsyncData.Success(aGroupedMediaItems(fileItems = listOf(aPdfMediaItem())))
            )
            val mediaViewerData = awaitItem().first() as MediaViewerPageData.MediaViewerData

            sut.loadMedia(mediaViewerData)

            assertThat(mediaViewerData.downloadedMedia.value.isFailure()).isTrue()
            assertThat(mediaFile.closeCount).isEqualTo(1)
        }
    }

    private fun TestScope.createMediaViewerDataSource(
        mode: MediaViewerMode = MediaViewerMode.TimelineImagesAndVideos(timelineMode = Timeline.Mode.Media),
        galleryDataSource: MediaGalleryDataSource = FakeMediaGalleryDataSource(),
        mediaLoader: MatrixMediaLoader = FakeMatrixMediaLoader(),
        localMediaFactory: LocalMediaFactory = FakeLocalMediaFactory(mockMediaUrl),
        appOperationalStateCoordinator: AppOperationalStateCoordinator = FakeAppOperationalStateCoordinator(),
        allowReusableOriginalCache: Boolean = true,
    ) = MediaViewerDataSource(
        coroutineScope = backgroundScope,
        mode = mode,
        dispatcher = testCoroutineDispatchers().computation,
        galleryDataSource = galleryDataSource,
        mediaLoader = mediaLoader,
        localMediaFactory = localMediaFactory,
        systemClock = FakeSystemClock(),
        pagerKeysHandler = PagerKeysHandler(),
        appOperationalStateCoordinator = appOperationalStateCoordinator,
        accountIdHash = "a".repeat(64),
        matrixDeviceIdHash = "b".repeat(64),
        allowReusableOriginalCache = allowReusableOriginalCache,
    )

    private class TrackingMediaFile(
        private val carrierUri: String = "file:///tmp/test-media",
        private val closeFailure: Throwable? = null,
    ) : MediaFile {
        var closeCount = 0
            private set

        override fun path(): String = ""
        override fun uriString(): String = carrierUri
        override fun openInputStream(): InputStream = ByteArrayInputStream(byteArrayOf(1, 2, 3))
        override fun persist(path: String): Boolean = false
        override fun close() {
            closeCount++
            closeFailure?.let { throw it }
        }
    }

    private fun anOfficeMediaItem() = aMediaItemFile(eventId = AN_EVENT_ID).let { item ->
        item.copy(
            mediaInfo = item.mediaInfo.copy(
                filename = "document.docx",
                mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                fileExtension = "docx",
            )
        )
    }

    private fun aPdfMediaItem() = aMediaItemFile(eventId = AN_EVENT_ID).let { item ->
        item.copy(
            mediaInfo = item.mediaInfo.copy(
                filename = "document.pdf",
                mimeType = "application/pdf",
                fileExtension = "pdf",
            )
        )
    }

    private companion object {
        const val SECURE_MEDIA_AUTHORITY = "com.yizhe.chat.debug.securemedia"
        const val SECURE_MEDIA_URI = "content://$SECURE_MEDIA_AUTHORITY/v1/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
