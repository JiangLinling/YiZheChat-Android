/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.voiceplayer.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.core.mimetype.MimeTypes
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.libraries.matrix.test.media.FakeMatrixMediaLoader
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DefaultVoiceMessageMediaRepoTest {
    @Test
    fun `download returns media handle without persisting a second voice cache file`() = runTest {
        val mediaLoader = FakeMatrixMediaLoader().apply { path = "media.ogg" }
        val repo = createRepo(mediaLoader)

        val result = repo.getMediaFile()

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrThrow().path()).isEqualTo("media.ogg")
    }

    @Test
    fun `download failure is propagated`() = runTest {
        val mediaLoader = FakeMatrixMediaLoader().apply { shouldFail = true }
        val repo = createRepo(mediaLoader)

        assertThat(repo.getMediaFile().isFailure).isTrue()
    }

    private fun createRepo(mediaLoader: FakeMatrixMediaLoader) = DefaultVoiceMessageMediaRepo(
        matrixMediaLoader = mediaLoader,
        mediaSource = MediaSource("mxc://matrix.org/media"),
        mimeType = MimeTypes.Ogg,
        filename = "voice.ogg",
    )
}
