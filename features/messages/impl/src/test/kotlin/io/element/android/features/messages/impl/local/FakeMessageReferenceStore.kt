/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.local

import io.element.android.features.messages.api.MessageReferenceState
import io.element.android.features.messages.api.MessageReferenceStore
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakeMessageReferenceStore(
    initialState: MessageReferenceState = MessageReferenceState(),
) : MessageReferenceStore {
    private val mutableState = MutableStateFlow(initialState)
    override val state: StateFlow<MessageReferenceState> = mutableState

    override suspend fun load() = Unit
    override suspend fun saveReadingNavigation(roomId: RoomId, navigation: io.element.android.features.messages.api.RoomReadingNavigation) {
        mutableState.value = mutableState.value.copy(readingNavigation = mutableState.value.readingNavigation + (roomId.value to navigation))
    }
    override suspend fun saveLastViewedPosition(roomId: RoomId, eventId: EventId) {
        mutableState.value = mutableState.value.copy(lastViewedEventIds = mutableState.value.lastViewedEventIds + (roomId.value to eventId.value))
    }

    override suspend fun toggleFavourite(roomId: RoomId, eventId: EventId): Boolean {
        val key = MessageReferenceState.key(roomId, eventId)
        val adding = key !in mutableState.value.favouriteKeys
        mutableState.value = mutableState.value.copy(
            favouriteKeys = if (adding) mutableState.value.favouriteKeys + key else mutableState.value.favouriteKeys - key,
        )
        return adding
    }

    override suspend fun addFavourites(roomId: RoomId, eventIds: Collection<EventId>) {
        val keys = eventIds.mapTo(mutableSetOf()) { MessageReferenceState.key(roomId, it) }
        mutableState.value = mutableState.value.copy(favouriteKeys = mutableState.value.favouriteKeys + keys)
    }

    override suspend fun removeFavourite(roomId: RoomId, eventId: EventId) {
        val key = MessageReferenceState.key(roomId, eventId)
        mutableState.value = mutableState.value.copy(favouriteKeys = mutableState.value.favouriteKeys - key)
    }

    override suspend fun deleteLocally(roomId: RoomId, eventIds: Collection<EventId>) {
        val keys = eventIds.mapTo(mutableSetOf()) { MessageReferenceState.key(roomId, it) }
        mutableState.value = mutableState.value.copy(
            favouriteKeys = mutableState.value.favouriteKeys - keys,
            locallyDeletedKeys = mutableState.value.locallyDeletedKeys + keys,
        )
    }

    override suspend fun deleteUnavailableHistoryLocally(
        references: Collection<io.element.android.features.messages.api.MessageReference>,
        cutoffTimestamp: Long,
    ) {
        val keys = references.mapTo(mutableSetOf()) { reference ->
            MessageReferenceState.key(reference.roomId, reference.eventId)
        }
        mutableState.value = mutableState.value.copy(
            favouriteKeys = mutableState.value.favouriteKeys - keys,
            locallyDeletedKeys = mutableState.value.locallyDeletedKeys + keys,
            unavailableHistoryCutoffTimestamp = maxOf(
                mutableState.value.unavailableHistoryCutoffTimestamp ?: Long.MIN_VALUE,
                cutoffTimestamp,
            ),
        )
    }

    override suspend fun deleteConversation(roomId: RoomId, cutoffTimestamp: Long) {
        mutableState.value = mutableState.value.copy(
            conversationCutoffs = mutableState.value.conversationCutoffs + (roomId.value to cutoffTimestamp),
        )
    }
}
