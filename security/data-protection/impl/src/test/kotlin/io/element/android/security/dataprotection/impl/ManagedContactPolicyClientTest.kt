package io.element.android.security.dataprotection.impl

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64

class ManagedContactPolicyClientTest {
    private val key = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    private val keyId = MessageDigest.getInstance("SHA-256").digest(key.public.encoded).take(8).joinToString("") { "%02x".format(it.toInt() and 255) }
    private val verifier = ManagementSignatureVerifier(mockk<ManagementControlConfiguration> {
        every { isConfigured } returns true
        every { signingPublicKeyDerBase64 } returns Base64.getEncoder().encodeToString(key.public.encoded)
        every { signingKeyId } returns keyId
    })

    private fun signed(query: JsonObject, damage: String? = null): JsonObject {
        val now = System.currentTimeMillis()
        val action = query.getValue("action").jsonPrimitive.content
        val payload = buildJsonObject {
            put("schema_version", 1)
            put("purpose", "CONTACT_POLICY_${if (damage == "purpose") "OTHER" else action.uppercase()}")
            put("installation_id", if (damage == "device") "other" else "test-installation")
            put("query", if (damage == "query") JsonObject(query + ("request_nonce" to JsonPrimitive("f".repeat(64)))) else query)
            put("issued_at_ms", if (damage == "future") now + 60000 else now - 500)
            put("expires_at_ms", if (damage == "expired") now - 1 else now + 10000)
            put("signing_key_id", keyId)
            putJsonObject("result") {
                if (action == "call_status") {
                    put("terminated", true)
                    put("reason", if (damage == "reason") "UNKNOWN" else "RTC_CALL_ENDED")
                }
                if (action == "admit") put("allowed", damage != "state")
                put("blocked", action == "change" && damage != "state")
                put("revision", "c".repeat(64))
                if (action == "change") put("operation_id", "contact-${if (damage == "operation") "d".repeat(64) else "c".repeat(64)}")
            }
        }
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(key.private)
            update(verifier.canonicalJson(payload))
            sign()
        }
        return JsonObject(payload + ("signature_b64" to JsonPrimitive(Base64.getEncoder().encodeToString(signature))))
    }

    @Test fun `change carries the freshly signed prior revision`() = runTest {
        val requests = mutableListOf<JsonObject>()
        val client = ManagedContactPolicyClient(verifier, { "test-installation" }) { query ->
            requests.add(query)
            signed(query)
        }
        client.setBlocked("a".repeat(64), "b".repeat(64), "@peer:test", true)
        assertThat(requests.map { it.getValue("action").jsonPrimitive.content }).containsExactly("preview", "change").inOrder()
        assertThat(requests.last().getValue("revision").jsonPrimitive.content).isEqualTo("c".repeat(64))
    }

    @Test fun `invalid authority envelope never permits a policy change`() = runTest {
        for (damage in listOf("purpose", "device", "query", "future", "expired")) {
            var changes = 0
            val client = ManagedContactPolicyClient(verifier, { "test-installation" }) {
                if (it.getValue("action").jsonPrimitive.content == "change") changes++
                signed(it, damage)
            }
            assertThat(runCatching { client.setBlocked("a".repeat(64), "b".repeat(64), "@peer:test", true) }.isFailure).isTrue()
            assertThat(changes).isEqualTo(0)
        }
    }

    @Test fun `wrong committed state or operation is not reported as success`() = runTest {
        for (damage in listOf("state", "operation")) {
            val client = ManagedContactPolicyClient(verifier, { "test-installation" }) { signed(it, damage) }
            assertThat(runCatching { client.setBlocked("a".repeat(64), "b".repeat(64), "@peer:test", true) }.isFailure).isTrue()
        }
    }

    @Test fun `room admission requires fresh signed exact room and installation`() = runTest {
        for (damage in listOf(null, "purpose", "device", "query", "future", "expired", "state")) {
            val client = ManagedContactPolicyClient(verifier, { "test-installation" }) { signed(it, damage) }
            val result = runCatching { client.requireRoomAllowed("a".repeat(64), "b".repeat(64), "!room:test") }
            assertThat(result.isSuccess).isEqualTo(damage == null)
        }
    }

    @Test fun `terminal call observation requires signed original operation and a known reason`() = runTest {
        for (damage in listOf(null, "purpose", "device", "query", "future", "expired", "reason")) {
            val client = ManagedContactPolicyClient(verifier, { "test-installation" }) {
                assertThat(it.getValue("call_operation_id").jsonPrimitive.content).isEqualTo("original-call")
                signed(it, damage)
            }
            val result = runCatching { client.callTerminationReason("a".repeat(64), "b".repeat(64), "!room:test", "original-call") }
            assertThat(result.isSuccess).isEqualTo(damage == null)
            if (damage == null) assertThat(result.getOrThrow()).isEqualTo("RTC_CALL_ENDED")
        }
    }
}
