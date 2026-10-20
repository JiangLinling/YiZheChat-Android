/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.widget

import kotlinx.serialization.json.*

/** Room-wide mentions require moderator power, including in DMs. Invite joined recipients individually. */
internal suspend fun prepareGroupRingingMessage(message: String, joinedRecipients: suspend () -> List<String>): String {
    val root = runCatching { Json.parseToJsonElement(message) as? JsonObject }.getOrNull() ?: return message
    val data = root["data"] as? JsonObject ?: return message
    val content = data["content"] as? JsonObject ?: return message
    if (root.text("api") != "fromWidget" || root.text("action") != "send_event" || "response" in root ||
        data.text("type") != "org.matrix.msc4075.rtc.notification" || content.text("notification_type") != "ring") return message
    val mentions = content["m.mentions"] as? JsonObject ?: return message
    if ((mentions["room"] as? JsonPrimitive)?.booleanOrNull != true) return message
    val recipients = joinedRecipients().distinct()
    val updated = JsonObject(content + ("m.mentions" to buildJsonObject {
        putJsonArray("user_ids") { recipients.forEach { add(it) } }
    }))
    return JsonObject(root + ("data" to JsonObject(data + ("content" to updated)))).toString()
}

private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
