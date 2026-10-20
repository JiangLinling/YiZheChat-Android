/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.home.impl.datasource

import dev.zacsweers.metro.Inject
import io.element.android.features.home.impl.model.LatestEvent
import io.element.android.features.home.impl.model.RoomListRoomSummary
import io.element.android.features.home.impl.model.RoomSummaryDisplayType
import io.element.android.libraries.core.extensions.orEmpty
import io.element.android.libraries.dateformatter.api.DateFormatter
import io.element.android.libraries.dateformatter.api.DateFormatterMode
import io.element.android.libraries.designsystem.components.avatar.AvatarSize
import io.element.android.libraries.eventformatter.api.RoomLatestEventFormatter
import io.element.android.libraries.matrix.api.room.CallIntentConsensus
import io.element.android.libraries.matrix.api.room.CurrentUserMembership
import io.element.android.libraries.matrix.api.roomlist.LatestEventValue
import io.element.android.libraries.matrix.api.roomlist.RoomSummary
import io.element.android.libraries.matrix.api.timeline.item.event.isAuthorizedForUserDisplay
import io.element.android.libraries.matrix.ui.model.getAvatarData
import io.element.android.libraries.matrix.ui.model.toInviteSender
import io.element.android.security.dataprotection.api.DataProtectionPolicyProvider
import io.element.android.security.dataprotection.api.ProtectedPreviewAuthorizationGate
import kotlinx.collections.immutable.toImmutableList

@Inject
class RoomListRoomSummaryFactory(
    private val dateFormatter: DateFormatter,
    private val roomLatestEventFormatter: RoomLatestEventFormatter,
    private val dataProtectionPolicyProvider: DataProtectionPolicyProvider,
    private val protectedPreviewAuthorizationGate: ProtectedPreviewAuthorizationGate,
) {
    fun create(roomSummary: RoomSummary): RoomListRoomSummary {
        val roomInfo = roomSummary.info
        val avatarData = roomInfo.getAvatarData(size = AvatarSize.RoomListItem)
        val latestEventAuthorized = roomSummary.latestEvent.isAuthorizedForRoomSummary()
        val displayName = roomInfo.invitationDisplayName()
        return RoomListRoomSummary(
            id = roomSummary.roomId.value,
            roomId = roomSummary.roomId,
            name = displayName,
            numberOfUnreadMessages = roomInfo.numUnreadMessages,
            numberOfUnreadMentions = roomInfo.numUnreadMentions,
            numberOfUnreadNotifications = roomInfo.numUnreadNotifications,
            isMarkedUnread = roomInfo.isMarkedUnread,
            timestamp = dateFormatter.format(
                timestamp = roomSummary.latestEventTimestamp,
                mode = DateFormatterMode.TimeOrDate,
                useRelative = true,
            ),
            latestEventTimestamp = roomSummary.latestEventTimestamp ?: 0L,
            latestEvent = if (latestEventAuthorized) {
                computeLatestEvent(roomSummary.latestEvent, roomInfo.isDm)
            } else {
                LatestEvent.Protected
            },
            avatarData = avatarData,
            userDefinedNotificationMode = roomInfo.userDefinedNotificationMode,
            hasRoomCall = roomInfo.hasRoomCall,
            activeCallIntent = when (val consensus = roomInfo.activeCallIntentConsensus) {
                is CallIntentConsensus.Full -> consensus.callIntent
                is CallIntentConsensus.Partial -> consensus.callIntent
                CallIntentConsensus.None -> null
            },
            isDirect = roomInfo.isDirect,
            isFavorite = roomInfo.isFavorite,
            inviteSender = roomInfo.inviter?.toInviteSender(),
            isDm = roomInfo.isDm || roomInfo.isDirectInvitation,
            activeMembersCount = roomInfo.activeMembersCount,
            invitedMembersCount = roomInfo.invitedMembersCount,
            joinedMembersCount = roomInfo.joinedMembersCount,
            canonicalAlias = roomInfo.canonicalAlias,
            displayType = when (roomInfo.currentUserMembership) {
                CurrentUserMembership.INVITED -> {
                    RoomSummaryDisplayType.INVITE
                }
                CurrentUserMembership.KNOCKED -> {
                    RoomSummaryDisplayType.KNOCKED
                }
                else -> {
                    RoomSummaryDisplayType.ROOM
                }
            },
            heroes = roomInfo.heroes.map { user ->
                user.getAvatarData(size = AvatarSize.RoomListItem)
            }.toImmutableList(),
            isTombstoned = roomInfo.successorRoom != null,
            isSpace = roomInfo.isSpace,
            isEncrypted = roomInfo.isEncrypted,
            historyVisibility = roomInfo.historyVisibility,
        )
    }

    private fun LatestEventValue.isAuthorizedForRoomSummary(): Boolean = when (this) {
        is LatestEventValue.Local -> content.isAuthorizedForRoomPreview()
        is LatestEventValue.Remote -> content.isAuthorizedForRoomPreview()
        LatestEventValue.None,
        is LatestEventValue.RoomInvite -> true
    }

    private fun io.element.android.libraries.matrix.api.timeline.item.event.EventContent.isAuthorizedForRoomPreview(): Boolean {
        val requiresProtection = dataProtectionPolicyProvider.current().policy.requireContentEnvelope
        if (!isAuthorizedForUserDisplay(requireContentProtection = requiresProtection)) return false
        if (!requiresProtection) return true
        return when (this) {
            is io.element.android.libraries.matrix.api.timeline.item.event.MessageContent ->
                protectedPreviewAuthorizationGate.canDisplayAuthorizedContent(isContentProtectionAuthorized)
            // Call-state and other non-message events do not carry a protected message body, so
            // they cannot have a content-envelope authorization marker. They still require the
            // same unlocked, trusted in-process preview boundary, but must not be mislabeled as a
            // protected message merely because the marker is structurally inapplicable.
            else -> protectedPreviewAuthorizationGate.canAttemptPreview()
        }
    }

    private fun computeLatestEvent(latestEvent: LatestEventValue, dm: Boolean): LatestEvent {
        return when (latestEvent) {
            is LatestEventValue.None -> {
                LatestEvent.None
            }
            is LatestEventValue.Local -> {
                if (latestEvent.isSending) {
                    val content = roomLatestEventFormatter.format(latestEvent, dm).orEmpty()
                    LatestEvent.Sending(
                        content = content,
                    )
                } else {
                    LatestEvent.Error
                }
            }
            is LatestEventValue.Remote -> {
                val content = roomLatestEventFormatter.format(latestEvent, dm).orEmpty()
                LatestEvent.Synced(
                    content = content,
                )
            }
            is LatestEventValue.RoomInvite -> LatestEvent.None
        }
    }
}
