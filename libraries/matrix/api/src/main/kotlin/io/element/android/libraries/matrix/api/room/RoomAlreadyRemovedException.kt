/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.matrix.api.room

import io.element.android.libraries.matrix.api.core.RoomId

/**
 * The homeserver has removed or purged this room while the SDK store still marks it joined.
 * The caller must rebuild the session cache because the SDK cannot forget a locally joined room.
 */
class RoomAlreadyRemovedException(
    val roomId: RoomId,
    cause: Throwable,
) : Exception("Room ${roomId.value} was already removed by the homeserver.", cause)
