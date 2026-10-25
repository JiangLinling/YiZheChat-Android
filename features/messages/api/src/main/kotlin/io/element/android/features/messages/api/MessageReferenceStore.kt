/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.api

import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import kotlinx.coroutines.flow.StateFlow

/** A reference only. Message plaintext is deliberately not persisted in the favourites index. */
data class MessageReference(
    val roomId: RoomId,
    val eventId: EventId,
)

/** Private navigation metadata only; neither message content nor a Matrix read receipt. */
data class RoomReadingNavigation(
    val latestSeenEventId: String? = null,
    val firstNewEventId: String? = null,
    val batchEndEventId: String? = null,
    val hintRemainingMillis: Long = 8_000L,
    val hintActivated: Boolean = false,
)

data class MessageReferenceState(
    val favouriteKeys: Set<String> = emptySet(),
    val locallyDeletedKeys: Set<String> = emptySet(),
    /** Local-only cutoff used by "Delete chat". No message body is stored. */
    val conversationCutoffs: Map<String, Long> = emptyMap(),
    /**
     * Local-only boundary committed when the user closes the unavailable-history lifecycle.
     * It suppresses recovery prompts for older undecryptable candidates discovered by later
     * pagination, but never hides normally decryptable messages.
     */
    val unavailableHistoryCutoffTimestamp: Long? = null,
    /** Per-room canonical position, without message bodies or a public read receipt. */
    val lastViewedEventIds: Map<String, String> = emptyMap(),
    val readingNavigation: Map<String, RoomReadingNavigation> = emptyMap(),
    /** This account's actual local file display; never a download or remote read receipt. */
    val openedFileKeys: Set<String> = emptySet(),
) {
    val favourites: Set<MessageReference>
        get() = favouriteKeys.mapNotNullTo(linkedSetOf(), ::reference)

    fun isFavourite(roomId: RoomId, eventId: EventId): Boolean = key(roomId, eventId) in favouriteKeys
    fun isLocallyDeleted(roomId: RoomId, eventId: EventId): Boolean = key(roomId, eventId) in locallyDeletedKeys
    fun isFileOpened(roomId: RoomId, eventId: EventId): Boolean = key(roomId, eventId) in openedFileKeys
    fun conversationCutoff(roomId: RoomId): Long? = conversationCutoffs[roomId.value]
    fun isUnavailableHistoryDismissed(sentTimeMillis: Long): Boolean =
        unavailableHistoryCutoffTimestamp?.let { sentTimeMillis <= it } == true

    companion object {
        private const val KEY_SEPARATOR = '\u0000'

        fun key(roomId: RoomId, eventId: EventId): String = "${roomId.value}$KEY_SEPARATOR${eventId.value}"

        private fun reference(key: String): MessageReference? {
            val separatorIndex = key.indexOf(KEY_SEPARATOR)
            if (separatorIndex <= 0 || separatorIndex == key.lastIndex) return null
            return MessageReference(
                roomId = RoomId(key.substring(0, separatorIndex)),
                eventId = EventId(key.substring(separatorIndex + 1)),
            )
        }
    }
}

interface MessageReferenceStore {
    val state: StateFlow<MessageReferenceState>

    suspend fun load()
    suspend fun markFileOpened(roomId: RoomId, eventId: EventId) = Unit
    suspend fun saveLastViewedPosition(roomId: RoomId, eventId: EventId) = Unit
    suspend fun saveReadingNavigation(roomId: RoomId, navigation: RoomReadingNavigation) = Unit
    suspend fun toggleFavourite(roomId: RoomId, eventId: EventId): Boolean
    suspend fun addFavourites(roomId: RoomId, eventIds: Collection<EventId>)
    suspend fun removeFavourite(roomId: RoomId, eventId: EventId)
    suspend fun deleteLocally(roomId: RoomId, eventIds: Collection<EventId>)
    /** Atomically hides references that may span several rooms. */
    suspend fun deleteLocally(references: Collection<MessageReference>) {
        references.groupBy(MessageReference::roomId).forEach { (roomId, roomReferences) ->
            deleteLocally(roomId, roomReferences.map(MessageReference::eventId))
        }
    }
    /** Atomically hides the current candidates and closes prompts for older candidates loaded later. */
    suspend fun deleteUnavailableHistoryLocally(
        references: Collection<MessageReference>,
        cutoffTimestamp: Long,
    ) {
        deleteLocally(references)
    }
    suspend fun deleteConversation(roomId: RoomId, cutoffTimestamp: Long)
}
