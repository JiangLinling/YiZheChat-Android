/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.dataprotection.api

enum class FriendshipRemovalStatus { ACCEPTED, PENDING, REMOVED }

data class FriendshipRemovalSnapshot(
    val accountIdHash: String,
    val matrixDeviceIdHash: String,
    val installationId: String,
    val peerUserId: String,
    val roomId: String,
    val revision: String,
    val status: FriendshipRemovalStatus,
    val operationId: String? = null,
    val frozenRoomIds: Set<String> = emptySet(),
)

data class FriendshipRemovalIntent(
    val operationId: String,
    val snapshot: FriendshipRemovalSnapshot,
    val roomIds: Set<String>,
    val alreadyCompleted: Boolean,
)
