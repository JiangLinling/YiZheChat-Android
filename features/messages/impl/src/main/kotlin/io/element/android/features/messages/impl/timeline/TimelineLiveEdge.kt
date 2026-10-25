/*
 * Copyright (c) 2026 YiZheChat.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline

import androidx.compose.foundation.lazy.LazyListLayoutInfo
import io.element.android.features.messages.impl.timeline.model.TimelineItem

/** An old measurement is unknown, rather than proof that the reader moved into history. */
internal fun hasCurrentTimelineMeasurement(items: List<TimelineItem>, layout: LazyListLayoutInfo): Boolean {
    if (layout.viewportSize.height <= 0 || layout.totalItemsCount != items.size) return false
    if (items.isEmpty()) return true
    return layout.visibleItemsInfo.isNotEmpty() && layout.visibleItemsInfo.all { row ->
        items.getOrNull(row.index)?.identifier() == row.key
    }
}

/**
 * Offset zero is the logical latest edge (bottom in reverse layout, top for TalkBack).
 * A tall first row can still have index zero while its latest edge is already off screen.
 * Virtual receipt/typing rows may precede the latest event; they are not a history threshold.
 */
internal fun isTimelineAtLiveEdge(items: List<TimelineItem>, layout: LazyListLayoutInfo): Boolean {
    if (layout.viewportSize.height <= 0 || layout.totalItemsCount != items.size) return false
    val latestEventIndex = items.indexOfFirst { it.events().isNotEmpty() }
    if (latestEventIndex < 0) return false
    val latestRow = layout.visibleItemsInfo.firstOrNull { it.index == latestEventIndex } ?: return false
    val visibleEnd = layout.viewportEndOffset - layout.afterContentPadding
    return latestRow.key == items[latestEventIndex].identifier() && latestRow.offset >= 0 && latestRow.offset < visibleEnd
}
