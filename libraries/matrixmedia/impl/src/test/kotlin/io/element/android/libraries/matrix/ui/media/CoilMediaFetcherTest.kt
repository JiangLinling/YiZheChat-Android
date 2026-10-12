/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.ui.media

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.media.MediaCachePolicy
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.libraries.matrix.test.media.FakeMatrixMediaLoader
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CoilMediaFetcherTest {
    @Test
    fun `non-cacheable thumbnail requests an ephemeral fetch from Matrix`() = runTest {
        val loader = FakeMatrixMediaLoader()
        val fetcher = CoilMediaFetcher(
            mediaLoader = loader,
            mediaData = MediaRequestData(
                source = MediaSource("mxc://example.org/one-time"),
                kind = MediaRequestData.Kind.Thumbnail(320, 240),
                cacheable = false,
            ),
        )

        assertThat(fetcher.fetch()).isNotNull()
        assertThat(loader.lastThumbnailCachePolicy).isEqualTo(MediaCachePolicy.EPHEMERAL)
    }
}
