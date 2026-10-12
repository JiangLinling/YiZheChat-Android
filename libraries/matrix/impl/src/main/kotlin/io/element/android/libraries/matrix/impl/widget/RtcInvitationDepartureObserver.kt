/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.widget

import io.element.android.libraries.matrix.api.widget.MatrixWidgetSettings
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.WidgetCapabilities
import org.matrix.rustcomponents.sdk.WidgetCapabilitiesProvider
import org.matrix.rustcomponents.sdk.WidgetEventFilter
import java.util.UUID

private const val RTC_NOTIFICATION = "org.matrix.msc4075.rtc.notification"

/** Native signalling observer. It has no media, send-event, file or admission capabilities. */
internal fun observeRtcInvitationDeparture(room: Room, roomId: String, invitationId: String): Flow<Boolean> = channelFlow {
    val id = "next-ringing-${UUID.randomUUID()}"
    val driver = RustWidgetDriver(
        MatrixWidgetSettings(id, false, "https://localhost.invalid/native-ringing"), room,
        object : WidgetCapabilitiesProvider {
            override fun acquireCapabilities(capabilities: WidgetCapabilities) = WidgetCapabilities(
                read = listOf(WidgetEventFilter.MessageLikeWithType(NEXT_RTC_MEMBER_EVENT_TYPE),
                    WidgetEventFilter.MessageLikeWithType(RTC_NOTIFICATION)),
                send = emptyList(), requiresClient = false, updateDelayedEvent = false,
                sendDelayedEvent = false, downloadFiles = false, sendStickyEvent = false, receiveStickyEvent = true,
            )
        },
    )
    val tracker = RtcInvitationDeparture(roomId, invitationId)
    launch(start = CoroutineStart.UNDISPATCHED) {
        driver.incomingMessages.collect { message ->
            val root = runCatching { Json.parseToJsonElement(message) as? JsonObject }.getOrNull() ?: return@collect
            if (root.string("widgetId") != id || root.string("api") != "toWidget" || "response" in root) return@collect
            val response = when (root.string("action")) {
                "capabilities" -> buildJsonObject {
                    putJsonArray("capabilities") {
                        add("org.matrix.msc2762.receive.event:$NEXT_RTC_MEMBER_EVENT_TYPE")
                        add("org.matrix.msc2762.receive.event:$RTC_NOTIFICATION")
                        add("org.matrix.msc4407.receive.sticky_event")
                    }
                }
                else -> buildJsonObject {}
            }
            // Acknowledge snapshot delivery immediately; the SDK has a bounded request window.
            driver.send(JsonObject(root + ("response" to response)).toString())
            if (root.string("action") == "send_event" && tracker.observe(root["data"] as? JsonObject)) send(true)
        }
    }
    launch(Dispatchers.IO) { driver.run() }
    try { awaitCancellation() } finally { driver.close() }
}

/** Snapshot order is arbitrary. An old leave can only end the parent member of this exact invitation. */
internal class RtcInvitationDeparture(private val roomId: String, private val invitationId: String) {
    private val events = linkedMapOf<String, JsonObject>()
    private var finished = false
    fun observe(event: JsonObject?): Boolean {
        if (finished) return true
        if (event == null || event.string("room_id") != roomId ||
            event.string("type") !in setOf(RTC_NOTIFICATION, NEXT_RTC_MEMBER_EVENT_TYPE)) return false
        val id = event.string("event_id")?.takeIf { it.startsWith('$') } ?: return false
        events[id] = event
        // Only short-lived RTC signalling, never message contents. Bound memory even in large rooms.
        if (events.size > 512) events.remove(events.keys.first())
        val invitation = events[invitationId] ?: return false
        val content = invitation["content"] as? JsonObject ?: return false
        if (invitation.string("type") != RTC_NOTIFICATION || content.string("notification_type") != "ring") return false
        val relation = content["m.relates_to"] as? JsonObject ?: return false
        if (relation.string("rel_type") != "m.reference") return false
        val parent = events[relation.string("event_id")] ?: return false
        val membership = parent["content"] as? JsonObject ?: return false
        val sender = invitation.string("sender") ?: return false
        val slot = content.string("slot_id") ?: return false
        val key = membership.string("msc4354_sticky_key")?.takeIf { it.isNotBlank() } ?: return false
        if (parent.string("sender") != sender || parent.string("type") != NEXT_RTC_MEMBER_EVENT_TYPE ||
            membership.string("slot_id") != slot || membership.keys == setOf("slot_id", "msc4354_sticky_key")) return false
        val notifiedAt = invitation.timestamp() ?: return false
        finished = events.values.any { leave ->
            val body = leave["content"] as? JsonObject
            leave.string("type") == NEXT_RTC_MEMBER_EVENT_TYPE && leave.string("sender") == sender &&
                (leave.timestamp() ?: 0) >= notifiedAt && body?.keys == setOf("slot_id", "msc4354_sticky_key") &&
                body.string("slot_id") == slot && body.string("msc4354_sticky_key") == key
        }
        return finished
    }
}

private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
private fun JsonObject.timestamp() = (get("origin_server_ts") as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }
