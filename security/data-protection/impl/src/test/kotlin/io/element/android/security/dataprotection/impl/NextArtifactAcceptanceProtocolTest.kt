/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.dataprotection.impl

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
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

/** Isolated synthetic authority fixtures; no native APK inspection or real DAK claims. */
class NextArtifactAcceptanceProtocolTest {
    @Test
    fun `exact accepted claim produces the bound challenge request`() {
        val f = Fixture()
        val request = f.request(f.verify())
        assertThat(request.keys).containsExactly("operationId", "accountIdHash", "bootId", "artifactAcceptanceReceiptId")
        assertThat(request["artifactAcceptanceReceiptId"]).isEqualTo(f.payload()["receiptId"])
    }

    @Test
    fun `tampered acceptance and another signing key are rejected`() {
        val f = Fixture()
        val envelope = f.envelope()
        val changed = JsonObject(envelope + ("payload" to JsonObject(f.payload() + ("buildId" to JsonPrimitive("other")))))
        reject { f.verify(changed) }
        reject { f.verify(Fixture().envelope()) }
    }

    @Test
    fun `every signed claim field and numeric type must match the original claim`() {
        val f = Fixture()
        f.claim.payload().keys.forEach { key ->
            reject { f.verify(f.envelope(JsonObject(f.payload() + (key to JsonPrimitive("changed"))))) }
        }
        reject { f.verify(f.envelope(JsonObject(f.payload() + ("appVersionCode" to JsonPrimitive("123"))))) }
    }

    @Test
    fun `current installation DAK digest and attestation version are mandatory`() {
        val f = Fixture()
        val contexts = listOf(
            f.dak.copy(installationId = "another-installation"),
            f.dak.copy(devicePublicKeySha256 = "9".repeat(64)),
            f.dak.copy(attestationVersion = 8),
            f.dak.copy(attestationVersion = 0),
        )
        contexts.forEach { reject { f.verify(dak = it) } }
    }

    @Test
    fun `acceptance binds exact persisted bytes even when JSON is equivalent`() {
        val f = Fixture()
        val spaced = (" " + f.raw.decodeToString() + "\n").encodeToByteArray()
        reject { f.verify(raw = spaced) }
        val payload = f.payload(raw = spaced)
        assertThat(f.verify(f.envelope(payload), raw = spaced).receiptId).isEqualTo((payload["receiptId"] as JsonPrimitive).content)
        reject { f.verify(raw = "{}".encodeToByteArray()) }
    }

    @Test
    fun `server inspection claims extras wrong contract and algorithm are rejected`() {
        val f = Fixture()
        reject { f.verify(f.envelope(JsonObject(f.payload() + ("claimProvenance" to JsonPrimitive("SERVER_INSPECTED_APK"))))) }
        reject { f.verify(f.envelope(JsonObject(f.payload() + ("extra" to JsonPrimitive(true))))) }
        reject { f.verify(JsonObject(f.envelope() + ("extra" to JsonPrimitive(true)))) }
        reject { f.verify(JsonObject(f.envelope() + ("contract" to JsonPrimitive("CP-POLICY")))) }
        reject { f.verify(JsonObject(f.envelope() + ("serverSignatureAlgorithm" to JsonPrimitive("none")))) }
    }

    @Test
    fun `expired future issued and invalid verification clock are rejected`() {
        val f = Fixture()
        reject { f.verify(f.envelope(f.payload(expiry = NOW))) }
        reject { f.verify(f.envelope(f.payload(accepted = NOW + 1))) }
        reject { f.verify(monotonic = -1) }
        reject { f.verify(f.envelope(JsonObject(f.payload() + ("expiresAtMs" to JsonPrimitive("1800000060000"))))) }
    }

    @Test
    fun `invalid local package digest build nonce and version cannot form a claim`() {
        val f = Fixture()
        listOf(
            f.claim.copy(packageName = "invalid/package"),
            f.claim.copy(appVersionCode = 0),
            f.claim.copy(appArtifactSha256 = "A".repeat(64)),
            f.claim.copy(signingCertificateSha256 = "bad"),
            f.claim.copy(buildId = "bad build"),
            f.claim.copy(claimNonce = ""),
        ).forEach { claim -> reject { claim.payload() } }
        reject { f.verify(f.envelope(JsonObject(f.payload() + ("receiptId" to JsonPrimitive("artifact-acceptance-" + "0".repeat(32)))))) }
    }

    @Test
    fun `changed installed artifact and DAK cannot reuse an acceptance`() {
        val f = Fixture()
        val accepted = f.verify()
        reject { f.request(accepted, claim = f.claim.copy(appArtifactSha256 = "0".repeat(64))) }
        reject { f.request(accepted, claim = f.claim.copy(appVersionCode = 124)) }
        reject { f.request(accepted, claim = f.claim.copy(signingCertificateSha256 = "0".repeat(64))) }
        reject { f.request(accepted, dak = f.dak.copy(attestationVersion = 8)) }
    }

    @Test
    fun `monotonic rollback frozen trusted time and expiry invalidate challenge preparation`() {
        val f = Fixture()
        val accepted = f.verify()
        reject { f.request(accepted, monotonic = MONOTONIC - 1) }
        reject { f.request(accepted, monotonic = MONOTONIC + 10, trusted = NOW) }
        reject { f.request(accepted, trusted = NOW + 60_000) }
    }

    @Test
    fun `consumed acknowledgement requires payload digest and every exact projection field`() {
        val f = Fixture()
        val payload = f.receiptPayload("APPLIED")
        val ack = f.ack(payload)
        validateNextPolicyConsumedAcknowledgement(payload, ack, f.verifier)
        ack.keys.forEach { key -> reject { validateNextPolicyConsumedAcknowledgement(payload, JsonObject(ack + (key to JsonPrimitive("other"))), f.verifier) } }
        reject { validateNextPolicyConsumedAcknowledgement(payload, JsonObject(ack + ("extra" to JsonPrimitive(true))), f.verifier) }
        val wholeEnvelopeDigest = f.digest(f.verifier.canonicalJson(buildJsonObject {
            put("contract", "next.policy-applied-receipt.v1")
            put("payload", payload)
        }))
        reject {
            validateNextPolicyConsumedAcknowledgement(
            payload,
            JsonObject(ack + ("receiptDigestSha256" to JsonPrimitive(wholeEnvelopeDigest))),
            f.verifier
        )
        }
    }

    @Test
    fun `response loss reconciliation preserves original payload and nonapplied outcomes`() {
        val f = Fixture()
        listOf("APPLIED", "APPLIED_CLEANUP_PENDING", "BLOCKED", "FAILED").forEach { result ->
            val original = f.receiptPayload(result)
            val ack = f.ack(original)
            repeat(2) { validateNextPolicyConsumedAcknowledgement(original, ack, f.verifier) }
            if (result !=
                "APPLIED") {
                    reject { validateNextPolicyConsumedAcknowledgement(original, JsonObject(ack + ("projectedState" to JsonPrimitive("APPLIED"))), f.verifier) }
                }
            val later = JsonObject(original + ("trustedServerTimeMs" to JsonPrimitive(NOW + 1)))
            reject { validateNextPolicyConsumedAcknowledgement(later, ack, f.verifier) }
        }
    }

    private fun reject(block: () -> Unit) {
        assertThrows(IllegalStateException::class.java, block)
    }

    private class Fixture {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val keyDigest = digest(pair.public.encoded)
        val verifier = ManagementSignatureVerifier(mockk<ManagementControlConfiguration> {
            every { isConfigured } returns true
            every { signingKeyId } returns keyDigest.take(16)
            every { signingPublicKeyDerBase64 } returns Base64.getEncoder().encodeToString(pair.public.encoded)
        })
        val claim =
            NextInstalledArtifactClaim(
                "artifact-op-1",
                "installation-1",
                "com.yizhe.chat.next.debug",
                123,
                "a".repeat(64),
                "b".repeat(64),
                "build-1",
                "claim-nonce-1"
            )
        val dak = NextCurrentArtifactDak(claim.installationId, "c".repeat(64), 7)
        val raw get() = verifier.canonicalJson(claim.payload())
        fun payload(raw: ByteArray = this.raw, accepted: Long = NOW, expiry: Long = NOW + 60_000): JsonObject {
            val digest = digest(raw)
            val identity = buildJsonObject {
                put("claim", claim.payload())
                put("requestDigestSha256", digest)
                put("acceptedAtMs", accepted)
            }
            return JsonObject(claim.payload() + buildJsonObject {
                put("receiptId", "artifact-acceptance-" + digest(verifier.canonicalJson(identity)).take(32))
                put("devicePublicKeySha256", dak.devicePublicKeySha256)
                put("attestationVersion", dak.attestationVersion)
                put("claimRequestDigestSha256", digest)
                put("acceptedAtMs", accepted)
                put("expiresAtMs", expiry)
                put("claimProvenance", "CURRENT_DAK_AUTHENTICATED_DEVICE_CLAIM_MATCHED_TO_PUBLISHED_ARTIFACT_NOT_SERVER_INSTALL_INSPECTION")
            })
        }
        fun envelope(payload: JsonObject = payload()): JsonObject {
            val signature = Signature.getInstance("SHA256withECDSA").run {
                initSign(pair.private)
                update(verifier.canonicalJson(payload))
                sign()
            }
            return buildJsonObject {
                put("contract", "next.installed-artifact-acceptance.v1")
                put("payload", payload)
                put("serverKeyId", keyDigest.take(16))
                put("serverSignatureAlgorithm", "SHA256withECDSA")
                put("serverSignatureB64", Base64.getEncoder().encodeToString(signature))
            }
        }
        fun verify(envelope: JsonObject = envelope(), raw: ByteArray = this.raw, dak: NextCurrentArtifactDak = this.dak, monotonic: Long = MONOTONIC) =
            VerifiedNextArtifactAcceptance.verify(envelope, claim, raw, dak, NOW, monotonic, verifier)
        fun request(
            accepted: VerifiedNextArtifactAcceptance,
            claim: NextInstalledArtifactClaim = this.claim,
            dak: NextCurrentArtifactDak = this.dak,
            monotonic: Long = MONOTONIC + 10,
            trusted: Long = NOW + 10,
        ) =
            accepted.challengeRequest("policy-op-1", "d".repeat(64), "boot-1", claim, dak, monotonic, trusted)
        fun receiptPayload(result: String) = buildJsonObject {
            put("challengeId", "challenge-1")
            put("result", result)
            put("trustedServerTimeMs", NOW)
        }
        fun ack(payload: JsonObject): JsonObject {
            val digest = digest(verifier.canonicalJson(payload))
            return buildJsonObject {
                put("status", "CONSUMED")
                put("challengeId", payload.getValue("challengeId"))
                put("receiptId", "policy-receipt-" + digest.take(32))
                put("receiptDigestSha256", digest)
                put("projectedState", payload.getValue("result"))
            }
        }
        fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val MONOTONIC = 100_000L
    }
}
