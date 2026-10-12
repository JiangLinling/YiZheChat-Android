package io.element.android.features.call.impl.ui

import io.element.android.libraries.architecture.AsyncData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Negotiation is read-only; identity, room writes, keys and media remain behind native admission. */
internal fun isPassiveCallStartupMessage(message: String, widgetId: String?): Boolean {
    if (widgetId == null) return false
    val data = runCatching { Json.parseToJsonElement(message) as? JsonObject }.getOrNull() ?: return false
    fun field(name: String) = (data[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (field("widgetId") != widgetId) return false
    val response = data["response"]?.let { it != JsonNull } == true
    return when (field("api")) {
        "fromWidget" -> !response && field("action") in setOf("supported_api_versions", "content_loaded")
        // These are acknowledgements of host-originated messages, never a request to send an event.
        "toWidget" -> response && field("action") in setOf("capabilities", "notify_capabilities", "update_state", "send_event")
        else -> false
    }
}

/** Only protected local preparation can overlap admission; failure and teardown remove it. */
internal fun CallScreenState.renderCallUrl(isNext: Boolean): String? = when {
    isTerminating || urlState is AsyncData.Failure -> null
    urlState is AsyncData.Success -> urlState.data
    isNext -> preparationUrl
    else -> null
}
