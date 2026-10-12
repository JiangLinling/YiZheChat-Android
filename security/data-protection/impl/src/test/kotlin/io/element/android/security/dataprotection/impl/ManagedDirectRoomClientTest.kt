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

class ManagedDirectRoomClientTest {
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
            put("room_id", if (damage == "room") "bad-room" else "!room:test")
            put("status", if (damage == "status") "FRIEND" else "INVITED")
            put("operation_id", "direct-" + "c".repeat(64))
            put("creation_operation_id", "dmroom-" + (if (damage == "child") "d" else "c").repeat(64))
            putJsonArray("client_operations") { add("direct-" + (if (damage == "operations") "d" else "c").repeat(64)); add("dmroom-" + "c".repeat(64)) }
        }
        val payload = buildJsonObject {
            put("schema_version", 1)
            put("purpose", if (damage == "purpose") "FRIENDSHIP_REMOVAL_BEGIN" else "MANAGED_DIRECT_ROOM_${query.getValue("action").jsonPrimitive.content.uppercase()}")
            put("installation_id", if (damage == "installation") "other" else "test-installation")
            put("query", if (damage == "nonce") JsonObject(query + ("request_nonce" to JsonPrimitive("f".repeat(64)))) else query)
            put("result", if (damage == "null") JsonNull else result)
            put("issued_at_ms", if (damage == "future") now + 60000 else now - 1000)
            put("expires_at_ms", when (damage) { "expired" -> now - 1; "duration" -> now + 60000; else -> now + 10000 })
            put("signing_key_id", keyId)
        }
        val signature = Signature.getInstance("SHA256withECDSA").run { initSign(key.private); update(verifier.canonicalJson(payload)); sign() }
        return JsonObject(payload + ("signature_b64" to JsonPrimitive(Base64.getEncoder().encodeToString(signature))))
    }
    @Test fun `fresh signed invite remains pending and binds parent and child`() = runTest {
        val result = ManagedDirectRoomClient(verifier, { "test-installation" }) { signed(it) }.execute("a".repeat(64), "b".repeat(64), "@peer:test", true)
        assertThat(result?.status).isEqualTo("INVITED")
        assertThat(result?.clientOperationIds).containsExactly("direct-" + "c".repeat(64), "dmroom-" + "c".repeat(64))
    }
    @Test fun `fresh signed absence is only allowed for read only lookup`() = runTest {
        val client = ManagedDirectRoomClient(verifier, { "test-installation" }) { signed(it, "null") }
        assertThat(client.execute("a".repeat(64), "b".repeat(64), "@peer:test", false)).isNull()
        assertThat(runCatching { client.execute("a".repeat(64), "b".repeat(64), "@peer:test", true) }.isFailure).isTrue()
    }
    @Test fun `valid signatures cannot substitute time device nonce purpose generation or room`() = runTest {
        for (damage in listOf("purpose", "installation", "nonce", "future", "expired", "duration", "room", "status", "child", "operations")) {
            val client = ManagedDirectRoomClient(verifier, { "test-installation" }) { signed(it, damage) }
            assertThat(runCatching { client.execute("a".repeat(64), "b".repeat(64), "@peer:test", true) }.isFailure).isTrue()
        }
    }
    @Test fun `tampered invitation is rejected cryptographically`() = runTest {
        val client = ManagedDirectRoomClient(verifier, { "test-installation" }) { JsonObject(signed(it) + ("result" to JsonNull)) }
        assertThat(runCatching { client.execute("a".repeat(64), "b".repeat(64), "@peer:test", false) }.isFailure).isTrue()
    }
}
