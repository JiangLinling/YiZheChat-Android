/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediapickers.impl

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.ArrayDeque

class VideoCaptureFileOwnerTest {
    @Test
    fun `each completed launch uses a unique file`() {
        withTempDirectory { directory ->
            val ids = ArrayDeque(listOf("first-id", "second-id"))
            val owner = VideoCaptureFileOwner(directory, idFactory = { ids.removeFirst() })
            val first = owner.prepare().apply { writeText("video") }
            assertThat(owner.complete(success = true)).isEqualTo(first)

            val second = owner.prepare()

            assertThat(first.exists()).isTrue()
            assertThat(second.name).isEqualTo("second-id-video.mp4")
            assertThat(second.exists()).isTrue()
        }
    }

    @Test
    fun `reentrant launch cannot replace the file awaited by the existing callback`() {
        withTempDirectory { directory ->
            val owner = VideoCaptureFileOwner(directory, idFactory = { "pending-id" })
            val first = owner.prepare().apply { writeText("video") }

            val secondLaunch = runCatching { owner.prepare() }

            assertThat(secondLaunch.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
            assertThat(owner.complete(success = true)).isEqualTo(first)
            assertThat(first.exists()).isTrue()
        }
    }

    @Test
    fun `saved filename restores the exact pending capture after activity recreation`() {
        withTempDirectory { cacheDirectory ->
            val id = "11111111-1111-4111-8111-111111111111"
            val captureDirectory = File(cacheDirectory, "video-captures")
            val owner = VideoCaptureFileOwner(captureDirectory, idFactory = { id })
            val file = owner.prepare().apply { writeText("video") }
            val unrelatedRootFile = File(cacheDirectory, file.name).apply { writeText("unrelated") }

            val restored = VideoCaptureFileOwner.restore(captureDirectory, owner.snapshotFileName().orEmpty())

            assertThat(file.parentFile).isEqualTo(captureDirectory)
            assertThat(restored.complete(success = true)).isEqualTo(file)
            assertThat(file.exists()).isTrue()
            assertThat(unrelatedRootFile.exists()).isTrue()
        }
    }

    @Test
    fun `restore ignores an unowned filename without deleting that file`() {
        withTempDirectory { directory ->
            val unrelated = File(directory, "not-owned-video.mp4").apply { writeText("unrelated") }

            val restored = VideoCaptureFileOwner.restore(directory, unrelated.name)
            restored.discard()

            assertThat(restored.complete(success = false)).isNull()
            assertThat(unrelated.exists()).isTrue()
        }
    }

    @Test
    fun `cancelled capture deletes the pending plaintext`() {
        withTempDirectory { directory ->
            val owner = VideoCaptureFileOwner(directory, idFactory = { "cancelled-id" })
            val file = owner.prepare().apply { writeText("partial") }

            assertThat(owner.complete(success = false)).isNull()
            assertThat(file.exists()).isFalse()
        }
    }

    @Test
    fun `empty successful result is rejected and deleted`() {
        withTempDirectory { directory ->
            val owner = VideoCaptureFileOwner(directory, idFactory = { "empty-id" })
            val file = owner.prepare()

            assertThat(owner.complete(success = true)).isNull()
            assertThat(file.exists()).isFalse()
        }
    }

    @Test
    fun `non-empty successful result transfers ownership`() {
        withTempDirectory { directory ->
            val owner = VideoCaptureFileOwner(directory, idFactory = { "success-id" })
            val file = owner.prepare().apply { writeText("video") }

            assertThat(owner.complete(success = true)).isEqualTo(file)
            assertThat(file.exists()).isTrue()
        }
    }

    @Test
    fun `launch failure cleanup deletes the pending destination`() {
        withTempDirectory { directory ->
            val owner = VideoCaptureFileOwner(directory, idFactory = { "launch-failure-id" })
            val file = owner.prepare()

            owner.discard()

            assertThat(file.exists()).isFalse()
        }
    }

    private fun withTempDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("video-capture-owner").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
