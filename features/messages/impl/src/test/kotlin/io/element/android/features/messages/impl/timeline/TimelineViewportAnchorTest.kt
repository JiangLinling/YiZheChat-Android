/*
 * Copyright (c) 2026 YiZheChat.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.features.messages.impl.timeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TimelineViewportAnchorTest {
    @Test
    fun `a bulk commit follows latest using pre commit intent`() {
        assertThat(shouldKeepTimelineAtLiveEdge(true, true, true, false, false, false)).isTrue()
    }

    @Test
    fun `reading history or navigating never jumps to latest`() {
        assertThat(shouldKeepTimelineAtLiveEdge(true, false, true, false, false, false)).isFalse()
        assertThat(shouldKeepTimelineAtLiveEdge(true, true, true, true, false, false)).isFalse()
        assertThat(shouldKeepTimelineAtLiveEdge(true, true, true, false, true, false)).isFalse()
        assertThat(shouldKeepTimelineAtLiveEdge(true, true, true, false, false, true)).isFalse()
        assertThat(shouldKeepTimelineAtLiveEdge(true, true, false, false, false, false)).isFalse()
    }

    @Test
    fun `real time insertion keeps the same stable event and pixel offset`() {
        val result = resolveTimelineViewportAnchor(
            candidates = listOf(candidate("event-4", offset = 23)),
            currentItemIndices = mapOf("new-event" to 0, "event-4" to 5),
            isAtLiveEdge = false,
            userScrollInProgress = false,
            programmaticScrollInProgress = false,
            focusNavigationInProgress = false,
        )

        assertThat(result).isEqualTo(
            TimelineViewportAnchorRequest(itemId = "event-4", targetIndex = 5, scrollOffset = 23)
        )
    }

    @Test
    fun `history pagination keeps the visible event instead of jumping to live edge`() {
        val result = resolveTimelineViewportAnchor(
            candidates = listOf(candidate("event-20", offset = 7)),
            currentItemIndices = mapOf("event-20" to 20, "older-1" to 21, "older-2" to 22),
            isAtLiveEdge = false,
            userScrollInProgress = false,
            programmaticScrollInProgress = false,
            focusNavigationInProgress = false,
        )

        assertThat(result?.targetIndex).isEqualTo(20)
        assertThat(result?.scrollOffset).isEqualTo(7)
    }

    @Test
    fun `pagination commit arriving during a user scroll preserves the pre commit event`() {
        val result = resolveTimelineViewportAnchor(
            candidates = listOf(candidate("event-under-finger", offset = 19)),
            currentItemIndices = mapOf("older" to 30, "event-under-finger" to 12),
            isAtLiveEdge = false,
            userScrollInProgress = true,
            preserveCommitDuringUserScroll = true,
            programmaticScrollInProgress = false,
            focusNavigationInProgress = false,
        )

        assertThat(result).isEqualTo(
            TimelineViewportAnchorRequest("event-under-finger", targetIndex = 12, scrollOffset = 19)
        )
    }

    @Test
    fun `destroyed event is never restored when no visible event survives`() {
        val result = resolveTimelineViewportAnchor(
            candidates = listOf(candidate("destroyed", offset = 4)),
            currentItemIndices = mapOf("unrelated" to 0),
            isAtLiveEdge = false,
            userScrollInProgress = false,
            programmaticScrollInProgress = false,
            focusNavigationInProgress = false,
        )

        assertThat(result).isNull()
    }

    @Test
    fun `security hard invalidation cannot retain an anchor`() {
        val result = resolveTimelineViewportAnchor(
            candidates = listOf(candidate("cleartext-event", offset = 12)),
            currentItemIndices = emptyMap(),
            isAtLiveEdge = false,
            userScrollInProgress = false,
            programmaticScrollInProgress = false,
            focusNavigationInProgress = false,
        )

        assertThat(result).isNull()
    }

    @Test
    fun `live edge user scroll and focus navigation are never overridden`() {
        val candidate = listOf(candidate("event", offset = 8))
        val indices = mapOf("event" to 4)

        assertThat(resolve(candidate, indices, liveEdge = true)).isNull()
        assertThat(resolve(candidate, indices, userScroll = true)).isNull()
        assertThat(resolve(candidate, indices, programmaticScroll = true)).isNull()
        assertThat(resolve(candidate, indices, focusNavigation = true)).isNull()
    }

    private fun candidate(id: String, offset: Int, distance: Int = 0) =
        TimelineViewportAnchorCandidate(
            itemId = id,
            scrollOffset = offset,
            distanceFromFirstVisibleItem = distance,
        )

    private fun resolve(
        candidates: List<TimelineViewportAnchorCandidate>,
        indices: Map<String, Int>,
        liveEdge: Boolean = false,
        userScroll: Boolean = false,
        programmaticScroll: Boolean = false,
        focusNavigation: Boolean = false,
    ) = resolveTimelineViewportAnchor(
        candidates = candidates,
        currentItemIndices = indices,
        isAtLiveEdge = liveEdge,
        userScrollInProgress = userScroll,
        programmaticScrollInProgress = programmaticScroll,
        focusNavigationInProgress = focusNavigation,
    )
}
