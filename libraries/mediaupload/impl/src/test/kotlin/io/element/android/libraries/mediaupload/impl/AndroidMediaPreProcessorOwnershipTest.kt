/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaupload.impl

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AndroidMediaPreProcessorOwnershipTest {
    @Test
    fun `legacy cleanup preserves pending camera captures while deleting preprocessor leftovers`() {
        withTempDirectory { cacheDirectory ->
            val id = "11111111-1111-4111-8111-111111111111"
            val pendingRootCapture = File(cacheDirectory, "$id-video.mp4").apply { writeText("pending") }
            val legacyPreprocessorFile = File(cacheDirectory, "$id-legacy.mp4").apply { writeText("leftover") }
            val captureDirectory = File(cacheDirectory, "video-captures").apply { mkdirs() }
            val pendingScopedCapture = File(captureDirectory, "$id-video.mp4").apply { writeText("pending") }

            cleanLegacyMediaCacheRoot(cacheDirectory)

            assertThat(pendingRootCapture.exists()).isTrue()
            assertThat(pendingScopedCapture.exists()).isTrue()
            assertThat(legacyPreprocessorFile.exists()).isFalse()
        }
    }

    @Test
    fun `cancellation after rename deletes every tracked post-process output`() {
        withTempDirectory { directory ->
            val source = File(directory, "source.mp4").apply { writeText("video") }
            val renamed = File(directory, "renamed.mp4")
            val thumbnail = File(directory, "thumbnail.jpg").apply { writeText("thumbnail") }
            val files = MediaUploadFileOwnership(listOf(source, thumbnail))
            assertThat(source.renameTo(renamed)).isTrue()
            files.track(listOf(renamed, thumbnail))

            files.cleanUpUnlessTransferred()

            assertThat(source.exists()).isFalse()
            assertThat(renamed.exists()).isFalse()
            assertThat(thumbnail.exists()).isFalse()
        }
    }

    @Test
    fun `post-processing failure deletes the transcode and thumbnail`() {
        withTempDirectory { directory ->
            val video = File(directory, "transcoded.mp4").apply { writeText("video") }
            val thumbnail = File(directory, "thumbnail.jpg").apply { writeText("thumbnail") }
            val files = VideoPostProcessFiles(video)
            files.registerThumbnail(thumbnail)

            files.cleanUpUnlessTransferred()

            assertThat(video.exists()).isFalse()
            assertThat(thumbnail.exists()).isFalse()
        }
    }

    @Test
    fun `successful post-processing keeps files for MediaUploadInfo ownership`() {
        withTempDirectory { directory ->
            val video = File(directory, "transcoded.mp4").apply { writeText("video") }
            val thumbnail = File(directory, "thumbnail.jpg").apply { writeText("thumbnail") }
            val files = VideoPostProcessFiles(video)
            files.registerThumbnail(thumbnail)
            files.transferOwnership()

            files.cleanUpUnlessTransferred()

            assertThat(video.exists()).isTrue()
            assertThat(thumbnail.exists()).isTrue()
        }
    }

    private fun withTempDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("video-post-process-ownership").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
