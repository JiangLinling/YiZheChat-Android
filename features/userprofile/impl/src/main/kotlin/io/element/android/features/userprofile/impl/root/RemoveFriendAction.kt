/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.userprofile.impl.root

import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.room.CurrentUserMembership
import io.element.android.libraries.matrix.api.room.DirectRoomRelationState
import io.element.android.libraries.matrix.api.room.RoomInfo
import io.element.android.libraries.matrix.api.room.directRoomRelationState
import io.element.android.security.dataprotection.api.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** A read-only confirmation followed by the persisted LC11 intent and its original LC12 leaves. */
internal class RemoveFriendAction(
    private val client: MatrixClient,
    private val peer: UserId,
    private val friendship: FriendshipRemovalProtocol,
) {
    private val remainingRooms = linkedSetOf<RoomId>()
    private var snapshot: FriendshipRemovalSnapshot? = null
    private var intent: FriendshipRemovalIntent? = null

    suspend fun prepare(canonicalRoomId: RoomId? = null) = membershipStage(MembershipStage.CONFIRM_TARGETS) {
        check(!client.isMe(peer))
        val confirmed = membershipStage(MembershipStage.LC11_PREVIEW) {
            checkNotNull(friendship.preview(peer)) { "Friend authority is unavailable" }
        }
        check(confirmed.peerUserId == peer.value && confirmed.status != FriendshipRemovalStatus.REMOVED)
        val canonical = RoomId(confirmed.roomId)
        check(canonicalRoomId == null || canonicalRoomId == canonical)
        validate(client.getCurrentRoomInfo(canonical).getOrThrow(), canonical)
        val targets = linkedSetOf(canonical)
        if (confirmed.frozenRoomIds.isNotEmpty()) {
            targets.addAll(confirmed.frozenRoomIds.map(::RoomId))
        }
        for (id in (if (confirmed.frozenRoomIds.isEmpty()) client.getRemoteJoinedRoomIds().getOrThrow() else emptySet())
            .filter { it != canonical }) {
            checkNotNull(client.getRoom(id)) { "Room unavailable; refresh and retry" }.use { room ->
                val relation = room.info().directRoomRelationState()
                if (relation in setOf(DirectRoomRelationState.ACCEPTED, DirectRoomRelationState.OUTGOING_REQUEST)) {
                    if (checkNotNull(room.getDirectRoomMember()).userId == peer) targets += id
                }
            }
        }
        remainingRooms.clear()
        remainingRooms.addAll(targets)
        snapshot = confirmed
        intent = null
    }

    suspend fun execute() = membershipStage(MembershipStage.ROOM_IDENTITY) {
        val confirmed = checkNotNull(snapshot) { "Removal must be confirmed" }
        val original = intent ?: membershipStage(MembershipStage.LC11_BEGIN) {
            friendship.begin(confirmed, remainingRooms.map { it.value }.toSet())
        }.also { intent = it }
        check(original.snapshot == confirmed && original.roomIds.containsAll(remainingRooms.map { it.value }))
        withContext(LifecycleOperationContext(original.operationId, FullAppLifecycleId.LC_11)) {
            for (id in remainingRooms.toList()) {
                val info = client.getCurrentRoomInfo(id).getOrThrow()
                validate(info, id)
                if (info.currentUserMembership == CurrentUserMembership.JOINED) {
                    check(!original.alreadyCompleted) { "Membership changed after the original action" }
                    checkNotNull(client.getRoom(id)) { "Room unavailable; wait for sync" }.use { room ->
                        if (id.value != confirmed.roomId) check(room.getDirectRoomMember()?.userId == peer)
                        // Existing RustBaseRoom persists/recovers the exact LC12 membership operation.
                        membershipStage(MembershipStage.NATIVE_LEAVE) { room.leave().getOrThrow() }
                    }
                }
                membershipStage(MembershipStage.LOCAL_LEFT) {
                    checkNotNull(withTimeoutOrNull(30_000) {
                        while (true) {
                            val current = client.getCurrentRoomInfo(id).getOrThrow()
                            validate(current, id)
                            if (current.currentUserMembership == CurrentUserMembership.LEFT) return@withTimeoutOrNull true
                            delay(250)
                        }
                    }) { "Local SDK membership pending; resume the original action" }
                }
                remainingRooms.remove(id)
            }
            membershipStage(MembershipStage.LC11_COMMIT) { friendship.complete(original) }
        }
    }

    private fun validate(info: RoomInfo, id: RoomId) {
        check(info.id == id && !info.isSpace && info.joinedMembersCount + info.invitedMembersCount <= 2)
        check(info.currentUserMembership in setOf(CurrentUserMembership.JOINED, CurrentUserMembership.LEFT)) {
            "New membership requires a new confirmation"
        }
        // Leaving removes m.direct locally. LEFT is read from the SDK, never synthesized from a server receipt.
        if (info.currentUserMembership == CurrentUserMembership.JOINED) check(info.isDm)
    }
}
