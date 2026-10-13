/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.api.timeline

import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.TransactionId
import java.util.UUID

/** Existing durable TEXT content/SDK transaction identity. This mapping grants no authority. */
fun protectedTextTransactionId(roomId: RoomId, transactionId: TransactionId): TransactionId = TransactionId(
    UUID.nameUUIDFromBytes("${roomId.value}\u0000${transactionId.value}".encodeToByteArray()).toString(),
)
