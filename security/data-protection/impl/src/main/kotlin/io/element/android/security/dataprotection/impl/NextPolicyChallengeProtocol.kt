/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.dataprotection.impl

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.util.Base64

/** Exact locally observed and previously verified authority, supplied by the future integration. */
internal data class NextPolicyChallengeContext(
    val operationId: String,
    val policyId: String,
    val policyVersion: Long,
    val policyDigestSha256: String,
    val installationId: String,
    val accountIdHash: String,
    val targetSnapshot: JsonObject,
    val requiredCapabilities: Set<String>,
    val devicePublicKeySha256: String,
)

/** One adapter's durable observation; this helper never manufactures adapter evidence. */
internal data class NextCapabilityObservation(
    val capability: String,
    val result: String,
    val evidenceDigestSha256: String,
    val cleanupOperationIds: List<String>,
)

/** Verified payload and exact whole-envelope digest. Does not claim local policy application. */
internal class VerifiedNextPolicyChallenge private constructor(
    private val envelope: JsonObject,
    private val context: NextPolicyChallengeContext,
    private val verifiedAtMonotonicMs: Long,
    private val verifiedAtTrustedServerTimeMs: Long,
    val challengeDigestSha256: String,
) {
    private val payload: JsonObject get() = envelope.getValue("payload") as JsonObject

    fun receiptPayload(
        currentContext: NextPolicyChallengeContext,
        appliedAtMonotonicMs: Long,
        trustedServerTimeMs: Long,
        observations: List<NextCapabilityObservation>,
    ): JsonObject {
        // Reject account, artifact, boot, DAK or policy transitions between challenge and application.
        check(currentContext == context) { "Policy challenge authority changed before application." }
        check(appliedAtMonotonicMs >= verifiedAtMonotonicMs) { "Monotonic application time rolled back." }
        check(trustedServerTimeMs >= verifiedAtTrustedServerTimeMs)
        check(trustedServerTimeMs - verifiedAtTrustedServerTimeMs >= appliedAtMonotonicMs - verifiedAtMonotonicMs) {
            "Trusted application time did not advance with the current boot's monotonic clock."
        }
        check(trustedServerTimeMs >= payload.number("issuedAtMs"))
        check(trustedServerTimeMs < payload.number("expiresAtMs")) { "Policy challenge expired." }
        check(observations.isNotEmpty() && observations.size <= 16)
        check(observations.map { it.capability }.toSet().size == observations.size)
        check(observations.map { it.capability }.toSet() == context.requiredCapabilities)
        val results = observations.sortedBy { it.capability }.map { observation ->
            check(observation.capability in NEXT_CAPABILITIES)
            check(observation.result in NEXT_RESULTS)
            check(observation.evidenceDigestSha256.matches(NEXT_SHA256))
            check(observation.cleanupOperationIds.size <= 32)
            check(observation.cleanupOperationIds.distinct().size == observation.cleanupOperationIds.size)
            check(observation.cleanupOperationIds.all { it.matches(NEXT_ID) })
            check((observation.result == "APPLIED_CLEANUP_PENDING") == observation.cleanupOperationIds.isNotEmpty())
            buildJsonObject {
                put("capability", observation.capability)
                put("result", observation.result)
                put("evidenceDigestSha256", observation.evidenceDigestSha256)
                put("cleanupOperationIds", JsonArray(observation.cleanupOperationIds.sorted().map(::JsonPrimitive)))
            }
        }
        val actualResults = observations.map { it.result }.toSet()
        val result = listOf("FAILED", "BLOCKED", "APPLIED_CLEANUP_PENDING", "APPLIED").first(actualResults::contains)
        return buildJsonObject {
            NEXT_RECEIPT_BINDINGS.forEach { key -> put(key, payload.getValue(key)) }
            put("challengeDigestSha256", challengeDigestSha256)
            put("appliedAtMonotonicMs", appliedAtMonotonicMs)
            put("trustedServerTimeMs", trustedServerTimeMs)
            put("result", result)
            put("capabilityResults", JsonArray(results))
        }
    }

    companion object {
        fun verify(
            envelope: JsonObject,
            expected: NextPolicyChallengeContext,
            trustedServerTimeMs: Long,
            currentMonotonicMs: Long,
            verifier: ManagementSignatureVerifier,
        ): VerifiedNextPolicyChallenge {
            check(envelope.keys == setOf("contract", "payload", "serverKeyId", "serverSignatureAlgorithm", "serverSignatureB64"))
            check(envelope.string("contract") == "next.policy-apply-challenge.v1")
            check(envelope.string("serverSignatureAlgorithm") == "SHA256withECDSA")
            val payload = envelope["payload"] as? JsonObject ?: error("Policy challenge payload is not an object.")
            check(payload.keys == NEXT_RECEIPT_BINDINGS + setOf("issuedAtMs", "expiresAtMs", "requiredCapabilities"))
            check(verifier.verifyDetached(payload, envelope.string("serverKeyId"), envelope.string("serverSignatureB64"))) {
                "Policy challenge signature is invalid."
            }
            check(currentMonotonicMs >= 0 && trustedServerTimeMs > 0)
            check(expected.operationId.matches(NEXT_ID) && expected.policyId.matches(NEXT_ID))
            check(expected.installationId.matches(NEXT_ID) && expected.policyVersion > 0)
            check(expected.policyDigestSha256.matches(NEXT_SHA256) && expected.accountIdHash.matches(NEXT_SHA256))
            check(expected.devicePublicKeySha256.matches(NEXT_SHA256))
            check(payload.string("operationId") == expected.operationId)
            check(payload.string("policyDomain") == "DATA_PROTECTION")
            check(payload.string("policyId") == expected.policyId && payload.number("policyVersion") == expected.policyVersion)
            check(payload.string("policyDigestSha256") == expected.policyDigestSha256)
            check(payload.string("installationId") == expected.installationId && payload.string("accountIdHash") == expected.accountIdHash)
            check(payload.string("challengeId").matches(NEXT_ID))
            val nonce = payload.string("challengeNonceB64")
            check(nonce.length in 4..8192 && Base64.getDecoder().decode(nonce).isNotEmpty())
            check(payload.number("issuedAtMs") > 0 && payload.number("issuedAtMs") <= trustedServerTimeMs)
            check(payload.number("expiresAtMs") > trustedServerTimeMs)
            val target = payload["targetSnapshot"] as? JsonObject ?: error("Policy challenge target is not an object.")
            check(target.keys == setOf("bootId", "appVersionCode", "appArtifactSha256", "accountMobileGeneration"))
            check(target.string("bootId").matches(NEXT_ID))
            check(target.number("appVersionCode") > 0 && target.number("accountMobileGeneration") > 0)
            check(target.string("appArtifactSha256").matches(NEXT_SHA256))
            check(target == expected.targetSnapshot)
            val capabilities = payload["requiredCapabilities"] as? JsonArray ?: error("Policy challenge capability set is invalid.")
            val names = capabilities.map { value ->
                val primitive = value as? JsonPrimitive ?: error("Policy capability is not a string.")
                check(primitive.isString)
                primitive.content
            }
            check(names.isNotEmpty() && names.size <= 16 && names.distinct().size == names.size)
            check(names.all(NEXT_CAPABILITIES::contains) && names.toSet() == expected.requiredCapabilities)
            return VerifiedNextPolicyChallenge(
                // Take owned snapshots so a caller's mutable map/set cannot change verified authority.
                envelope = Json.parseToJsonElement(verifier.canonicalJson(envelope).decodeToString()) as JsonObject,
                context = expected.copy(
                    targetSnapshot = Json.parseToJsonElement(verifier.canonicalJson(expected.targetSnapshot).decodeToString()) as JsonObject,
                    requiredCapabilities = expected.requiredCapabilities.toSet(),
                ),
                verifiedAtMonotonicMs = currentMonotonicMs,
                verifiedAtTrustedServerTimeMs = trustedServerTimeMs,
                challengeDigestSha256 = MessageDigest.getInstance("SHA-256").digest(verifier.canonicalJson(envelope))
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) },
            )
        }
    }
}

private fun JsonObject.string(key: String): String {
    val value = getValue(key) as? JsonPrimitive ?: error("Policy string field is invalid.")
    check(value.isString)
    return value.content
}

private fun JsonObject.number(key: String): Long {
    val value = getValue(key) as? JsonPrimitive ?: error("Policy integer field is invalid.")
    check(!value.isString)
    return checkNotNull(value.content.toLongOrNull()) { "Policy integer field is invalid." }
}

private val NEXT_SHA256 = Regex("[0-9a-f]{64}")
private val NEXT_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}")
private val NEXT_CAPABILITIES = setOf("SCREEN_CAPTURE", "NOTIFICATION_EXPOSURE", "KMS_LEASE", "SECURE_STORAGE", "CLIPBOARD", "RTC")
private val NEXT_RESULTS = setOf("APPLIED", "APPLIED_CLEANUP_PENDING", "BLOCKED", "FAILED")
private val NEXT_RECEIPT_BINDINGS = setOf(
    "operationId", "policyDomain", "policyId", "policyVersion", "policyDigestSha256", "installationId",
    "accountIdHash", "challengeId", "challengeNonceB64", "targetSnapshot",
)
