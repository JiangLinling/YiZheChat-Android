/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.room

import io.element.android.libraries.matrix.api.room.RoomMember
import io.element.android.libraries.matrix.api.room.RoomMembershipState
import io.element.android.security.securestorage.api.accountIdHash

/** Invitations remain visible in member UI, but only joined members belong to a protected send audience. */
internal fun Iterable<RoomMember>.protectedRoomAudienceAccountIdHashes(): Set<String> =
    asSequence()
        .filter { !it.isServiceMember && it.membership == RoomMembershipState.JOIN }
        .map { accountIdHash(it.userId.value) }
        .toSet()

/** A missing joined sender must fail before recipient lookup; adding the sender cannot repair an unknown snapshot. */
internal fun Set<String>.requireProtectedRoomSender(senderAccountIdHash: String): Set<String> = also {
    check(senderAccountIdHash in it) { "Protected sending requires joined sender membership." }
}
