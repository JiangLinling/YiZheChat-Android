/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.messages.impl.timeline

import io.element.android.libraries.matrix.api.core.UniqueId
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import java.util.IdentityHashMap

/** Reuses only display aliases of the exact immutable source; never caches an authorization decision. */
internal class DisplayIdentityProjectionCache {
    private var previous = IdentityHashMap<MatrixTimelineItem.Event, MatrixTimelineItem.Event>()

    @Synchronized
    fun project(
        items: List<MatrixTimelineItem>,
        replacements: Map<Int, MatrixTimelineItem.Event>,
        displayIdFor: (Int, MatrixTimelineItem) -> UniqueId?,
    ): List<MatrixTimelineItem> {
        val retained = IdentityHashMap<MatrixTimelineItem.Event, MatrixTimelineItem.Event>()
        val result = items.mapIndexed { index, item ->
            replacements[index] ?: run {
                // Resolve the current binding on every emission, including previously missing bindings.
                val displayId = displayIdFor(index, item)
                if (item !is MatrixTimelineItem.Event || displayId == null || displayId == item.displayUniqueId) {
                    item
                } else {
                    val projection = previous[item]?.takeIf { it.displayUniqueId == displayId }
                        ?: item.copy(displayUniqueId = displayId)
                    retained[item] = projection
                    projection
                }
            }
        }
        // A removed source cannot keep its plaintext or native provider alive through this cache.
        previous = retained
        return result
    }
}
