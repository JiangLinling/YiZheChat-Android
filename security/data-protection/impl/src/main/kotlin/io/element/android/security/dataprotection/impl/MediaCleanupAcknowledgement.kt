/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.dataprotection.impl

import io.element.android.security.dataprotection.api.LifecycleMediaObjectCleanup
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.MessageDigest

internal fun mediaCleanupRequest(fact: LifecycleMediaObjectCleanup): JsonObject = buildJsonObject {
    put("protocol_version", 1)
    put("operation_id", fact.operationId)
    put("installation_id", checkNotNull(fact.subjects.installationId))
    put("account_id_hash", checkNotNull(fact.subjects.accountIdHash))
    put("matrix_device_id_hash", checkNotNull(fact.subjects.matrixDeviceIdHash))
    put("room_id_hash", fact.subjects.roomIdHash?.let(::JsonPrimitive) ?: JsonNull)
    put("event_id_hash", fact.subjects.eventIdHash?.let(::JsonPrimitive) ?: JsonNull)
    put("record_sha256", fact.recordSha256)
    put("confirmed_absent_at_ms", fact.confirmedAbsentAtEpochMillis)
    put("lease_quiescent_at_ms", fact.leaseQuiescentAtEpochMillis)
}

internal fun requireMediaCleanupAcknowledgement(
    request: JsonObject,
    payload: JsonObject,
    verifier: ManagementSignatureVerifier,
): String {
    check(verifier.verifyDetached(
        JsonObject(payload.filterKeys { it != "signature_b64" }),
        payload.getValue("signing_key_id").jsonPrimitive.content,
        payload.getValue("signature_b64").jsonPrimitive.content
    ))
    check(
        payload.getValue("schema_version").jsonPrimitive.content == "1" &&
        payload.getValue("purpose").jsonPrimitive.content == "LOCAL_MEDIA_OBJECT_CLEANUP_ACK"
    )
    check(payload.getValue("observation") == request) { "Media cleanup acknowledgement changed the original fact." }
    val digest = MessageDigest.getInstance("SHA-256").digest(verifier.canonicalJson(request))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    val expected = "android-media-object-cleanup:v1:$digest"
    check(payload.getValue("receipt_reference").jsonPrimitive.content == expected)
    check(payload.getValue("accepted_at_ms").jsonPrimitive.content.toLong() > 0)
    return expected
}
