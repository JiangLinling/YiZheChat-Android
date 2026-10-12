/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.widget

import io.element.android.libraries.matrix.api.widget.MatrixWidgetDriver
import io.element.android.libraries.matrix.api.widget.MatrixWidgetSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.WidgetCapabilitiesProvider
import org.matrix.rustcomponents.sdk.makeWidgetDriver
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

class RustWidgetDriver(
    widgetSettings: MatrixWidgetSettings,
    private val room: Room,
    private val widgetCapabilitiesProvider: WidgetCapabilitiesProvider,
    private val safeLifecycleDiagnosticsEnabled: Boolean = false,
    private val restoreStickyMetadata: suspend (String) -> String = { it },
    private val prepareOutgoingMessage: suspend (String) -> String = { it },
) : MatrixWidgetDriver {
    // It's important to have extra capacity here to make sure we don't drop any messages
    override val incomingMessages = MutableSharedFlow<String>(extraBufferCapacity = 10)

    private val driverAndHandle = makeWidgetDriver(widgetSettings.toRustWidgetSettings())
    private var receiveMessageJob: Job? = null

    private var isRunning = AtomicBoolean(false)

    override val id: String = widgetSettings.id
    private val departure = RtcWidgetDeparture(id)
    private val invitation = RtcWidgetInvitation(id)
    override val outgoingCallInvitation = invitation.invitation
    private val stopping = AtomicBoolean(false)

    override suspend fun run() {
        // Don't run the driver if it's already running
        if (!isRunning.compareAndSet(false, true)) {
            return
        }

        val coroutineScope = CoroutineScope(coroutineContext)
        coroutineScope.launch {
            // This call will suspend the coroutine while the driver is running, so it needs to be launched separately
            driverAndHandle.driver.run(room, widgetCapabilitiesProvider)
            if (safeLifecycleDiagnosticsEnabled) {
                Timber.w("Next RTC widget driver stopped.")
            }
        }
        receiveMessageJob = coroutineScope.launch(Dispatchers.IO) {
            val relay = RtcWidgetIncomingRelay(this, restoreStickyMetadata, incomingMessages::emit)
            try {
                while (isActive) {
                    val message = driverAndHandle.handle.recv() ?: break
                    if (safeLifecycleDiagnosticsEnabled) invitation.received(message)
                    if (safeLifecycleDiagnosticsEnabled && departure.received(message)) continue
                    if (safeLifecycleDiagnosticsEnabled) {
                        Timber.d("Next RTC widget native response %s.", message.safeProtocolIdentity())
                    }
                    relay.deliver(message)
                }
                if (safeLifecycleDiagnosticsEnabled && isActive) {
                    Timber.w("Next RTC widget receive channel closed.")
                }
            } finally {
                driverAndHandle.handle.close()
            }
        }
    }

    override suspend fun send(message: String) {
        if (stopping.get()) return
        val outgoing = prepareOutgoingMessage(message)
        if (safeLifecycleDiagnosticsEnabled) {
            departure.sent(outgoing)
            invitation.sent(outgoing)
        }
        try {
            if (safeLifecycleDiagnosticsEnabled) {
                Timber.d("Next RTC widget native send entering %s.", message.safeProtocolIdentity())
            }
            val accepted = driverAndHandle.handle.send(outgoing)
            if (safeLifecycleDiagnosticsEnabled) {
                if (accepted) {
                    Timber.d("Next RTC widget native send accepted %s.", message.safeProtocolIdentity())
                } else {
                    Timber.e("Next RTC widget rejected a message because the driver is stopped.")
                }
            }
        } catch (_: IllegalStateException) {
            // The handle is closed, ignore
            if (safeLifecycleDiagnosticsEnabled) {
                Timber.e("Next RTC widget handle was already closed.")
            }
        }
    }

    override suspend fun stopCallMembership() {
        if (!safeLifecycleDiagnosticsEnabled || !stopping.compareAndSet(false, true)) return
        val acknowledged = departure.depart { driverAndHandle.handle.send(it) }
        Timber.d("Next RTC native departure acknowledged=%s.", acknowledged)
    }

    override fun close() {
        receiveMessageJob?.cancel()
        driverAndHandle.driver.close()
        check(!safeLifecycleDiagnosticsEnabled || !invitation.hasPendingResponse()) {
            "RTC invitation acknowledgement is unresolved; cancellation completion cannot be attested."
        }
    }
}

internal fun String.safeProtocolIdentity(): String {
    val json = runCatching { Json.parseToJsonElement(this) as? JsonObject }.getOrNull()
        ?: return "action=unparseable api=unknown response=unknown"
    val response = json["response"] as? JsonObject
    val errorMessage = ((response?.get("error") as? JsonObject)?.get("message") as? JsonPrimitive)
        ?.contentOrNull.orEmpty()
    val outcome = when {
        errorMessage.contains("org.matrix.msc4407.send.sticky_event") -> "error=missing_send_sticky_capability"
        errorMessage.contains("org.matrix.msc4157.send.delayed_event") -> "error=missing_send_delayed_capability"
        errorMessage.contains("Not allowed to send event") -> "error=event_not_allowed"
        errorMessage.isNotEmpty() -> "error=other"
        response != null -> "error=none hasEventId=${response.containsKey("event_id")} hasDelayId=${response.containsKey("delay_id")}"
        else -> "error=none"
    }
    val requestData = json["data"] as? JsonObject
    val eventType = (requestData?.get("type") as? JsonPrimitive)?.contentOrNull.orEmpty()
    val rtcShape = if (eventType == NEXT_RTC_MEMBER_EVENT_TYPE) {
        (requestData?.get("content") as? JsonObject)?.safeRtcMembershipShape()
            ?: " rtcShape=missing_content_object"
    } else {
        ""
    }
    val action = (json["action"] as? JsonPrimitive)?.contentOrNull ?: "unknown"
    val api = (json["api"] as? JsonPrimitive)?.contentOrNull ?: "unknown"
    val responseFlag = response != null || (json["response"] as? JsonPrimitive)?.booleanOrNull == true
    return "action=$action api=$api response=$responseFlag $outcome$rtcShape"
}

/**
 * Describes only the JSON schema presented to the native RTC validator. Values,
 * identifiers, tokens and Matrix IDs are deliberately excluded from the log.
 */
private fun JsonObject.safeRtcMembershipShape(): String {
    val member = get("member") as? JsonObject
    val application = get("application") as? JsonObject
    val versions = get("versions") as? JsonArray
    val transports = get("transports")
    val rtcTransports = get("rtc_transports")
    val transportCount = when (val value = transports ?: rtcTransports) {
        is JsonArray -> value.size
        is JsonObject -> (value["published"] as? JsonArray)?.size ?: -1
        else -> -1
    }
    val transportKey = if (containsKey("transports")) "transports" else "rtc_transports"
    return buildString {
        append(" rtcShape=root[")
        append(sortedKeys())
        append("]")
        append(" member[")
        append(member?.sortedKeys().orEmpty())
        append("]")
        append(" application[")
        append(application?.sortedKeys().orEmpty())
        append("]")
        append(" versionsType=")
        append(valueKind("versions"))
        append(" versionsCount=")
        append(versions?.size ?: -1)
        append(" transportsType=")
        append(valueKind(transportKey))
        append(" transportsCount=")
        append(transportCount)
        append(" stickyKeyType=")
        append(valueKind("msc4354_sticky_key"))
        append(" legacyStickyKeyType=")
        append(valueKind("sticky_key"))
        append(" relationType=")
        append(valueKind("m.relation"))
        append(" relatesToType=")
        append(valueKind("m.relates_to"))
        append(" slotType=")
        append(valueKind("slot_id"))
    }
}

private fun JsonObject.sortedKeys(): String = keys.sorted().joinToString(",")

private fun JsonObject.valueKind(key: String): String = when (val value = get(key)) {
    null -> "missing"
    JsonNull -> "null"
    is JsonObject -> "object"
    is JsonArray -> "array"
    is JsonPrimitive -> when {
        value.isString -> "string"
        value.booleanOrNull != null -> "boolean"
        else -> "number"
    }
}
