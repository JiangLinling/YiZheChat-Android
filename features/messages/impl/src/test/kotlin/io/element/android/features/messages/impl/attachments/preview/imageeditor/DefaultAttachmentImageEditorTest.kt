/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.attachments.preview.imageeditor

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.core.mimetype.MimeTypes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import java.io.FileNotFoundException
import org.junit.Test

class DefaultAttachmentImageEditorTest {
    @Test
    fun `video capability check never reads its source`() = runTest {
        assertThat(canEditStillImage("video/mp4") { error("Video must not be decoded as a bitmap") }).isFalse()
    }

    @Test
    fun `removed source cannot be edited without crashing`() = runTest {
        assertThat(canEditStillImage(null) { throw FileNotFoundException("QA source removed") }).isFalse()
    }

    @Test
    fun `revoked source cannot be edited without crashing`() = runTest {
        assertThat(canEditStillImage(null) { throw SecurityException("QA access revoked") }).isFalse()
    }

    @Test
    fun `unknown mime supports a decoded still image`() = runTest {
        assertThat(canEditStillImage(null) { "image/png" }).isTrue()
    }

    @Test
    fun `capability probe retains coroutine cancellation`() = runTest {
        val cancellation = CancellationException("QA cancelled")
        try {
            canEditStillImage(null) { throw cancellation }
            error("Cancellation must propagate")
        } catch (failure: CancellationException) {
            assertThat(failure).isSameInstanceAs(cancellation)
        }
    }

    @Test
    fun `exported mime type preserves png`() {
        assertThat(exportedMimeTypeFor(MimeTypes.Png)).isEqualTo(MimeTypes.Png)
    }

    @Test
    fun `exported mime type normalizes non-png images to jpeg`() {
        assertThat(exportedMimeTypeFor("image/heic")).isEqualTo(MimeTypes.Jpeg)
    }
}
