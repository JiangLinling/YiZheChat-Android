/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.dataprotection.impl

import io.element.android.security.dataprotection.api.ManagedDirectRoomResult
import kotlinx.serialization.json.*
import java.util.UUID

internal class ManagedDirectRoomClient(
    private val verifier: ManagementSignatureVerifier,
    private val installation: suspend () -> String,
    private val send: suspend (JsonObject) -> JsonObject,
) {
    suspend fun execute(account: String, device: String, peer: String, create: Boolean): ManagedDirectRoomResult? {
        val (existing, generation) = request(account, device, peer, expectedGeneration = null)
        if (!create || existing != null) return existing
        // Bind this explicit action to the reviewed generation. Transport retries retain
        // this exact request; a delayed old invite cannot open a new rejected generation.
        return request(account, device, peer, expectedGeneration = generation).first
    }

    private suspend fun request(account: String, device: String, peer: String, expectedGeneration: String?): Pair<ManagedDirectRoomResult?, String> {
        val create = expectedGeneration != null
        val query = buildJsonObject {
            require(account.matches(Regex("[a-f0-9]{64}")) && device.matches(Regex("[a-f0-9]{64}")))
            require(peer.matches(Regex("@[^\\s:]+:[^\\s]+")))
            put("action", if (create) "invite" else "resolve")
            put("account_id_hash", account); put("matrix_device_id_hash", device); put("peer_user_id", peer)
            put("request_nonce", UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""))
            expectedGeneration?.let { put("expected_generation", it) }
        }
        val payload = send(query)
        check(verifier.verifyDetached(JsonObject(payload.filterKeys { it != "signature_b64" }),
            payload.text("signing_key_id"), payload.text("signature_b64"))) { "LC11 direct room signature rejected" }
        check(payload.text("schema_version") == "1" && payload.text("purpose") == "MANAGED_DIRECT_ROOM_${query.text("action").uppercase()}")
        check(payload["query"] == query && payload.text("installation_id") == installation())
        val issued = payload.text("issued_at_ms").toLong()
        val expires = payload.text("expires_at_ms").toLong()
        val now = System.currentTimeMillis()
        check(issued > 0 && issued <= now + 5000 && expires > now && expires > issued && expires - issued <= 15000)
        val generation = payload.text("generation")
        check(generation.matches(Regex("[a-f0-9]{64}")))
        if (expectedGeneration != null) check(generation == expectedGeneration) { "Invitation generation changed" }
        val result = payload.getValue("result").takeUnless { it is JsonNull }?.jsonObject
        if (result == null) {
            check(!create) { "Managed invitation returned no authoritative room" }
            return null to generation
        }
        val room = result.text("room_id")
        check(room.matches(Regex("![^\\s:]+:[^\\s]+")) && room.length <= 320)
        val status = result.text("status")
        check(status in setOf("INVITED", "ACCEPTED"))
        val operations = result.getValue("client_operations").jsonArray.map { it.jsonPrimitive.content }
        check(operations.size <= 2 && operations.distinct().size == operations.size)
        if (result.containsKey("operation_id")) {
            val parent = result.text("operation_id")
            val child = result.text("creation_operation_id")
            check(parent.matches(Regex("direct-[a-f0-9]{64}")) && child == "dmroom-${parent.removePrefix("direct-")}")
            check(operations.all { it == parent || it == child })
        } else check(operations.isEmpty() && status == "ACCEPTED")
        return ManagedDirectRoomResult(room, status, operations) to generation
    }

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
}
