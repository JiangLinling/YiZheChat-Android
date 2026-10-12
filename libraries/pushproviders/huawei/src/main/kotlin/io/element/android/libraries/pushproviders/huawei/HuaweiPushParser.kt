/*
 * Copyright 2026 YiZheChat
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.pushproviders.huawei

import dev.zacsweers.metro.Inject
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.pushproviders.api.PushData

@Inject
class HuaweiPushParser {
    fun parse(message: Map<String, String>): PushData? {
        val eventId = message["event_id"]?.takeIf(String::isNotBlank) ?: return null
        val roomId = message["room_id"]?.takeIf(String::isNotBlank) ?: return null
        val clientSecret = message["cs"]?.takeIf(String::isNotBlank) ?: return null
        return PushData(
            eventId = EventId(eventId),
            roomId = RoomId(roomId),
            unread = message["unread"]?.toIntOrNull(),
            clientSecret = clientSecret,
        )
    }
}
