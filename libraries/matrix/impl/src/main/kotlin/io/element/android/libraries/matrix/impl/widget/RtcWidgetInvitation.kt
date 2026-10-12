package io.element.android.libraries.matrix.impl.widget

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Correlates the server response with this widget's actual invitation; never guesses from room history. */
internal class RtcWidgetInvitation(private val widgetId: String) {
    private val pending = mutableSetOf<String>()
    private val eventId = MutableStateFlow<String?>(null)
    val invitation = eventId.asStateFlow()
    @Synchronized fun hasPendingResponse(): Boolean = pending.isNotEmpty()

    @Synchronized
    fun sent(message: String) {
        val root = parse(message) ?: return
        if (!own(root) || "response" in root) return
        val data = root["data"] as? JsonObject ?: return
        val content = data["content"] as? JsonObject ?: return
        if (data.text("type") != "org.matrix.msc4075.rtc.notification" || content.text("notification_type") != "ring") return
        root.text("requestId")?.let(pending::add)
    }

    @Synchronized
    fun received(message: String) {
        val root = parse(message) ?: return
        if (!own(root)) return
        val response = root["response"] as? JsonObject ?: return
        if (!pending.remove(root.text("requestId")) || "error" in response) return
        response.text("event_id")?.takeIf { it.startsWith("$") }?.let { eventId.value = it }
    }

    private fun own(root: JsonObject) = root.text("widgetId") == widgetId &&
        root.text("api") == "fromWidget" && root.text("action") == "send_event"
    private fun parse(message: String) = runCatching { Json.parseToJsonElement(message) as? JsonObject }.getOrNull()
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
}
