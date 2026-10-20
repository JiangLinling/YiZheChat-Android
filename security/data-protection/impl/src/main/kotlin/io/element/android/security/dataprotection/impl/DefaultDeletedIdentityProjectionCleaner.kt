/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.dataprotection.impl

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.features.messages.api.MessageReferenceStore
import io.element.android.features.messages.api.draft.ComposerDraftPreviewService
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.push.api.notifications.NotificationCleaner
import io.element.android.security.dataprotection.api.DeletedIdentityProjectionCleaner
import io.element.android.security.dataprotection.api.DeletedIdentityProjectionCleanupResult
import io.element.android.security.dataprotection.api.DeletedIdentityProjectionCleanupTarget
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class)
class DefaultDeletedIdentityProjectionCleaner(
    private val matrixClient: MatrixClient,
    private val draftPreviewService: ComposerDraftPreviewService,
    private val messageReferenceStore: MessageReferenceStore,
    private val notificationCleaner: NotificationCleaner,
) : DeletedIdentityProjectionCleaner {
    private val mutex = Mutex()
    private val completedRooms = mutableSetOf<CleanupKey>()

    override suspend fun clear(
        target: DeletedIdentityProjectionCleanupTarget,
    ): DeletedIdentityProjectionCleanupResult = mutex.withLock {
        val cleared = linkedSetOf<String>()
        val failed = linkedSetOf<String>()
        target.roomIds.forEach { roomIdValue ->
            val roomId = RoomId(roomIdValue)
            val key = CleanupKey(
                accountIdHash = target.deletedIdentity.accountIdHash,
                generation = target.deletedIdentity.generation,
                roomId = roomId,
            )
            if (key in completedRooms) {
                cleared += roomIdValue
                return@forEach
            }
            try {
                draftPreviewService.update(roomId, null)
                messageReferenceStore.deleteConversation(roomId, Long.MAX_VALUE)
                notificationCleaner.clearMessagesForRoom(matrixClient.sessionId, roomId)
                notificationCleaner.clearMembershipNotificationForRoom(matrixClient.sessionId, roomId)
                completedRooms += key
                cleared += roomIdValue
            } catch (_: Exception) {
                failed += roomIdValue
            }
        }
        DeletedIdentityProjectionCleanupResult(clearedRoomIds = cleared, failedRoomIds = failed)
    }

    private data class CleanupKey(
        val accountIdHash: String,
        val generation: Long,
        val roomId: RoomId,
    )
}
