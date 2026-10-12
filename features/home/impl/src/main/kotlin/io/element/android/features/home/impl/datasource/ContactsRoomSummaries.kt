/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.home.impl.datasource

import io.element.android.features.home.impl.model.LatestEvent
import io.element.android.features.home.impl.model.RoomListRoomSummary
import io.element.android.features.home.impl.model.RoomSummaryDisplayType
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.designsystem.components.avatar.AvatarSize
import io.element.android.libraries.matrix.api.room.CallIntentConsensus
import io.element.android.libraries.matrix.api.room.CurrentUserMembership
import io.element.android.libraries.matrix.api.roomlist.RoomList
import io.element.android.libraries.matrix.api.roomlist.RoomSummary
import io.element.android.libraries.matrix.ui.model.getAvatarData
import io.element.android.libraries.matrix.ui.model.toInviteSender
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/** Metadata-only directory from allRooms, independent of chat filters, drafts and conversation cutoffs. */
internal fun contactsRoomSummaries(
    summaries: List<RoomSummary>?,
    loadingState: RoomList.LoadingState,
): AsyncData<ImmutableList<RoomListRoomSummary>> {
    if (loadingState !is RoomList.LoadingState.Loaded || summaries.isNullOrEmpty() && loadingState.numberOfRooms > 0) {
        return AsyncData.Loading()
    }
    return AsyncData.Success(
        summaries.orEmpty().mapNotNull { summary ->
            val info = summary.info
            val displayType = when (info.currentUserMembership) {
                CurrentUserMembership.JOINED -> RoomSummaryDisplayType.ROOM
                CurrentUserMembership.INVITED -> RoomSummaryDisplayType.INVITE
                CurrentUserMembership.KNOCKED -> RoomSummaryDisplayType.KNOCKED
                CurrentUserMembership.LEFT, CurrentUserMembership.BANNED -> return@mapNotNull null
            }
            RoomListRoomSummary(
                id = summary.roomId.value,
                roomId = summary.roomId,
                name = info.name,
                canonicalAlias = info.canonicalAlias,
                displayType = displayType,
                numberOfUnreadMessages = info.numUnreadMessages,
                numberOfUnreadMentions = info.numUnreadMentions,
                numberOfUnreadNotifications = info.numUnreadNotifications,
                isMarkedUnread = info.isMarkedUnread,
                timestamp = null,
                latestEventTimestamp = summary.latestEventTimestamp ?: 0L,
                latestEvent = LatestEvent.None,
                avatarData = info.getAvatarData(size = AvatarSize.RoomListItem),
                userDefinedNotificationMode = info.userDefinedNotificationMode,
                hasRoomCall = info.hasRoomCall,
                activeCallIntent = when (val consensus = info.activeCallIntentConsensus) {
                    is CallIntentConsensus.Full -> consensus.callIntent
                    is CallIntentConsensus.Partial -> consensus.callIntent
                    CallIntentConsensus.None -> null
                },
                isDirect = info.isDirect,
                isDm = info.isDm,
                activeMembersCount = info.activeMembersCount,
                invitedMembersCount = info.invitedMembersCount,
                joinedMembersCount = info.joinedMembersCount,
                isFavorite = info.isFavorite,
                inviteSender = info.inviter?.toInviteSender(),
                heroes = info.heroes.map { it.getAvatarData(size = AvatarSize.RoomListItem) }.toImmutableList(),
                isTombstoned = info.successorRoom != null,
                isSpace = info.isSpace,
                isEncrypted = info.isEncrypted,
                historyVisibility = info.historyVisibility,
            )
        }.toImmutableList()
    )
}
