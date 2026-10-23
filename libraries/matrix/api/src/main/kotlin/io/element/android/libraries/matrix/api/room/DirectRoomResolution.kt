/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.api.room

import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.UserId

/** Result of this invocation only. Never persist or reuse it as permission to enter another chat. */
data class DirectRoomResolution(
    val roomId: RoomId,
    val managedEvidence: DirectRoomOperationEvidence? = null,
)

/** References from the verified managed response, not a second authority or an authorization cache. */
data class DirectRoomOperationEvidence(
    val roomId: RoomId,
    val accountIdHash: String,
    val matrixDeviceIdHash: String,
    val peerUserId: UserId,
    val status: String,
    val operationIds: List<String>,
    val installationId: String,
    val issuedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
)
