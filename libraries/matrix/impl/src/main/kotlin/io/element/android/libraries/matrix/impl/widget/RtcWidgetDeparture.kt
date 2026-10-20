/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.widget

import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.UUID

/** Only handles delayed empty RTC memberships registered by this exact widget, never arbitrary delay IDs. */
internal class RtcWidgetDeparture(private val widgetId: String) {
    private val registrations = mutableSetOf<String>()
    private val delays = mutableSetOf<String>()
    private val completions = mutableMapOf<String, CompletableDeferred<Boolean>>()
    private val joins = mutableSetOf<String>()
    private val joinedEvents = mutableSetOf<String>()

    /** Exact server echoes of this widget's own membership writes; never accepts IDs from a peer. */
    @Synchronized
    fun membershipEvents(): List<String> = joinedEvents.toList()

    @Synchronized
    fun sent(message: String) {
        val envelope = parse(message) ?: return
        if (!isOwn(envelope) || "response" in envelope || envelope.text("action") != "send_event") return
        val data = envelope["data"] as? JsonObject ?: return
        val content = data["content"] as? JsonObject ?: return
        if (data.text("type") == NEXT_RTC_MEMBER_EVENT_TYPE && "state_key" !in data && "delay" !in data &&
            content["member"] is JsonObject && content.text("slot_id") == "m.call#ROOM" &&
            !content.text("msc4354_sticky_key").isNullOrBlank()
        ) {
            envelope.text("requestId")?.let(joins::add)
            return
        }
        if (data.text("type") != NEXT_RTC_MEMBER_EVENT_TYPE || "state_key" in data ||
            (data["delay"] as? JsonPrimitive)?.longOrNull?.let { it > 0 } != true ||
            (data["sticky_duration_ms"] as? JsonPrimitive)?.longOrNull?.let { it > 0 } != true ||
            content.keys != setOf("slot_id", "msc4354_sticky_key") ||
            content.text("slot_id").isNullOrBlank() || content.text("msc4354_sticky_key").isNullOrBlank()
        ) return
        envelope.text("requestId")?.let(registrations::add)
    }

    /** Returns true for host-owned cleanup responses, which must not be forwarded to the destroyed widget. */
    @Synchronized
    fun received(message: String): Boolean {
        val envelope = parse(message) ?: return false
        if (!isOwn(envelope)) return false
        val response = envelope["response"] as? JsonObject ?: return false
        val requestId = envelope.text("requestId") ?: return false
        if (envelope.text("action") == "send_event" && joins.remove(requestId) && "error" !in response) {
            response.text("event_id")?.takeIf { it.startsWith('$') }?.let(joinedEvents::add)
        }
        if (envelope.text("action") == UPDATE && completions.containsKey(requestId)) {
            completions.remove(requestId)?.complete("error" !in response)
            return true
        }
        if (envelope.text("action") == "send_event" && registrations.remove(requestId) && "error" !in response) {
            response.text("delay_id")?.takeIf { it.isNotBlank() }?.let(delays::add)
        }
        return false
    }

    suspend fun depart(send: suspend (String) -> Boolean): Boolean {
        val pending = synchronized(this) {
            delays.map { delayId ->
                val requestId = "next-rtc-departure-${UUID.randomUUID()}"
                val completed = CompletableDeferred<Boolean>()
                completions[requestId] = completed
                val request = buildJsonObject {
                    put("api", "fromWidget")
                    put("widgetId", widgetId)
                    put("requestId", requestId)
                    put("action", UPDATE)
                    put("data", buildJsonObject { put("delay_id", delayId); put("action", "send") })
                }
                Triple(requestId, request.toString(), completed)
            }
        }
        try {
            // Enqueue all registered leaves before awaiting any reply. Expired/replaced IDs can fail independently.
            for ((_, request, completed) in pending) if (!send(request)) completed.complete(false)
            var acknowledged = false
            for ((_, _, completed) in pending) acknowledged = completed.await() || acknowledged
            return acknowledged
        } finally {
            synchronized(this) { pending.forEach { completions.remove(it.first) }; delays.clear() }
        }
    }

    private fun isOwn(envelope: JsonObject) = envelope.text("api") == "fromWidget" && envelope.text("widgetId") == widgetId
    private fun parse(message: String) = runCatching { Json.parseToJsonElement(message) as? JsonObject }.getOrNull()
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull

    private companion object { const val UPDATE = "org.matrix.msc4157.update_delayed_event" }
}
