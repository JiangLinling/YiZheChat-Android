/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.userprofile.impl.root

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.security.dataprotection.api.FriendshipRemovalIntent
import io.element.android.security.dataprotection.api.FriendshipRemovalSnapshot
import io.element.android.security.dataprotection.api.ManagementControlService
import java.security.MessageDigest

interface FriendshipRemovalProtocol {
    suspend fun preview(peer: UserId): FriendshipRemovalSnapshot?
    suspend fun begin(snapshot: FriendshipRemovalSnapshot, rooms: Set<String>): FriendshipRemovalIntent
    suspend fun complete(intent: FriendshipRemovalIntent)
}

@Inject
@ContributesBinding(SessionScope::class)
class DefaultFriendshipRemovalProtocol(
    private val client: MatrixClient,
    private val management: ManagementControlService,
) : FriendshipRemovalProtocol {
    override suspend fun preview(peer: UserId) = management.friendshipRemovalPreview(
        hash(client.sessionId.value), hash(client.deviceId.value), peer.value,
    )
    override suspend fun begin(snapshot: FriendshipRemovalSnapshot, rooms: Set<String>) = management.beginFriendshipRemoval(snapshot, rooms)
    override suspend fun complete(intent: FriendshipRemovalIntent) = management.completeFriendshipRemoval(intent)
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
