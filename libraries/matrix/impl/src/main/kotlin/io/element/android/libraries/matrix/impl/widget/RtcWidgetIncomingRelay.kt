/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.widget

import io.element.android.libraries.core.extensions.runCatchingExceptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import timber.log.Timber

/** Missing event metadata may require HTTP; protocol replies must never wait for that lookup. */
internal class RtcWidgetIncomingRelay(
    private val scope: CoroutineScope,
    private val restore: suspend (String) -> String,
    private val emit: suspend (String) -> Unit,
) {
    suspend fun deliver(message: String) {
        val envelope = runCatching { Json.parseToJsonElement(message) as? JsonObject }.getOrNull()
        val data = envelope?.get("data") as? JsonObject
        val needsLookup = envelope?.text("api") == "toWidget" && envelope.text("action") == "send_event" &&
            "response" !in envelope && data?.text("type") == NEXT_RTC_MEMBER_EVENT_TYPE &&
            data["msc4354_sticky"] == null && (data["content"] as? JsonObject)?.text("msc4354_sticky_key") != null
        if (!needsLookup) {
            // The initial sync's remaining TTL was measured before this message
            // waited in the driver queue. Do not start that TTL again at delivery.
            // Match restored live events: use the server timestamp and duration.
            val unsigned = data?.get("unsigned") as? JsonObject
            val absoluteSticky = envelope?.text("api") == "toWidget" && envelope.text("action") == "send_event" &&
                "response" !in envelope && data?.text("type") == NEXT_RTC_MEMBER_EVENT_TYPE &&
                data["msc4354_sticky"] is JsonObject && unsigned?.containsKey("msc4354_sticky_duration_ttl_ms") == true
            val forwarded = if (absoluteSticky) {
                val normalized = JsonObject(data!! + ("unsigned" to JsonObject(unsigned!! - "msc4354_sticky_duration_ttl_ms")))
                JsonObject(envelope!! + ("data" to normalized)).toString()
            } else {
                message
            }
            emit(forwarded)
            return
        }
        // Sticky events are ordered by the authoritative event timestamp in the JS SDK,
        // so a later tombstone may safely arrive before an older, slower membership lookup.
        // The receive job owns this child: closing the driver cancels pending lookups.
        scope.launch {
            val restored = runCatchingExceptions { restore(message) }
                .onFailure { Timber.w("RTC sticky event authority unavailable; no lifetime synthesized.") }
                .getOrElse { message }
            emit(restored)
        }
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
}
