package io.element.android.security.dataprotection.impl

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64

class FriendshipRemovalClientTest {
    private val key = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    private val keyId = MessageDigest.getInstance("SHA-256").digest(key.public.encoded).take(8).joinToString("") { "%02x".format(it.toInt() and 255) }
    private val verifier = ManagementSignatureVerifier(mockk<ManagementControlConfiguration> {
        every { isConfigured } returns true
        every { signingPublicKeyDerBase64 } returns Base64.getEncoder().encodeToString(key.public.encoded)
        every { signingKeyId } returns keyId
    })
    private fun signed(query: JsonObject, damage: String? = null): JsonObject {
        val now = System.currentTimeMillis()
        val result = buildJsonObject {
            put("peer_user_id", if (damage == "peer") "@other:test" else "@peer:test")
            put("room_id", "!room:test"); put("revision", "c".repeat(64)); put("status", "ACCEPTED")
            putJsonArray("rooms") {}
        }
        val payload = buildJsonObject {
            put("schema_version", 1)
            put("purpose", if (damage == "purpose") "ROOM_MEMBERSHIP" else "FRIENDSHIP_REMOVAL_PREVIEW")
            put("installation_id", if (damage == "installation") "other" else "test-installation")
            put("query", if (damage == "nonce") JsonObject(query + ("request_nonce" to JsonPrimitive("f".repeat(64)))) else query)
            put("result", result); put("issued_at_ms", if (damage == "future") now + 60_000 else now - 1_000)
            put("expires_at_ms", when (damage) { "expired" -> now - 1; "duration" -> now + 60_000; else -> now + 10_000 })
            put("signing_key_id", keyId)
        }
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(key.private); update(verifier.canonicalJson(payload)); sign()
        }
        return JsonObject(payload + ("signature_b64" to JsonPrimitive(Base64.getEncoder().encodeToString(signature))))
    }
    @Test fun `signed fresh preview is bound to exact request nonce and installation`() = runTest {
        val client = FriendshipRemovalClient(verifier, { "test-installation" }) { signed(it) }
        assertThat(client.preview("a".repeat(64), "b".repeat(64), "@peer:test")?.roomId).isEqualTo("!room:test")
    }
    @Test fun `valid signatures cannot substitute wrong purpose nonce installation peer or time window`() = runTest {
        for (damage in listOf("purpose", "nonce", "installation", "peer", "future", "expired", "duration")) {
            val client = FriendshipRemovalClient(verifier, { "test-installation" }) { signed(it, damage) }
            assertThat(runCatching { client.preview("a".repeat(64), "b".repeat(64), "@peer:test") }.isFailure).isTrue()
        }
    }
    @Test fun `tampered response fails cryptographic verification`() = runTest {
        val client = FriendshipRemovalClient(verifier, { "test-installation" }) {
            JsonObject(signed(it) + ("result" to JsonNull))
        }
        assertThat(runCatching { client.preview("a".repeat(64), "b".repeat(64), "@peer:test") }.isFailure).isTrue()
    }

    private fun hash(value: String) = hash(value.encodeToByteArray())
    private fun hash(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun sign(payload: JsonObject): JsonObject {
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(key.private); update(verifier.canonicalJson(payload)); sign()
        }
        return JsonObject(payload + ("signature_b64" to JsonPrimitive(Base64.getEncoder().encodeToString(signature))))
    }
    private fun completion(damage: String? = null): Pair<io.element.android.security.dataprotection.api.FriendshipRemovalIntent, (JsonObject) -> JsonObject> {
        val actor = "@actor:test"; val device = "DEVICE"; val room = "!room:test"
        val original = buildJsonObject {
            put("user_a_id", actor); put("user_b_id", "@peer:test"); put("matrix_room_id", room)
            put("status", "ACCEPTED"); put("source", "ONBOARDING_INVITATION"); put("invitation_id", JsonNull)
            put("created_at", 1); put("accepted_at", 2)
        }
        val snapshot = io.element.android.security.dataprotection.api.FriendshipRemovalSnapshot(hash(actor), hash(device),
            "test-installation", "@peer:test", room, hash(verifier.canonicalJson(original)),
            io.element.android.security.dataprotection.api.FriendshipRemovalStatus.ACCEPTED)
        val intent = io.element.android.security.dataprotection.api.FriendshipRemovalIntent("friend-" + "d".repeat(64), snapshot, setOf(room), false)
        val scope = buildJsonObject {
            put("actor", actor); put("matrix_device", device); put("installation", snapshot.installationId)
            put("peer", if (damage == "peer_scope") "@other:test" else snapshot.peerUserId)
            put("snapshot", if (damage == "generation") JsonObject(original + ("accepted_at" to JsonPrimitive(3))) else original)
            putJsonObject("rooms") { put(if (damage == "room_scope") "!other:test" else room, buildJsonObject {}) }
        }
        val receipt = sign(buildJsonObject {
            put("schema_version", 1); put("catalog_id", "yizhechat-full-app-lifecycle-v1")
            put("receipt_id", "test-receipt-001"); put("operation_id", if (damage == "operation") "friend-" + "e".repeat(64) else intent.operationId)
            put("lifecycle_id", "LC-11"); put("terminal_state", if (damage == "terminal") "CANCELLED" else "SUCCEEDED")
            putJsonObject("subjects") {
                put("account_id_hash", if (damage == "subject") "0".repeat(64) else snapshot.accountIdHash)
                put("matrix_device_id_hash", snapshot.matrixDeviceIdHash); put("installation_id", snapshot.installationId); put("room_id_hash", hash(room))
            }
            putJsonObject("authority_revisions") { put("friendship_removal_v1", verifier.canonicalJson(scope).decodeToString()) }
            putJsonObject("required_step_receipts") {
                listOf("matrix", "control_plane", if (damage == "steps") "kms" else "database").forEach {
                    put(intent.operationId + ":" + it, "friendship-revoked:" + "f".repeat(64))
                }
            }
            put("policy_version", 1); put("started_at_ms", 1); put("completed_at_ms", 2)
        })
        return intent to { query ->
            val now = System.currentTimeMillis()
            sign(buildJsonObject {
                put("schema_version", 1); put("purpose", "FRIENDSHIP_REMOVAL_COMPLETE"); put("installation_id", snapshot.installationId)
                put("query", query); put("signing_key_id", keyId); put("issued_at_ms", now); put("expires_at_ms", now + 15_000)
                putJsonObject("result") {
                    put("status", "REMOVED"); put("operation_id", intent.operationId)
                    put("receipt", if (damage == "signature") JsonObject(receipt + ("signature_b64" to JsonPrimitive("invalid"))) else receipt)
                }
            })
        }
    }
    @Test fun `actual LC11 wire fields and canonical friendship generation verify successfully`() = runTest {
        val (intent, response) = completion()
        FriendshipRemovalClient(verifier, { "test-installation" }) { response(it) }.complete(intent)
    }
    @Test fun `signed ack cannot hide invalid original LC11 receipt or mismatched scope`() = runTest {
        for (damage in listOf("signature", "operation", "terminal", "subject", "peer_scope", "room_scope", "generation", "steps")) {
            val (intent, response) = completion(damage)
            assertThat(runCatching { FriendshipRemovalClient(verifier, { "test-installation" }) { response(it) }.complete(intent) }.isFailure).isTrue()
        }
    }
}
