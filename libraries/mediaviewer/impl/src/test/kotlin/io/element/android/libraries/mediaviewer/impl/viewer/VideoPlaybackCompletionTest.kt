/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.mediaviewer.impl.viewer

import io.element.android.libraries.mediaviewer.api.MediaViewerEntryPoint
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

class VideoPlaybackCompletionTest {
    @Test
    fun `ordinary video EOF keeps viewer open for replay`() {
        val callback = mockk<MediaViewerEntryPoint.Callback>(relaxed = true)
        callback.onVideoPlaybackEnded(isOneTimeView = false)
        verify(exactly = 0) { callback.onPlaybackEnded() }
        verify(exactly = 0) { callback.onDone() }
    }

    @Test
    fun `one time video EOF retains its viewing session termination`() {
        val callback = mockk<MediaViewerEntryPoint.Callback>(relaxed = true)
        callback.onVideoPlaybackEnded(isOneTimeView = true)
        verify(exactly = 1) { callback.onPlaybackEnded() }
    }
}
