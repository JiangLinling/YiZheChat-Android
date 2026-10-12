/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaupload.impl

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.core.mimetype.MimeTypes
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.media.FileInfo
import io.element.android.libraries.matrix.api.media.ImageInfo
import io.element.android.libraries.matrix.api.media.MediaUploadHandler
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.matrix.api.room.CreateTimelineParams
import io.element.android.libraries.matrix.api.core.ThreadId
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.matrix.test.media.FakeMediaUploadHandler
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.libraries.matrix.test.timeline.FakeTimeline
import io.element.android.libraries.mediaupload.api.MediaOptimizationConfig
import io.element.android.libraries.mediaupload.api.MediaOptimizationConfigProvider
import io.element.android.libraries.mediaupload.api.MediaPreProcessor
import io.element.android.libraries.mediaupload.api.MediaUploadInfo
import io.element.android.libraries.mediaupload.test.FakeMediaPreProcessor
import io.element.android.libraries.preferences.api.store.VideoCompressionPreset
import io.element.android.tests.testutils.lambda.lambdaRecorder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class DefaultMediaSenderTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `cleanup waits for thread timeline acquisition before retiring unsent files`() = runTest {
        val directory = Files.createTempDirectory("thread-media-owner").toFile()
        try {
            val file = File(directory, "prepared.jpeg").apply { writeText("prepared") }
            val info = MediaUploadInfo.AnyFile(file, FileInfo(null, 8, null, null))
            val gate = CompletableDeferred<Unit>()
            val entered = CompletableDeferred<Unit>()
            val room = object : JoinedRoom by FakeJoinedRoom() {
                override suspend fun createTimeline(createTimelineParams: CreateTimelineParams): Result<Timeline> {
                    entered.complete(Unit)
                    gate.await()
                    return Result.failure(IllegalStateException("test timeline unavailable"))
                }
            }
            val processor = FakeMediaPreProcessor().apply { givenResult(Result.success(info)) }
            val sender = createDefaultMediaSender(processor, room, timelineMode = Timeline.Mode.Thread(ThreadId("\$thread")))
            sender.preProcessMedia(Uri.fromFile(file), MimeTypes.Jpeg, mediaOptimizationConfig).getOrThrow()
            val job = launch { sender.sendPreProcessedMedia(info, null, null, null) }
            entered.await()
            sender.cleanUp()
            assertThat(file.exists()).isTrue()
            gate.complete(Unit)
            job.join()
            assertThat(file.exists()).isFalse()
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `cleanup retires only this sender outputs when two senders share a preprocessor`() = runTest {
        val directory = Files.createTempDirectory("sender-owned-media").toFile()
        try {
            val first = File(directory, "first.jpeg").apply { writeText("first") }
            val second = File(directory, "second.mp4").apply { writeText("second") }
            val processor = FakeMediaPreProcessor()
            val firstSender = createDefaultMediaSender(preProcessor = processor)
            val secondSender = createDefaultMediaSender(preProcessor = processor)
            processor.givenResult(Result.success(MediaUploadInfo.AnyFile(first, FileInfo(null, 5, null, null))))
            firstSender.preProcessMedia(Uri.fromFile(first), MimeTypes.Jpeg, mediaOptimizationConfig).getOrThrow()
            processor.givenResult(Result.success(MediaUploadInfo.AnyFile(second, FileInfo(null, 6, null, null))))
            secondSender.preProcessMedia(Uri.fromFile(second), MimeTypes.Mp4, mediaOptimizationConfig).getOrThrow()

            firstSender.cleanUp()

            assertThat(first.exists()).isFalse()
            assertThat(second.exists()).isTrue()
            assertThat(processor.cleanUpCallCount).isEqualTo(0)
            secondSender.cleanUp()
            assertThat(second.exists()).isFalse()
        } finally {
            directory.deleteRecursively()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `caller cancellation and cleanup retain files already owned by the upload handler`() = runTest {
        val directory = Files.createTempDirectory("handler-owned-media").toFile()
        try {
            val file = File(directory, "accepted.jpeg").apply { writeText("accepted") }
            val awaitStarted = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val handler = object : MediaUploadHandler {
                override suspend fun await(): Result<Unit> {
                    awaitStarted.complete(Unit)
                    gate.await()
                    return Result.success(Unit)
                }
                override fun cancel() { gate.cancel() }
            }
            val timeline = FakeTimeline().apply {
                sendFileLambda = lambdaRecorder<File, FileInfo, String?, String?, EventId?, Result<MediaUploadHandler>> {
                    _, _, _, _, _ -> Result.success(handler)
                }
            }
            val sender = createDefaultMediaSender(room = FakeJoinedRoom(liveTimeline = timeline))
            var accepted = false
            val job = launch {
                sender.sendPreProcessedMedia(MediaUploadInfo.AnyFile(file, FileInfo(null, 8, null, null)), null, null, null,
                    onUploadStarted = { accepted = true; sender.cleanUp() },
                )
            }
            awaitStarted.await()
            assertThat(accepted).isTrue()
            assertThat(job.isActive).isTrue()
            job.cancel()
            runCurrent()
            sender.cleanUp()
            assertThat(file.exists()).isTrue()
            gate.complete(Unit)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `protected upload identity precedes completion without changing send result on callback failure`() = runTest {
        for (failCallback in listOf(false, true)) {
            val identity = ".yizhe-17f99b91-4f2b-44bb-b98b-6d7c630da5a6.yzm"
            val room = FakeJoinedRoom(liveTimeline = FakeTimeline().apply {
                sendFileLambda = lambdaRecorder<File, FileInfo, String?, String?, EventId?, Result<FakeMediaUploadHandler>> {
                    _, _, _, _, _ -> Result.success(FakeMediaUploadHandler(protectedMediaTransportName = identity))
                }
            })
            val sender = createDefaultMediaSender(room = room)
            val calls = mutableListOf<String>()
            val info = io.element.android.libraries.mediaupload.api.MediaUploadInfo.AnyFile(
                File("prepared"), FileInfo(null, 42L, null, null),
            )
            val result = sender.sendPreProcessedMedia(info, null, null, null,
                onUploadIdentity = {
                    assertThat(it).isEqualTo(identity)
                    calls += "identity"
                    if (failCallback) error("presentation-only failure")
                },
                onUploadStarted = { calls += "started" },
            )
            calls += "completed"
            assertThat(result.isSuccess).isTrue()
            assertThat(calls).containsExactly("identity", "started", "completed").inOrder()
            assertThat(sender.hasOngoingMediaUploads).isFalse()
        }
    }

    private val mediaOptimizationConfig = MediaOptimizationConfig(
        compressImages = true,
        videoCompressionPreset = VideoCompressionPreset.STANDARD,
    )

    @Test
    fun `given an attachment when sending it the preprocessor always runs`() = runTest {
        val preProcessor = FakeMediaPreProcessor()
        val sender = createDefaultMediaSender(
            preProcessor = preProcessor,
            room = FakeJoinedRoom(
                liveTimeline = FakeTimeline().apply {
                    sendFileLambda = lambdaRecorder<
                        File,
                        FileInfo,
                        String?,
                        String?,
                        EventId?,
                        Result<FakeMediaUploadHandler>,
                        > { _, _, _, _, _ ->
                        Result.success(FakeMediaUploadHandler())
                    }
                },
            )
        )

        val uri = Uri.parse("content://image.jpg")
        sender.sendMedia(uri = uri, mimeType = MimeTypes.Jpeg, mediaOptimizationConfig = mediaOptimizationConfig)

        assertThat(preProcessor.processCallCount).isEqualTo(1)
    }

    @Test
    fun `given an attachment when sending it the Room will call sendMedia`() = runTest {
        val sendImageResult =
            lambdaRecorder { _: File, _: File?, _: ImageInfo, _: String?, _: String?, _: EventId? ->
                Result.success(FakeMediaUploadHandler())
            }
        val room = FakeJoinedRoom(
            liveTimeline = FakeTimeline().apply {
                sendImageLambda = sendImageResult
            },
        )
        val sender = createDefaultMediaSender(room = room)

        val uri = Uri.parse("content://image.jpg")
        sender.sendMedia(uri = uri, mimeType = MimeTypes.Jpeg, mediaOptimizationConfig = mediaOptimizationConfig)
    }

    @Test
    fun `given a failure in the preprocessor when sending the whole process fails`() = runTest {
        val preProcessor = FakeMediaPreProcessor().apply {
            givenResult(Result.failure(Exception()))
        }
        val sender = createDefaultMediaSender(preProcessor)

        val uri = Uri.parse("content://image.jpg")
        val result = sender.sendMedia(uri = uri, mimeType = MimeTypes.Jpeg, mediaOptimizationConfig = mediaOptimizationConfig)

        assertThat(result.exceptionOrNull()).isNotNull()
    }

    @Test
    fun `given a failure in the media upload when sending the whole process fails`() = runTest {
        val preProcessor = FakeMediaPreProcessor().apply {
            givenImageResult()
        }
        val sendImageResult =
            lambdaRecorder { _: File, _: File?, _: ImageInfo, _: String?, _: String?, _: EventId? ->
                Result.failure<FakeMediaUploadHandler>(Exception())
            }
        val room = FakeJoinedRoom(
            liveTimeline = FakeTimeline().apply {
                sendImageLambda = sendImageResult
            },
        )
        val sender = createDefaultMediaSender(
            preProcessor = preProcessor,
            room = room,
        )

        val uri = Uri.parse("content://image.jpg")
        val result = sender.sendMedia(uri = uri, mimeType = MimeTypes.Jpeg, mediaOptimizationConfig = mediaOptimizationConfig)

        assertThat(result.exceptionOrNull()).isNotNull()
    }

    @Test
    fun `given a failure while awaiting an accepted upload the whole process fails`() = runTest {
        val failure = IllegalStateException("send queue rejected upload")
        val preProcessor = FakeMediaPreProcessor().apply { givenImageResult() }
        val room = FakeJoinedRoom(
            liveTimeline = FakeTimeline().apply {
                sendImageLambda = lambdaRecorder { _: File, _: File?, _: ImageInfo, _: String?, _: String?, _: EventId? ->
                    Result.success(FakeMediaUploadHandler(Result.failure(failure)))
                }
            },
        )
        val sender = createDefaultMediaSender(preProcessor = preProcessor, room = room)

        val result = sender.sendMedia(
            uri = Uri.parse("content://image.jpg"),
            mimeType = MimeTypes.Jpeg,
            mediaOptimizationConfig = mediaOptimizationConfig,
        )

        assertThat(result.exceptionOrNull()).isSameInstanceAs(failure)
        assertThat(sender.hasOngoingMediaUploads).isFalse()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `given an accepted upload the started callback runs before upload completion`() = runTest(StandardTestDispatcher()) {
        val room = FakeJoinedRoom(
            liveTimeline = FakeTimeline().apply {
                sendFileLambda = lambdaRecorder<
                    File,
                    FileInfo,
                    String?,
                    String?,
                    EventId?,
                    Result<FakeMediaUploadHandler>,
                    > { _, _, _, _, _ -> Result.success(FakeMediaUploadHandler()) }
            },
        )
        val sender = createDefaultMediaSender(room = room)
        var uploadStarted = false
        val sendJob = launch {
            sender.sendMedia(
                uri = Uri.parse("content://document.docx"),
                mimeType = MimeTypes.OctetStream,
                mediaOptimizationConfig = mediaOptimizationConfig,
                onUploadStarted = { uploadStarted = true },
            )
        }

        // Pre-processing has finished and the SDK local event exists, while handler.await() is
        // still suspended. The UI can therefore enter the room without claiming send success.
        advanceTimeBy(2L)
        runCurrent()
        assertThat(uploadStarted).isTrue()
        assertThat(sendJob.isActive).isTrue()

        advanceTimeBy(1L)
        runCurrent()
        assertThat(sendJob.isCompleted).isTrue()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `given a cancellation in the media upload when sending the job is cancelled`() = runTest(StandardTestDispatcher()) {
        val sendFileResult =
            lambdaRecorder<File, FileInfo, String?, String?, EventId?, Result<FakeMediaUploadHandler>> { _, _, _, _, _ ->
                Result.success(FakeMediaUploadHandler())
            }
        val room = FakeJoinedRoom(
            liveTimeline = FakeTimeline().apply {
                sendFileLambda = sendFileResult
            },
        )
        val sender = createDefaultMediaSender(room = room)
        val sendJob = launch {
            val uri = Uri.parse("content://image.jpg")
            sender.sendMedia(uri = uri, mimeType = MimeTypes.Jpeg, mediaOptimizationConfig = mediaOptimizationConfig)
        }
        // Wait until several internal tasks run and the file is being uploaded
        advanceTimeBy(3L)

        // Assert the file is being uploaded
        assertThat(sender.hasOngoingMediaUploads).isTrue()

        // Cancel the coroutine
        sendJob.cancel()

        // Wait for the coroutine cleanup to happen
        advanceTimeBy(1L)

        // Assert the file is not being uploaded anymore
        assertThat(sender.hasOngoingMediaUploads).isFalse()
        sendFileResult.assertions().isCalledOnce()
    }

    private fun createDefaultMediaSender(
        preProcessor: MediaPreProcessor = FakeMediaPreProcessor(),
        room: JoinedRoom = FakeJoinedRoom(),
        mediaOptimizationConfigProvider: MediaOptimizationConfigProvider = MediaOptimizationConfigProvider { mediaOptimizationConfig },
        timelineMode: Timeline.Mode = Timeline.Mode.Live,
    ) = DefaultMediaSender(
        preProcessor = preProcessor,
        room = room,
        timelineMode = timelineMode,
        mediaOptimizationConfigProvider = mediaOptimizationConfigProvider,
    )
}
