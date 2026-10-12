/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaviewer.impl.local.video

import androidx.media3.common.Player
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.mediaviewer.impl.segmented.player.SegmentedVideoTimelineSnapshot
import org.junit.Test

class MediaVideoPlaybackStateTest {
    @Test
    fun `empty player ended state does not close viewer`() {
        assertThat(shouldNotifyPlaybackEnded(Player.STATE_ENDED, hasPreparedPlayableMedia = false)).isFalse()
    }

    @Test
    fun `prepared video ended state closes viewer`() {
        assertThat(shouldNotifyPlaybackEnded(Player.STATE_ENDED, hasPreparedPlayableMedia = true)).isTrue()
    }

    @Test
    fun `ready state does not close viewer`() {
        assertThat(shouldNotifyPlaybackEnded(Player.STATE_READY, hasPreparedPlayableMedia = true)).isFalse()
    }

    @Test
    fun `segmented local position is projected onto the global asset timeline`() {
        val timeline = SegmentedVideoTimelineSnapshot(
            assetDurationUs = 8_000_000L,
            segmentStartUs = 4_000_000L,
            segmentEndUsExclusive = 6_000_000L,
        )

        assertThat(globalPlaybackPositionMillis(750L, timeline)).isEqualTo(4_750L)
        assertThat(globalPlaybackPositionMillis(9_000L, timeline)).isEqualTo(6_000L)
        assertThat(globalPlaybackPositionMillis(750L, null)).isEqualTo(750L)
    }
}
