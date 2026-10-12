/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.home.impl.model

import io.element.android.security.dataprotection.api.DeletedIdentityProjection
import io.element.android.security.dataprotection.api.DeletedIdentityRecord
import io.element.android.security.dataprotection.api.isDeletedMatrixUserId
import io.element.android.security.dataprotection.api.recordForMatrixUserId

/**
 * Suppress only a direct relationship whose peer has an authoritative ACCOUNT tombstone.
 * Group rooms are deliberately retained: LC-22 removes the deleted member but must not erase a
 * group owned by another account merely because the deleted identity once appeared in its heroes.
 */
internal fun RoomListRoomSummary.referencesDeletedDirectPeer(
    deletedIdentityProjection: DeletedIdentityProjection,
): Boolean = deletedDirectPeerRecords(deletedIdentityProjection).isNotEmpty()

internal fun RoomListRoomSummary.deletedDirectPeerRecords(
    deletedIdentityProjection: DeletedIdentityProjection,
): Set<DeletedIdentityRecord> {
    if (!isDm) return emptySet()
    return buildSet {
        heroes.mapNotNullTo(this) { deletedIdentityProjection.recordForMatrixUserId(it.id) }
        inviteSender?.userId?.value
            ?.let(deletedIdentityProjection::recordForMatrixUserId)
            ?.let(::add)
    }
}

internal fun RoomListRoomSummary.referencesDeletedDirectPeer(
    isDeletedMatrixUserId: (String) -> Boolean,
): Boolean {
    if (!isDm) return false
    val peerUserIds = buildSet {
        heroes.mapTo(this) { it.id }
        inviteSender?.userId?.value?.let(::add)
    }
    return peerUserIds.any(isDeletedMatrixUserId)
}
