/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.security.dataprotection.impl

import io.element.android.security.dataprotection.api.APP_OPERATIONAL_SNAPSHOT_MAX_FUTURE_ISSUE_SKEW_MILLIS
import io.element.android.security.dataprotection.api.AppOperationalSnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive

internal const val OPERATION_SNAPSHOT_PATH = "/v1/lifecycle/operation-snapshots"
internal const val OPERATION_SNAPSHOT_MAX_REQUEST_BYTES = 131_072
internal const val OPERATION_SNAPSHOT_MAX_RESPONSE_BYTES = 4_194_304

/** Decode only a complete signed reply for the existing coordinator's atomic recovery publication. */
internal fun decodeOperationalRecoveryBatch(
    json: Json,
    payload: JsonObject,
    snapshot: AppOperationalSnapshot,
    requestedIds: List<String>,
    nonce: String,
    snapshotSha256: String,
    now: Long,
    verify: (JsonElement, String, String) -> Boolean,
): List<LifecycleOperationWire> {
    fun value(name: String) = checkNotNull(payload[name]).jsonPrimitive.content
    check(verify(JsonObject(payload.filterKeys { it != "signature_b64" }), value("signing_key_id"), value("signature_b64")))
    check(value("schema_version") == "1" && value("purpose") == "OPERATIONAL_SNAPSHOT_RECOVERY")
    check(value("signing_key_id") == snapshot.signingKeyId)
    check(value("installation_id") == snapshot.installationId && value("account_id_hash") == snapshot.accountIdHash)
    check(value("matrix_device_id_hash") == snapshot.matrixDeviceIdHash && value("nonce") == nonce)
    check(value("snapshot_sha256") == snapshotSha256)
    check(snapshot.isUsableAt(now, APP_OPERATIONAL_SNAPSHOT_MAX_FUTURE_ISSUE_SKEW_MILLIS))
    val issued = value("issued_at_ms").toLong()
    val expires = value("expires_at_ms").toLong()
    check(issued >= snapshot.issuedAtEpochMillis && issued <= now + APP_OPERATIONAL_SNAPSHOT_MAX_FUTURE_ISSUE_SKEW_MILLIS)
    check(now < expires && expires > issued && expires <= snapshot.expiresAtEpochMillis && expires - issued <= 60_000)
    check(requestedIds.size in 1..128 && requestedIds.distinct().size == requestedIds.size)
    check(requestedIds.all { it in snapshot.activeOperationIds })
    val operations = json.decodeFromJsonElement<List<LifecycleOperationWire>>(checkNotNull(payload["operations"]))
    check(operations.size == requestedIds.size && operations.map { it.operationId }.toSet() == requestedIds.toSet())
    operations.forEach {
        check(it.subjects.installationId == snapshot.installationId)
        check(it.subjects.accountIdHash == null || it.subjects.accountIdHash == snapshot.accountIdHash)
        // Older Matrix-device subjects and original anonymous intents keep their original meaning.
    }
    val byId = operations.associateBy { it.operationId }
    return requestedIds.map(byId::getValue)
}
