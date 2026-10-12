/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.api.room

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DirectRoomRelationStateTest {
    @Test
    fun `a group is not governed by direct friendship restrictions`() {
        assertThat(state(isDm = false, joined = 8, active = 8)).isEqualTo(DirectRoomRelationState.NOT_DIRECT)
    }

    @Test
    fun `an invite is an incoming request`() {
        assertThat(state(membership = CurrentUserMembership.INVITED, joined = 1, invited = 1, active = 2))
            .isEqualTo(DirectRoomRelationState.INCOMING_REQUEST)
    }

    @Test
    fun `a joined creator with an invited peer is an outgoing request`() {
        assertThat(state(joined = 1, invited = 1, active = 2))
            .isEqualTo(DirectRoomRelationState.OUTGOING_REQUEST)
    }

    @Test
    fun `two joined members form an accepted direct relationship`() {
        assertThat(state(joined = 2, active = 2)).isEqualTo(DirectRoomRelationState.ACCEPTED)
    }

    @Test
    fun `a direct room with only the current member is stale`() {
        assertThat(state(joined = 1, active = 1)).isEqualTo(DirectRoomRelationState.STALE)
    }

    @Test
    fun `a multi-member direct room is invalid and cannot create friendships`() {
        val relationState = state(joined = 3, active = 3)
        assertThat(relationState).isEqualTo(DirectRoomRelationState.INVALID)
        assertThat(relationState.canInteract).isTrue()
    }

    @Test
    fun `an incomplete membership snapshot fails closed`() {
        assertThat(state(joined = 0, active = 0)).isEqualTo(DirectRoomRelationState.RESOLVING)
    }

    private fun state(
        isDm: Boolean = true,
        membership: CurrentUserMembership = CurrentUserMembership.JOINED,
        joined: Long,
        invited: Long = 0,
        active: Long,
    ) = directRoomRelationState(
        isDm = isDm,
        isSpace = false,
        currentUserMembership = membership,
        activeMembersCount = active,
        invitedMembersCount = invited,
        joinedMembersCount = joined,
    )
}
