/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.invite.api

import io.element.android.libraries.matrix.api.core.RoomId

/** Account notification projection. Display snapshots must only be persisted through Secure Storage. */
data class InviteNotification(
    val roomId: RoomId,
    val kind: Kind,
    val senderId: String?,
    val timestamp: Long,
    val outgoing: Boolean = false,
    val outcome: Outcome = Outcome.PENDING,
    val handledAt: Long = 0L,
    val seen: Boolean = false,
    val sequence: Long = 0,
    val active: Boolean = true,
    val receivedAt: Long = 0,
    val displayName: String? = null,
    val avatarUrl: String? = null,
) {
    enum class Kind { FRIEND, GROUP, SPACE }
    enum class Outcome { PENDING, ACCEPTED, DECLINED, UNAVAILABLE }

    val notificationTime: Long get() = receivedAt.takeIf { it > 0 } ?: maxOf(timestamp, handledAt)
}
