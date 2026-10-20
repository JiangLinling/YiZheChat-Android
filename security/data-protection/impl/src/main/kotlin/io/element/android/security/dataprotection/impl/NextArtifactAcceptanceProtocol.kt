/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.dataprotection.impl

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest

/** Values must come from the installed APK reader and the current DAK, not a release label. */
internal data class NextInstalledArtifactClaim(
    val operationId: String,
    val installationId: String,
    val packageName: String,
    val appVersionCode: Long,
    val appArtifactSha256: String,
    val signingCertificateSha256: String,
    val buildId: String,
    val claimNonce: String,
) {
    fun payload(): JsonObject {
        check(listOf(operationId, installationId, claimNonce).all { it.matches(ARTIFACT_ID) })
        check(packageName.matches(Regex("[A-Za-z0-9._]{3,256}")))
        check(buildId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")))
        check(appVersionCode > 0)
        check(appArtifactSha256.matches(ARTIFACT_SHA256) && signingCertificateSha256.matches(ARTIFACT_SHA256))
        return buildJsonObject {
            put("operationId", operationId)
            put("installationId", installationId)
            put("packageName", packageName)
            put("appVersionCode", appVersionCode)
            put("appArtifactSha256", appArtifactSha256)
            put("signingCertificateSha256", signingCertificateSha256)
            put("buildId", buildId)
            put("claimNonce", claimNonce)
        }
    }
}

internal data class NextCurrentArtifactDak(
    val installationId: String,
    val devicePublicKeySha256: String,
    val attestationVersion: Long,
)

/** This is a signed accepted device claim, never independent server inspection of Android bytes. */
internal class VerifiedNextArtifactAcceptance private constructor(
    val receiptId: String,
    private val claim: NextInstalledArtifactClaim,
    private val dak: NextCurrentArtifactDak,
    private val expiresAtMs: Long,
    private val verifiedAtMonotonicMs: Long,
    private val verifiedAtTrustedTimeMs: Long,
) {
    fun challengeRequest(
        operationId: String,
        accountIdHash: String,
        bootId: String,
        currentClaim: NextInstalledArtifactClaim,
        currentDak: NextCurrentArtifactDak,
        currentMonotonicMs: Long,
        currentTrustedTimeMs: Long,
    ): JsonObject {
        check(currentClaim == claim && currentDak == dak) { "Installed artifact or DAK changed after acceptance." }
        check(operationId.matches(ARTIFACT_ID) && bootId.matches(ARTIFACT_ID) && accountIdHash.matches(ARTIFACT_SHA256))
        check(currentMonotonicMs >= verifiedAtMonotonicMs && currentTrustedTimeMs >= verifiedAtTrustedTimeMs)
        check(currentTrustedTimeMs - verifiedAtTrustedTimeMs >= currentMonotonicMs - verifiedAtMonotonicMs)
        check(currentTrustedTimeMs < expiresAtMs) { "Artifact acceptance expired." }
        return buildJsonObject {
            put("operationId", operationId)
            put("accountIdHash", accountIdHash)
            put("bootId", bootId)
            put("artifactAcceptanceReceiptId", receiptId)
        }
    }

    companion object {
        fun verify(
            envelope: JsonObject,
            claim: NextInstalledArtifactClaim,
            exactPersistedRequestBytes: ByteArray,
            currentDak: NextCurrentArtifactDak,
            trustedTimeMs: Long,
            monotonicMs: Long,
            verifier: ManagementSignatureVerifier,
        ): VerifiedNextArtifactAcceptance {
            val claimPayload = claim.payload()
            check(Json.parseToJsonElement(exactPersistedRequestBytes.decodeToString(throwOnInvalidSequence = true)) == claimPayload)
            check(currentDak.installationId == claim.installationId && currentDak.attestationVersion > 0)
            check(currentDak.devicePublicKeySha256.matches(ARTIFACT_SHA256))
            check(envelope.keys == setOf("contract", "payload", "serverKeyId", "serverSignatureAlgorithm", "serverSignatureB64"))
            check(envelope.artifactString("contract") == "next.installed-artifact-acceptance.v1")
            check(envelope.artifactString("serverSignatureAlgorithm") == "SHA256withECDSA")
            val payload = envelope["payload"] as? JsonObject ?: error("Invalid artifact acceptance payload.")
            check(payload.keys == claimPayload.keys + setOf(
                "receiptId",
                "devicePublicKeySha256",
                "attestationVersion",
                "claimRequestDigestSha256",
                "acceptedAtMs",
                "expiresAtMs",
                "claimProvenance",
            ))
            check(verifier.verifyDetached(payload, envelope.artifactString("serverKeyId"), envelope.artifactString("serverSignatureB64")))
            check(claimPayload.all { (key, value) -> payload[key] == value })
            check(payload.artifactString("devicePublicKeySha256") == currentDak.devicePublicKeySha256)
            check(payload.artifactNumber("attestationVersion") == currentDak.attestationVersion)
            val requestDigest = artifactDigest(exactPersistedRequestBytes)
            check(payload.artifactString("claimRequestDigestSha256") == requestDigest)
            check(payload.artifactString("claimProvenance") == ARTIFACT_CLAIM_PROVENANCE)
            val acceptedAt = payload.artifactNumber("acceptedAtMs")
            val expiry = payload.artifactNumber("expiresAtMs")
            check(monotonicMs >= 0 && acceptedAt > 0 && trustedTimeMs >= acceptedAt && expiry > trustedTimeMs)
            val idPayload = buildJsonObject {
                put("claim", claimPayload)
                put("requestDigestSha256", requestDigest)
                put("acceptedAtMs", acceptedAt)
            }
            val receiptId = "artifact-acceptance-" + artifactDigest(verifier.canonicalJson(idPayload)).take(32)
            check(payload.artifactString("receiptId") == receiptId)
            return VerifiedNextArtifactAcceptance(receiptId, claim, currentDak, expiry, monotonicMs, trustedTimeMs)
        }
    }
}

/**
 * Validate the unsigned HTTP acknowledgement against an already durable, locally signed payload.
 * The transport must authenticate the pinned TLS authority and preserve current context before
 * invoking this check. This does not authorize policy application or erase historical operations.
 */
internal fun validateNextPolicyConsumedAcknowledgement(
    durableSignedPayload: JsonObject,
    acknowledgement: JsonObject,
    verifier: ManagementSignatureVerifier,
) {
    check(acknowledgement.keys == setOf("status", "challengeId", "receiptId", "receiptDigestSha256", "projectedState"))
    check(acknowledgement.artifactString("status") == "CONSUMED")
    val challengeId = durableSignedPayload.artifactString("challengeId")
    val result = durableSignedPayload.artifactString("result")
    check(challengeId.matches(ARTIFACT_ID) && result in setOf("APPLIED", "APPLIED_CLEANUP_PENDING", "BLOCKED", "FAILED"))
    val payloadDigest = artifactDigest(verifier.canonicalJson(durableSignedPayload))
    check(acknowledgement.artifactString("challengeId") == challengeId)
    check(acknowledgement.artifactString("receiptId") == "policy-receipt-" + payloadDigest.take(32))
    check(acknowledgement.artifactString("receiptDigestSha256") == payloadDigest)
    check(acknowledgement.artifactString("projectedState") == result)
}

private fun JsonObject.artifactString(key: String): String {
    val value = getValue(key) as? JsonPrimitive ?: error("Invalid artifact string field.")
    check(value.isString)
    return value.content
}

private fun JsonObject.artifactNumber(key: String): Long {
    val value = getValue(key) as? JsonPrimitive ?: error("Invalid artifact integer field.")
    check(!value.isString)
    return checkNotNull(value.content.toLongOrNull())
}

private fun artifactDigest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }

private val ARTIFACT_SHA256 = Regex("[0-9a-f]{64}")
private val ARTIFACT_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}")
private const val ARTIFACT_CLAIM_PROVENANCE =
    "CURRENT_DAK_AUTHENTICATED_DEVICE_CLAIM_MATCHED_TO_PUBLISHED_ARTIFACT_NOT_SERVER_INSTALL_INSPECTION"
