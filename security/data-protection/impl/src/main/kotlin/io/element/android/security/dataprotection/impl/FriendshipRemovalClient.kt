/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.dataprotection.impl

import io.element.android.security.dataprotection.api.FriendshipRemovalIntent
import io.element.android.security.dataprotection.api.FriendshipRemovalSnapshot
import io.element.android.security.dataprotection.api.FriendshipRemovalStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.security.MessageDigest
import java.util.UUID

/** Signed domain calls use the existing trust/session transport and lifecycle receipts. */
internal class FriendshipRemovalClient(
    private val verifier: ManagementSignatureVerifier,
    private val installation: suspend () -> String,
    private val send: suspend (JsonObject) -> JsonObject,
) {
    suspend fun preview(account: String, device: String, peer: String): FriendshipRemovalSnapshot? {
        val query = request("preview", account, device, peer)
        val payload = call(query)
        val result = payload["result"]?.takeUnless { it is JsonNull }?.jsonObject ?: return null
        check(result.text("peer_user_id") == peer)
        val room = result.text("room_id").also { check(it.startsWith('!') && ':' in it) }
        val revision = result.text("revision").also { check(it.matches(Regex("[a-f0-9]{64}"))) }
        val operation = result["operation_id"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
        operation?.let { check(it.matches(OPERATION)) }
        val status = FriendshipRemovalStatus.valueOf(result.text("status"))
        check(status == FriendshipRemovalStatus.ACCEPTED || operation != null)
        val frozen = result.getValue("rooms").jsonArray.map { it.jsonPrimitive.content }.toSet()
        check(frozen.size <= 64 && frozen.all { it.startsWith('!') && ':' in it })
        check(status == FriendshipRemovalStatus.ACCEPTED || room in frozen)
        return FriendshipRemovalSnapshot(account, device, installation(), peer, room, revision, status, operation, frozen)
    }

    suspend fun begin(snapshot: FriendshipRemovalSnapshot, rooms: Set<String>): FriendshipRemovalIntent {
        check(snapshot.installationId == installation() && snapshot.status != FriendshipRemovalStatus.REMOVED)
        check(rooms.size in 1..64 && snapshot.roomId in rooms && rooms.all { it.startsWith('!') && ':' in it })
        check(snapshot.frozenRoomIds.isEmpty() || snapshot.frozenRoomIds == rooms)
        val query = JsonObject(request("begin", snapshot.accountIdHash, snapshot.matrixDeviceIdHash, snapshot.peerUserId) + buildJsonObject {
            put("revision", snapshot.revision)
            putJsonArray("rooms") { rooms.sorted().forEach { add(it) } }
        })
        val result = call(query).getValue("result").jsonObject
        val operation = result.text("operation_id").also { check(it.matches(OPERATION)) }
        snapshot.operationId?.let { check(operation == it) }
        val status = result.text("status")
        check(status in setOf("PENDING", "RUNNING", "WAITING_EXTERNAL", "RETRYABLE_FAILURE", "BLOCKED", "SUCCEEDED"))
        return FriendshipRemovalIntent(operation, snapshot, rooms, status == "SUCCEEDED")
    }

    suspend fun complete(intent: FriendshipRemovalIntent) {
        val snapshot = intent.snapshot
        check(snapshot.installationId == installation())
        val query = JsonObject(
            request("complete", snapshot.accountIdHash, snapshot.matrixDeviceIdHash, snapshot.peerUserId) +
            ("operation_id" to JsonPrimitive(intent.operationId))
        )
        val payload = call(query)
        val result = payload.getValue("result").jsonObject
        check(result.text("status") == "REMOVED" && result.text("operation_id") == intent.operationId)
        val receipt = result.getValue("receipt").jsonObject
        check(receipt.text("schema_version") == "1" && receipt.text("catalog_id") == "yizhechat-full-app-lifecycle-v1")
        check(verifier.verifyDetached(
            JsonObject(receipt.filterKeys { it != "signature_b64" }),
            payload.text("signing_key_id"),
            receipt.text("signature_b64")
        )) { "LC11 receipt signature rejected" }
        check(receipt.text("lifecycle_id") == "LC-11" && receipt.text("operation_id") == intent.operationId &&
            receipt.text("terminal_state") == "SUCCEEDED" && receipt.text("receipt_id").length >= 8)
        val subjects = receipt.getValue("subjects").jsonObject
        check(subjects.text("account_id_hash") == snapshot.accountIdHash && subjects.text("matrix_device_id_hash") == snapshot.matrixDeviceIdHash &&
            subjects.text("installation_id") == snapshot.installationId && subjects.text("room_id_hash") == sha(snapshot.roomId))
        val scope = Json.parseToJsonElement(receipt.getValue("authority_revisions").jsonObject.text("friendship_removal_v1")).jsonObject
        check(scope.text("peer") == snapshot.peerUserId && scope.text("installation") == snapshot.installationId &&
            sha(scope.text("actor")) == snapshot.accountIdHash && sha(scope.text("matrix_device")) == snapshot.matrixDeviceIdHash)
        check(sha(verifier.canonicalJson(scope.getValue("snapshot"))) == snapshot.revision)
        check(scope.getValue("rooms").jsonObject.keys == intent.roomIds)
        val steps = receipt.getValue("required_step_receipts").jsonObject
        check(steps.keys == setOf("matrix", "control_plane", "database").map { intent.operationId + ":" + it }.toSet())
        check(
            steps.values.map { it.jsonPrimitive.content }.toSet().size == 1 &&
            steps.values.all { it.jsonPrimitive.content.matches(Regex("friendship-revoked:[a-f0-9]{64}")) }
        )
        check(receipt.text("policy_version").toLong() >= 0)
        check(receipt.text("completed_at_ms").toLong() >= receipt.text("started_at_ms").toLong())
    }

    private suspend fun call(query: JsonObject): JsonObject {
        val payload = send(query)
        check(verifier.verifyDetached(
            JsonObject(payload.filterKeys { it != "signature_b64" }),
            payload.text("signing_key_id"),
            payload.text("signature_b64")
        ))
        check(payload.text("schema_version") == "1" && payload.text("purpose") == "FRIENDSHIP_REMOVAL_${query.text("action").uppercase()}")
        check(payload["query"] == query && payload.text("installation_id") == installation())
        val issued = payload.text("issued_at_ms").toLong()
        val expires = payload.text("expires_at_ms").toLong()
        val now = System.currentTimeMillis()
        check(issued <= now + 5_000 && expires > now && expires > issued && expires - issued <= 15_000)
        return payload
    }

    private fun request(action: String, account: String, device: String, peer: String) = buildJsonObject {
        check(account.matches(Regex("[a-f0-9]{64}")) && device.matches(Regex("[a-f0-9]{64}")))
        check(peer.startsWith('@') && ':' in peer)
        put("action", action)
        put("account_id_hash", account)
        put("matrix_device_id_hash", device)
        put("peer_user_id", peer)
        put("request_nonce", UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""))
    }

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    private fun sha(text: String) = sha(text.encodeToByteArray())
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private companion object {
        val OPERATION = Regex("friend-[a-f0-9]{64}")
    }
}
