/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.dataprotection.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.features.messages.api.MessageReferenceState
import io.element.android.features.messages.api.MessageReferenceStore
import io.element.android.features.messages.api.draft.ComposerDraftPreviewService
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.room.draft.ComposerDraft
import io.element.android.libraries.matrix.test.A_SESSION_ID
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.libraries.push.test.notifications.FakeNotificationCleaner
import io.element.android.security.dataprotection.api.DeletedIdentityProjectionCleanupTarget
import io.element.android.security.dataprotection.api.DeletedIdentityRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DeletedIdentityProjectionCleanerTest {
    @Test
    fun `clears only target rooms and treats repeated generation cleanup as idempotent`() = runTest {
        val firstRoom = RoomId("!deleted-one:example.org")
        val secondRoom = RoomId("!deleted-two:example.org")
        val draftUpdates = mutableListOf<RoomId>()
        val referenceDeletes = mutableListOf<RoomId>()
        val messageNotificationClears = mutableListOf<RoomId>()
        val membershipNotificationClears = mutableListOf<RoomId>()
        val cleaner = DefaultDeletedIdentityProjectionCleaner(
            matrixClient = FakeMatrixClient(sessionId = A_SESSION_ID),
            draftPreviewService = object : ComposerDraftPreviewService {
                override val previews = MutableStateFlow<Map<RoomId, String>>(emptyMap())
                override suspend fun refresh(roomIds: Collection<RoomId>) = Unit
                override fun update(roomId: RoomId, draft: ComposerDraft?) {
                    assertThat(draft).isNull()
                    draftUpdates += roomId
                }
            },
            messageReferenceStore = object : MessageReferenceStore {
                override val state = MutableStateFlow(MessageReferenceState())
                override suspend fun load() = Unit
                override suspend fun toggleFavourite(roomId: RoomId, eventId: EventId) = false
                override suspend fun addFavourites(roomId: RoomId, eventIds: Collection<EventId>) = Unit
                override suspend fun removeFavourite(roomId: RoomId, eventId: EventId) = Unit
                override suspend fun deleteLocally(roomId: RoomId, eventIds: Collection<EventId>) = Unit
                override suspend fun deleteConversation(roomId: RoomId, cutoffTimestamp: Long) {
                    assertThat(cutoffTimestamp).isEqualTo(Long.MAX_VALUE)
                    referenceDeletes += roomId
                }
            },
            notificationCleaner = FakeNotificationCleaner(
                clearMessagesForRoomLambda = { sessionId, roomId ->
                    assertThat(sessionId).isEqualTo(A_SESSION_ID)
                    messageNotificationClears += roomId
                },
                clearMembershipNotificationForRoomLambda = { sessionId, roomId ->
                    assertThat(sessionId).isEqualTo(A_SESSION_ID)
                    membershipNotificationClears += roomId
                },
            ),
        )
        val target = DeletedIdentityProjectionCleanupTarget(
            deletedIdentity = DeletedIdentityRecord("hash-a", 7, 12, 100),
            roomIds = setOf(firstRoom.value, secondRoom.value),
        )

        val firstResult = cleaner.clear(target)
        val replayResult = cleaner.clear(target)

        assertThat(firstResult.failedRoomIds).isEmpty()
        assertThat(replayResult.clearedRoomIds).containsExactly(firstRoom.value, secondRoom.value)
        assertThat(draftUpdates).containsExactly(firstRoom, secondRoom)
        assertThat(referenceDeletes).containsExactly(firstRoom, secondRoom)
        assertThat(messageNotificationClears).containsExactly(firstRoom, secondRoom)
        assertThat(membershipNotificationClears).containsExactly(firstRoom, secondRoom)
    }
}
