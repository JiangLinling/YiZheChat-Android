/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.widget

import kotlinx.serialization.json.*

private const val CLEANUP = "org.yizhe.rtc.membership_cleanup"

/** Presentation metadata only. Actual departure is proven separately by Matrix and RTC receipts. */
internal fun rtcMembershipRedactionContent(eventId: String): String = buildJsonObject {
    putJsonObject(CLEANUP) { put("event_id", eventId); put("type", NEXT_RTC_MEMBER_EVENT_TYPE) }
}.toString()

/** An accepted same-sender redaction of the exact encrypted carrier, never an arbitrary removed message. */
internal fun isRtcMembershipCleanup(raw: String?, eventId: String?, sender: String): Boolean = runCatching {
    if (eventId?.startsWith('$') != true) return false
    val event = Json.parseToJsonElement(raw ?: return false) as? JsonObject ?: return false
    fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
    if (event.text("event_id") != eventId || event.text("sender") != sender || event.text("type") != "m.room.encrypted") return false
    if ((event["content"] as? JsonObject)?.isNotEmpty() != false) return false
    val redaction = (event["unsigned"] as? JsonObject)?.get("redacted_because") as? JsonObject ?: return false
    if (redaction.text("type") != "m.room.redaction" || redaction.text("sender") != sender ||
        redaction.text("event_id")?.startsWith('$') != true) return false
    val content = redaction["content"] as? JsonObject ?: return false
    val targets = listOfNotNull(redaction.text("redacts"), content.text("redacts"))
    if (targets.isEmpty() || targets.any { it != eventId }) return false
    val marker = content[CLEANUP] as? JsonObject ?: return false
    marker.text("event_id") == eventId && marker.text("type") == NEXT_RTC_MEMBER_EVENT_TYPE
}.getOrDefault(false)
