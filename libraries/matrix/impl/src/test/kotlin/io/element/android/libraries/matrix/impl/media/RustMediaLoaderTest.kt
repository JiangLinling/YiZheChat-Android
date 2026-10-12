/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.media

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.media.EphemeralMediaCacheUnavailableException
import io.element.android.libraries.matrix.api.media.MediaCachePolicy
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.tests.testutils.testCoroutineDispatchers
import io.mockk.mockk
import io.mockk.coVerify
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.matrix.rustcomponents.sdk.Client

class RustMediaLoaderTest {
    @Test
    fun `ephemeral content uses uncached transport and never SDK cache or tempfile`() = runTest {
        val client = mockk<Client>()
        val source = MediaSource("mxc://example.org/encrypted-voice")
        val frame = byteArrayOf(1, 2, 3)
        val loader = RustMediaLoader(temporaryFolder.root, testCoroutineDispatchers(), client,
            ephemeralMediaContent = { assertThat(it).isSameInstanceAs(source); frame })
        assertThat(loader.loadMediaContent(source, MediaCachePolicy.EPHEMERAL).getOrThrow()).isSameInstanceAs(frame)
        coVerify(exactly = 0) { client.getMediaContent(any()) }
        coVerify(exactly = 0) { client.getMediaFile(any(), any(), any(), any(), any()) }
        assertThat(temporaryFolder.root.listFiles()).isEmpty()
    }

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `ephemeral thumbnail fails closed before entering Rust media cache`() = runTest {
        val loader = RustMediaLoader(
            baseCacheDirectory = temporaryFolder.root,
            dispatchers = testCoroutineDispatchers(),
            innerClient = mockk<Client>(),
        )

        val result = loader.loadMediaThumbnail(
            source = MediaSource("mxc://example.org/one-time-thumbnail"),
            width = 320,
            height = 240,
            cachePolicy = MediaCachePolicy.EPHEMERAL,
        )

        assertThat(result.exceptionOrNull()).isInstanceOf(EphemeralMediaCacheUnavailableException::class.java)
    }

    @Test
    fun `ephemeral content fails closed before entering Rust media cache`() = runTest {
        val loader = RustMediaLoader(
            baseCacheDirectory = temporaryFolder.root,
            dispatchers = testCoroutineDispatchers(),
            innerClient = mockk<Client>(),
        )

        val result = loader.loadMediaContent(
            source = MediaSource("mxc://example.org/one-time-content"),
            cachePolicy = MediaCachePolicy.EPHEMERAL,
        )

        assertThat(result.exceptionOrNull()).isInstanceOf(EphemeralMediaCacheUnavailableException::class.java)
    }
}
