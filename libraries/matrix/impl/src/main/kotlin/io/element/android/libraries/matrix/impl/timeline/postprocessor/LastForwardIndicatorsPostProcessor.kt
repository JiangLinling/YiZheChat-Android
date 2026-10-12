/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.timeline.postprocessor

import io.element.android.libraries.matrix.api.core.UniqueId
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.matrix.api.timeline.item.virtual.VirtualTimelineItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** A cold timeline must keep marker history private to each subscription. */
internal fun Flow<List<MatrixTimelineItem>>.withLastForwardIndicators(mode: Timeline.Mode): Flow<List<MatrixTimelineItem>> = flow {
    val processor = LastForwardIndicatorsPostProcessor(mode)
    collect { emit(processor.process(it)) }
}

/**
 * This post processor is responsible for adding virtual items to indicate all the previous last forward item.
 */
class LastForwardIndicatorsPostProcessor(
    private val mode: Timeline.Mode,
) {
    private var lastForwardIdentifiers: Set<UniqueId> = emptySet()

    fun process(
        items: List<MatrixTimelineItem>,
    ): List<MatrixTimelineItem> {
        // We don't need to add the last forward indicator if we are not in the FOCUSED_ON_EVENT mode
        if (mode !is Timeline.Mode.FocusedOnEvent) {
            return items
        } else {
            val latestEventIdentifier = items.latestEventIdentifier()
            // Rendering reads one immutable snapshot, even if this processor is used concurrently.
            val historicalIdentifiers = synchronized(this) {
                val previous = lastForwardIdentifiers - latestEventIdentifier
                lastForwardIdentifiers = previous + latestEventIdentifier
                previous
            }
            return buildList {

                items.forEach { item ->
                    add(item)

                    if (item is MatrixTimelineItem.Event) {
                        if (item.uniqueId != latestEventIdentifier && item.uniqueId in historicalIdentifiers) {
                            add(createLastForwardIndicator(item.uniqueId))
                        }
                    }
                }
                // This is important to always add this one at the end of the list so it's used to keep the scroll position.
                add(createLastForwardIndicator(latestEventIdentifier))
            }
        }
    }
}

private fun createLastForwardIndicator(identifier: UniqueId): MatrixTimelineItem {
    return MatrixTimelineItem.Virtual(
        uniqueId = UniqueId("last_forward_indicator_$identifier"),
        virtual = VirtualTimelineItem.LastForwardIndicator
    )
}

private fun List<MatrixTimelineItem>.latestEventIdentifier(): UniqueId {
    return findLast {
        it is MatrixTimelineItem.Event
    }?.let {
        (it as MatrixTimelineItem.Event).uniqueId
    } ?: UniqueId("fake_id")
}
