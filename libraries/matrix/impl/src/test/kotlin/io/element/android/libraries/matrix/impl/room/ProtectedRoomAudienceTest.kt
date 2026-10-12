/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.room

import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.room.RoomMember
import io.element.android.libraries.matrix.api.room.RoomMembershipState
import io.element.android.security.securestorage.api.accountIdHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProtectedRoomAudienceTest {
    @Test
    fun `pending invitation does not stop existing joined audience or receive shares`() {
        val members = listOf(member("sender"), member("peer"), member("pending", RoomMembershipState.INVITE))
        assertEquals(setOf(hash("sender"), hash("peer")), members.protectedRoomAudienceAccountIdHashes())
    }

    @Test
    fun `inviter can send while every invited peer is pending`() {
        val members = listOf(member("sender"), member("pending", RoomMembershipState.INVITE))
        assertEquals(setOf(hash("sender")), members.protectedRoomAudienceAccountIdHashes())
    }

    @Test
    fun `invite join leave and ban each change the audience from the supplied snapshot`() {
        for (membership in RoomMembershipState.entries) {
            val members = listOf(member("sender"), member("peer", membership))
            val expected = if (membership == RoomMembershipState.JOIN) setOf(hash("sender"), hash("peer")) else setOf(hash("sender"))
            assertEquals(expected, members.protectedRoomAudienceAccountIdHashes())
        }
    }

    @Test
    fun `service members never receive protected content even when joined`() {
        val members = listOf(member("sender"), member("service", isServiceMember = true))
        assertEquals(setOf(hash("sender")), members.protectedRoomAudienceAccountIdHashes())
    }

    @Test
    fun `duplicate users and input order cannot duplicate or change the audience`() {
        val members = listOf(member("sender"), member("peer"), member("peer"), member("pending", RoomMembershipState.INVITE))
        assertEquals(setOf(hash("sender"), hash("peer")), members.protectedRoomAudienceAccountIdHashes())
        assertEquals(members.protectedRoomAudienceAccountIdHashes(), members.reversed().protectedRoomAudienceAccountIdHashes())
    }

    @Test
    fun `empty or inactive snapshot never synthesizes recipients`() {
        assertEquals(emptySet<String>(), emptyList<RoomMember>().protectedRoomAudienceAccountIdHashes())
        assertEquals(emptySet<String>(), listOf(member("left", RoomMembershipState.LEAVE)).protectedRoomAudienceAccountIdHashes())
    }

    @Test
    fun `empty or invitation only snapshot fails before recipients can be resolved`() {
        for (members in listOf(emptyList(), listOf(member("sender", RoomMembershipState.INVITE), member("peer", RoomMembershipState.INVITE)))) {
            assertThrows(IllegalStateException::class.java) {
                members.protectedRoomAudienceAccountIdHashes().requireProtectedRoomSender(hash("sender"))
            }
        }
    }

    @Test
    fun `sender must be joined and nonservice even when another eligible member exists`() {
        for (membership in RoomMembershipState.entries.filter { it != RoomMembershipState.JOIN }) {
            assertThrows(IllegalStateException::class.java) {
                listOf(member("sender", membership), member("peer"))
                    .protectedRoomAudienceAccountIdHashes().requireProtectedRoomSender(hash("sender"))
            }
        }
        assertThrows(IllegalStateException::class.java) {
            listOf(member("sender", isServiceMember = true), member("peer"))
                .protectedRoomAudienceAccountIdHashes().requireProtectedRoomSender(hash("sender"))
        }
    }

    @Test
    fun `joined sender admission keeps the exact audience and pending peers receive nothing`() {
        val audience = listOf(member("sender"), member("peer"), member("pending", RoomMembershipState.INVITE))
            .protectedRoomAudienceAccountIdHashes()
        assertEquals(setOf(hash("sender"), hash("peer")), audience.requireProtectedRoomSender(hash("sender")))
        assertEquals(setOf(hash("sender")), listOf(member("sender"), member("pending", RoomMembershipState.INVITE))
            .protectedRoomAudienceAccountIdHashes().requireProtectedRoomSender(hash("sender")))
    }

    private fun hash(name: String) = accountIdHash("@group-fixture-$name:fixture.invalid")

    private fun member(name: String, membership: RoomMembershipState = RoomMembershipState.JOIN, isServiceMember: Boolean = false) = RoomMember(
        userId = UserId("@group-fixture-$name:fixture.invalid"),
        displayName = null,
        avatarUrl = null,
        membership = membership,
        isNameAmbiguous = false,
        powerLevel = 0,
        isIgnored = false,
        role = RoomMember.Role.User,
        membershipChangeReason = null,
        isServiceMember = isServiceMember,
    )
}
