/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.dataprotection.impl

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64

/** Synthetic component fixtures only; none constitutes a real device capability observation. */
class NextPolicyChallengeProtocolTest {
    @Test
    fun `challenge uses exact whole envelope digest without claiming applied`() {
        val f = Fixture()
        val envelope = f.envelope()
        val verified = f.verify(envelope)
        assertThat(verified.challengeDigestSha256).isEqualTo(f.digest(f.verifier.canonicalJson(envelope)))
        val receipt = verified.receiptPayload(f.context, MONOTONIC + 10, NOW + 10, f.observations())
        assertThat(receipt["challengeDigestSha256"]).isEqualTo(JsonPrimitive(verified.challengeDigestSha256))
        assertThat(receipt["result"]).isEqualTo(JsonPrimitive("APPLIED"))
    }

    @Test
    fun `changed payload and another signing key are rejected`() {
        val f = Fixture()
        val envelope = f.envelope()
        val tampered = JsonObject(envelope + ("payload" to JsonObject(f.payload() + ("policyVersion" to JsonPrimitive(99)))))
        assertThrows(IllegalStateException::class.java) { f.verify(tampered) }
        val other = Fixture()
        assertThrows(IllegalStateException::class.java) { f.verify(other.envelope()) }
    }

    @Test
    fun `signed account operation installation policy and artifact mismatches are rejected`() {
        val f = Fixture()
        val changed = listOf(
            f.context.copy(operationId = "another-operation"),
            f.context.copy(policyId = "another-policy"),
            f.context.copy(policyVersion = 8),
            f.context.copy(policyDigestSha256 = "9".repeat(64)),
            f.context.copy(accountIdHash = "8".repeat(64)),
            f.context.copy(installationId = "another-installation"),
            f.context.copy(targetSnapshot = JsonObject(f.context.targetSnapshot + ("bootId" to JsonPrimitive("boot-next-2")))),
            f.context.copy(targetSnapshot = JsonObject(f.context.targetSnapshot + ("appArtifactSha256" to JsonPrimitive("7".repeat(64))))),
            f.context.copy(targetSnapshot = JsonObject(f.context.targetSnapshot + ("accountMobileGeneration" to JsonPrimitive(2)))),
        )
        changed.forEach { context ->
            assertThrows(IllegalStateException::class.java) { f.verify(f.envelope(), context) }
        }
    }

    @Test
    fun `extra fields wrong contract and stringified integers are rejected even when signed`() {
        val f = Fixture()
        assertThrows(IllegalStateException::class.java) { f.verify(JsonObject(f.envelope() + ("extra" to JsonPrimitive(true)))) }
        assertThrows(IllegalStateException::class.java) { f.verify(JsonObject(f.envelope() + ("contract" to JsonPrimitive("CP-POLICY")))) }
        assertThrows(IllegalStateException::class.java) { f.verify(f.envelope(JsonObject(f.payload() + ("policyVersion" to JsonPrimitive("7"))))) }
        assertThrows(IllegalStateException::class.java) { f.verify(f.envelope(JsonObject(f.payload() + ("extra" to JsonPrimitive(true))))) }
    }

    @Test
    fun `exact expiry future issuance and invalid nonce are rejected`() {
        val f = Fixture()
        assertThrows(IllegalStateException::class.java) { f.verify(f.envelope(JsonObject(f.payload() + ("expiresAtMs" to JsonPrimitive(NOW))))) }
        assertThrows(IllegalStateException::class.java) { f.verify(f.envelope(JsonObject(f.payload() + ("issuedAtMs" to JsonPrimitive(NOW + 1))))) }
        assertThrows(IllegalArgumentException::class.java) { f.verify(f.envelope(JsonObject(f.payload() + ("challengeNonceB64" to JsonPrimitive("!!!!"))))) }
    }

    @Test
    fun `unknown duplicated and missing required capabilities are rejected`() {
        val f = Fixture()
        listOf(listOf("FUTURE_CAPABILITY"), listOf("KMS_LEASE", "KMS_LEASE"), emptyList()).forEach { names ->
            val payload = JsonObject(f.payload() + ("requiredCapabilities" to JsonArray(names.map(::JsonPrimitive))))
            assertThrows(IllegalStateException::class.java) { f.verify(f.envelope(payload)) }
        }
    }

    @Test
    fun `account boot artifact and DAK transitions invalidate receipt preparation`() {
        val f = Fixture()
        val verified = f.verify()
        listOf(
            f.context.copy(accountIdHash = "9".repeat(64)),
            f.context.copy(devicePublicKeySha256 = "8".repeat(64)),
            f.context.copy(targetSnapshot = JsonObject(f.context.targetSnapshot + ("bootId" to JsonPrimitive("another-boot")))),
            f.context.copy(targetSnapshot = JsonObject(f.context.targetSnapshot + ("appArtifactSha256" to JsonPrimitive("7".repeat(64))))),
        ).forEach { current ->
            assertThrows(IllegalStateException::class.java) { verified.receiptPayload(current, MONOTONIC + 10, NOW + 10, f.observations()) }
        }
    }

    @Test
    fun `monotonic rollback frozen trusted time and receipt expiry are rejected`() {
        val f = Fixture()
        val verified = f.verify()
        assertThrows(IllegalStateException::class.java) { verified.receiptPayload(f.context, MONOTONIC - 1, NOW, f.observations()) }
        assertThrows(IllegalStateException::class.java) { verified.receiptPayload(f.context, MONOTONIC + 10, NOW, f.observations()) }
        assertThrows(IllegalStateException::class.java) { verified.receiptPayload(f.context, MONOTONIC + 60_000, NOW + 60_000, f.observations()) }
    }

    @Test
    fun `missing duplicate capability or malformed evidence cannot become applied`() {
        val f = Fixture()
        val verified = f.verify()
        listOf(
            emptyList(),
            listOf(f.observations().first()),
            f.observations() + f.observations().first(),
            listOf(f.observations().first().copy(evidenceDigestSha256 = "malformed"), f.observations().last()),
        ).forEach { observations ->
            assertThrows(IllegalStateException::class.java) { verified.receiptPayload(f.context, MONOTONIC + 10, NOW + 10, observations) }
        }
    }

    @Test
    fun `applied with cleanup pending is rejected and cleanup pending stays explicit`() {
        val f = Fixture()
        val verified = f.verify()
        val pending = f.observations().last().copy(cleanupOperationIds = listOf("cleanup-kms-1"))
        assertThrows(IllegalStateException::class.java) {
            verified.receiptPayload(f.context, MONOTONIC + 10, NOW + 10, listOf(f.observations().first(), pending))
        }
        val receipt = verified.receiptPayload(
            f.context,
            MONOTONIC + 10,
            NOW + 10,
            listOf(f.observations().first(), pending.copy(result = "APPLIED_CLEANUP_PENDING"))
        )
        assertThat(receipt["result"]).isEqualTo(JsonPrimitive("APPLIED_CLEANUP_PENDING"))
    }

    @Test
    fun `failed and blocked observations dominate an otherwise applied result`() {
        val f = Fixture()
        val verified = f.verify()
        listOf("FAILED", "BLOCKED").forEach { result ->
            val receipt = verified.receiptPayload(
                f.context,
                MONOTONIC + 10,
                NOW + 10,
                listOf(f.observations().first().copy(result = result), f.observations().last())
            )
            assertThat(receipt["result"]).isEqualTo(JsonPrimitive(result))
        }
    }

    @Test
    fun `caller mutable collections cannot rewrite previously verified authority`() {
        val f = Fixture()
        val target = f.context.targetSnapshot.toMutableMap()
        val capabilities = f.context.requiredCapabilities.toMutableSet()
        val context = f.context.copy(targetSnapshot = JsonObject(target), requiredCapabilities = capabilities)
        val verified = f.verify(f.envelope(), context)
        target["bootId"] = JsonPrimitive("another-boot")
        capabilities.clear()
        assertThrows(IllegalStateException::class.java) {
            verified.receiptPayload(context, MONOTONIC + 10, NOW + 10, f.observations())
        }
        assertThat(verified.receiptPayload(f.context, MONOTONIC + 10, NOW + 10, f.observations())["result"])
            .isEqualTo(JsonPrimitive("APPLIED"))
    }

    private class Fixture {
        private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        private val keyDigest = digest(pair.public.encoded)
        val verifier = ManagementSignatureVerifier(mockk<ManagementControlConfiguration>().also { configuration ->
            every { configuration.isConfigured } returns true
            every { configuration.signingKeyId } returns keyDigest.take(16)
            every { configuration.signingPublicKeyDerBase64 } returns Base64.getEncoder().encodeToString(pair.public.encoded)
        })
        val context = NextPolicyChallengeContext(
            operationId = "policy-operation-1",
            policyId = "policy-1",
            policyVersion = 7,
            policyDigestSha256 = "a".repeat(64),
            installationId = "installation-1",
            accountIdHash = "b".repeat(64),
            targetSnapshot = buildJsonObject {
                put("bootId", "boot-next-1")
                put("appVersionCode", 123)
                put("appArtifactSha256", "c".repeat(64))
                put("accountMobileGeneration", 1)
            },
            requiredCapabilities = setOf("SCREEN_CAPTURE", "KMS_LEASE"),
            devicePublicKeySha256 = "d".repeat(64),
        )
        fun payload(): JsonObject = buildJsonObject {
            put("operationId", context.operationId)
            put("policyDomain", "DATA_PROTECTION")
            put("policyId", context.policyId)
            put("policyVersion", context.policyVersion)
            put("policyDigestSha256", context.policyDigestSha256)
            put("installationId", context.installationId)
            put("accountIdHash", context.accountIdHash)
            put("challengeId", "policy-challenge-1")
            put("challengeNonceB64", Base64.getEncoder().encodeToString(ByteArray(32) { 1 }))
            put("issuedAtMs", NOW)
            put("expiresAtMs", NOW + 60_000)
            put("requiredCapabilities", JsonArray(context.requiredCapabilities.sorted().map(::JsonPrimitive)))
            put("targetSnapshot", context.targetSnapshot)
        }
        fun envelope(payload: JsonObject = payload()): JsonObject {
            val signature = Signature.getInstance("SHA256withECDSA").run {
                initSign(pair.private)
                update(verifier.canonicalJson(payload))
                sign()
            }
            return buildJsonObject {
                put("contract", "next.policy-apply-challenge.v1")
                put("payload", payload)
                put("serverKeyId", keyDigest.take(16))
                put("serverSignatureAlgorithm", "SHA256withECDSA")
                put("serverSignatureB64", Base64.getEncoder().encodeToString(signature))
            }
        }
        fun verify(envelope: JsonObject = envelope(), context: NextPolicyChallengeContext = this.context) =
            VerifiedNextPolicyChallenge.verify(envelope, context, NOW, MONOTONIC, verifier)
        fun observations() = listOf(
            NextCapabilityObservation("SCREEN_CAPTURE", "APPLIED", "e".repeat(64), emptyList()),
            NextCapabilityObservation("KMS_LEASE", "APPLIED", "f".repeat(64), emptyList()),
        )
        fun digest(raw: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(raw)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
    private companion object {
        const val NOW = 1_800_000_000_000L
        const val MONOTONIC = 100_000L
    }
}
