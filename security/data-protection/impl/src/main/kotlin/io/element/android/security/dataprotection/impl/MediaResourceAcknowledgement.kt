/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.dataprotection.impl

import io.element.android.security.dataprotection.api.LifecycleMediaReservation
import io.element.android.security.dataprotection.api.LifecycleMediaRuntimeCleanup
import io.element.android.security.dataprotection.api.LifecycleMediaSeal
import io.element.android.security.dataprotection.api.LifecycleSubjectHashes
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal fun mediaResourceRequest(request: LifecycleMediaReservation): JsonObject = buildJsonObject {
    putMediaScope(request.operationId, request.subjects)
    put("resources", buildJsonArray {
        request.resources.forEach { resource ->
            add(buildJsonObject {
            put("resource_type", resource.type.name)
            put("resource_id_hash", resource.recordSha256)
        })
        }
    })
}

internal fun mediaSealRequest(request: LifecycleMediaSeal): JsonObject = JsonObject(
    mediaResourceRequest(request.reservation) + buildJsonObject {
        put("owner_process_sha256", request.ownerProcessSha256)
        put("sealed_at_ms", request.sealedAtEpochMillis)
        put("outcome", request.outcome.name)
    }
)

internal fun mediaRuntimeRequest(fact: LifecycleMediaRuntimeCleanup): JsonObject = buildJsonObject {
    putMediaScope(fact.operationId, fact.subjects)
    put("record_sha256", fact.recordSha256)
    put("released_at_ms", fact.releasedAtEpochMillis)
    put("runtime_released", fact.runtimeReleased)
    put("active_work_finished", fact.activeWorkFinished)
    put("owned_payload_cleared", fact.ownedPayloadCleared)
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putMediaScope(operationId: String, subjects: LifecycleSubjectHashes) {
    put("protocol_version", 1)
    put("operation_id", operationId)
    put("installation_id", checkNotNull(subjects.installationId))
    put("account_id_hash", checkNotNull(subjects.accountIdHash))
    put("matrix_device_id_hash", checkNotNull(subjects.matrixDeviceIdHash))
    put("room_id_hash", subjects.roomIdHash?.let(::JsonPrimitive) ?: JsonNull)
    put("event_id_hash", subjects.eventIdHash?.let(::JsonPrimitive) ?: JsonNull)
}

internal fun requireMediaResourceAcknowledgement(
    request: JsonObject,
    payload: JsonObject,
    verifier: ManagementSignatureVerifier,
    purpose: String,
): String? {
    check(purpose in setOf("MEDIA_RESOURCE_RESERVATION_ACK", "MEDIA_RESOURCE_SEAL_ACK", "ANDROID_MEDIA_RUNTIME_CLEANUP_ACK"))
    check(verifier.verifyDetached(
        JsonObject(payload.filterKeys { it != "signature_b64" }),
        payload.getValue("signing_key_id").jsonPrimitive.content,
        payload.getValue("signature_b64").jsonPrimitive.content
    ))
    check(payload.getValue("schema_version").jsonPrimitive.content == "1" && payload.getValue("purpose").jsonPrimitive.content == purpose)
    val field = if (purpose == "ANDROID_MEDIA_RUNTIME_CLEANUP_ACK") "observation" else "request"
    check(payload.getValue(field) == request) { "Media acknowledgement changed the original resource fact." }
    if (purpose == "MEDIA_RESOURCE_RESERVATION_ACK") return null
    val digest = sha256Hex(verifier.canonicalJson(request))
    val expected = if (purpose == "MEDIA_RESOURCE_SEAL_ACK") {
        "android-media-manifest:v1:$digest:${request.getValue("outcome").jsonPrimitive.content}:" +
            "${request.getValue("owner_process_sha256").jsonPrimitive.content}:${request.getValue("sealed_at_ms").jsonPrimitive.content}"
    } else {
        "android-media-runtime-cleanup:v1:$digest"
    }
    check(payload.getValue("receipt_reference").jsonPrimitive.content == expected)
    check(payload.getValue("accepted_at_ms").jsonPrimitive.content.toLong() > 0)
    return expected
}
