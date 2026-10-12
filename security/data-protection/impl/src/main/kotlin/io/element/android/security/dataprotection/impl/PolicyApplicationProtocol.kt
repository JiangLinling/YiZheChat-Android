/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.dataprotection.impl

import io.element.android.security.dataprotection.api.DataProtectionPolicyV1
import io.element.android.security.dataprotection.api.HardwareTrustRequirement
import io.element.android.security.dataprotection.api.NotificationExposure
import io.element.android.security.dataprotection.api.OfflineAccessMode
import io.element.android.security.dataprotection.api.ManagementPolicyApplicationState
import io.element.android.security.dataprotection.api.PolicyApplicationEvidence
import io.element.android.security.dataprotection.api.ScreenCapturePolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest

internal enum class LocalPolicyApplicationState {
    PENDING_EFFECTIVE,
    PENDING_APPLY,
    BLOCKED_VERSION,
    FAILED,
    APPLIED_CLEANUP_PENDING,
    APPLIED,
}

internal data class VerifiedPolicyPublication(
    val publication: PolicyPublicationWire,
    val localState: LocalPolicyApplicationState,
    val refusedCapabilities: List<String> = emptyList(),
    val errorCode: String? = null,
) {
    val mayStagePolicy: Boolean
        get() = localState == LocalPolicyApplicationState.PENDING_EFFECTIVE ||
            localState == LocalPolicyApplicationState.PENDING_APPLY
}

/**
 * Validates the exact control-plane full-sync transport and then verifies both signed statements.
 */
internal fun verifyPolicyRevisionEnvelope(
    envelope: SignedPolicyRevisionEnvelopeWire,
    serverTimeEpochMillis: Long,
    highestAcceptedVersion: Long,
    localSecurityEpoch: Int,
    supportedCapabilities: Set<String>,
    verifier: ManagementSignatureVerifier,
): VerifiedPolicyPublication {
    check(envelope.contract == PUBLICATION_CONTRACT)
    check(envelope.signatureSchemaVersion == PUBLICATION_SIGNATURE_SCHEMA_VERSION)
    check(envelope.domain == GLOBAL_POLICY_DOMAIN)
    check(envelope.version > 0)
    check(envelope.payloadDigest.matches(SHA256_PATTERN))
    check(envelope.serverFenceEvidenceDigest.matches(SHA256_PATTERN))

    val publication = STRICT_JSON.decodeFromJsonElement(
        PolicyPublicationWire.serializer(),
        envelope.publicationCanonical,
    )
    check(envelope.revisionId == publication.revisionId)
    check(envelope.operationId == publication.operationId)
    check(envelope.domain == publication.domain)
    check(envelope.version == publication.version)
    check(envelope.payloadDigest == publication.payloadDigest)
    check(envelope.effectiveAt == publication.effectiveAt)
    check(envelope.expiresAt == publication.expiresAt)

    val legacyPolicy = STRICT_JSON.decodeFromJsonElement(PolicyWire.serializer(), envelope.legacyPolicy)
    check(legacyPolicy.version == envelope.version)
    check(legacyPolicy.issuedAtEpochMillis == publication.issuedAt)
    check(legacyPolicy.effectiveAtEpochMillis == envelope.effectiveAt)
    check(legacyPolicy.expiresAtEpochMillis == envelope.expiresAt)
    val projectedPolicy = JsonObject(envelope.legacyPolicy.filterKeys { it !in LEGACY_PUBLICATION_METADATA })
    check(MessageDigest.isEqual(verifier.canonicalJson(projectedPolicy), verifier.canonicalJson(envelope.policy))) {
        "R05 policy content does not match its legacy signed projection."
    }

    return verifyPolicyPublication(
        policyEnvelope = SignedJsonEnvelopeWire(
            policy = envelope.legacyPolicy,
            keyId = envelope.keyId,
            signatureBase64 = envelope.legacyPolicySignatureB64,
        ),
        publicationEnvelope = SignedPolicyPublicationWire(
            publication = envelope.publicationCanonical,
            keyId = envelope.keyId,
            signatureBase64 = envelope.publicationSignatureB64,
        ),
        serverTimeEpochMillis = serverTimeEpochMillis,
        highestAcceptedVersion = highestAcceptedVersion,
        localSecurityEpoch = localSecurityEpoch,
        supportedCapabilities = supportedCapabilities,
        verifier = verifier,
    )
}

/**
 * Verifies the two-signature R05 publication pair without performing local side effects.
 *
 * A successful return is not application evidence. In particular, [LocalPolicyApplicationState.PENDING_APPLY]
 * only means that the signed policy is eligible to enter the real app/coordinator apply path.
 */
internal fun verifyPolicyPublication(
    policyEnvelope: SignedJsonEnvelopeWire,
    publicationEnvelope: SignedPolicyPublicationWire,
    serverTimeEpochMillis: Long,
    highestAcceptedVersion: Long,
    localSecurityEpoch: Int,
    supportedCapabilities: Set<String>,
    verifier: ManagementSignatureVerifier,
): VerifiedPolicyPublication {
    check(serverTimeEpochMillis > 0) { "Policy publication requires trusted server time." }
    check(localSecurityEpoch >= 0) { "Local security epoch is invalid." }
    check(policyEnvelope.policy != null && policyEnvelope.hasOnlyPolicyPayload()) {
        "Policy publication compatibility envelope must contain only a policy payload."
    }
    check(policyEnvelope.keyId == publicationEnvelope.keyId) {
        "Policy publication signatures must use the same approved key."
    }
    check(verifier.verify(policyEnvelope)) { "Management policy signature verification failed." }
    check(
        verifier.verifyDetached(
            publicationEnvelope.publication,
            publicationEnvelope.keyId,
            publicationEnvelope.signatureBase64,
        )
    ) { "CP-POLICY publication signature verification failed." }

    val publication = STRICT_JSON.decodeFromJsonElement(
        PolicyPublicationWire.serializer(),
        publicationEnvelope.publication,
    )
    check(publication.contract == PUBLICATION_CONTRACT)
    check(publication.signatureSchemaVersion == PUBLICATION_SIGNATURE_SCHEMA_VERSION)
    check(publication.domain == GLOBAL_POLICY_DOMAIN && publication.scope == GLOBAL_SCOPE)
    check(publication.operationId.isBoundedIdentifier() && publication.revisionId.isBoundedIdentifier())
    check(publication.version > 0)
    check(publication.version >= highestAcceptedVersion) { "Management policy publication rolled back." }
    check(publication.issuedAt > 0 && publication.issuedAt <= serverTimeEpochMillis + MAX_CLOCK_SKEW_MILLIS) {
        "Policy publication issue time is invalid."
    }
    check(publication.effectiveAt > 0)
    check(publication.expiresAt == null || publication.expiresAt > publication.effectiveAt)
    check(publication.minSecurityEpoch >= 0)
    check(publication.payloadDigest.matches(SHA256_PATTERN))
    check(publication.targetSnapshotDigest.matches(SHA256_PATTERN))
    check(publication.requiredCapabilities.isSortedUniqueBoundedIdentifiers(maximumLength = MAX_CAPABILITY_LENGTH)) {
        "Policy required capabilities must be sorted, unique, and bounded."
    }

    val policyPayload = checkNotNull(policyEnvelope.policy)
    val policy = STRICT_JSON.decodeFromJsonElement(PolicyWire.serializer(), policyPayload)
    check(policy.schemaVersion == LEGACY_POLICY_SCHEMA_VERSION)
    check(policy.version == publication.version)
    check(policy.issuedAtEpochMillis == publication.issuedAt)
    check(policy.effectiveAtEpochMillis == publication.effectiveAt)
    check(policy.expiresAtEpochMillis == publication.expiresAt)
    // Decode every baseline field into the authoritative local model even when the publication declares
    // no additional capabilities. A sparse requiredCapabilities list never turns into permission to skip
    // GLOBAL_POLICY validation and later claim application.
    DataProtectionPolicyV1(
        policyId = policy.policyId,
        version = policy.version,
        issuedAtEpochMillis = policy.issuedAtEpochMillis,
        effectiveAtEpochMillis = policy.effectiveAtEpochMillis,
        expiresAtEpochMillis = policy.expiresAtEpochMillis,
        contentRetentionMillis = policy.contentRetentionMillis,
        maximumOfflineMillis = policy.maximumOfflineMillis,
        offlineAccessMode = OfflineAccessMode.valueOf(policy.offlineAccessMode),
        notificationExposure = NotificationExposure.valueOf(policy.notificationExposure),
        hardwareTrustRequirement = HardwareTrustRequirement.valueOf(policy.hardwareTrustRequirement),
        allowContentExport = policy.allowContentExport,
        allowClipboard = policy.allowClipboard,
        allowExternalShare = policy.allowExternalShare,
        allowGroupReadReceipts = policy.allowGroupReadReceipts,
        screenCapturePolicy = ScreenCapturePolicy.valueOf(policy.screenCapturePolicy),
        requireContentEnvelope = policy.requireContentEnvelope,
        allowRtc = policy.allowRtc,
        schemaVersion = policy.schemaVersion,
    )
    val contentPayload = JsonObject(policyPayload.filterKeys { it !in LEGACY_PUBLICATION_METADATA })
    val contentDigest = MessageDigest.getInstance("SHA-256").digest(verifier.canonicalJson(contentPayload)).toHex()
    check(MessageDigest.isEqual(contentDigest.encodeToByteArray(), publication.payloadDigest.encodeToByteArray())) {
        "Policy publication payload digest does not match the compatibility policy."
    }

    if (publication.expiresAt?.let { it <= serverTimeEpochMillis } == true) {
        return VerifiedPolicyPublication(
            publication = publication,
            localState = LocalPolicyApplicationState.FAILED,
            refusedCapabilities = publication.requiredCapabilities,
            errorCode = ERROR_POLICY_EXPIRED,
        )
    }
    if (publication.minSecurityEpoch > localSecurityEpoch) {
        return VerifiedPolicyPublication(
            publication = publication,
            localState = LocalPolicyApplicationState.BLOCKED_VERSION,
            refusedCapabilities = publication.requiredCapabilities,
            errorCode = ERROR_SECURITY_EPOCH_UNSUPPORTED,
        )
    }
    val unsupported = publication.requiredCapabilities.filterNot(supportedCapabilities::contains)
    if (unsupported.isNotEmpty()) {
        return VerifiedPolicyPublication(
            publication = publication,
            localState = LocalPolicyApplicationState.BLOCKED_VERSION,
            refusedCapabilities = publication.requiredCapabilities,
            errorCode = ERROR_REQUIRED_CAPABILITY_UNSUPPORTED,
        )
    }
    return VerifiedPolicyPublication(
        publication = publication,
        localState = if (publication.effectiveAt > serverTimeEpochMillis) {
            LocalPolicyApplicationState.PENDING_EFFECTIVE
        } else {
            LocalPolicyApplicationState.PENDING_APPLY
        },
    )
}

internal fun validatePolicyApplicationReceipt(
    receipt: PolicyApplicationReceiptWire,
    application: PersistedPolicyApplicationWire,
    allowCompletedCleanupEvidence: Boolean = false,
): LocalPolicyApplicationState {
    val publication = STRICT_JSON.decodeFromJsonElement(
        PolicyPublicationWire.serializer(),
        application.publication.publication,
    )
    check(receipt.operationId == publication.operationId)
    check(receipt.policyDomain == publication.domain)
    check(receipt.version == publication.version)
    check(receipt.digest == publication.payloadDigest)
    check(receipt.installationId.isBoundedIdentifier())
    check(receipt.bootId.isBoundedIdentifier())
    check(receipt.nonce.length in 16..128)
    check(receipt.deviceSignature.isNotBlank() && receipt.deviceSignature.length <= 4_096)
    check(receipt.enforcedCapabilities.isSortedUniqueBoundedIdentifiers(maximumLength = MAX_CAPABILITY_LENGTH))
    check(receipt.refusedCapabilities.isSortedUniqueBoundedIdentifiers(maximumLength = MAX_CAPABILITY_LENGTH))
    check(receipt.cleanupOperationIds.isSortedUniqueBoundedIdentifiers(maximumItems = MAX_CLEANUP_OPERATIONS))
    val required = publication.requiredCapabilities.toSet()
    val enforced = receipt.enforcedCapabilities.toSet()
    val refused = receipt.refusedCapabilities.toSet()
    val pendingCleanupIds = application.cleanupOperations
        .filter { it.state == "PENDING" }
        .map { it.operationId }
    val stagedCleanupIds = application.cleanupOperations.map { it.operationId }.sorted()
    check(enforced.intersect(refused).isEmpty())
    check(enforced + refused == required) { "Receipt must account for every required capability exactly once." }
    return when (receipt.result) {
        LocalPolicyApplicationState.APPLIED.name -> {
            check(application.applyHandshakeAtEpochMillis != null)
            check(receipt.appliedAt != null && receipt.appliedAt > 0)
            check(refused.isEmpty() && enforced == required && receipt.cleanupOperationIds.isEmpty())
            check(pendingCleanupIds.isEmpty()) { "APPLIED cannot hide pending local cleanup operations." }
            LocalPolicyApplicationState.APPLIED
        }
        LocalPolicyApplicationState.APPLIED_CLEANUP_PENDING.name -> {
            check(application.applyHandshakeAtEpochMillis != null)
            check(receipt.appliedAt != null && receipt.appliedAt > 0)
            check(refused.isEmpty() && enforced == required && receipt.cleanupOperationIds.isNotEmpty())
            check(pendingCleanupIds.isNotEmpty() || allowCompletedCleanupEvidence) {
                "Cleanup-pending receipt requires unfinished local cleanup."
            }
            check(receipt.cleanupOperationIds == stagedCleanupIds && stagedCleanupIds.isNotEmpty()) {
                "Cleanup-pending receipt must bind every durable cleanup operation identity."
            }
            LocalPolicyApplicationState.APPLIED_CLEANUP_PENDING
        }
        LocalPolicyApplicationState.BLOCKED_VERSION.name -> {
            check(receipt.appliedAt == null && receipt.cleanupOperationIds.isEmpty())
            LocalPolicyApplicationState.BLOCKED_VERSION
        }
        LocalPolicyApplicationState.FAILED.name -> {
            check(receipt.appliedAt == null && receipt.cleanupOperationIds.isEmpty())
            LocalPolicyApplicationState.FAILED
        }
        else -> error("Unsupported device policy application result.")
    }
}

internal fun canonicalPolicyApplicationReceiptPayload(
    receipt: PolicyApplicationReceiptWire,
    verifier: ManagementSignatureVerifier,
): ByteArray = verifier.canonicalJson(
    buildJsonObject {
        put("operationId", receipt.operationId)
        put("installationId", receipt.installationId)
        put("bootId", receipt.bootId)
        put("policyDomain", receipt.policyDomain)
        put("version", receipt.version)
        put("digest", receipt.digest)
        put("appliedAt", receipt.appliedAt?.let(::JsonPrimitive) ?: JsonNull)
        put("enforcedCapabilities", JsonArray(receipt.enforcedCapabilities.map(::JsonPrimitive)))
        put("refusedCapabilities", JsonArray(receipt.refusedCapabilities.map(::JsonPrimitive)))
        put("cleanupOperationIds", JsonArray(receipt.cleanupOperationIds.map(::JsonPrimitive)))
        put("result", receipt.result)
        put("nonce", receipt.nonce)
    }
)

internal fun policyApplicationReceiptEvidenceDigest(
    receipt: PolicyApplicationReceiptWire,
    verifier: ManagementSignatureVerifier,
): String = MessageDigest.getInstance("SHA-256")
    .digest(
        canonicalPolicyApplicationReceiptPayload(receipt, verifier) +
            byteArrayOf(0) +
            receipt.deviceSignature.encodeToByteArray()
    )
    .toHex()

internal fun validatePolicyApplicationReceiptAcknowledgement(
    receipt: PolicyApplicationReceiptWire,
    application: PersistedPolicyApplicationWire,
    acknowledgement: PolicyApplicationReceiptAcknowledgementWire,
    verifier: ManagementSignatureVerifier,
): PolicyApplicationReceiptAcknowledgementWire {
    val publication = STRICT_JSON.decodeFromJsonElement(
        PolicyPublicationWire.serializer(),
        application.publication.publication,
    )
    check(acknowledgement.revisionId == publication.revisionId)
    check(acknowledgement.domain == GLOBAL_POLICY_DOMAIN && acknowledgement.domain == publication.domain)
    check(acknowledgement.domain == receipt.policyDomain)
    check(acknowledgement.version == publication.version && acknowledgement.version == receipt.version)
    check(acknowledgement.installationId == receipt.installationId)
    check(acknowledgement.state == receipt.result)
    check(acknowledgement.bootId == receipt.bootId)
    check(acknowledgement.digest == publication.payloadDigest && acknowledgement.digest == receipt.digest)
    check(acknowledgement.appliedAt == receipt.appliedAt)
    check(acknowledgement.enforcedCapabilities == receipt.enforcedCapabilities)
    check(acknowledgement.refusedCapabilities == receipt.refusedCapabilities)
    check(acknowledgement.cleanupOperationIds == receipt.cleanupOperationIds)
    check(acknowledgement.lastEvidenceDigest.matches(SHA256_PATTERN))
    val expectedEvidenceDigest = policyApplicationReceiptEvidenceDigest(receipt, verifier)
    check(
        MessageDigest.isEqual(
            acknowledgement.lastEvidenceDigest.encodeToByteArray(),
            expectedEvidenceDigest.encodeToByteArray(),
        )
    )
    return acknowledgement
}

internal fun policyApplicationReceiptMatchesEvidence(
    receipt: PolicyApplicationReceiptWire,
    application: PersistedPolicyApplicationWire,
    evidence: PolicyApplicationEvidence,
): Boolean {
    val publication = STRICT_JSON.decodeFromJsonElement(
        PolicyPublicationWire.serializer(),
        application.publication.publication,
    )
    val expectedCleanupIds = if (evidence.result == ManagementPolicyApplicationState.APPLIED_CLEANUP_PENDING) {
        application.cleanupOperations.map { it.operationId }.sorted()
    } else {
        emptyList()
    }
    return receipt.operationId == publication.operationId &&
        receipt.policyDomain == publication.domain &&
        receipt.version == publication.version &&
        receipt.version == evidence.version &&
        receipt.digest == publication.payloadDigest &&
        (receipt.appliedAt != null) == (evidence.appliedAtEpochMillis != null) &&
        receipt.enforcedCapabilities == evidence.enforcedCapabilities.sorted() &&
        receipt.refusedCapabilities == evidence.refusedCapabilities.sorted() &&
        receipt.cleanupOperationIds == expectedCleanupIds &&
        receipt.result == evidence.result.name
}

internal fun buildUnsignedPolicyApplicationReceipt(
    application: PersistedPolicyApplicationWire,
    evidence: PolicyApplicationEvidence,
    installationId: String,
    bootCount: Long,
    nonce: String,
): PolicyApplicationReceiptWire {
    val publication = STRICT_JSON.decodeFromJsonElement(
        PolicyPublicationWire.serializer(),
        application.publication.publication,
    )
    check(evidence.version == publication.version) { "Policy evidence targets another version." }
    check(bootCount >= 0)
    if (evidence.result in setOf(
            ManagementPolicyApplicationState.APPLIED,
            ManagementPolicyApplicationState.APPLIED_CLEANUP_PENDING,
        )
    ) {
        check(application.applyHandshakeAtEpochMillis != null) {
            "Applied policy receipt requires an exact-version coordinator apply handshake."
        }
    }
    val cleanupOperationIds = if (
        evidence.result == ManagementPolicyApplicationState.APPLIED_CLEANUP_PENDING
    ) {
        application.cleanupOperations
            .map { it.operationId }
            .sorted()
    } else {
        emptyList()
    }
    return PolicyApplicationReceiptWire(
        operationId = publication.operationId,
        installationId = installationId,
        bootId = "android-boot-$bootCount",
        policyDomain = publication.domain,
        version = publication.version,
        digest = publication.payloadDigest,
        appliedAt = evidence.appliedAtEpochMillis,
        enforcedCapabilities = evidence.enforcedCapabilities.sorted(),
        refusedCapabilities = evidence.refusedCapabilities.sorted(),
        cleanupOperationIds = cleanupOperationIds,
        result = evidence.result.name,
        nonce = nonce,
        deviceSignature = "",
    )
}

private fun SignedJsonEnvelopeWire.hasOnlyPolicyPayload(): Boolean =
    command == null && decision == null && license == null && release == null && ticket == null &&
        receipt == null && grant == null && milestone == null && tombstones == null

private fun List<String>.isSortedUniqueBoundedIdentifiers(
    maximumItems: Int = MAX_CAPABILITIES,
    maximumLength: Int = MAX_IDENTIFIER_LENGTH,
): Boolean = size <= maximumItems && this == distinct().sorted() && all { value ->
    value.isNotBlank() && value.length <= maximumLength
}

private fun String.isBoundedIdentifier(): Boolean = isNotBlank() && length <= MAX_IDENTIFIER_LENGTH

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

private val STRICT_JSON = Json {
    ignoreUnknownKeys = false
    explicitNulls = true
    encodeDefaults = true
}

private const val PUBLICATION_CONTRACT = "CP-POLICY"
private const val PUBLICATION_SIGNATURE_SCHEMA_VERSION = 1
private const val LEGACY_POLICY_SCHEMA_VERSION = 2
private const val GLOBAL_POLICY_DOMAIN = "GLOBAL_POLICY"
private const val GLOBAL_SCOPE = "GLOBAL"
private const val MAX_CLOCK_SKEW_MILLIS = 5 * 60 * 1_000L
private const val MAX_IDENTIFIER_LENGTH = 256
private const val MAX_CAPABILITY_LENGTH = 128
private const val MAX_CAPABILITIES = 128
private const val MAX_CLEANUP_OPERATIONS = 256
private const val ERROR_POLICY_EXPIRED = "POLICY_PUBLICATION_EXPIRED"
private const val ERROR_SECURITY_EPOCH_UNSUPPORTED = "POLICY_SECURITY_EPOCH_UNSUPPORTED"
private const val ERROR_REQUIRED_CAPABILITY_UNSUPPORTED = "POLICY_REQUIRED_CAPABILITY_UNSUPPORTED"
private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
private val LEGACY_PUBLICATION_METADATA = setOf("version", "issued_at_epoch_millis", "schema_version")
