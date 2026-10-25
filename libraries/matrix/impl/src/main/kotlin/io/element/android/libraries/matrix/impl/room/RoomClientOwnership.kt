/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.room

import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.SessionId
import java.util.concurrent.atomic.AtomicBoolean

/** Identifies a local Room's actual factory generation, without authorizing any operation. */
internal class RoomClientOwnership(
    private val factoryToken: Any?,
    private val sessionId: SessionId,
    private val roomId: RoomId,
) {
    private val closeRequested = AtomicBoolean(false)

    fun belongsTo(token: Any, session: SessionId, room: RoomId): Boolean =
        factoryToken === token && sessionId == session && roomId == room && !closeRequested.get()

    fun close() {
        closeRequested.set(true)
    }
}
