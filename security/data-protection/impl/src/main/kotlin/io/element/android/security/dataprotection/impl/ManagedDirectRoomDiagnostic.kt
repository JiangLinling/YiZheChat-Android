/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.dataprotection.impl

/** No response text, user, room, URL, token, or throwable is logged. */
internal fun managedDirectRoomFailureDiagnostic(status: Int, detail: String?, requestId: String?): String {
    val safeStatus = status.takeIf { it in 100..599 } ?: 0
    val code = detail?.takeIf { it.matches(Regex("(?:LC11|LC12|ROOM_ACCESS)_[A-Z0-9_]{1,96}")) } ?: "UNKNOWN"
    val correlation = requestId?.takeIf { it.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) } ?: "absent"
    return "LC11_DIRECT_HTTP_FAILED status=$safeStatus code=$code request=$correlation"
}
