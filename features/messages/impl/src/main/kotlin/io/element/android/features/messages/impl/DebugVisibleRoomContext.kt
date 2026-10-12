/*
 * Copyright (c) 2026 YiZheChat.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.features.messages.impl

import java.security.MessageDigest

/** Debug-only visible-node identity for read-only diagnostics. Never authorizes protected reads. */
internal fun debugVisibleRoomContextTag(
    debug: Boolean,
    sessionId: String,
    clientSessionId: String,
    deviceId: String,
    roomId: String,
    processId: Int,
): String? {
    if (!debug || processId <= 0 || sessionId.isBlank() || roomId.isBlank() || deviceId.isBlank()) return null
    if (sessionId != clientSessionId) return null
    fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    return "yizhe_debug_room_v2_${processId}_${hash(sessionId)}_${hash(roomId)}_${hash(deviceId)}"
}
