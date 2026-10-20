/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.widget

import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.notification.CallIntent
import io.element.android.libraries.matrix.api.room.CallIntentConsensus
import io.element.android.libraries.matrix.api.room.RoomInfo
import io.element.android.libraries.matrix.api.widget.MatrixWidgetSettings
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.WidgetCapabilities
import org.matrix.rustcomponents.sdk.WidgetCapabilitiesProvider
import org.matrix.rustcomponents.sdk.WidgetEventFilter
import java.util.UUID

internal data class RtcRoomCallSnapshot(val participants: List<UserId>, val intent: CallIntentConsensus) {
    fun applyTo(info: RoomInfo) = info.copy(
        hasRoomCall = participants.isNotEmpty(), activeRoomCallParticipants = participants.toImmutableList(), activeCallIntentConsensus = intent,
    )
}

/** Projection of authenticated sticky membership, shared by the banner and call admission UI.
 * The native SDK's legacy room-info call fields do not include MSC4143 message-like memberships.
 */
internal fun observeRtcRoomCall(
    room: Room,
    roomId: String,
    fetchEvent: suspend (String) -> JsonObject,
    now: () -> Long,
): Flow<RtcRoomCallSnapshot> = channelFlow {
    val id = "next-room-call-${UUID.randomUUID()}"
    val metadata = RtcWidgetStickyMetadata(roomId, fetchEvent)
    val driver = RustWidgetDriver(
        MatrixWidgetSettings(id, false, "https://localhost.invalid/native-room-call"), room,
        object : WidgetCapabilitiesProvider {
            override fun acquireCapabilities(capabilities: WidgetCapabilities) = WidgetCapabilities(
                read = listOf(WidgetEventFilter.MessageLikeWithType(NEXT_RTC_MEMBER_EVENT_TYPE)), send = emptyList(),
                requiresClient = false, updateDelayedEvent = false, sendDelayedEvent = false,
                downloadFiles = false, sendStickyEvent = false, receiveStickyEvent = true,
            )
        },
        restoreStickyMetadata = metadata::restore,
    )
    val membership = RtcRoomCallMembership(roomId)
    launch(start = CoroutineStart.UNDISPATCHED) {
        driver.incomingMessages.collect { message ->
            val root = runCatching { Json.parseToJsonElement(message) as? JsonObject }.getOrNull() ?: return@collect
            if (root.text("widgetId") != id || root.text("api") != "toWidget" || "response" in root) return@collect
            val response = if (root.text("action") == "capabilities") buildJsonObject {
                putJsonArray("capabilities") {
                    add("org.matrix.msc2762.receive.event:$NEXT_RTC_MEMBER_EVENT_TYPE")
                    add("org.matrix.msc4407.receive.sticky_event")
                }
            } else buildJsonObject {}
            driver.send(JsonObject(root + ("response" to response)).toString())
            if (root.text("action") == "send_event") {
                membership.observe(root["data"] as? JsonObject)
                send(membership.snapshot(now()))
            }
        }
    }
    launch(Dispatchers.IO) { driver.run() }
    // Expiry is an absolute server deadline. No network polling and no renewed lease on delivery.
    launch {
        while (isActive) { send(membership.snapshot(now())); delay(1_000) }
    }
    launch {
        while (isActive) {
            delay(5_000)
            // SDK sticky snapshots may retain the decrypted pre-redaction event. Confirm only
            // existing live IDs with Matrix; transport failure is never evidence of departure.
            for (eventId in membership.activeEventIds(now())) {
                try { membership.observeRedaction(eventId, fetchEvent(eventId)) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Retain the last authenticated membership until expiry. */ }
            }
            send(membership.snapshot(now()))
        }
    }
    try { awaitCancellation() } finally { driver.close() }
}.distinctUntilChanged()

internal class RtcRoomCallMembership(private val roomId: String) {
    private data class Member(val timestamp: Long, val expiresAt: Long, val user: String?, val intent: String?, val eventId: String)
    private val members = linkedMapOf<Pair<String, String>, Member>()

    @Synchronized
    fun observe(event: JsonObject?) {
        if (event == null || event.text("room_id") != roomId || event.text("type") != NEXT_RTC_MEMBER_EVENT_TYPE ||
            event.text("event_id")?.startsWith('$') != true) return
        val sender = event.text("sender") ?: return
        val timestamp = event.long("origin_server_ts")?.takeIf { it > 0 } ?: return
        val content = event["content"] as? JsonObject ?: return
        if (content.text("slot_id") != "m.call#ROOM") return
        val key = content.text("msc4354_sticky_key")?.takeIf { it.isNotBlank() } ?: return
        val identity = sender to key
        if ((members[identity]?.timestamp ?: 0) >= timestamp) return
        if (content.keys == setOf("slot_id", "msc4354_sticky_key")) {
            members[identity] = Member(timestamp, timestamp, null, null, requireNotNull(event.text("event_id")))
        } else {
            val member = content["member"] as? JsonObject ?: return
            val application = content["application"] as? JsonObject ?: return
            if (member.text("user_id") != sender || member.text("device_id").isNullOrBlank() ||
                member.text("id").isNullOrBlank() || application.text("type") != "m.call") return
            val duration = (event["msc4354_sticky"] as? JsonObject)?.long("duration_ms")?.takeIf { it in 1..3_600_000 } ?: return
            members[identity] = Member(timestamp, timestamp + duration, sender, application.text("m.call.intent"), requireNotNull(event.text("event_id")))
        }
        if (members.size > 2_048) members.remove(members.keys.first())
    }

    @Synchronized
    fun activeEventIds(now: Long): List<String> = members.values.filter { it.user != null && it.expiresAt > now }.map { it.eventId }

    /** Called only with the authenticated /rooms/{room}/event/{id} response. */
    @Synchronized
    fun observeRedaction(eventId: String, event: JsonObject) {
        if (event.text("event_id") != eventId || event.text("room_id") !in setOf(null, roomId)) return
        val redaction = (event["unsigned"] as? JsonObject)?.get("redacted_because") as? JsonObject ?: return
        if (redaction.text("type") != "m.room.redaction" || redaction.text("event_id")?.startsWith('$') != true) return
        val target = redaction.text("redacts") ?: (redaction["content"] as? JsonObject)?.text("redacts")
        if (target != eventId) return
        members.entries.forEach { (identity, member) ->
            if (member.eventId == eventId && event.text("sender") == identity.first) {
                members[identity] = member.copy(user = null, expiresAt = member.timestamp)
            }
        }
    }

    @Synchronized
    fun snapshot(now: Long): RtcRoomCallSnapshot {
        val active = members.values.filter { it.user != null && it.timestamp <= now && it.expiresAt > now }
        val users = active.map { UserId(requireNotNull(it.user)) }.distinct()
        val intent = when {
            active.isEmpty() -> CallIntentConsensus.None
            active.all { it.intent == "audio" } -> CallIntentConsensus.Full(CallIntent.AUDIO)
            active.all { it.intent == "video" } -> CallIntentConsensus.Full(CallIntent.VIDEO)
            else -> CallIntentConsensus.None
        }
        return RtcRoomCallSnapshot(users, intent)
    }
}

private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
private fun JsonObject.long(key: String) = (get(key) as? JsonPrimitive)?.longOrNull
