/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TimelineItemRowLogicTest {
    @Test
    fun `outer merged semantics are absent when TalkBack is inactive`() {
        val result = timelineItemAccessibilityInfo(
            isTalkbackActive = false,
            safeSenderName = "Alice",
            voiceMessageDescription = null,
            isTraversalGroup = false,
        )

        assertThat(result).isNull()
    }

    @Test
    fun `TalkBack semantics describe a regular message by sender`() {
        val result = timelineItemAccessibilityInfo(
            isTalkbackActive = true,
            safeSenderName = "Alice",
            voiceMessageDescription = null,
            isTraversalGroup = true,
        )

        assertThat(result).isEqualTo(
            TimelineItemAccessibilityInfo(
                contentDescription = "Alice",
                isTraversalGroup = true,
            ),
        )
    }

    @Test
    fun `TalkBack semantics include voice message description`() {
        val result = timelineItemAccessibilityInfo(
            isTalkbackActive = true,
            safeSenderName = "Alice",
            voiceMessageDescription = "Voice message, 1 minute",
            isTraversalGroup = false,
        )

        assertThat(result?.contentDescription).isEqualTo("Alice, Voice message, 1 minute")
    }
}
