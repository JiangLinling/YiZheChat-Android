/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaupload.impl

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executor

class VideoCompressorLifecycleTest {
    @Test
    fun `close runs Transformer cancellation and cleanup before releasing the permit`() {
        withTempDirectory { directory ->
            val outputFile = File(directory, "partial.mp4").apply { writeText("partial") }
            val output = VideoTranscodeOutput(outputFile)
            val scheduled = mutableListOf<Runnable>()
            var cancelCount = 0
            var releaseCount = 0
            val cleanup = VideoTranscodeCleanup(
                mainExecutor = Executor { command -> scheduled.add(command) },
                cancelTransformer = { cancelCount += 1 },
                output = output,
                releasePermit = { releaseCount += 1 },
            )

            cleanup.close()
            cleanup.close()

            assertThat(scheduled).hasSize(1)
            assertThat(cancelCount).isEqualTo(0)
            assertThat(releaseCount).isEqualTo(0)
            assertThat(outputFile.exists()).isTrue()

            scheduled.single().run()

            assertThat(cancelCount).isEqualTo(1)
            assertThat(releaseCount).isEqualTo(1)
            assertThat(outputFile.exists()).isFalse()
        }
    }

    @Test
    fun `failed Completed delivery deletes the output immediately`() {
        withTempDirectory { directory ->
            val outputFile = File(directory, "undelivered.mp4").apply { writeText("complete") }
            val output = VideoTranscodeOutput(outputFile)
            val channel = Channel<VideoTranscodingEvent>(capacity = Channel.RENDEZVOUS)

            assertThat(channel.trySend(VideoTranscodingEvent.Completed(output)).isFailure).isTrue()
            output.cleanUp()
            assertThat(outputFile.exists()).isFalse()
        }
    }

    @Test
    fun `enqueue followed by cancellation before consumption deletes output`() {
        withTempDirectory { directory ->
            val outputFile = File(directory, "buffered.mp4").apply { writeText("complete") }
            val output = VideoTranscodeOutput(outputFile)
            val channel = Channel<VideoTranscodingEvent>(capacity = 1)
            var claimCallbackCount = 0

            assertThat(
                channel.trySend(VideoTranscodingEvent.Completed(output) { claimCallbackCount += 1 }).isSuccess
            ).isTrue()
            channel.cancel()
            output.cleanUp()

            assertThat(claimCallbackCount).isEqualTo(0)
            assertThat(outputFile.exists()).isFalse()
        }
    }

    @Test
    fun `consuming Completed explicitly transfers output ownership`() {
        withTempDirectory { directory ->
            val outputFile = File(directory, "delivered.mp4").apply { writeText("complete") }
            val output = VideoTranscodeOutput(outputFile)
            var claimCallbackCount = 0
            val cleanup = VideoTranscodeCleanup(
                mainExecutor = Executor { command -> command.run() },
                cancelTransformer = {},
                output = output,
                releasePermit = {},
            )

            assertThat(
                VideoTranscodingEvent.Completed(output) { claimCallbackCount += 1 }.claimFile()
            ).isEqualTo(outputFile)
            cleanup.close()

            assertThat(claimCallbackCount).isEqualTo(1)
            assertThat(outputFile.exists()).isTrue()
        }
    }

    @Test
    fun `permit serializes concurrent transcodes`() = runTest {
        val permit = VideoTranscodePermit()
        permit.acquire()
        var secondStarted = false
        val second = launch {
            permit.acquire()
            secondStarted = true
            permit.release()
        }

        runCurrent()
        assertThat(secondStarted).isFalse()

        permit.release()
        second.join()

        assertThat(secondStarted).isTrue()
    }

    private fun withTempDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("video-compressor-lifecycle").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
