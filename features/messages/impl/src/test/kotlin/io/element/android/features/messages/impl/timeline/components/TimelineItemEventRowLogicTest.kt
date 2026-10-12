/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline.components

import com.google.common.truth.Truth.assertThat
import io.element.android.features.messages.impl.timeline.model.TimelineItemGroupPosition
import org.junit.Test

class TimelineItemEventRowLogicTest {
    @Test
    fun `direct room avatar is not shown for the oldest event in a group`() {
        assertThat(shouldShowDirectRoomAvatar(TimelineItemGroupPosition.First)).isTrue()
    }

    @Test
    fun `direct room avatar is shown for an ungrouped event`() {
        assertThat(shouldShowDirectRoomAvatar(TimelineItemGroupPosition.None)).isTrue()
    }

    @Test
    fun `direct room avatar is not shown for the middle of a group`() {
        assertThat(shouldShowDirectRoomAvatar(TimelineItemGroupPosition.Middle)).isTrue()
    }

    @Test
    fun `direct room avatar is shown for the newest event in a group`() {
        assertThat(shouldShowDirectRoomAvatar(TimelineItemGroupPosition.Last)).isTrue()
    }

    @Test
    fun `reply swipe indicator is absent at rest`() {
        assertThat(shouldComposeReplySwipeIndicator(0f)).isFalse()
    }

    @Test
    fun `reply swipe indicator is present while swiping in either direction`() {
        assertThat(shouldComposeReplySwipeIndicator(1f)).isTrue()
        assertThat(shouldComposeReplySwipeIndicator(-1f)).isTrue()
    }

    @Test
    fun `protected whole-bubble media keeps its inner click handler`() {
        assertThat(
            shouldHandleEventClickInsideContent(
                isWholeContentClickable = true,
                mustBeProtected = true,
            )
        ).isTrue()
    }

    @Test
    fun `ordinary whole-bubble event is handled by the outer bubble`() {
        assertThat(
            shouldHandleEventClickInsideContent(
                isWholeContentClickable = true,
                mustBeProtected = false,
            )
        ).isFalse()
    }

    @Test
    fun `separately clickable content keeps its inner click handler`() {
        assertThat(
            shouldHandleEventClickInsideContent(
                isWholeContentClickable = false,
                mustBeProtected = false,
            )
        ).isTrue()
    }

    @Test
    fun `single child common layout is used only without reply or thread decoration`() {
        assertThat(shouldUseSingleChildCommonLayout(showThreadDecoration = false, hasInReplyTo = false)).isTrue()
        assertThat(shouldUseSingleChildCommonLayout(showThreadDecoration = true, hasInReplyTo = false)).isFalse()
        assertThat(shouldUseSingleChildCommonLayout(showThreadDecoration = false, hasInReplyTo = true)).isFalse()
        assertThat(shouldUseSingleChildCommonLayout(showThreadDecoration = true, hasInReplyTo = true)).isFalse()
    }
}
