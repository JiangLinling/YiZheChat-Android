/*
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.matrix.impl.timeline.item.event

import io.element.android.libraries.matrix.api.notification.RtcNotificationType
import io.element.android.libraries.matrix.api.timeline.item.event.EventType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Unknown or unavailable metadata must preserve the existing call row. */
internal fun rtcNotificationTypeFromSource(source: String?): RtcNotificationType? {
    if (source == null || source.length > 16_384) return null
    return runCatching {
        val event = Json.parseToJsonElement(source).jsonObject
        if (event["type"]?.jsonPrimitive?.content != EventType.RTC_NOTIFICATION) return null
        when (event["content"]?.jsonObject?.get("notification_type")?.jsonPrimitive?.content) {
            "ring" -> RtcNotificationType.RING
            "notification" -> RtcNotificationType.NOTIFY
            else -> null
        }
    }.getOrNull()
}
