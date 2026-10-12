/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.api.room

/**
 * Product-facing projection of a Matrix direct room.
 *
 * This does not persist a second friendship authority. It deliberately derives the state from the
 * existing Matrix membership snapshot so feature code can fail closed while a direct invitation is
 * still pending, without changing room creation, encryption, storage, or server lifecycles.
 */
enum class DirectRoomRelationState {
    NOT_DIRECT,
    INCOMING_REQUEST,
    OUTGOING_REQUEST,
    ACCEPTED,
    STALE,
    INVALID,
    RESOLVING,
    ;

    val canInteract: Boolean
        get() = this == NOT_DIRECT || this == ACCEPTED || this == INVALID

    val isPendingRequest: Boolean
        get() = this == INCOMING_REQUEST || this == OUTGOING_REQUEST
}

fun RoomInfo.directRoomRelationState(): DirectRoomRelationState = directRoomRelationState(
    isDm = isDm,
    isSpace = isSpace,
    currentUserMembership = currentUserMembership,
    activeMembersCount = activeMembersCount,
    invitedMembersCount = invitedMembersCount,
    joinedMembersCount = joinedMembersCount,
)

fun directRoomRelationState(
    isDm: Boolean,
    isSpace: Boolean,
    currentUserMembership: CurrentUserMembership,
    activeMembersCount: Long,
    invitedMembersCount: Long,
    joinedMembersCount: Long,
): DirectRoomRelationState {
    if (!isDm || isSpace) return DirectRoomRelationState.NOT_DIRECT
    if (currentUserMembership == CurrentUserMembership.INVITED) {
        return DirectRoomRelationState.INCOMING_REQUEST
    }
    if (currentUserMembership != CurrentUserMembership.JOINED) {
        return DirectRoomRelationState.STALE
    }
    if (activeMembersCount > 2L || joinedMembersCount > 2L || invitedMembersCount > 1L) {
        return DirectRoomRelationState.INVALID
    }
    return when {
        joinedMembersCount == 2L && invitedMembersCount == 0L && activeMembersCount == 2L ->
            DirectRoomRelationState.ACCEPTED
        joinedMembersCount == 1L && invitedMembersCount == 1L && activeMembersCount == 2L ->
            DirectRoomRelationState.OUTGOING_REQUEST
        joinedMembersCount == 1L && invitedMembersCount == 0L && activeMembersCount <= 1L ->
            DirectRoomRelationState.STALE
        else -> DirectRoomRelationState.RESOLVING
    }
}
