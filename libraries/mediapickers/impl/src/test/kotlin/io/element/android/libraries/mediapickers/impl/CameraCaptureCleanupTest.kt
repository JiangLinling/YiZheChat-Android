/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediapickers.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.mediapickers.api.MediaPickFailureReason
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CameraCaptureCleanupTest {
    @Test
    fun `cancel deletes only the owned temporary capture`() {
        val directory = Files.createTempDirectory("camera-cancel-test").toFile()
        try {
            val capture = File(directory, "photo-owned.jpg").apply { writeText("partial QA capture") }
            val unrelated = File(directory, "other.jpg").apply { writeText("other QA fixture") }
            assertThat(clearCancelledCameraCapture(capture)).isEqualTo(MediaPickFailureReason.CANCELLED)
            assertThat(capture.exists()).isFalse()
            assertThat(unrelated.exists()).isTrue()
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `failed deletion remains recoverable and retry settles only after real removal`() {
        val directory = Files.createTempDirectory("camera-cancel-failure-test").toFile()
        try {
            val capture = File(directory, "photo-owned.jpg").apply { mkdir() }
            val child = File(capture, "retained").apply { writeText("QA cleanup obstruction") }
            assertThat(clearCancelledCameraCapture(capture)).isEqualTo(MediaPickFailureReason.TEMPORARY_FILE_CLEANUP_FAILED)
            assertThat(child.exists()).isTrue()
            assertThat(child.delete()).isTrue()
            assertThat(clearCancelledCameraCapture(capture)).isEqualTo(MediaPickFailureReason.CANCELLED)
            assertThat(capture.exists()).isFalse()
            assertThat(clearCancelledCameraCapture(capture)).isEqualTo(MediaPickFailureReason.CANCELLED)
        } finally {
            directory.deleteRecursively()
        }
    }
}
