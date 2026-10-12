/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline.groups

import androidx.annotation.VisibleForTesting
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.features.messages.impl.timeline.model.GroupedEventsKind
import io.element.android.features.messages.impl.timeline.model.TimelineItem
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemImageContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemVideoContent
import io.element.android.libraries.di.RoomScope
import io.element.android.libraries.matrix.api.core.UniqueId
import kotlinx.collections.immutable.toImmutableList

@SingleIn(RoomScope::class)
@Inject
class TimelineItemGrouper {
    /**
     * Keys are identifier of items in a group, only one by group will be kept.
     * Values are the actual groupIds.
     */
    private val groupIds = HashMap<String, String>()

    /**
     * Create a new list of [TimelineItem] by grouping some of them into [TimelineItem.GroupedEvents].
     */
    fun group(from: List<TimelineItem>): List<TimelineItem> {
        val result = mutableListOf<TimelineItem>()
        val currentRoomChanges = mutableListOf<TimelineItem.Event>()
        val currentMediaAlbum = mutableListOf<TimelineItem.Event>()

        fun flushRoomChanges() {
            if (currentRoomChanges.isNotEmpty()) {
                result.addGroup(groupIds, currentRoomChanges)
                currentRoomChanges.clear()
            }
        }

        fun flushMediaAlbum() {
            if (currentMediaAlbum.isNotEmpty()) {
                result.addMediaAlbum(currentMediaAlbum)
                currentMediaAlbum.clear()
            }
        }

        from.forEach { timelineItem ->
            val album = (timelineItem as? TimelineItem.Event)?.mediaAlbum()
            when {
                timelineItem is TimelineItem.Event && album != null -> {
                    flushRoomChanges()
                    val activeAlbum = currentMediaAlbum.firstOrNull()
                        ?.mediaAlbum()
                    val sameAlbum = activeAlbum?.id == album.id &&
                        activeAlbum.count == album.count &&
                        currentMediaAlbum.firstOrNull()?.senderId == timelineItem.senderId &&
                        currentMediaAlbum.none { event ->
                            event.mediaAlbum()?.index == album.index
                        }
                    if (!sameAlbum) {
                        flushMediaAlbum()
                    }
                    currentMediaAlbum += timelineItem
                }
                timelineItem is TimelineItem.Event && timelineItem.canBeGrouped() -> {
                    flushMediaAlbum()
                    currentRoomChanges.add(0, timelineItem)
                }
                else -> {
                    flushMediaAlbum()
                    flushRoomChanges()
                    result += timelineItem
                }
            }
        }
        flushMediaAlbum()
        flushRoomChanges()
        return result
    }
}

private fun MutableList<TimelineItem>.addMediaAlbum(groupOfItems: MutableList<TimelineItem.Event>) {
    if (groupOfItems.size == 1) {
        add(groupOfItems.first())
        return
    }
    val ordered = groupOfItems.sortedBy { event ->
        event.mediaAlbum()?.index
    }
    val albumId = requireNotNull(ordered.first().mediaAlbum()).id
    add(
        TimelineItem.GroupedEvents(
            id = UniqueId("media-album:$albumId"),
            events = ordered.toImmutableList(),
            aggregatedReadReceipts = ordered.flatMap { it.readReceiptState.receipts }.distinct().toImmutableList(),
            kind = GroupedEventsKind.MEDIA_ALBUM,
        )
    )
}

private fun TimelineItem.Event.mediaAlbum() = when (val mediaContent = content) {
    is TimelineItemImageContent -> mediaContent.mediaAlbum
    is TimelineItemVideoContent -> mediaContent.mediaAlbum
    else -> null
}

/**
 * Will add a group if there is more than 1 item, else add the item to the list.
 */
private fun MutableList<TimelineItem>.addGroup(
    groupIds: MutableMap<String, String>,
    groupOfItems: MutableList<TimelineItem.Event>
) {
    if (groupOfItems.size == 1) {
        // Do not create a group with just 1 item, just add the item to the result
        add(groupOfItems.first())
    } else {
        val groupId = groupIds.getOrPutGroupId(groupOfItems)
        add(
            TimelineItem.GroupedEvents(
                id = UniqueId(groupId),
                events = groupOfItems.toImmutableList(),
                aggregatedReadReceipts = groupOfItems.flatMap { it.readReceiptState.receipts }.toImmutableList()
            )
        )
    }
}

private fun MutableMap<String, String>.getOrPutGroupId(timelineItems: List<TimelineItem>): String {
    assert(timelineItems.isNotEmpty())
    for (item in timelineItems) {
        val itemIdentifier = item.identifier()
        if (this.contains(itemIdentifier.value)) {
            return this[itemIdentifier.value]!!
        }
    }
    val timelineItem = timelineItems.first()
    return computeGroupIdWith(timelineItem).value.also { groupId ->
        this[timelineItem.identifier().value] = groupId
    }
}

@VisibleForTesting
internal fun computeGroupIdWith(timelineItem: TimelineItem): UniqueId = UniqueId("${timelineItem.identifier()}_group")
