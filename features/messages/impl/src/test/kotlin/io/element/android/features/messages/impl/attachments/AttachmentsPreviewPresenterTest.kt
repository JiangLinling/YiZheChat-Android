/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalCoroutinesApi::class)

package io.element.android.features.messages.impl.attachments

import android.net.Uri
import androidx.core.net.toUri
import com.google.common.truth.Truth.assertThat
import io.element.android.features.messages.impl.attachments.preview.AttachmentsPreviewEvent
import io.element.android.features.messages.impl.attachments.preview.AttachmentsPreviewPresenter
import io.element.android.features.messages.impl.attachments.preview.OnDoneListener
import io.element.android.features.messages.impl.attachments.preview.SendActionState
import io.element.android.features.messages.impl.attachments.preview.imageeditor.AttachmentImageEditor
import io.element.android.features.messages.impl.attachments.preview.imageeditor.AttachmentImageEdits
import io.element.android.features.messages.impl.attachments.preview.imageeditor.EditedLocalMedia
import io.element.android.features.messages.impl.attachments.preview.imageeditor.NormalizedCropRect
import io.element.android.features.messages.impl.attachments.preview.imageeditor.assertIsSimilarTo
import io.element.android.features.messages.impl.attachments.video.MediaOptimizationSelectorState
import io.element.android.features.messages.impl.attachments.video.VideoCompressionPresetSelector
import io.element.android.features.messages.impl.attachments.video.VideoUploadEstimation
import io.element.android.features.messages.impl.fixtures.aMediaAttachment
import io.element.android.features.messages.impl.timeline.TimelineController
import io.element.android.features.messages.test.attachments.video.FakeMediaOptimizationSelectorPresenterFactory
import io.element.android.libraries.androidutils.file.TemporaryUriDeleter
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.core.mimetype.MimeTypes
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.media.AudioInfo
import io.element.android.libraries.matrix.api.media.FileInfo
import io.element.android.libraries.matrix.api.media.ImageInfo
import io.element.android.libraries.matrix.api.media.VideoInfo
import io.element.android.libraries.matrix.api.permalink.PermalinkBuilder
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.matrix.test.A_CAPTION
import io.element.android.libraries.matrix.test.media.FakeMediaUploadHandler
import io.element.android.libraries.matrix.test.permalink.FakePermalinkBuilder
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.libraries.matrix.test.timeline.FakeTimeline
import io.element.android.libraries.mediaupload.api.MediaOptimizationConfig
import io.element.android.libraries.mediaupload.api.MediaPreProcessor
import io.element.android.libraries.mediaupload.api.MediaSenderFactory
import io.element.android.libraries.mediaupload.api.MediaUploadInfo
import io.element.android.libraries.mediaupload.impl.DefaultMediaSender
import io.element.android.libraries.mediaupload.test.FakeMediaOptimizationConfigProvider
import io.element.android.libraries.mediaupload.test.FakeMediaPreProcessor
import io.element.android.libraries.mediaviewer.api.aVideoMediaInfo
import io.element.android.libraries.mediaviewer.api.anApkMediaInfo
import io.element.android.libraries.mediaviewer.api.anAudioMediaInfo
import io.element.android.libraries.mediaviewer.api.anImageMediaInfo
import io.element.android.libraries.mediaviewer.api.local.LocalAttachmentPreviewCancelReason
import io.element.android.libraries.mediaviewer.api.local.LocalAttachmentPreviewCleanup
import io.element.android.libraries.mediaviewer.api.local.LocalAttachmentPreviewLease
import io.element.android.libraries.mediaviewer.api.local.LocalAttachmentPreviewLeaseService
import io.element.android.libraries.mediaviewer.api.local.LocalMedia
import io.element.android.libraries.mediaviewer.test.viewer.aLocalMedia
import io.element.android.libraries.preferences.api.store.VideoCompressionPreset
import io.element.android.security.dataprotection.api.*
import io.element.android.features.messages.impl.attachments.preview.MediaSubmissionCheck
import io.element.android.security.dataprotection.api.LifecycleComponent
import io.element.android.security.dataprotection.test.FakeAppOperationalStateCoordinator
import io.element.android.tests.testutils.WarmUpRule
import io.element.android.tests.testutils.awaitLastSequentialItem
import io.element.android.tests.testutils.consumeItemsUntilPredicate
import io.element.android.tests.testutils.fake.FakeTemporaryUriDeleter
import io.element.android.tests.testutils.lambda.any
import io.element.android.tests.testutils.lambda.lambdaError
import io.element.android.tests.testutils.lambda.lambdaRecorder
import io.element.android.tests.testutils.lambda.value
import io.element.android.tests.testutils.test
import io.element.android.tests.testutils.testCoroutineDispatchers
import io.element.android.features.messages.impl.draft.MediaSelectionDraft
import io.element.android.features.messages.impl.draft.MediaSelectionDraftStore
import io.element.android.features.messages.impl.draft.recoverMediaSubmissions
import io.element.android.libraries.matrix.api.media.MediaSubmissionFact
import io.element.android.libraries.matrix.api.media.MediaSubmissionPhase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.collections.immutable.persistentListOf
import io.element.android.libraries.matrix.api.media.MediaUploadHandler
import io.element.android.libraries.matrix.api.media.MediaSendCancellationResult
import io.element.android.libraries.matrix.api.media.MediaSubmissionContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.io.path.createTempFile

@Suppress("LargeClass")
@RunWith(RobolectricTestRunner::class)
class AttachmentsPreviewPresenterTest {
    @get:Rule
    val warmUpRule = WarmUpRule()

    private val mockMediaUrl: Uri = "file:///path/to/media".toUri()

    @Test
    fun `local media withdraw stops only its original item awaits acknowledgement and uses LC16`() = runTest {
        for ((outcome, controlFails) in listOf(MediaSendCancellationResult.Cancelled to false, MediaSendCancellationResult.Unknown to false, MediaSendCancellationResult.Cancelled to true)) {
            val coordinator = FakeAppOperationalStateCoordinator(startFailure = { request ->
                IllegalStateException("Cancel transport unavailable").takeIf { controlFails && request.trigger == "cancel" }
            })
            val entered = CompletableDeferred<Unit>()
            val acknowledged = CompletableDeferred<MediaSendCancellationResult>()
            var stopped = false
            var sent = 0
            val snapshots = mutableListOf<MediaSelectionDraft>()
            val draftStore = mockk<MediaSelectionDraftStore>(relaxed = true)
            coEvery { draftStore.load(any()) } returns null
            coEvery { draftStore.save(any(), any()) } coAnswers { snapshots += secondArg<MediaSelectionDraft>(); true }
            val timeline = FakeTimeline().apply {
                sendImageLambda = { _, _, _, _, _, _ ->
                    sent++
                    Result.success(if (sent == 1) object : MediaUploadHandler {
                        override suspend fun await(): Result<Unit> {
                            entered.complete(Unit)
                            try { awaitCancellation() } finally { stopped = true }
                        }
                        override fun cancel() = error("Do not settle by a signal")
                        override suspend fun cancelPending() = Result.success(acknowledged.await())
                    } else object : MediaUploadHandler {
                        override suspend fun await(): Result<Unit> {
                            currentCoroutineContext()[MediaSubmissionContext]?.accepted?.invoke(
                                MediaSubmissionFact(MediaSubmissionPhase.ACCEPTED, ".yizhe-second-carrier.yzm", "\$second-event"))
                            return Result.success(Unit)
                        }
                        override fun cancel() = Unit
                    })
                }
            }
            val room = FakeJoinedRoom(liveTimeline = timeline)
            val controller = TimelineController(room, timeline)
            val first = aLocalMedia(mockMediaUrl, anImageMediaInfo()).copy(
                lifecycleOperationId = "original-media-operation-first", lifecycleRoomIdHash = "c".repeat(64))
            val second = first.copy(lifecycleOperationId = "original-media-operation-second")
            createAttachmentsPreviewPresenter(localMedia = first, additionalMedia = listOf(second), room = room,
                timelineController = controller, mediaDraftStore = draftStore,
                mediaPreProcessor = FakeMediaPreProcessor().apply { givenImageResult() },
                appOperationalStateCoordinator = coordinator, onDoneListener = {}).test {
                val initial = awaitItem()
                advanceUntilIdle()
                initial.eventSink(AttachmentsPreviewEvent.SendAttachment)
                entered.await()
                val id = controller.pendingMediaSendPresentations().value.first().displayId
                val cancelling = async { controller.discardOptimisticLocalEcho(id) }
                runCurrent()
                assertThat(stopped).isTrue()
                assertThat(cancelling.isCompleted).isFalse()
                assertThat(controller.optimisticLocalEchoId(id, id)).isEqualTo(id)
                assertThat(coordinator.startedRequests.none { it.lifecycleId == FullAppLifecycleId.LC_21 }).isTrue()
                acknowledged.complete(outcome)
                val result = cancelling.await()
                advanceUntilIdle()
                val cancel = coordinator.startedRequests.single { it.trigger == "cancel" }
                assertThat(cancel.lifecycleId).isEqualTo(FullAppLifecycleId.LC_16)
                assertThat(cancel.roomIdHash).isEqualTo(first.lifecycleRoomIdHash)
                assertThat(sent).isEqualTo(2)
                assertThat(snapshots.any { 0 in it.committed }).isFalse()
                assertThat(snapshots.any { 0 in it.cancellationRequested }).isTrue()
                if (outcome == MediaSendCancellationResult.Cancelled && !controlFails) {
                    assertThat(result.isSuccess).isTrue()
                    assertThat(snapshots.any { 0 in it.discarded }).isTrue()
                    assertThat(controller.optimisticLocalEchoId(id, id)).isNull()
                } else {
                    assertThat(result.isFailure).isTrue()
                    assertThat(snapshots.none { 0 in it.discarded }).isTrue()
                    assertThat(controller.optimisticLocalEchoId(id, id)).isEqualTo(id)
                    assertThat(coordinator.clientReceipts.none { it.operationId == cancel.operationId }).isTrue()
                }
                cancelAndIgnoreRemainingEvents()
            }
            controller.close()
        }
    }

    @Test
    fun `each selected image keeps its own upload operation in live and focused timelines`() = runTest {
        for (mode in listOf(Timeline.Mode.Live, Timeline.Mode.FocusedOnEvent(EventId("\$focused")))) {
            val coordinator = FakeAppOperationalStateCoordinator()
            val selected = (0..2).map { index ->
                aLocalMedia(mockMediaUrl, anImageMediaInfo()).copy(
                    lifecycleOperationId = "selected-image-operation-$index",
                    lifecycleRoomIdHash = "c".repeat(64),
                )
            }
            val calls = mutableListOf<String>()
            val timeline = FakeTimeline().apply {
                sendImageLambda = { _, _, _, _, _, _ ->
                    calls += coordinator.startedRequests.last { it.lifecycleId == FullAppLifecycleId.LC_16 }.operationId
                    assertThat(coordinator.startedRequests.last().lifecycleId).isEqualTo(FullAppLifecycleId.LC_15)
                    Result.success(FakeMediaUploadHandler())
                }
            }
            val presenter = createAttachmentsPreviewPresenter(
                localMedia = selected.first(),
                additionalMedia = selected.drop(1),
                room = FakeJoinedRoom(liveTimeline = timeline),
                timelineMode = mode,
                mediaPreProcessor = FakeMediaPreProcessor().apply { givenImageResult() },
                appOperationalStateCoordinator = coordinator,
                onDoneListener = {},
            )
            presenter.test {
                val initial = awaitItem()
                advanceUntilIdle()
                initial.eventSink(AttachmentsPreviewEvent.SendAttachment)
                advanceUntilIdle()
                assertThat(calls).containsExactlyElementsIn(selected.map { it.lifecycleOperationId }).inOrder()
                assertThat(coordinator.startedRequests.filter { it.lifecycleId == FullAppLifecycleId.LC_16 }.map { it.operationId })
                    .containsExactlyElementsIn(calls).inOrder()
                val captions = coordinator.startedRequests.filter { it.lifecycleId == FullAppLifecycleId.LC_15 }
                assertThat(captions.map { it.operationId }.toSet()).hasSize(3)
                assertThat(captions.map { it.trigger }.toSet()).containsExactly("media_caption")
                assertThat(
                    coordinator.clientReceipts.filter { receipt -> captions.any { it.operationId == receipt.operationId } }
                    .map { it.component }
                ).containsExactlyElementsIn(List(3) { LifecycleComponent.ANDROID_FRONTEND })
                assertThat(coordinator.startedRequests.map { it.roomIdHash }.toSet()).containsExactly("c".repeat(64))
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    @Test
    fun `present - initial state`() = runTest {
        createAttachmentsPreviewPresenter().test {
            val initialState = awaitItem()
            assertThat(initialState.sendActionState).isEqualTo(SendActionState.Idle)
        }
    }

    @Test
    fun `present - send media success scenario`() = runTest {
        val sendFileResult =
            lambdaRecorder<File, FileInfo, String?, String?, EventId?, Result<FakeMediaUploadHandler>> { _, _, _, _, _ ->
                Result.success(FakeMediaUploadHandler())
            }
        val room = FakeJoinedRoom(
            liveTimeline = FakeTimeline().apply {
                sendFileLambda = sendFileResult
            },
        )
        val onDoneListener = lambdaRecorder<Unit> { }
        val mediaPreProcessor = FakeMediaPreProcessor()
        val presenter = createAttachmentsPreviewPresenter(
            localMedia = aLocalMedia(mockMediaUrl, anApkMediaInfo()).copy(lifecycleRoomIdHash = "c".repeat(64)),
            room = room,
            mediaPreProcessor = mediaPreProcessor,
            onDoneListener = { onDoneListener() },
        )
        presenter.test {
            val initialState = awaitItem()
            assertThat(initialState.sendActionState).isEqualTo(SendActionState.Idle)
            consumeItemsUntilPredicate {
                it.sendActionState == SendActionState.Sending.Processing(displayProgress = false)
            }
            initialState.eventSink(AttachmentsPreviewEvent.SendAttachment)
            assertThat(awaitItem().sendActionState).isEqualTo(SendActionState.Sending.Processing(displayProgress = true))
            assertThat(awaitItem().sendActionState).isEqualTo(SendActionState.Sending.ReadyToUpload(mediaUploadInfo))
            assertThat(awaitItem().sendActionState).isEqualTo(SendActionState.Sending.Uploading(mediaUploadInfo))
            consumeItemsUntilPredicate { it.sendActionState == SendActionState.Done }
            sendFileResult.assertions().isCalledOnce()
            onDoneListener.assertions().isCalledOnce()
            advanceUntilIdle()
            assertThat(mediaPreProcessor.cleanUpCallCount).isEqualTo(0)
        }
    }

    @Test
    fun `present - send media after pre-processing success scenario`() = runTest {
        val lifecycleOperationId = "lc16-preview-upload-operation"
        val appOperationalStateCoordinator = FakeAppOperationalStateCoordinator()
        val sendFileResult =
            lambdaRecorder<File, FileInfo, String?, String?, EventId?, Result<FakeMediaUploadHandler>> { _, _, _, _, _ ->
                Result.success(FakeMediaUploadHandler())
            }
        val room = FakeJoinedRoom(
            liveTimeline = FakeTimeline().apply {
                sendFileLambda = sendFileResult
            },
        )
        val onDoneListener = lambdaRecorder<Unit> { }
        val processLatch = CompletableDeferred<Unit>()
        val mediaPreProcessor = FakeMediaPreProcessor(processLatch)
        val presenter = createAttachmentsPreviewPresenter(
            localMedia = aLocalMedia(uri = mockMediaUrl).copy(
                lifecycleOperationId = lifecycleOperationId,
                lifecycleRoomIdHash = "c".repeat(64),
            ),
            room = room,
            mediaPreProcessor = mediaPreProcessor,
            onDoneListener = { onDoneListener() },
            appOperationalStateCoordinator = appOperationalStateCoordinator,
            timelineController = TimelineController(
                room = room,
                liveTimeline = room.liveTimeline,
            ),
        )
        presenter.test {
            val initialState = awaitItem()
            assertThat(initialState.sendActionState).isEqualTo(SendActionState.Idle)
            // Pre-processing finishes
            processLatch.complete(Unit)
            advanceUntilIdle()
            consumeItemsUntilPredicate {
                it.sendActionState == SendActionState.Sending.Processing(displayProgress = false)
            }
            initialState.eventSink(AttachmentsPreviewEvent.SendAttachment)
            assertThat(awaitItem().sendActionState).isEqualTo(SendActionState.Sending.ReadyToUpload(mediaUploadInfo))
            assertThat(awaitItem().sendActionState).isEqualTo(SendActionState.Sending.Uploading(mediaUploadInfo))
            consumeItemsUntilPredicate { it.sendActionState == SendActionState.Done }
            sendFileResult.assertions().isCalledOnce()
            onDoneListener.assertions().isCalledOnce()
            advanceUntilIdle()
            assertThat(mediaPreProcessor.cleanUpCallCount).isEqualTo(0)
            with(appOperationalStateCoordinator.startedRequests.single { it.lifecycleId == FullAppLifecycleId.LC_16 }) {
                assertThat(operationId).isEqualTo(lifecycleOperationId)
                assertThat(accountIdHash).isEqualTo("a".repeat(64))
                assertThat(matrixDeviceIdHash).isEqualTo("b".repeat(64))
                assertThat(roomIdHash).isEqualTo("c".repeat(64))
            }
        }
    }

    @Test
    fun `present - send media before pre-processing success scenario`() = runTest {
        val sendFileResult =
            lambdaRecorder<File, FileInfo, String?, String?, EventId?, Result<FakeMediaUploadHandler>> { _, _, _, _, _ ->
                Result.success(FakeMediaUploadHandler())
            }
        val room = FakeJoinedRoom(
            liveTimeline = FakeTimeline().apply {
                sendFileLambda = sendFileResult
            },
        )
        val onDoneListener = lambdaRecorder<Unit> { }
        val processLatch = CompletableDeferred<Unit>()
        val mediaPreProcessor = FakeMediaPreProcessor(processLatch)
        val presenter = createAttachmentsPreviewPresenter(
            localMedia = aLocalMedia(mockMediaUrl, anApkMediaInfo()).copy(lifecycleRoomIdHash = "c".repeat(64)),
            room = room,
            mediaPreProcessor = mediaPreProcessor,
            onDoneListener = { onDoneListener() },
        )
        presenter.test {
            val initialState = awaitItem()
            assertThat(initialState.sendActionState).isEqualTo(SendActionState.Idle)
            consumeItemsUntilPredicate {
                it.sendActionState == SendActionState.Sending.Processing(displayProgress = false)
            }
            initialState.eventSink(AttachmentsPreviewEvent.SendAttachment)
            // Pre-processing finishes
            processLatch.complete(Unit)
            assertThat(awaitItem().sendActionState).isEqualTo(SendActionState.Sending.Processing(displayProgress = true))
            assertThat(awaitItem().sendActionState).isEqualTo(SendActionState.Sending.ReadyToUpload(mediaUploadInfo))
            assertThat(awaitItem().sendActionState).isEqualTo(SendActionState.Sending.Uploading(mediaUploadInfo))
            consumeItemsUntilPredicate { it.sendActionState == SendActionState.Done }
            sendFileResult.assertions().isCalledOnce()
            onDoneListener.assertions().isCalledOnce()
            advanceUntilIdle()
            assertThat(mediaPreProcessor.cleanUpCallCount).isEqualTo(0)
        }
    }

    @Test
    fun `present - send media with pre-processing failure after user sends media`() = runTest {
        val room = FakeJoinedRoom()
        val onDoneListener = lambdaRecorder<Unit> { }
        val processLatch = CompletableDeferred<Unit>()
        val presenter = createAttachmentsPreviewPresenter(
            room = room,
            mediaPreProcessor = FakeMediaPreProcessor().apply {
                givenResult(Result.failure(Exception()))
            },
            onDoneListener = { onDoneListener() },
        )
        presenter.test {
            val initialState = awaitItem()
            assertThat(initialState.sendActionState).isEqualTo(SendActionState.Idle)
            initialState.eventSink(AttachmentsPreviewEvent.SendAttachment)
            assertThat(awaitItem().sendActionState).isEqualTo(SendActionState.Sending.Processing(displayProgress = false))
            // Pre-processing finishes
            processLatch.complete(Unit)
            val finalFailure = consumeItemsUntilPredicate {
                it.sendActionState is SendActionState.Failure && 0 in it.failedIndices
            }.last()
            assertThat(finalFailure.sendActionState).isInstanceOf(SendActionState.Failure::class.java)
        }
    }

    @Test
    fun `present - send media with pre-processing failure before user sends media`() = runTest {
        val room = FakeJoinedRoom()
        val onDoneListener = lambdaRecorder<Unit> { }
        val processLatch = CompletableDeferred<Unit>()
        val presenter = createAttachmentsPreviewPresenter(
            room = room,
            mediaPreProcessor = FakeMediaPreProcessor().apply {
                givenResult(Result.failure(Exception()))
            },
            onDoneListener = { onDoneListener() },
        )
        presenter.test {
            val initialState = awaitItem()
            assertThat(initialState.sendActionState).isEqualTo(SendActionState.Idle)
            // Pre-processing finishes
            processLatch.complete(Unit)
            advanceUntilIdle()
            initialState.eventSink(AttachmentsPreviewEvent.SendAttachment)
            assertThat(awaitItem().sendActionState).isEqualTo(SendActionState.Sending.Processing(displayProgress = false))
            assertThat(awaitItem().sendActionState).isInstanceOf(SendActionState.Failure::class.java)
        }
    }

    @Test
    fun `present - cancel scenario`() = runTest {
        val onDoneListener = lambdaRecorder<Unit> { }
        val deleteCallback = lambdaRecorder<Uri?, Unit> {}
        val mediaPreProcessor = FakeMediaPreProcessor()
        val presenter = createAttachmentsPreviewPresenter(
            mediaPreProcessor = mediaPreProcessor,
            temporaryUriDeleter = FakeTemporaryUriDeleter(deleteCallback),
            onDoneListener = { onDoneListener() },
        )
        presenter.test {
            val initialState = awaitItem()
            assertThat(initialState.sendActionState).isEqualTo(SendActionState.Idle)
            initialState.eventSink(AttachmentsPreviewEvent.CancelAndDismiss)
            consumeItemsUntilPredicate { it.sendActionState == SendActionState.Done }
            deleteCallback.assertions().isCalledOnce()
            onDoneListener.assertions().isCalledOnce()
            assertThat(mediaPreProcessor.cleanUpCallCount).isEqualTo(0)
        }
    }

    @Test
    fun `present - send image with caption success scenario`() = runTest {
        val sendImageResult =
            lambdaRecorder { _: File, _: File?, _: ImageInfo, _: String?, _: String?, _: EventId? ->
                Result.success(FakeMediaUploadHandler())
            }
        val mediaPreProcessor = FakeMediaPreProcessor().apply {
            givenImageResult()
        }
        val room = FakeJoinedRoom(
            liveTimeline = FakeTimeline().apply {
                sendImageLambda = sendImageResult
            },
        )
        val onDoneListener = lambdaRecorder<Unit> { }
        val presenter = createAttachmentsPreviewPresenter(
            room = room,
            mediaPreProcessor = mediaPreProcessor,
            onDoneListener = { onDoneListener() },
        )
        presenter.test {
            val initialState = awaitItem()
            assertThat(initialState.sendActionState).isEqualTo(SendActionState.Idle)
            initialState.textEditorState.setMarkdown(A_CAPTION)
            initialState.eventSink(AttachmentsPreviewEvent.SendAttachment)
            consumeItemsUntilPredicate {
                it.sendActionState == SendActionState.Sending.Processing(displayProgress = false)
            }
            consumeItemsUntilPredicate { it.sendActionState == SendActionState.Done }
            sendImageResult.assertions().isCalledOnce().with(
                any(),
                any(),
                any(),
                value(A_CAPTION),
                any(),
                any(),
            )
            onDoneListener.assertions().isCalledOnce()
        }
    }

    @Test
    fun `present - file preview renders only the lease projection`() = runTest {
        val source = aLocalMedia(mockMediaUrl, anApkMediaInfo()).copy(
            lifecycleOperationId = "file-preview-operation",
        )
        val preview = source.copy(
            uri = Uri.parse("content://com.yizhe.chat.debug.securemedia/v1/${"a".repeat(64)}"),
            filePreviewLeaseToken = "preview-token",
        )
        val service = RecordingLocalAttachmentPreviewLeaseService(source, preview)

        createAttachmentsPreviewPresenter(
            localMedia = source,
            localAttachmentPreviewLeaseService = service,
        ).test {
            val initial = awaitItem()
            assertThat(initial.filePreviewStates[0]).isEqualTo(
                io.element.android.features.messages.impl.attachments.preview.AttachmentFilePreviewState.Preparing
            )
            assertThat((initial.attachment as Attachment.Media).localMedia.filePreviewLeaseToken).isNull()
            val state = consumeItemsUntilPredicate { item ->
                (item.attachment as Attachment.Media).localMedia.filePreviewLeaseToken == "preview-token"
            }.last()
            assertThat(state.filePreviewStates[0]).isEqualTo(
                io.element.android.features.messages.impl.attachments.preview.AttachmentFilePreviewState.Ready
            )

            assertThat((state.attachment as Attachment.Media).localMedia).isEqualTo(preview)
            assertThat(service.openedSource).isEqualTo(source)
            assertThat(service.closeCalls).isEqualTo(0)
        }
        assertThat(service.closeCalls).isEqualTo(1)
    }

    @Test
    fun `present - burn-after-read image preserves encrypted caption and one-time mode`() = runTest {
        val sendImageResult =
            lambdaRecorder { _: File, _: File?, _: ImageInfo, _: String?, _: String?, _: EventId? ->
                Result.success(FakeMediaUploadHandler())
            }
        val timeline = FakeTimeline().apply {
            sendImageLambda = sendImageResult
        }
        val mediaPreProcessor = FakeMediaPreProcessor().apply {
            givenImageResult()
        }
        val presenter = createAttachmentsPreviewPresenter(
            room = FakeJoinedRoom(liveTimeline = timeline),
            mediaPreProcessor = mediaPreProcessor,
            onDoneListener = {},
            burnAfterRead = true,
        )

        presenter.test {
            val initialState = awaitItem()
            assertThat(initialState.isBurnAfterRead).isTrue()
            initialState.textEditorState.setMarkdown(A_CAPTION)
            initialState.eventSink(AttachmentsPreviewEvent.SendAttachment)
            consumeItemsUntilPredicate { it.sendActionState == SendActionState.Done }

            sendImageResult.assertions().isCalledOnce().with(
                any(),
                any(),
                any(),
                value(A_CAPTION),
                any(),
                any(),
            )
            assertThat(timeline.lastSendImageBurnAfterRead).isTrue()
        }
    }

    @Test
    fun `present - send video with caption success scenario`() = runTest {
        val sendVideoResult =
            lambdaRecorder { _: File, _: File?, _: VideoInfo, _: String?, _: String?, _: EventId? ->
                Result.success(FakeMediaUploadHandler())
            }
        val mediaPreProcessor = FakeMediaPreProcessor().apply {
            givenVideoResult()
        }
        val room = FakeJoinedRoom(
            liveTimeline = FakeTimeline().apply {
                sendVideoLambda = sendVideoResult
            },
        )
        val onDoneListener = lambdaRecorder<Unit> { }
        val presenter = createAttachmentsPreviewPresenter(
            room = room,
            mediaPreProcessor = mediaPreProcessor,
            onDoneListener = { onDoneListener() },
        )
        presenter.test {
            val initialState = awaitItem()
            assertThat(initialState.sendActionState).isEqualTo(SendActionState.Idle)
            initialState.textEditorState.setMarkdown(A_CAPTION)
            initialState.eventSink(AttachmentsPreviewEvent.SendAttachment)
            consumeItemsUntilPredicate {
                it.sendActionState == SendActionState.Sending.Processing(displayProgress = false)
            }
            consumeItemsUntilPredicate { it.sendActionState == SendActionState.Done }
            sendVideoResult.assertions().isCalledOnce().with(
                any(),
                any(),
                any(),
                value(A_CAPTION),
                any(),
                any(),
            )
            onDoneListener.assertions().isCalledOnce()
        }
    }

    @Test
    fun `present - burn-after-read video preserves encrypted caption and one-time mode`() = runTest {
        val sendVideoResult =
            lambdaRecorder { _: File, _: File?, _: VideoInfo, _: String?, _: String?, _: EventId? ->
                Result.success(FakeMediaUploadHandler())
            }
        val timeline = FakeTimeline().apply {
            sendVideoLambda = sendVideoResult
        }
        val mediaPreProcessor = FakeMediaPreProcessor().apply {
            givenVideoResult()
        }
        val presenter = createAttachmentsPreviewPresenter(
            room = FakeJoinedRoom(liveTimeline = timeline),
            mediaPreProcessor = mediaPreProcessor,
            onDoneListener = {},
            burnAfterRead = true,
        )

        presenter.test {
            val initialState = awaitItem()
            assertThat(initialState.isBurnAfterRead).isTrue()
            initialState.textEditorState.setMarkdown(A_CAPTION)
            initialState.eventSink(AttachmentsPreviewEvent.SendAttachment)
            consumeItemsUntilPredicate { it.sendActionState == SendActionState.Done }

            sendVideoResult.assertions().isCalledOnce().with(
                any(),
                any(),
                any(),
                value(A_CAPTION),
                any(),
                any(),
            )
            assertThat(timeline.lastSendVideoBurnAfterRead).isTrue()
        }
    }

    @Test
    fun `audio metadata without user caption has its own lifecycle before upload`() = runTest {
        val coordinator = FakeAppOperationalStateCoordinator()
        var sent = false
        val timeline = object : Timeline by FakeTimeline() {
            override suspend fun sendAudio(
                file: File,
                audioInfo: AudioInfo,
                caption: String?,
                formattedCaption: String?,
                inReplyToEventId: EventId?,
                burnAfterRead: Boolean,
            ): Result<MediaUploadHandler> {
                val owner = requireNotNull(currentCoroutineContext()[
                    io.element.android.libraries.matrix.api.media.MediaCaptionOperationContext
                ])
                val upload = requireNotNull(
                    io.element.android.security.dataprotection.api.currentLifecycleOperationContext()
                )
                assertThat(caption).isNull()
                assertThat(upload.operationId).isEqualTo(owner.uploadOperationId)
                assertThat(coordinator.startedRequests.last().operationId).isEqualTo(owner.captionOperationId)
                assertThat(coordinator.startedRequests.last().lifecycleId).isEqualTo(FullAppLifecycleId.LC_15)
                assertThat(owner.roomIdHash).isEqualTo("c".repeat(64))
                sent = true
                return Result.success(FakeMediaUploadHandler())
            }
        }
        createAttachmentsPreviewPresenter(
            localMedia = aLocalMedia(mockMediaUrl, anAudioMediaInfo()).copy(lifecycleRoomIdHash = "c".repeat(64)),
            room = FakeJoinedRoom(liveTimeline = timeline),
            mediaPreProcessor = FakeMediaPreProcessor().apply { givenAudioResult() },
            appOperationalStateCoordinator = coordinator,
            onDoneListener = {},
        ).test {
            val initial = awaitItem()
            initial.eventSink(AttachmentsPreviewEvent.SendAttachment)
            consumeItemsUntilPredicate { it.sendActionState == SendActionState.Done }
            assertThat(sent).isTrue()
            val caption = coordinator.startedRequests.single { it.trigger == "media_caption" }
            assertThat(coordinator.clientReceipts.filter { it.operationId == caption.operationId }.map { it.component })
                .containsExactly(LifecycleComponent.ANDROID_FRONTEND)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - send audio with caption success scenario`() = runTest {
        val sendAudioResult =
            lambdaRecorder<File, AudioInfo, String?, String?, EventId?, Result<FakeMediaUploadHandler>> { _, _, _, _, _ ->
                Result.success(FakeMediaUploadHandler())
            }
        val mediaPreProcessor = FakeMediaPreProcessor().apply {
            givenAudioResult()
        }
        val room = FakeJoinedRoom(
            liveTimeline = FakeTimeline().apply {
                sendAudioLambda = sendAudioResult
            },
        )
        val onDoneListener = lambdaRecorder<Unit> { }
        val presenter = createAttachmentsPreviewPresenter(
            localMedia = aLocalMedia(mockMediaUrl, anAudioMediaInfo()).copy(lifecycleRoomIdHash = "c".repeat(64)),
            room = room,
            mediaPreProcessor = mediaPreProcessor,
            onDoneListener = { onDoneListener() },
        )
        presenter.test {
            val initialState = awaitItem()
            assertThat(initialState.sendActionState).isEqualTo(SendActionState.Idle)
            initialState.textEditorState.setMarkdown(A_CAPTION)
            initialState.eventSink(AttachmentsPreviewEvent.SendAttachment)
            consumeItemsUntilPredicate {
                it.sendActionState == SendActionState.Sending.Processing(displayProgress = false)
            }
            assertThat(awaitItem().sendActionState).isInstanceOf(SendActionState.Sending.ReadyToUpload::class.java)
            assertThat(awaitItem().sendActionState).isInstanceOf(SendActionState.Sending.Uploading::class.java)
            consumeItemsUntilPredicate { it.sendActionState == SendActionState.Done }
            sendAudioResult.assertions().isCalledOnce().with(
                any(),
                any(),
                value(A_CAPTION),
                any(),
                any(),
            )
            onDoneListener.assertions().isCalledOnce()
        }
    }

    @Test
    fun `present - send media failure scenario`() = runTest {
        val failure = MediaPreProcessor.Failure(null)
        val sendFileResult =
            lambdaRecorder<File, FileInfo, String?, String?, EventId?, Result<FakeMediaUploadHandler>> { _, _, _, _, _ ->
                Result.failure(failure)
            }
        val onDoneListenerResult = lambdaRecorder<Unit> {}
        val room = FakeJoinedRoom(
            liveTimeline = FakeTimeline().apply {
                sendFileLambda = sendFileResult
            },
        )
        val presenter = createAttachmentsPreviewPresenter(
            localMedia = aLocalMedia(mockMediaUrl, anApkMediaInfo()).copy(lifecycleRoomIdHash = "c".repeat(64)),
            room = room,
            onDoneListener = onDoneListenerResult,
        )
        presenter.test {
            val initialState = awaitItem()
            assertThat(initialState.sendActionState).isEqualTo(SendActionState.Idle)
            initialState.eventSink(AttachmentsPreviewEvent.SendAttachment)
            consumeItemsUntilPredicate {
                it.sendActionState == SendActionState.Sending.Processing(displayProgress = false)
            }
            assertThat(awaitItem().sendActionState).isEqualTo(SendActionState.Sending.ReadyToUpload(mediaUploadInfo))
            assertThat(awaitItem().sendActionState).isEqualTo(SendActionState.Sending.Uploading(mediaUploadInfo))

            val failureState = consumeItemsUntilPredicate { it.sendActionState is SendActionState.Failure }.last()
            assertThat(failureState.sendActionState).isEqualTo(SendActionState.Failure(failure, mediaUploadInfo))
            // A failed protected send must keep the preview visible so the user can retry.
            onDoneListenerResult.assertions().isNeverCalled()
            sendFileResult.assertions().isCalledOnce()
            failureState.eventSink(AttachmentsPreviewEvent.CancelAndClearSendState)
            val clearedState = awaitLastSequentialItem()
            assertThat(clearedState.sendActionState).isEqualTo(SendActionState.Sending.ReadyToUpload(mediaUploadInfo))
        }
    }

    @Test
    fun `present - image send returns to the timeline in the same event turn`() = runTest {
        val onDoneListenerResult = lambdaRecorder<Unit> {}
        val presenter = createAttachmentsPreviewPresenter(
            localMedia = aLocalMedia(mockMediaUrl, anImageMediaInfo()).copy(lifecycleRoomIdHash = "c".repeat(64)),
            onDoneListener = onDoneListenerResult,
        )

        presenter.test {
            val initialState = awaitItem()
            initialState.eventSink(AttachmentsPreviewEvent.SendAttachment)

            // Do not advance the test dispatcher here: navigation and the optimistic media row must
            // be published by the click itself, before preprocessing, encryption or upload starts.
            onDoneListenerResult.assertions().isCalledOnce()
            assertThat(initialState.displaySendProgressInline).isTrue()
            consumeItemsUntilPredicate { it.sendActionState == SendActionState.Done }
        }
    }

    @Test
    fun `present - dismissing the progress dialog stops media upload`() = runTest {
        val onDoneListenerResult = lambdaRecorder<Unit> {}
        val presenter = createAttachmentsPreviewPresenter(
            localMedia = aLocalMedia(mockMediaUrl, anApkMediaInfo()).copy(lifecycleRoomIdHash = "c".repeat(64)),
            room = FakeJoinedRoom(
                liveTimeline = FakeTimeline().apply {
                    sendFileLambda = { _, _, _, _, _ ->
                        Result.success(FakeMediaUploadHandler())
                    }
                }
            ),
            onDoneListener = onDoneListenerResult,
        )
        presenter.test {
            val initialState = awaitItem()
            assertThat(initialState.sendActionState).isEqualTo(SendActionState.Idle)
            initialState.eventSink(AttachmentsPreviewEvent.SendAttachment)
            consumeItemsUntilPredicate {
                it.sendActionState == SendActionState.Sending.Processing(displayProgress = false)
            }
            assertThat(awaitItem().sendActionState).isEqualTo(SendActionState.Sending.ReadyToUpload(mediaUploadInfo))
            assertThat(awaitItem().sendActionState).isEqualTo(SendActionState.Sending.Uploading(mediaUploadInfo))
            initialState.eventSink(AttachmentsPreviewEvent.CancelAndClearSendState)
            assertThat(awaitItem().sendActionState).isEqualTo(SendActionState.Sending.ReadyToUpload(mediaUploadInfo))
            // The sending is cancelled and the state is kept at ReadyToUpload
            ensureAllEventsConsumed()

            onDoneListenerResult.assertions().isNeverCalled()
        }
    }

    @Test
    fun `present - file too large will display error`() = runTest {
        val onDoneListenerResult = lambdaRecorder<Unit> {}

        val localMedia = aLocalMedia(uri = Uri.EMPTY, mediaInfo = anApkMediaInfo())
        val maxUploadSize = 999L // Set a max upload size smaller than the file size

        val presenter = createAttachmentsPreviewPresenter(
            localMedia = localMedia,
            room = FakeJoinedRoom(
                liveTimeline = FakeTimeline().apply {
                    sendFileLambda = { _, _, _, _, _ ->
                        Result.success(FakeMediaUploadHandler())
                    }
                }
            ),
            onDoneListener = onDoneListenerResult,
            mediaOptimizationSelectorPresenterFactory = FakeMediaOptimizationSelectorPresenterFactory {
                MediaOptimizationSelectorState(
                    // Set a max upload size smaller than the file size
                    maxUploadSize = AsyncData.Success(maxUploadSize),
                    videoSizeEstimations = AsyncData.Uninitialized,
                    isImageOptimizationEnabled = null,
                    selectedVideoPreset = null,
                    displayMediaSelectorViews = false,
                    displayVideoPresetSelectorDialog = false,
                    eventSink = {},
                )
            }
        )

        presenter.test {
            assertThat(localMedia.info.fileSize).isGreaterThan(maxUploadSize)

            consumeItemsUntilPredicate { it.mediaOptimizationSelectorState.maxUploadSize.isSuccess() }

            assertThat(awaitItem().displayFileTooLargeError).isTrue()
        }
    }

    @Test
    fun `present - video size estimations too large will display error`() = runTest {
        val onDoneListenerResult = lambdaRecorder<Unit> {}

        val localMedia = aLocalMedia(uri = Uri.EMPTY, mediaInfo = aVideoMediaInfo())

        val presenter = createAttachmentsPreviewPresenter(
            localMedia = localMedia,
            room = FakeJoinedRoom(
                liveTimeline = FakeTimeline().apply {
                    sendFileLambda = { _, _, _, _, _ ->
                        Result.success(FakeMediaUploadHandler())
                    }
                }
            ),
            onDoneListener = onDoneListenerResult,
            mediaOptimizationSelectorPresenterFactory = FakeMediaOptimizationSelectorPresenterFactory {
                MediaOptimizationSelectorState(
                    // Set a max upload size smaller than the file size
                    maxUploadSize = AsyncData.Success(Long.MAX_VALUE),
                    videoSizeEstimations = AsyncData.Success(
                        persistentListOf(
                            VideoUploadEstimation(
                                preset = VideoCompressionPreset.LOW,
                                // The important field is canUpload, it will normally be based on the sizeInBytes
                                canUpload = false,
                                sizeInBytes = 0L,
                            ),
                            VideoUploadEstimation(
                                preset = VideoCompressionPreset.STANDARD,
                                canUpload = false,
                                sizeInBytes = 0L,
                            ),
                            VideoUploadEstimation(
                                preset = VideoCompressionPreset.HIGH,
                                canUpload = false,
                                sizeInBytes = 0L,
                            ),
                        )
                    ),
                    isImageOptimizationEnabled = null,
                    selectedVideoPreset = null,
                    displayMediaSelectorViews = false,
                    displayVideoPresetSelectorDialog = false,
                    eventSink = {},
                )
            }
        )

        presenter.test {
            consumeItemsUntilPredicate {
                it.mediaOptimizationSelectorState.maxUploadSize.isSuccess() &&
                    it.mediaOptimizationSelectorState.videoSizeEstimations.dataOrNull()?.isNotEmpty() == true
            }

            assertThat(awaitItem().displayFileTooLargeError).isTrue()
        }
    }

    @Test
    fun `present - applying image edits updates the attachment`() = runTest {
        val editedUri = Uri.parse("file:///tmp/edited.jpeg")
        val presenter = createAttachmentsPreviewPresenter(
            displayMediaQualitySelectorViews = true,
            attachmentImageEditor = FakeAttachmentImageEditor {
                Result.success(
                    EditedLocalMedia(
                        localMedia = aLocalMedia(uri = editedUri),
                        file = File("/tmp/edited.jpeg"),
                    )
                )
            }
        )

        presenter.test {
            val initialState = awaitItem()
            initialState.eventSink(AttachmentsPreviewEvent.OpenImageEditor)
            val editorState = awaitItem()
            assertThat(editorState.imageEditorState).isNotNull()

            editorState.eventSink(AttachmentsPreviewEvent.RotateImageToTheLeft)
            val rotatedState = awaitItem()
            assertThat(rotatedState.imageEditorState?.edits?.rotationQuarterTurns).isEqualTo(3)

            rotatedState.eventSink(AttachmentsPreviewEvent.ApplyImageEdits)
            assertThat(awaitItem().isApplyingImageEdits).isTrue()

            val appliedState = awaitItem()
            assertThat((appliedState.attachment as Attachment.Media).localMedia.uri).isEqualTo(editedUri)
            assertThat(appliedState.imageEditorState).isNull()
            assertThat(appliedState.isApplyingImageEdits).isFalse()
        }
    }

    @Test
    fun `present - reopening image editor keeps original media and previous edits`() = runTest {
        val editedUri = Uri.parse("file:///tmp/edited.jpeg")
        val originalLocalMedia = aLocalMedia(uri = mockMediaUrl)
        val cropRect = NormalizedCropRect(
            left = 0.2f,
            top = 0.15f,
            right = 0.85f,
            bottom = 0.9f,
        )
        val presenter = createAttachmentsPreviewPresenter(
            localMedia = originalLocalMedia,
            displayMediaQualitySelectorViews = true,
            attachmentImageEditor = FakeAttachmentImageEditor {
                Result.success(
                    EditedLocalMedia(
                        localMedia = aLocalMedia(uri = editedUri),
                        file = File("/tmp/edited.jpeg"),
                    )
                )
            }
        )

        presenter.test {
            val initialState = awaitItem()
            initialState.eventSink(AttachmentsPreviewEvent.OpenImageEditor)
            val editorState = consumeItemsUntilPredicate { it.imageEditorState != null }.last()

            editorState.eventSink(AttachmentsPreviewEvent.UpdateImageCropRect(cropRect))
            val croppedState = awaitItem()
            croppedState.eventSink(AttachmentsPreviewEvent.RotateImageToTheLeft)
            val rotatedState = awaitItem()
            rotatedState.eventSink(AttachmentsPreviewEvent.FlipImageHorizontally)
            val flippedHorizontallyState = awaitItem()
            flippedHorizontallyState.eventSink(AttachmentsPreviewEvent.FlipImageVertically)
            val flippedState = awaitItem()
            flippedState.eventSink(AttachmentsPreviewEvent.ApplyImageEdits)

            val appliedState = consumeItemsUntilPredicate { !it.isApplyingImageEdits && it.imageEditorState == null }.last()
            assertThat((appliedState.attachment as Attachment.Media).localMedia.uri).isEqualTo(editedUri)

            appliedState.eventSink(AttachmentsPreviewEvent.OpenImageEditor)
            val reopenedState = consumeItemsUntilPredicate { it.imageEditorState != null }.last()
            assertThat(reopenedState.imageEditorState!!.localMedia.uri).isEqualTo(originalLocalMedia.uri)
            val rotatedCropRect = NormalizedCropRect(
                left = cropRect.top,
                top = 1f - cropRect.right,
                right = cropRect.bottom,
                bottom = 1f - cropRect.left,
            )
            val flippedCropRect = NormalizedCropRect(
                left = 1f - rotatedCropRect.right,
                top = 1f - rotatedCropRect.bottom,
                right = 1f - rotatedCropRect.left,
                bottom = 1f - rotatedCropRect.top,
            )
            reopenedState.imageEditorState.edits.cropRect.assertIsSimilarTo(flippedCropRect)
            assertThat(reopenedState.imageEditorState.edits.rotationQuarterTurns).isEqualTo(3)
            assertThat(reopenedState.imageEditorState.edits.rotationDegrees).isEqualTo(270)
            assertThat(reopenedState.imageEditorState.edits.isFlippedHorizontally).isTrue()
            assertThat(reopenedState.imageEditorState.edits.isFlippedVertically).isTrue()
        }
    }

    @Test
    fun `present - image editor flip events update edits`() = runTest {
        val presenter = createAttachmentsPreviewPresenter(displayMediaQualitySelectorViews = true)

        presenter.test {
            val initialState = awaitItem()
            initialState.eventSink(AttachmentsPreviewEvent.OpenImageEditor)
            val editorState = consumeItemsUntilPredicate { it.imageEditorState != null }.last()

            editorState.eventSink(AttachmentsPreviewEvent.FlipImageHorizontally)
            val flippedHorizontallyState = awaitItem()
            assertThat(flippedHorizontallyState.imageEditorState?.edits?.isFlippedHorizontally).isTrue()

            flippedHorizontallyState.eventSink(AttachmentsPreviewEvent.FlipImageVertically)
            val flippedState = awaitItem()
            assertThat(flippedState.imageEditorState?.edits?.isFlippedVertically).isTrue()
        }
    }

    fun `present - sendAsFile attachment is pre-processed without image compression`() = runTest {
        // Even though the user has enabled "Optimize media quality" globally, picking the file
        // through the Files picker (sendAsFile = true) must skip compression. Regression test
        // for https://github.com/element-hq/element-x-android/issues/6365
        val mediaPreProcessor = FakeMediaPreProcessor()
        val presenter = createAttachmentsPreviewPresenter(
            localMedia = aLocalMedia(mockMediaUrl, anImageMediaInfo()).copy(lifecycleRoomIdHash = "c".repeat(64)),
            sendAsFile = true,
            mediaPreProcessor = mediaPreProcessor,
            // Selector views are hidden in the sendAsFile flow, which triggers the auto pre-process path.
            displayMediaQualitySelectorViews = false,
            mediaOptimizationConfigProvider = FakeMediaOptimizationConfigProvider(
                config = MediaOptimizationConfig(
                    compressImages = true,
                    videoCompressionPreset = VideoCompressionPreset.STANDARD,
                )
            ),
        )

        presenter.test {
            consumeItemsUntilPredicate { mediaPreProcessor.processCallCount > 0 }
            assertThat(mediaPreProcessor.lastMediaOptimizationConfig).isEqualTo(
                MediaOptimizationConfig(
                    compressImages = false,
                    videoCompressionPreset = VideoCompressionPreset.HIGH,
                )
            )
        }
    }

    @Test
    fun `present - sending edited media keeps the edited file available until upload starts`() = runTest {
        val editedFile = createTempFile(suffix = ".jpeg").toFile().apply {
            writeText("edited-media")
        }
        val sendFileResult =
            lambdaRecorder<File, FileInfo, String?, String?, EventId?, Result<FakeMediaUploadHandler>> { file, _, _, _, _ ->
                assertThat(file.exists()).isTrue()
                Result.success(FakeMediaUploadHandler())
            }
        val room = FakeJoinedRoom(
            liveTimeline = FakeTimeline().apply {
                sendFileLambda = sendFileResult
            },
        )
        val presenter = createAttachmentsPreviewPresenter(
            room = room,
            displayMediaQualitySelectorViews = true,
            onDoneListener = OnDoneListener {},
            mediaPreProcessor = FakeMediaPreProcessor().apply {
                givenResult(
                    Result.success(
                        MediaUploadInfo.AnyFile(
                            file = editedFile,
                            fileInfo = FileInfo(
                                mimetype = MimeTypes.Jpeg,
                                size = editedFile.length(),
                                thumbnailInfo = null,
                                thumbnailSource = null,
                            )
                        )
                    )
                )
            },
            attachmentImageEditor = FakeAttachmentImageEditor {
                Result.success(
                    EditedLocalMedia(
                        localMedia = aLocalMedia(uri = editedFile.toUri()),
                        file = editedFile,
                    )
                )
            }
        )

        presenter.test {
            val initialState = awaitItem()
            initialState.eventSink(AttachmentsPreviewEvent.OpenImageEditor)
            val editorState = consumeItemsUntilPredicate { it.imageEditorState != null }.last()

            editorState.eventSink(AttachmentsPreviewEvent.ApplyImageEdits)
            val appliedState = consumeItemsUntilPredicate { !it.isApplyingImageEdits && it.imageEditorState == null }.last()

            appliedState.eventSink(AttachmentsPreviewEvent.SendAttachment)
            consumeItemsUntilPredicate { it.sendActionState == SendActionState.Done }

            sendFileResult.assertions().isCalledOnce()
        }
    }

    @Test
    fun `present - image with generic mime type and png extension is still editable`() = runTest {
        val localMedia = aLocalMedia(
            uri = mockMediaUrl,
            mediaInfo = anImageMediaInfo().copy(
                mimeType = MimeTypes.OctetStream,
                filename = "Screenshot.png",
                fileExtension = "png",
            ),
        )
        val presenter = createAttachmentsPreviewPresenter(localMedia = localMedia)

        presenter.test {
            val initialState = awaitItem()
            assertThat(initialState.canEditImage).isTrue()

            initialState.eventSink(AttachmentsPreviewEvent.OpenImageEditor)
            val editorState = consumeItemsUntilPredicate { it.imageEditorState != null }.last()
            assertThat(editorState.imageEditorState).isNotNull()
        }
    }

    @Test
    fun `present - image can still be edited when editor can decode it despite generic media info`() = runTest {
        val localMedia = aLocalMedia(
            uri = mockMediaUrl,
            mediaInfo = anImageMediaInfo().copy(
                mimeType = MimeTypes.OctetStream,
                filename = "",
                fileExtension = "",
            ),
        )
        val presenter = createAttachmentsPreviewPresenter(
            localMedia = localMedia,
            attachmentImageEditor = FakeAttachmentImageEditor(
                canEditResult = true,
            ) {
                Result.success(
                    EditedLocalMedia(
                        localMedia = localMedia.copy(uri = Uri.parse("file:///tmp/decoded.jpeg")),
                        file = File("/tmp/decoded.jpeg"),
                    )
                )
            }
        )

        presenter.test {
            val initialState = consumeItemsUntilPredicate { it.canEditImage }.last()
            assertThat(initialState.canEditImage).isTrue()

            initialState.eventSink(AttachmentsPreviewEvent.OpenImageEditor)
            val editorState = consumeItemsUntilPredicate { it.imageEditorState != null }.last()
            assertThat(editorState.imageEditorState).isNotNull()
        }
    }

    @Test
    fun `present - sendAsFile video is pre-processed with best fitting preset`() = runTest {
        val mediaPreProcessor = FakeMediaPreProcessor()
        val presenter = createAttachmentsPreviewPresenter(
            localMedia = aLocalMedia(mockMediaUrl, aVideoMediaInfo()),
            sendAsFile = true,
            mediaPreProcessor = mediaPreProcessor,
            // Selector views are hidden in the sendAsFile flow, which triggers the auto pre-process path.
            displayMediaQualitySelectorViews = false,
            mediaOptimizationSelectorPresenterFactory = FakeMediaOptimizationSelectorPresenterFactory {
                MediaOptimizationSelectorState(
                    maxUploadSize = AsyncData.Success(250_000_000L),
                    videoSizeEstimations = AsyncData.Success(
                        persistentListOf(
                            VideoUploadEstimation(VideoCompressionPreset.HIGH, sizeInBytes = 513_216_000L, canUpload = false),
                            VideoUploadEstimation(VideoCompressionPreset.STANDARD, sizeInBytes = 228_096_000L, canUpload = true),
                            VideoUploadEstimation(VideoCompressionPreset.LOW, sizeInBytes = 57_024_000L, canUpload = true),
                        )
                    ),
                    isImageOptimizationEnabled = false,
                    selectedVideoPreset = VideoCompressionPreset.STANDARD,
                    displayMediaSelectorViews = false,
                    displayVideoPresetSelectorDialog = false,
                    eventSink = {},
                )
            },
            mediaOptimizationConfigProvider = FakeMediaOptimizationConfigProvider(
                config = MediaOptimizationConfig(
                    compressImages = true,
                    videoCompressionPreset = VideoCompressionPreset.LOW,
                )
            ),
        )

        presenter.test {
            consumeItemsUntilPredicate { mediaPreProcessor.processCallCount > 0 }
            assertThat(mediaPreProcessor.lastMediaOptimizationConfig).isEqualTo(
                MediaOptimizationConfig(
                    compressImages = false,
                    videoCompressionPreset = VideoCompressionPreset.STANDARD,
                )
            )
        }
    }

    @Test
    fun `multiple media preserve edit results when switching and returning to gallery selection`() = runTest {
        val first = aMediaAttachment(aLocalMedia(Uri.parse("content://test/first")), sendAsFile = false)
        val second = aMediaAttachment(aLocalMedia(Uri.parse("content://test/second")), sendAsFile = false)
        val editor = object : AttachmentImageEditor {
            override suspend fun canEdit(localMedia: LocalMedia) = true
            override suspend fun exportEdits(localMedia: LocalMedia, edits: AttachmentImageEdits) = Result.success(
                EditedLocalMedia(localMedia.copy(uri = Uri.parse("content://test/edited")))
            )
        }
        createAttachmentsPreviewPresenter(attachmentsOverride = listOf(first, second), attachmentImageEditor = editor).test {
            var state = awaitItem()
            state.eventSink(AttachmentsPreviewEvent.FocusAttachment(1))
            state = consumeItemsUntilPredicate { it.focusedIndex == 1 }.last()
            state.eventSink(AttachmentsPreviewEvent.OpenImageEditor)
            state = consumeItemsUntilPredicate { it.imageEditorState != null }.last()
            state.eventSink(AttachmentsPreviewEvent.RotateImageToTheLeft)
            state = consumeItemsUntilPredicate { it.imageEditorState?.edits?.rotationQuarterTurns == 3 }.last()
            state.eventSink(AttachmentsPreviewEvent.ApplyImageEdits)
            state = consumeItemsUntilPredicate { (it.attachment as Attachment.Media).localMedia.uri.toString() == "content://test/edited" }.last()
            assertThat((state.attachments[0] as Attachment.Media).localMedia.uri).isEqualTo(first.localMedia.uri)
            state.eventSink(AttachmentsPreviewEvent.UpdateGallerySelection(listOf(first, second), originalPhotos = true, send = false))
            state = consumeItemsUntilPredicate { it.originalPhotos }.last()
            state.eventSink(AttachmentsPreviewEvent.FocusAttachment(0))
            state = consumeItemsUntilPredicate { it.focusedIndex == 0 }.last()
            state.eventSink(AttachmentsPreviewEvent.FocusAttachment(1))
            state = consumeItemsUntilPredicate { it.focusedIndex == 1 }.last()
            assertThat((state.attachment as Attachment.Media).localMedia.uri.toString()).isEqualTo("content://test/edited")
            assertThat(state.selectedIndices).containsExactly(0, 1).inOrder()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `delayed pager callback cannot overwrite a different focused media item`() = runTest {
        val photo = aMediaAttachment(aLocalMedia(Uri.parse("content://test/photo")), sendAsFile = false)
        val video = aMediaAttachment(aLocalMedia(Uri.parse("content://test/video"), aVideoMediaInfo()), sendAsFile = false)
        createAttachmentsPreviewPresenter(attachmentsOverride = listOf(photo, video)).test {
            var state = awaitItem()
            state.eventSink(AttachmentsPreviewEvent.FocusAttachment(1))
            state = consumeItemsUntilPredicate { it.focusedIndex == 1 }.last()
            val delayedVideoSink = state.eventSink
            state.eventSink(AttachmentsPreviewEvent.FocusAttachment(0))
            state = consumeItemsUntilPredicate { it.focusedIndex == 0 }.last()
            delayedVideoSink(AttachmentsPreviewEvent.FocusAttachment(0))
            delayedVideoSink(AttachmentsPreviewEvent.SetOriginalPhotos(true))
            state = consumeItemsUntilPredicate { it.originalPhotos }.last()
            assertThat(state.attachments).containsExactly(photo, video).inOrder()
            assertThat(state.attachment).isEqualTo(photo)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `deselect and reselect reorder the outgoing selection without losing source media`() = runTest {
        val items = (0..2).map { aMediaAttachment(aLocalMedia(Uri.parse("content://test/$it"))) }
        createAttachmentsPreviewPresenter(attachmentsOverride = items).test {
            var state = awaitItem()
            state.eventSink(AttachmentsPreviewEvent.ToggleAttachment(1))
            state = consumeItemsUntilPredicate { it.selectedIndices.size == 2 }.last()
            state.eventSink(AttachmentsPreviewEvent.ToggleAttachment(1))
            state = consumeItemsUntilPredicate { it.selectedIndices.size == 3 }.last()
            assertThat(state.selectedIndices).containsExactly(0, 2, 1).inOrder()
            assertThat(state.sourceAttachments).containsExactlyElementsIn(items).inOrder()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `video send exports the selected clip mute and quality choices`() = runTest {
        val processor = FakeMediaPreProcessor().apply { givenVideoResult() }
        val video = aMediaAttachment(aLocalMedia(Uri.parse("content://test/video"), aVideoMediaInfo()), sendAsFile = false)
        val second = aMediaAttachment(aLocalMedia(Uri.parse("content://test/second")), sendAsFile = false)
        createAttachmentsPreviewPresenter(attachmentsOverride = listOf(video, second), mediaPreProcessor = processor, onDoneListener = OnDoneListener {}).test {
            var state = awaitItem()
            state.eventSink(AttachmentsPreviewEvent.ApplyVideoEdits(1250, 5500, true))
            state = consumeItemsUntilPredicate { (it.attachment as Attachment.Media).muteAudio }.last()
            state.eventSink(AttachmentsPreviewEvent.SetVideoQuality(VideoCompressionPreset.HIGH))
            state = consumeItemsUntilPredicate { it.videoQuality == VideoCompressionPreset.HIGH }.last()
            state.eventSink(AttachmentsPreviewEvent.SendAttachment)
            consumeItemsUntilPredicate { processor.processCallCount > 0 }
            assertThat(processor.lastMediaOptimizationConfig).isEqualTo(MediaOptimizationConfig(true, VideoCompressionPreset.HIGH, 1250, 5500, true))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `retry after preprocessing failure starts another preparation instead of waiting forever`() = runTest {
        val processor = FakeMediaPreProcessor().apply { givenResult(Result.failure(Exception("test failure"))) }
        createAttachmentsPreviewPresenter(mediaPreProcessor = processor, onDoneListener = OnDoneListener {}).test {
            var state = consumeItemsUntilPredicate { it.sendActionState is SendActionState.Failure }.last()
            assertThat(processor.processCallCount).isEqualTo(1)
            state.eventSink(AttachmentsPreviewEvent.RetryAttachment)
            state = consumeItemsUntilPredicate { it.sendActionState is SendActionState.Failure }.last()
            assertThat(processor.processCallCount).isEqualTo(2)
            assertThat(state.sendActionState).isInstanceOf(SendActionState.Failure::class.java)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `editing an unselected gallery image preserves selection and focuses the requested image`() = runTest {
        val selected = aMediaAttachment(aLocalMedia(Uri.parse("content://test/selected")))
        val preview = aMediaAttachment(aLocalMedia(Uri.parse("content://test/preview"))).copy(initiallySelected = false, focused = true)
        createAttachmentsPreviewPresenter(attachmentsOverride = listOf(selected, preview)).test {
            var state = awaitItem()
            assertThat(state.focusedIndex).isEqualTo(1)
            assertThat(state.selectedIndices).containsExactly(0)
            state.eventSink(AttachmentsPreviewEvent.OpenImageEditor)
            state = consumeItemsUntilPredicate { it.imageEditorState != null }.last()
            assertThat(state.imageEditorState!!.localMedia.uri).isEqualTo(preview.localMedia.uri)
            state.eventSink(AttachmentsPreviewEvent.CloseImageEditor)
            state = consumeItemsUntilPredicate { it.imageEditorState == null }.last()
            assertThat(state.selectedIndices).containsExactly(0)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `gallery preview selection does not silently select the image being edited`() = runTest {
        val first = aMediaAttachment(aLocalMedia(Uri.parse("content://test/first")))
        val preview = aMediaAttachment(aLocalMedia(Uri.parse("content://test/preview"))).copy(initiallySelected = false, focused = true, openEditor = true)
        createAttachmentsPreviewPresenter(attachmentsOverride = listOf(first)).test {
            var state = awaitItem()
            state.eventSink(AttachmentsPreviewEvent.UpdateGallerySelection(listOf(first, preview), originalPhotos = false, send = false))
            state = consumeItemsUntilPredicate { it.imageEditorState != null }.last()
            assertThat(state.focusedIndex).isEqualTo(1)
            assertThat(state.selectedIndices).containsExactly(0)
            assertThat(state.imageEditorState!!.localMedia.uri).isEqualTo(preview.localMedia.uri)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `exit selection invokes dismissal without a send or draft discard`() = runTest {
        var dismissals = 0
        createAttachmentsPreviewPresenter(onDoneListener = OnDoneListener { dismissals++ }).test {
            val state = awaitItem()
            state.eventSink(AttachmentsPreviewEvent.DismissPreservingDraft)
            advanceUntilIdle()
            assertThat(dismissals).isEqualTo(1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `cancel waits for draft clear before allowing room reentry`() = runTest {
        val clearEntered = CompletableDeferred<Unit>()
        val allowClear = CompletableDeferred<Unit>()
        var dismissals = 0
        val store = mockk<MediaSelectionDraftStore>(relaxed = true)
        coEvery { store.load(any()) } returns null
        coEvery { store.clear(any()) } coAnswers {
            clearEntered.complete(Unit)
            allowClear.await()
            true
        }
        val deleted = mutableListOf<Uri?>()
        createAttachmentsPreviewPresenter(
            mediaDraftStore = store,
            temporaryUriDeleter = FakeTemporaryUriDeleter { deleted += it },
            onDoneListener = OnDoneListener { dismissals++ },
        ).test {
            val state = consumeItemsUntilPredicate { it.draftReady }.last()
            state.eventSink(AttachmentsPreviewEvent.CancelAndDismiss)
            advanceUntilIdle()
            assertThat(clearEntered.isCompleted).isTrue()
            assertThat(dismissals).isEqualTo(0)
            allowClear.complete(Unit)
            advanceUntilIdle()
            assertThat(dismissals).isEqualTo(1)
            assertThat(deleted).containsExactly(mockMediaUrl)
            coVerify(exactly = 1) { store.clear(any()) }
            cancelAndIgnoreRemainingEvents()
        }
    }


    @Test
    fun `reopened pre KMS failure restores selection and responsive selection control`() = runTest {
        val media = aMediaAttachment(aLocalMedia(mockMediaUrl).copy(lifecycleRoomIdHash = "c".repeat(64)))
        val saved = MediaSelectionDraft(listOf(media), listOf(0), 0, emptyMap(), null, true, VideoCompressionPreset.HIGH,
            emptyMap(), inFlight = listOf(0), submissions = mapOf(0 to MediaSubmissionFact(MediaSubmissionPhase.PRE_SDK))).recoverMediaSubmissions()
        val store = mockk<MediaSelectionDraftStore>(relaxed = true)
        coEvery { store.load(any()) } returns saved
        createAttachmentsPreviewPresenter(attachmentsOverride = listOf(media), mediaDraftStore = store).test {
            var state = consumeItemsUntilPredicate { it.draftReady }.last()
            assertThat(state.failedIndices).containsExactly(0)
            assertThat(state.uncertainIndices).isEmpty()
            assertThat(state.selectedIndices).containsExactly(0)
            state.eventSink(AttachmentsPreviewEvent.ToggleAttachment(0))
            state = consumeItemsUntilPredicate { it.selectedIndices.isEmpty() }.last()
            state.eventSink(AttachmentsPreviewEvent.ToggleAttachment(0))
            state = consumeItemsUntilPredicate { it.selectedIndices == listOf(0) }.last()
            assertThat(state.originalPhotos).isTrue()
            assertThat(state.sourceAttachments.single()).isEqualTo(media)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `legacy unknown stays protected and confirmation reports pending without a new send`() = runTest {
        val media = aMediaAttachment(aLocalMedia(mockMediaUrl).copy(lifecycleRoomIdHash = "c".repeat(64)))
        val saved = MediaSelectionDraft(listOf(media), listOf(0), 0, emptyMap(), null, true, VideoCompressionPreset.HIGH,
            emptyMap(), inFlight = listOf(0))
        val store = mockk<MediaSelectionDraftStore>(relaxed = true)
        coEvery { store.load(any()) } returns saved
        val operations = FakeAppOperationalStateCoordinator()
        createAttachmentsPreviewPresenter(attachmentsOverride = listOf(media), mediaDraftStore = store,
            appOperationalStateCoordinator = operations).test {
            var state = consumeItemsUntilPredicate { it.draftReady }.last()
            assertThat(state.selectedIndices).isEmpty()
            state.eventSink(AttachmentsPreviewEvent.ToggleAttachment(0))
            state.eventSink(AttachmentsPreviewEvent.SendAttachment)
            state.eventSink(AttachmentsPreviewEvent.ConfirmSubmission)
            state = consumeItemsUntilPredicate { it.confirmationStillPending }.last()
            assertThat(state.selectedIndices).isEmpty()
            assertThat(state.uncertainIndices).containsExactly(0)
            assertThat(state.blockedIndices).containsExactly(0)
            coVerify(exactly = 0) { store.clear(any()) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `dismissing an unknown send preserves the original draft instead of clearing it`() = runTest {
        val media = aMediaAttachment(aLocalMedia(mockMediaUrl).copy(lifecycleRoomIdHash = "c".repeat(64)))
        val saved = MediaSelectionDraft(listOf(media), listOf(0), 0, emptyMap(), null, true, VideoCompressionPreset.HIGH,
            emptyMap(), inFlight = listOf(0))
        val store = mockk<MediaSelectionDraftStore>(relaxed = true)
        coEvery { store.load(any()) } returns saved
        val onDoneListener = lambdaRecorder<Unit> { }
        createAttachmentsPreviewPresenter(attachmentsOverride = listOf(media), mediaDraftStore = store,
            onDoneListener = { onDoneListener() }).test {
            val state = consumeItemsUntilPredicate { it.draftReady }.last()
            state.eventSink(AttachmentsPreviewEvent.CancelAndDismiss)
            advanceUntilIdle()
            onDoneListener.assertions().isCalledOnce()
            coVerify(exactly = 0) { store.clear(any()) }
            coVerify { store.save(any(), match { it.inFlight == listOf(0) && it.sources == listOf(media) }) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `legacy without transport reads only its original expired task and remains locked`() = runTest {
        val media = aMediaAttachment(aLocalMedia(mockMediaUrl).copy(lifecycleRoomIdHash = "c".repeat(64)))
        val id = media.localMedia.lifecycleOperationId
        val saved = MediaSelectionDraft(listOf(media), listOf(0), 0, emptyMap(), null, true, VideoCompressionPreset.HIGH,
            emptyMap(), inFlight = listOf(0))
        val store = mockk<MediaSelectionDraftStore>(relaxed = true)
        coEvery { store.load(any()) } returns saved
        val coordinator = mockk<AppOperationalStateCoordinator>(relaxed = true)
        coEvery { coordinator.operation(id) } returns LifecycleOperation(
            operationId = id, lifecycleId = FullAppLifecycleId.LC_16, idempotencyKey = id,
            subjects = LifecycleSubjectHashes(accountIdHash = "a".repeat(64), matrixDeviceIdHash = "b".repeat(64), roomIdHash = "c".repeat(64)),
            status = LifecycleOperationStatus.RUNNING, currentPhase = LifecyclePhase.PROJECTED,
            deadlineEpochMillis = 2000, createdAtEpochMillis = 1000, updatedAtEpochMillis = 1500)
        createAttachmentsPreviewPresenter(attachmentsOverride = listOf(media), mediaDraftStore = store,
            appOperationalStateCoordinator = coordinator).test {
            var state = consumeItemsUntilPredicate { it.draftReady }.last()
            state.eventSink(AttachmentsPreviewEvent.ToggleAttachment(0))
            state = consumeItemsUntilPredicate { it.recoveryDetailsVisible }.last()
            assertThat(state.selectedIndices).isEmpty()
            state.eventSink(AttachmentsPreviewEvent.ConfirmSubmission)
            state = consumeItemsUntilPredicate { it.recoveryChecks.isNotEmpty() && !it.checkingRecovery }.last()
            assertThat(state.recoveryChecks.single().submission).isEqualTo(MediaSubmissionCheck.MISSING_IDENTITY)
            assertThat(state.recoveryChecks.single().operation!!.deadlinePassed).isTrue()
            assertThat(state.blockedIndices).containsExactly(0)
            coVerify(exactly = 1) { coordinator.operation(id) }
            coVerify(exactly = 0) { coordinator.start(any()) }
            coVerify(exactly = 0) { coordinator.resume(any()) }
            coVerify(exactly = 0) { coordinator.recordClientReceipt(any()) }
            coVerify(exactly = 0) { store.clear(any()) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `legacy committed UI marker is unknown and selecting it opens an explanation`() = runTest {
        val media = aMediaAttachment(aLocalMedia(mockMediaUrl).copy(lifecycleRoomIdHash = "c".repeat(64)))
        val saved = MediaSelectionDraft(listOf(media), emptyList(), 0, emptyMap(), null, true, VideoCompressionPreset.HIGH,
            emptyMap(), committed = listOf(0))
        val store = mockk<MediaSelectionDraftStore>(relaxed = true)
        coEvery { store.load(any()) } returns saved
        createAttachmentsPreviewPresenter(attachmentsOverride = listOf(media), mediaDraftStore = store).test {
            var state = consumeItemsUntilPredicate { it.draftReady }.last()
            assertThat(state.uncertainIndices).containsExactly(0)
            assertThat(state.acceptedIndices).isEmpty()
            state.eventSink(AttachmentsPreviewEvent.ToggleAttachment(0))
            state = consumeItemsUntilPredicate { it.recoveryDetailsVisible }.last()
            assertThat(state.selectedIndices).isEmpty()
            assertThat(state.blockedIndices).containsExactly(0)
            coVerify(exactly = 0) { store.clear(any()) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `checking cancellation pending with an accepted marker cannot commit or resend the original`() = runTest {
        val media = aMediaAttachment(aLocalMedia(mockMediaUrl).copy(lifecycleRoomIdHash = "c".repeat(64)))
        val id = media.localMedia.lifecycleOperationId
        val saved = MediaSelectionDraft(listOf(media), emptyList(), 0, emptyMap(), null, true, VideoCompressionPreset.HIGH,
            emptyMap(), inFlight = listOf(0), cancellationRequested = listOf(0),
            submissions = mapOf(0 to MediaSubmissionFact(MediaSubmissionPhase.ACCEPTED, ".yizhe-original-content.yzm", eventId = "\$accepted")))
        val store = mockk<MediaSelectionDraftStore>(relaxed = true)
        coEvery { store.load(any()) } returns saved
        val coordinator = mockk<AppOperationalStateCoordinator>(relaxed = true)
        coEvery { coordinator.operation(id) } returns LifecycleOperation(
            operationId = id, lifecycleId = FullAppLifecycleId.LC_16, idempotencyKey = id,
            subjects = LifecycleSubjectHashes(accountIdHash = "a".repeat(64), matrixDeviceIdHash = "b".repeat(64), roomIdHash = "c".repeat(64)),
            status = LifecycleOperationStatus.RUNNING, currentPhase = LifecyclePhase.PROJECTED,
            deadlineEpochMillis = 2000, createdAtEpochMillis = 1000, updatedAtEpochMillis = 1500)
        createAttachmentsPreviewPresenter(attachmentsOverride = listOf(media), mediaDraftStore = store,
            appOperationalStateCoordinator = coordinator).test {
            var state = consumeItemsUntilPredicate { it.draftReady }.last()
            assertThat(state.acceptedIndices).isEmpty()
            assertThat(state.cancellationRequestedIndices).containsExactly(0)
            state.eventSink(AttachmentsPreviewEvent.ToggleAttachment(0))
            state.eventSink(AttachmentsPreviewEvent.SendAttachment)
            state.eventSink(AttachmentsPreviewEvent.ConfirmSubmission)
            state = consumeItemsUntilPredicate { it.recoveryChecks.isNotEmpty() && !it.checkingRecovery }.last()
            assertThat(state.recoveryChecks.single().submission).isEqualTo(MediaSubmissionCheck.CANCEL_PENDING)
            assertThat(state.recoveryChecks.single().operation!!.deadlinePassed).isTrue()
            assertThat(state.selectedIndices).isEmpty()
            assertThat(state.blockedIndices).containsExactly(0)
            assertThat(state.acceptedIndices).isEmpty()
            coVerify(exactly = 1) { coordinator.operation(id) }
            coVerify(exactly = 0) { coordinator.start(any()) }
            coVerify(exactly = 0) { coordinator.resume(any()) }
            coVerify(exactly = 0) { coordinator.recordClientReceipt(any()) }
            coVerify(exactly = 0) { store.clear(any()) }
            coVerify(exactly = 0) { store.save(any(), match { 0 in it.committed || 0 in it.discarded }) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun TestScope.createAttachmentsPreviewPresenter(
        localMedia: LocalMedia = aLocalMedia(
            uri = mockMediaUrl,
        ).copy(lifecycleRoomIdHash = "c".repeat(64)),
        sendAsFile: Boolean = false,
        burnAfterRead: Boolean = false,
        room: JoinedRoom = FakeJoinedRoom(),
        timelineMode: Timeline.Mode = Timeline.Mode.Live,
        permalinkBuilder: PermalinkBuilder = FakePermalinkBuilder(),
        mediaPreProcessor: MediaPreProcessor = FakeMediaPreProcessor(),
        temporaryUriDeleter: TemporaryUriDeleter = FakeTemporaryUriDeleter(),
        onDoneListener: OnDoneListener = OnDoneListener { lambdaError() },
        displayMediaQualitySelectorViews: Boolean = false,
        mediaOptimizationSelectorPresenterFactory: FakeMediaOptimizationSelectorPresenterFactory = FakeMediaOptimizationSelectorPresenterFactory(
            fakePresenter = {
                MediaOptimizationSelectorState(
                    maxUploadSize = AsyncData.Uninitialized,
                    videoSizeEstimations = AsyncData.Uninitialized,
                    isImageOptimizationEnabled = null,
                    selectedVideoPreset = null,
                    displayMediaSelectorViews = displayMediaQualitySelectorViews,
                    displayVideoPresetSelectorDialog = false,
                    eventSink = {},
                )
            }
        ),
        mediaOptimizationConfigProvider: FakeMediaOptimizationConfigProvider = FakeMediaOptimizationConfigProvider(),
        attachmentImageEditor: AttachmentImageEditor = FakeAttachmentImageEditor {
            Result.success(
                EditedLocalMedia(
                    localMedia = localMedia.copy(uri = Uri.parse("file:///tmp/default-edited.jpeg")),
                    file = File("/tmp/default-edited.jpeg"),
                )
            )
        },
        videoCompressionPresetSelector: VideoCompressionPresetSelector = VideoCompressionPresetSelector(),
        appOperationalStateCoordinator: AppOperationalStateCoordinator = FakeAppOperationalStateCoordinator(),
        localAttachmentPreviewLeaseService: LocalAttachmentPreviewLeaseService = NoLocalAttachmentPreviewLeaseService,
        timelineController: TimelineController = TimelineController(room, room.liveTimeline),
        additionalMedia: List<LocalMedia> = emptyList(),
        attachmentsOverride: List<Attachment>? = null,
        mediaDraftStore: MediaSelectionDraftStore? = null,
    ): AttachmentsPreviewPresenter {
        return AttachmentsPreviewPresenter(
            attachments = attachmentsOverride ?: (listOf(localMedia) + additionalMedia).map { aMediaAttachment(it, sendAsFile = sendAsFile) },
            onDoneListener = onDoneListener,
            mediaSenderFactory = MediaSenderFactory { timelineMode ->
                DefaultMediaSender(
                    preProcessor = mediaPreProcessor,
                    room = room,
                    timelineMode = timelineMode,
                    mediaOptimizationConfigProvider = {
                        MediaOptimizationConfig(compressImages = true, videoCompressionPreset = VideoCompressionPreset.STANDARD)
                    }
                )
            },
            permalinkBuilder = permalinkBuilder,
            temporaryUriDeleter = temporaryUriDeleter,
            attachmentImageEditor = attachmentImageEditor,
            sessionCoroutineScope = this,
            dispatchers = testCoroutineDispatchers(),
            mediaOptimizationSelectorPresenterFactory = mediaOptimizationSelectorPresenterFactory,
            videoCompressionPresetSelector = videoCompressionPresetSelector,
            timelineMode = timelineMode,
            inReplyToEventId = null,
            burnAfterRead = burnAfterRead,
            accountIdHash = "a".repeat(64),
            matrixDeviceIdHash = "b".repeat(64),
            mediaOptimizationConfigProvider = mediaOptimizationConfigProvider,
            appOperationalStateCoordinator = appOperationalStateCoordinator,
            localAttachmentPreviewLeaseService = localAttachmentPreviewLeaseService,
            timelineController = timelineController,
            mediaDraftStore = mediaDraftStore,
        )
    }

    private class FakeAttachmentImageEditor(
        private val canEditResult: Boolean = true,
        private val result: () -> Result<EditedLocalMedia>,
    ) : AttachmentImageEditor {
        override suspend fun canEdit(localMedia: LocalMedia): Boolean {
            return canEditResult
        }

        override suspend fun exportEdits(
            localMedia: LocalMedia,
            edits: AttachmentImageEdits,
        ): Result<EditedLocalMedia> {
            return result()
        }
    }

    private object NoLocalAttachmentPreviewLeaseService : LocalAttachmentPreviewLeaseService {
        override fun requiresLease(source: LocalMedia): Boolean = false

        override suspend fun open(source: LocalMedia): Result<LocalAttachmentPreviewLease> =
            Result.failure(UnsupportedOperationException("No file-preview lease is configured."))
    }

    private class RecordingLocalAttachmentPreviewLeaseService(
        private val source: LocalMedia,
        preview: LocalMedia,
    ) : LocalAttachmentPreviewLeaseService {
        var openedSource: LocalMedia? = null
            private set
        var closeCalls = 0
            private set
        private val lease = object : LocalAttachmentPreviewLease {
            override val previewMedia: LocalMedia = preview
            override suspend fun transferToUpload(): Result<Unit> = Result.success(Unit)
            override suspend fun completeUpload(): Result<Unit> = Result.success(Unit)
            override suspend fun cancel(
                reason: LocalAttachmentPreviewCancelReason,
            ): Result<LocalAttachmentPreviewCleanup> = Result.success(COMPLETE_CLEANUP)
            override suspend fun closeAndAwait(): Result<LocalAttachmentPreviewCleanup> =
                Result.success(COMPLETE_CLEANUP)
            override fun close() {
                closeCalls++
            }
        }

        override fun requiresLease(source: LocalMedia): Boolean = source == this.source

        override suspend fun open(source: LocalMedia): Result<LocalAttachmentPreviewLease> {
            openedSource = source
            return Result.success(lease)
        }
    }

    private val mediaUploadInfo = MediaUploadInfo.AnyFile(
        File("test"),
        FileInfo(
            mimetype = MimeTypes.Any,
            size = 999L,
            thumbnailInfo = null,
            thumbnailSource = null,
        )
    )

    private companion object {
        val COMPLETE_CLEANUP = LocalAttachmentPreviewCleanup(
            readAccessRevoked = true,
            encryptedObjectDeleted = true,
            ownerReleased = true,
        )
    }
}
