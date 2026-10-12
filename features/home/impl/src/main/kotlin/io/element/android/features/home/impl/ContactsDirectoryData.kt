/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.home.impl

import io.element.android.features.home.impl.model.RoomListRoomSummary
import io.element.android.features.home.impl.model.RoomSummaryDisplayType
import io.element.android.features.home.impl.model.referencesDeletedDirectPeer
import io.element.android.libraries.matrix.api.room.DirectRoomRelationState

internal data class ContactDirectoryEntry(
    val room: RoomListRoomSummary,
    val displayName: String,
)

internal data class ContactsDirectoryData(
    val isLoading: Boolean = false,
    val friendRequests: List<RoomListRoomSummary> = emptyList(),
    val outgoingFriendRequests: List<RoomListRoomSummary> = emptyList(),
    val friendSections: Map<String, List<ContactDirectoryEntry>> = emptyMap(),
    val sectionIndices: Map<String, Int> = emptyMap(),
    val groups: List<RoomListRoomSummary> = emptyList(),
)

/**
 * Builds the contact projection once for a stable room/profile snapshot.
 *
 * In particular, [sortKey] may perform an ICU transliteration. Keeping it outside the comparator
 * makes that work linear in the number of friends instead of repeating it for every comparison.
 */
internal fun buildContactsDirectoryData(
    summaries: List<RoomListRoomSummary>,
    displayName: (RoomListRoomSummary) -> String,
    sortKey: (String) -> String,
    isDeletedMatrixUserId: (String) -> Boolean = { false },
): ContactsDirectoryData {
    val visibleSummaries = summaries.filterNot { it.referencesDeletedDirectPeer(isDeletedMatrixUserId) }
    val friendRequests = visibleSummaries
        .asSequence()
        .filter { it.displayType == RoomSummaryDisplayType.INVITE && it.isDm && !it.isSpace }
        .sortedByDescending { it.latestEventTimestamp }
        .toList()

    val outgoingFriendRequests = visibleSummaries
        .asSequence()
        .filter { it.directRoomRelationState() == DirectRoomRelationState.OUTGOING_REQUEST }
        .sortedByDescending { it.latestEventTimestamp }
        .toList()

    val friends = visibleSummaries
        .asSequence()
        .filter { it.directRoomRelationState() == DirectRoomRelationState.ACCEPTED }
        .distinctBy { it.avatarData.id }
        .map { room ->
            val resolvedName = displayName(room)
            val resolvedSortKey = sortKey(resolvedName)
            IndexedContact(
                entry = ContactDirectoryEntry(room, resolvedName),
                sortKey = resolvedSortKey,
                section = contactSection(resolvedSortKey),
            )
        }
        .sortedWith(compareBy<IndexedContact> { it.section == "#" }.thenBy { it.sortKey })
        .toList()

    val friendSections = friends
        .groupByTo(linkedMapOf(), keySelector = { it.section }, valueTransform = { it.entry })
    val sectionIndices = buildMap {
        var itemIndex = 1
        friendSections.forEach { (section, sectionFriends) ->
            put(section, itemIndex)
            itemIndex += 1 + sectionFriends.size
        }
    }
    val groups = visibleSummaries
        .asSequence()
        .filter {
            it.displayType == RoomSummaryDisplayType.ROOM &&
                !it.isSpace &&
                (!it.isDm || it.directRoomRelationState() == DirectRoomRelationState.INVALID)
        }
        .sortedBy { it.name.orEmpty().lowercase() }
        .toList()

    return ContactsDirectoryData(
        friendRequests = friendRequests,
        outgoingFriendRequests = outgoingFriendRequests,
        friendSections = friendSections,
        sectionIndices = sectionIndices,
        groups = groups,
    )
}

private data class IndexedContact(
    val entry: ContactDirectoryEntry,
    val sortKey: String,
    val section: String,
)

private fun contactSection(sortKey: String): String {
    val initial = sortKey.firstOrNull()?.uppercaseChar()
    return initial?.takeIf { it in 'A'..'Z' }?.toString() ?: "#"
}
