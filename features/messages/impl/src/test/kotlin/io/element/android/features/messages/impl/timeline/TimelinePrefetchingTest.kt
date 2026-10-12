/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.features.messages.impl.timeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TimelinePrefetchingTest {
    @Test
    fun `programmatic scrolling never triggers history pagination`() {
        assertThat(
            shouldPrefetchTimeline(
                needsPrefetch = true,
                isScrolling = true,
                isEmptyAndNeedsBackPagination = false,
                isProgrammaticScroll = true,
            )
        ).isFalse()
    }

    @Test
    fun `user scrolling near history boundary triggers pagination`() {
        assertThat(
            shouldPrefetchTimeline(
                needsPrefetch = true,
                isScrolling = true,
                isEmptyAndNeedsBackPagination = false,
                isProgrammaticScroll = false,
            )
        ).isTrue()
    }

    @Test
    fun `empty detached timeline can bootstrap without a scroll`() {
        assertThat(
            shouldPrefetchTimeline(
                needsPrefetch = false,
                isScrolling = false,
                isEmptyAndNeedsBackPagination = true,
                isProgrammaticScroll = false,
            )
        ).isTrue()
    }
}
