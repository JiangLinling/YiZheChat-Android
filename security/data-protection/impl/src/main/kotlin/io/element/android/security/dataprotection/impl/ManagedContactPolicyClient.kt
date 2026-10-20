/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.dataprotection.impl

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

internal class ManagedContactPolicyClient(
    private val verifier: ManagementSignatureVerifier,
    private val installation: suspend () -> String,
    private val send: suspend (JsonObject) -> JsonObject,
) {
    suspend fun callTerminationReason(account: String, device: String, room: String, operationId: String): String? {
        require(account.matches(Regex("[a-f0-9]{64}")) && device.matches(Regex("[a-f0-9]{64}")))
        require(room.matches(Regex("![^\\s:]+:[^\\s]+")) && operationId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        val query = buildJsonObject {
            put("action", "call_status")
            put("account_id_hash", account)
            put("matrix_device_id_hash", device)
            put("room_id", room)
            put("call_operation_id", operationId)
            put("request_nonce", UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""))
        }
        val result = verifiedResult(query)
        if (!result.getValue("terminated").jsonPrimitive.boolean) {
            check(result["reason"] == JsonNull)
            return null
        }
        return result.text("reason").also {
            check(it in setOf("CONTACT_BLOCKED_BY_YOU", "CONTACT_UNAVAILABLE", "RTC_CALL_ENDED"))
        }
    }

    suspend fun requireRoomAllowed(account: String, device: String, room: String) {
        require(account.matches(Regex("[a-f0-9]{64}")) && device.matches(Regex("[a-f0-9]{64}")))
        require(room.matches(Regex("![^\\s:]+:[^\\s]+")))
        val query = buildJsonObject {
            put("action", "admit")
            put("account_id_hash", account)
            put("matrix_device_id_hash", device)
            put("room_id", room)
            put("request_nonce", UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""))
        }
        check(verifiedResult(query).getValue("allowed").jsonPrimitive.boolean) { "CONTACT_UNAVAILABLE" }
    }

    suspend fun setBlocked(account: String, device: String, peer: String, blocked: Boolean) {
        require(account.matches(Regex("[a-f0-9]{64}")) && device.matches(Regex("[a-f0-9]{64}")))
        require(peer.matches(Regex("@[^\\s:]+:[^\\s]+")))
        suspend fun request(action: String, revision: String? = null): JsonObject {
            val query = buildJsonObject {
                put("action", action)
                put("account_id_hash", account)
                put("matrix_device_id_hash", device)
                put("peer_user_id", peer)
                put("request_nonce", UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""))
                revision?.let {
                    put("revision", it)
                    put("blocked", blocked)
                }
            }
            return verifiedResult(query).also {
                check(it.text("revision").matches(Regex("[a-f0-9]{64}")))
                it.getValue("blocked").jsonPrimitive.boolean
            }
        }
        val preview = request("preview")
        if (preview.getValue("blocked").jsonPrimitive.boolean == blocked) return
        val result = request("change", preview.text("revision"))
        check(result.getValue("blocked").jsonPrimitive.boolean == blocked)
        check(result.text("operation_id") == "contact-${result.text("revision")}")
    }

    private suspend fun verifiedResult(query: JsonObject): JsonObject {
        val response = send(query)
        check(verifier.verifyDetached(
            JsonObject(response.filterKeys { it != "signature_b64" }),
            response.text("signing_key_id"),
            response.text("signature_b64")
        )) { "Contact policy signature rejected" }
        check(response.text("schema_version") == "1" && response.text("purpose") == "CONTACT_POLICY_${query.text("action").uppercase()}")
        check(response["query"] == query && response.text("installation_id") == installation())
        val issued = response.text("issued_at_ms").toLong()
        val expires = response.text("expires_at_ms").toLong()
        val now = System.currentTimeMillis()
        check(issued > 0 && issued <= now + 5000 && expires > now && expires > issued && expires - issued <= 15000)
        return response.getValue("result").jsonObject
    }

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
}
