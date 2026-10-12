/*
 * Copyright (c) 2026 YiZhe Chat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TimelineTimeSeparatorLabelsTest {
    @Test
    fun `nearby messages share one centralized time marker`() {
        val first = aTimelineItemEvent().copy(sentTimeMillis = 1_000L, sentTime = "16:10", sentDate = "Today")
        val second = aTimelineItemEvent().copy(sentTimeMillis = 61_000L, sentTime = "16:11", sentDate = "Today")

        val labels = buildTimelineTimeSeparatorLabels(listOf(second, first))

        assertThat(labels).containsExactly(first.identifier().value, "Today 16:10")
    }

    @Test
    fun `large gap and date change create new markers`() {
        val first = aTimelineItemEvent().copy(sentTimeMillis = 1_000L, sentTime = "16:10", sentDate = "Today")
        val afterGap = aTimelineItemEvent().copy(sentTimeMillis = 361_000L, sentTime = "16:16", sentDate = "Today")
        val nextDay = aTimelineItemEvent().copy(sentTimeMillis = 86_401_000L, sentTime = "09:33", sentDate = "Yesterday")

        val labels = buildTimelineTimeSeparatorLabels(listOf(nextDay, first, afterGap))

        assertThat(labels).containsExactly(
            first.identifier().value,
            "Today 16:10",
            afterGap.identifier().value,
            "16:16",
            nextDay.identifier().value,
            "Yesterday 09:33",
        )
    }
}
