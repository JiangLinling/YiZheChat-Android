/*
 * Copyright (c) 2026 YiZheChat.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.features.messages.impl.timeline

/** A stable, already-rendered event and its distance from the LazyList start edge. */
internal data class TimelineViewportAnchorCandidate(
    val itemId: String,
    val scrollOffset: Int,
    val distanceFromFirstVisibleItem: Int,
)

internal data class TimelineViewportAnchorRequest(
    val itemId: String,
    val targetIndex: Int,
    val scrollOffset: Int,
)

/** A batch may move the old latest row before LazyList reports its new index. Keep the pre-commit intent. */
internal fun shouldKeepTimelineAtLiveEdge(
    timelineChanged: Boolean,
    wasAtLiveEdge: Boolean,
    isLive: Boolean,
    userScrollInProgress: Boolean,
    programmaticScrollInProgress: Boolean,
    focusNavigationInProgress: Boolean,
): Boolean = timelineChanged && wasAtLiveEdge && isLive &&
    !userScrollInProgress && !programmaticScrollInProgress && !focusNavigationInProgress

/**
 * Selects an anchor only from events that are present in the newly committed timeline.
 *
 * This function never carries content across commits. In particular, a redacted or destroyed event
 * that is absent from [currentItemIndices] cannot be selected and therefore cannot be resurrected by
 * viewport stabilization.
 */
internal fun resolveTimelineViewportAnchor(
    candidates: List<TimelineViewportAnchorCandidate>,
    currentItemIndices: Map<String, Int>,
    isAtLiveEdge: Boolean,
    userScrollInProgress: Boolean,
    preserveCommitDuringUserScroll: Boolean = false,
    programmaticScrollInProgress: Boolean,
    focusNavigationInProgress: Boolean,
): TimelineViewportAnchorRequest? {
    if (
        isAtLiveEdge ||
        userScrollInProgress && !preserveCommitDuringUserScroll ||
        programmaticScrollInProgress ||
        focusNavigationInProgress ||
        currentItemIndices.isEmpty()
    ) {
        return null
    }

    return candidates
        .asSequence()
        .sortedBy(TimelineViewportAnchorCandidate::distanceFromFirstVisibleItem)
        .mapNotNull { candidate ->
            currentItemIndices[candidate.itemId]?.let { targetIndex ->
                TimelineViewportAnchorRequest(
                    itemId = candidate.itemId,
                    targetIndex = targetIndex,
                    scrollOffset = candidate.scrollOffset,
                )
            }
        }
        .firstOrNull()
}
