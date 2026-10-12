/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.mediaupload.impl

import io.element.android.security.contentprotection.api.HistoricalMediaSendPrepared
import io.element.android.security.contentprotection.api.LiveMediaSendIdentity
import io.element.android.security.contentprotection.api.MediaSendIssuerKeyStatus
import io.element.android.security.contentprotection.api.MediaSendJournalRecord
import io.element.android.security.contentprotection.api.MediaSendJournalState
import io.element.android.security.contentprotection.api.MediaSendTrustedTime
import io.element.android.security.contentprotection.api.VerifiedMediaSendIssuerRegistry
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

internal data class NextMediaSendIssuerKey(
    val keyId: String,
    val algorithm: String,
    val publicKeyPem: String,
    val notBeforeMs: Long,
    val notAfterMs: Long,
    val status: Status,
) {
    enum class Status { ACTIVE, RETAINED, REVOKED }
}

/** The CP release-trust owner must verify and anti-rollback this registry before constructing it. */
internal data class VerifiedNextMediaSendIssuerRegistry(
    val revision: Long,
    val issuedAtMs: Long,
    val expiresAtMs: Long,
    val keys: List<NextMediaSendIssuerKey>,
)

internal data class NextMediaPolicyRevision(
    val domain: String,
    val revisionId: String,
    val version: Long,
    val digestSha256: String,
)

internal data class NextMediaAuthorityRevision(
    val deviceTrustGeneration: Long,
    val roomEpoch: Long,
    val kmsFenceGeneration: Long,
    val resourceRootGeneration: Long,
)

internal data class NextMatrixEncryptedFileKeyDescriptor(
    val kty: String,
    val keyOperations: List<String>,
    val algorithm: String,
    val keyBase64Url: String,
    val extractable: Boolean,
)

internal data class NextMatrixEncryptedFileDescriptor(
    val url: String,
    val key: NextMatrixEncryptedFileKeyDescriptor,
    val ivBase64: String,
    val sha256Base64: String,
    val version: String,
)

internal data class NextSegmentedMediaManifestDescriptor(
    val contract: String,
    val role: String,
    val resourceId: String,
    val contentId: String,
    val index: Long,
    val startUs: Long,
    val durationUs: Long,
    val plainLength: Long,
    val innerProtectedLength: Long,
    val innerProtectedSha256: String,
    val innerContextDigestSha256: String,
    val outerCipherLength: Long,
    val outerCipherSha256Hex: String,
    val matrixEncryptedFile: NextMatrixEncryptedFileDescriptor,
    val mimeType: String,
    val independentRandomAccess: Boolean,
)

/** Full immutable PREPARED row persisted before any send lease is requested. */
internal data class PersistedNextSegmentedMediaSendIntent(
    val operationId: String,
    val requestId: String,
    val transactionId: String,
    val roomId: String,
    val assetId: String,
    val mediaVersion: Long,
    val resourceSetDigestSha256: String,
    val manifestDigestSha256: String,
    val pagesDigestSha256: String,
    val accountIdHash: String,
    val contentContractId: String,
    val eventContentDigestSha256: String,
)

internal data class AuthenticatedNextSegmentedMediaPreparedSnapshot(
    val journalRecord: MediaSendJournalRecord,
    val intent: PersistedNextSegmentedMediaSendIntent,
) {
    val journalRevision: Long get() = journalRecord.revision
    val integrityReceiptSha256: String get() = journalRecord.recordDigestSha256
}

internal fun MediaSendJournalRecord.toNextSnapshot(): AuthenticatedNextSegmentedMediaPreparedSnapshot {
    val value = prepared
    return AuthenticatedNextSegmentedMediaPreparedSnapshot(
        journalRecord = this,
        intent = PersistedNextSegmentedMediaSendIntent(
            operationId = value.operationId,
            requestId = value.requestId,
            transactionId = value.transactionId,
            roomId = value.roomId,
            assetId = value.assetId,
            mediaVersion = value.mediaVersion,
            resourceSetDigestSha256 = value.resourceSetDigestSha256,
            manifestDigestSha256 = value.manifestDigestSha256,
            pagesDigestSha256 = value.pagesDigestSha256,
            accountIdHash = value.accountIdHash,
            contentContractId = value.contentContractId,
            eventContentDigestSha256 = value.eventContentDigestSha256,
        ),
    )
}

internal fun VerifiedMediaSendIssuerRegistry.toNextRegistry(): VerifiedNextMediaSendIssuerRegistry =
    VerifiedNextMediaSendIssuerRegistry(
        revision = revision,
        issuedAtMs = issuedAtMillis,
        expiresAtMs = expiresAtMillis,
        keys = keys.map { key ->
            NextMediaSendIssuerKey(
                keyId = key.keyId,
                algorithm = key.algorithm,
                publicKeyPem = key.publicKeyPem,
                notBeforeMs = key.notBeforeMillis,
                notAfterMs = key.notAfterMillis,
                status = when (key.status) {
                    MediaSendIssuerKeyStatus.ACTIVE -> NextMediaSendIssuerKey.Status.ACTIVE
                    MediaSendIssuerKeyStatus.RETAINED -> NextMediaSendIssuerKey.Status.RETAINED
                    MediaSendIssuerKeyStatus.REVOKED -> NextMediaSendIssuerKey.Status.REVOKED
                },
            )
        },
    )

internal data class NextSegmentedMediaBinding(
    val operationId: String,
    val assetId: String,
    val mediaVersion: Long,
    val resourceSetDigestSha256: String,
    val manifestDigestSha256: String,
    val pagesDigestSha256: String,
    val accountIdHash: String,
    val installationId: String,
    val matrixUserId: String,
    val matrixDeviceId: String,
    val deviceTrustGeneration: Long,
    val roomId: String,
    val roomIdHash: String,
    val roomEpoch: Long,
    val contentContractId: String,
    val policyRevision: NextMediaPolicyRevision,
    val authorityRevision: NextMediaAuthorityRevision,
    val requestId: String,
    val nonce: String,
    val effectiveAtMs: Long,
    val expiresAtMs: Long,
    val canonicalJson: String,
)

internal data class VerifiedNextMediaSendAllow(
    val binding: NextSegmentedMediaBinding,
    val bindingFingerprintSha256: String,
    val issuedAtMs: Long,
    val serverNowMs: Long,
    val decisionRevision: Long,
    val keyId: String,
)

internal data class VerifiedNextMediaSendBound(
    val operationId: String,
    val requestId: String,
    val bindingFingerprintSha256: String,
    val transactionId: String,
    val eventContentDigestSha256: String,
    val issuedAtMs: Long,
    val expiresAtMs: Long,
    val decisionRevision: Long,
    val keyId: String,
)

internal data class ValidatedContractBEvent(
    val canonicalContentJson: String,
    val eventContentDigestSha256: String,
    val manifestDescriptor: NextSegmentedMediaManifestDescriptor,
)

internal data class RevalidatedNextSegmentedMediaQueueAuthority(
    val allow: VerifiedNextMediaSendAllow,
    val bound: VerifiedNextMediaSendBound,
    val event: ValidatedContractBEvent,
)

internal class NextSegmentedMediaContractVerifier {
    fun requirePreparedRequestMatches(
        snapshot: AuthenticatedNextSegmentedMediaPreparedSnapshot,
        operationId: String,
        requestId: String,
        transactionId: String,
        roomId: String,
        eventContentDigestSha256: String,
    ) {
        requireAuthenticatedSnapshotShape(snapshot)
        require(snapshot.intent.operationId == operationId)
        require(snapshot.intent.requestId == requestId)
        require(snapshot.intent.transactionId == transactionId)
        require(snapshot.intent.roomId == roomId)
        require(snapshot.intent.eventContentDigestSha256 == eventContentDigestSha256)
    }

    fun verifyAllow(
        responseBytes: ByteArray,
        operationId: String,
        requestId: String,
        roomId: String,
        registry: VerifiedNextMediaSendIssuerRegistry,
        trustedTime: MediaSendTrustedTime,
    ): VerifiedNextMediaSendAllow {
        validateRegistry(registry, trustedTime)
        val envelope = StrictJson.parse(responseBytes).objectValue("ALLOW envelope")
        envelope.requireExactKeys("contract", "payload", "signature")
        require(envelope.string("contract") == SEND_LEASE_RESPONSE)
        val payload = envelope.objectMember("payload")
        payload.requireExactKeys(
            "contract", "issuer", "purpose", "decision", "binding", "bindingFingerprintSha256",
            "issuedAtMs", "serverNowMs", "decisionRevision", "retryAfterMs",
        )
        require(payload.string("contract") == SEND_LEASE_PAYLOAD)
        require(payload.string("issuer") == NEXT_MEDIA_CONTROL)
        require(payload.string("purpose") == SEND_PURPOSE)
        require(payload.string("decision") == "ALLOW")
        require(payload.long("retryAfterMs") == 0L)

        val bindingObject = payload.objectMember("binding")
        val binding = parseBinding(bindingObject)
        require(binding.operationId == operationId)
        require(binding.requestId == requestId)
        require(binding.roomId == roomId)
        val fingerprint = sha256Hex(binding.canonicalJson.encodeToByteArray())
        require(payload.string("bindingFingerprintSha256") == fingerprint)

        val issuedAtMs = payload.positiveLong("issuedAtMs")
        val serverNowMs = payload.nonNegativeLong("serverNowMs")
        val decisionRevision = payload.positiveLong("decisionRevision")
        require(issuedAtMs <= serverNowMs)
        require(binding.effectiveAtMs <= serverNowMs && serverNowMs < binding.expiresAtMs)
        require(binding.effectiveAtMs <= trustedTime.lowerBoundMillis)
        require(trustedTime.upperBoundMillis < binding.expiresAtMs)

        val signature = parseSignature(envelope.objectMember("signature"))
        verifySignature(
            registry = registry,
            keyId = signature.keyId,
            algorithm = signature.algorithm,
            signatureBase64 = signature.signatureBase64,
            issuedAtMs = issuedAtMs,
            trustedTime = trustedTime,
            domain = SEND_LEASE_DOMAIN,
            canonicalPayload = payload.canonical().encodeToByteArray(),
        )
        return VerifiedNextMediaSendAllow(
            binding = binding,
            bindingFingerprintSha256 = fingerprint,
            issuedAtMs = issuedAtMs,
            serverNowMs = serverNowMs,
            decisionRevision = decisionRevision,
            keyId = signature.keyId,
        )
    }

    /**
     * Binds every client-owned A v6 field to authenticated local state. The only binding members
     * without a local expected value are the server nonce and signed validity timestamps, which are
     * independently signature- and trusted-time-validated by [verifyAllow].
     */
    fun requireAllowMatchesPrepared(
        allow: VerifiedNextMediaSendAllow,
        snapshot: AuthenticatedNextSegmentedMediaPreparedSnapshot,
        liveIdentity: LiveMediaSendIdentity,
        requestedRoomId: String,
    ) {
        requireAuthenticatedSnapshotShape(snapshot)
        val intent = snapshot.intent
        val historical = snapshot.journalRecord.prepared
        val binding = allow.binding

        require(intent.accountIdHash == liveIdentity.accountIdHash)
        require(intent.roomId == liveIdentity.roomId && liveIdentity.roomId == requestedRoomId)
        require(binding.operationId == intent.operationId)
        require(binding.requestId == intent.requestId)
        require(binding.roomId == intent.roomId)
        require(binding.assetId == intent.assetId)
        require(binding.mediaVersion == intent.mediaVersion)
        require(binding.resourceSetDigestSha256 == intent.resourceSetDigestSha256)
        require(binding.manifestDigestSha256 == intent.manifestDigestSha256)
        require(binding.pagesDigestSha256 == intent.pagesDigestSha256)
        require(binding.accountIdHash == intent.accountIdHash)
        require(binding.contentContractId == intent.contentContractId)

        require(binding.installationId == liveIdentity.installationId && binding.installationId == historical.installationId)
        require(binding.matrixUserId == liveIdentity.matrixUserId && binding.matrixUserId == historical.matrixUserId)
        require(binding.matrixDeviceId == liveIdentity.matrixDeviceId && binding.matrixDeviceId == historical.matrixDeviceId)
        require(binding.deviceTrustGeneration == historical.deviceTrustGeneration)
        require(binding.roomEpoch == historical.roomEpoch)
        require(binding.contentContractId == liveIdentity.contentContractId)
        require(binding.policyRevision == NextMediaPolicyRevision(
            historical.policyDomain, historical.policyRevisionId, historical.policyVersion, historical.policyDigestSha256,
        ))
        require(binding.authorityRevision == NextMediaAuthorityRevision(
            historical.deviceTrustGeneration, historical.roomEpoch,
            historical.kmsFenceGeneration, historical.resourceRootGeneration,
        ))
    }

    fun requireEventMatchesPrepared(
        event: ValidatedContractBEvent,
        snapshot: AuthenticatedNextSegmentedMediaPreparedSnapshot,
    ) {
        requireAuthenticatedSnapshotShape(snapshot)
        require(event.eventContentDigestSha256 == snapshot.intent.eventContentDigestSha256)
    }

    fun requireSnapshotStillAuthoritative(
        initial: AuthenticatedNextSegmentedMediaPreparedSnapshot,
        current: AuthenticatedNextSegmentedMediaPreparedSnapshot,
        initialLiveIdentity: LiveMediaSendIdentity,
        currentLiveIdentity: LiveMediaSendIdentity,
        allow: VerifiedNextMediaSendAllow,
        event: ValidatedContractBEvent,
        requestedRoomId: String,
    ) {
        requireAuthenticatedSnapshotShape(initial)
        requireAuthenticatedSnapshotShape(current)
        require(current.journalRevision >= initial.journalRevision)
        require(current.intent == initial.intent)
        require(current.journalRecord.prepared == initial.journalRecord.prepared)
        require(initialLiveIdentity == currentLiveIdentity)
        requireAllowMatchesPrepared(allow, current, currentLiveIdentity, requestedRoomId)
        requireEventMatchesPrepared(event, current)
    }

    /**
     * The single queue-adjacent authority predicate. Callers must acquire [registry], [trustedTime]
     * and [current] immediately before invoking this method, then perform no suspend/blocking call
     * before enqueueing [RevalidatedNextSegmentedMediaQueueAuthority.event].
     */
    fun revalidateQueueAuthority(
        allowResponseBytes: ByteArray,
        boundResponseBytes: ByteArray,
        initial: AuthenticatedNextSegmentedMediaPreparedSnapshot,
        current: AuthenticatedNextSegmentedMediaPreparedSnapshot,
        initialLiveIdentity: LiveMediaSendIdentity,
        currentLiveIdentity: LiveMediaSendIdentity,
        operationId: String,
        requestId: String,
        transactionId: String,
        roomId: String,
        canonicalEventContentJson: String,
        registry: VerifiedNextMediaSendIssuerRegistry,
        trustedTime: MediaSendTrustedTime,
        minimumRegistryRevision: Long,
        minimumTrustedLowerBoundMillis: Long,
    ): RevalidatedNextSegmentedMediaQueueAuthority {
        require(registry.revision >= minimumRegistryRevision)
        require(trustedTime.lowerBoundMillis >= minimumTrustedLowerBoundMillis)
        requirePreparedRequestMatches(
            snapshot = current,
            operationId = operationId,
            requestId = requestId,
            transactionId = transactionId,
            roomId = roomId,
            eventContentDigestSha256 = sha256Hex(canonicalEventContentJson.encodeToByteArray()),
        )
        val allow = verifyAllow(
            responseBytes = allowResponseBytes,
            operationId = operationId,
            requestId = requestId,
            roomId = roomId,
            registry = registry,
            trustedTime = trustedTime,
        )
        require(current.journalRecord.state == MediaSendJournalState.MATRIX_IN_FLIGHT)
        require(current.journalRecord.allowEnvelope?.contentEquals(allowResponseBytes) == true)
        require(current.journalRecord.sendBoundEnvelope?.contentEquals(boundResponseBytes) == true)
        requireAllowMatchesPrepared(allow, current, currentLiveIdentity, roomId)
        val event = validateEventContent(canonicalEventContentJson, allow)
        requireSnapshotStillAuthoritative(initial, current, initialLiveIdentity, currentLiveIdentity, allow, event, roomId)
        val bound = verifySendBound(
            responseBytes = boundResponseBytes,
            allow = allow,
            transactionId = transactionId,
            eventContentDigestSha256 = event.eventContentDigestSha256,
            registry = registry,
            trustedTime = trustedTime,
        )
        return RevalidatedNextSegmentedMediaQueueAuthority(allow, bound, event)
    }

    fun validateEventContent(
        canonicalContentJson: String,
        allow: VerifiedNextMediaSendAllow,
    ): ValidatedContractBEvent {
        val bytes = canonicalContentJson.encodeToByteArray()
        val content = StrictJson.parse(bytes).objectValue("Contract B content")
        content.requireExactKeys(
            "msgtype",
            "body",
            "org.yizhechat.media_resource_root",
            "org.yizhechat.segmented_media_binding",
            "org.yizhechat.segmented_media_manifest",
        )
        require(content.canonical() == canonicalContentJson)
        require(content.string("msgtype") == "org.yizhechat.video.segmented.v1")
        require(content.string("body") == "Protected video")

        val root = content.objectMember("org.yizhechat.media_resource_root")
        root.requireExactKeys(
            "contract", "operationId", "assetId", "mediaVersion", "resourceSetDigestSha256", "manifestDigestSha256",
        )
        require(root.string("contract") == "next.media-resource-root-binding.v1")
        require(root.string("operationId") == allow.binding.operationId)
        require(root.string("assetId") == allow.binding.assetId)
        require(root.long("mediaVersion") == allow.binding.mediaVersion)
        require(root.string("resourceSetDigestSha256") == allow.binding.resourceSetDigestSha256)
        require(root.string("manifestDigestSha256") == allow.binding.manifestDigestSha256)

        val binding = content.objectMember("org.yizhechat.segmented_media_binding")
        binding.requireExactKeys("contract", "bindingFingerprintSha256", "requestId")
        require(binding.string("contract") == "next.segmented-media-event-binding.v1")
        require(binding.string("bindingFingerprintSha256") == allow.bindingFingerprintSha256)
        require(binding.string("requestId") == allow.binding.requestId)

        val manifest = content.objectMember("org.yizhechat.segmented_media_manifest")
        manifest.requireExactKeys("contract", "resource")
        require(manifest.string("contract") == "next.segmented-media-manifest-reference.v1")
        val manifestDescriptor = validateManifestDescriptor(
            manifest.objectMember("resource"),
            allow.binding.manifestDigestSha256,
        )
        return ValidatedContractBEvent(canonicalContentJson, sha256Hex(bytes), manifestDescriptor)
    }

    fun verifySendBound(
        responseBytes: ByteArray,
        allow: VerifiedNextMediaSendAllow,
        transactionId: String,
        eventContentDigestSha256: String,
        registry: VerifiedNextMediaSendIssuerRegistry,
        trustedTime: MediaSendTrustedTime,
    ): VerifiedNextMediaSendBound {
        validateRegistry(registry, trustedTime)
        val envelope = StrictJson.parse(responseBytes).objectValue("SEND_BOUND envelope")
        envelope.requireExactKeys("contract", "payload", "signature")
        require(envelope.string("contract") == SEND_BOUND_ENVELOPE)
        val payload = envelope.objectMember("payload")
        payload.requireExactKeys(
            "contract", "issuer", "purpose", "operationId", "requestId", "nonceDigestSha256",
            "bindingFingerprintSha256", "transactionId", "eventContentDigestSha256", "issuedAtMs", "expiresAtMs",
            "decisionRevision",
        )
        require(payload.string("contract") == SEND_BOUND_PAYLOAD)
        require(payload.string("issuer") == NEXT_MEDIA_CONTROL)
        require(payload.string("purpose") == SEND_PURPOSE)
        require(payload.string("operationId") == allow.binding.operationId)
        require(payload.string("requestId") == allow.binding.requestId)
        require(payload.string("nonceDigestSha256") == sha256Hex(allow.binding.nonce.encodeToByteArray()))
        require(payload.string("bindingFingerprintSha256") == allow.bindingFingerprintSha256)
        require(payload.string("transactionId") == transactionId)
        require(payload.string("eventContentDigestSha256") == eventContentDigestSha256)
        val issuedAtMs = payload.positiveLong("issuedAtMs")
        val expiresAtMs = payload.positiveLong("expiresAtMs")
        val decisionRevision = payload.positiveLong("decisionRevision")
        require(issuedAtMs >= allow.issuedAtMs)
        require(expiresAtMs <= allow.binding.expiresAtMs)
        require(decisionRevision >= allow.decisionRevision)
        require(issuedAtMs <= trustedTime.lowerBoundMillis)
        require(trustedTime.upperBoundMillis < expiresAtMs)

        val signature = parseSignature(envelope.objectMember("signature"))
        verifySignature(
            registry = registry,
            keyId = signature.keyId,
            algorithm = signature.algorithm,
            signatureBase64 = signature.signatureBase64,
            issuedAtMs = issuedAtMs,
            trustedTime = trustedTime,
            domain = SEND_BOUND_DOMAIN,
            canonicalPayload = payload.canonical().encodeToByteArray(),
        )
        return VerifiedNextMediaSendBound(
            operationId = allow.binding.operationId,
            requestId = allow.binding.requestId,
            bindingFingerprintSha256 = allow.bindingFingerprintSha256,
            transactionId = transactionId,
            eventContentDigestSha256 = eventContentDigestSha256,
            issuedAtMs = issuedAtMs,
            expiresAtMs = expiresAtMs,
            decisionRevision = decisionRevision,
            keyId = signature.keyId,
        )
    }

    private fun parseBinding(value: JsonObject): NextSegmentedMediaBinding {
        value.requireExactKeys(
            "contract", "operationId", "assetId", "mediaVersion", "resourceSetDigestSha256",
            "manifestDigestSha256", "pagesDigestSha256", "accountIdHash", "installationId", "matrixUserId",
            "matrixDeviceId", "deviceTrustGeneration", "roomId", "roomIdHash", "roomEpoch", "contentContractId",
            "policyRevision", "authorityRevision", "requestId", "nonce", "effectiveAtMs", "expiresAtMs",
        )
        require(value.string("contract") == "next.segmented-media-binding.v1")
        val operationId = value.internalId("operationId")
        val assetId = value.internalId("assetId")
        require(value.long("mediaVersion") == 1L)
        val resourceSetDigest = value.sha256("resourceSetDigestSha256")
        val manifestDigest = value.sha256("manifestDigestSha256")
        val pagesDigest = value.sha256("pagesDigestSha256")
        val accountIdHash = value.sha256("accountIdHash")
        val installationId = value.internalId("installationId")
        val matrixUserId = value.string("matrixUserId").also { require(it.isMatrixId('@')) }
        val matrixDeviceId = value.string("matrixDeviceId").also { require(it.isMatrixDeviceId()) }
        val deviceTrustGeneration = value.positiveLong("deviceTrustGeneration")
        val roomId = value.string("roomId").also { require(it.isMatrixId('!')) }
        val roomIdHash = value.sha256("roomIdHash")
        require(roomIdHash == sha256Hex(roomId.encodeToByteArray()))
        val roomEpoch = value.positiveLong("roomEpoch")
        val contentContractId = value.internalId("contentContractId")
        val policy = value.objectMember("policyRevision")
        policy.requireExactKeys("domain", "revisionId", "version", "digestSha256")
        val policyRevision = NextMediaPolicyRevision(
            domain = policy.string("domain").also { require(it.isNotEmpty()) },
            revisionId = policy.internalId("revisionId"),
            version = policy.positiveLong("version"),
            digestSha256 = policy.sha256("digestSha256"),
        )
        val authority = value.objectMember("authorityRevision")
        authority.requireExactKeys("deviceTrustGeneration", "roomEpoch", "kmsFenceGeneration", "resourceRootGeneration")
        val authorityRevision = NextMediaAuthorityRevision(
            deviceTrustGeneration = authority.positiveLong("deviceTrustGeneration"),
            roomEpoch = authority.positiveLong("roomEpoch"),
            kmsFenceGeneration = authority.positiveLong("kmsFenceGeneration"),
            resourceRootGeneration = authority.positiveLong("resourceRootGeneration"),
        )
        require(authorityRevision.deviceTrustGeneration == deviceTrustGeneration)
        require(authorityRevision.roomEpoch == roomEpoch)
        val requestId = value.internalId("requestId")
        val nonce = value.internalId("nonce")
        val effectiveAtMs = value.nonNegativeLong("effectiveAtMs")
        val expiresAtMs = value.positiveLong("expiresAtMs")
        require(effectiveAtMs < expiresAtMs)
        return NextSegmentedMediaBinding(
            operationId, assetId, 1L, resourceSetDigest, manifestDigest, pagesDigest, accountIdHash,
            installationId, matrixUserId, matrixDeviceId, deviceTrustGeneration, roomId, roomIdHash,
            roomEpoch, contentContractId, policyRevision, authorityRevision, requestId, nonce,
            effectiveAtMs, expiresAtMs, value.canonical(),
        )
    }

    private fun validateManifestDescriptor(
        resource: JsonObject,
        expectedManifestDigest: String,
    ): NextSegmentedMediaManifestDescriptor {
        resource.requireExactKeys(
            "contract", "role", "resourceId", "contentId", "index", "startUs", "durationUs", "plainLength",
            "innerProtectedLength", "innerProtectedSha256", "innerContextDigestSha256", "outerCipherLength",
            "outerCipherSha256Hex", "matrixEncryptedFile", "mimeType", "independentRandomAccess",
        )
        val contract = resource.string("contract").also { require(it == "next.segmented-media-resource.v1") }
        val role = resource.string("role").also { require(it == "MANIFEST") }
        val resourceId = resource.internalId("resourceId")
        val contentId = resource.internalId("contentId")
        val index = resource.long("index").also { require(it == 0L) }
        val startUs = resource.long("startUs")
        val durationUs = resource.long("durationUs")
        require(startUs == 0L && durationUs == 0L)
        val plainLength = resource.positiveLong("plainLength").also { require(it <= 1_048_576L) }
        val innerProtectedLength = resource.positiveLong("innerProtectedLength").also { require(it <= 1_310_720L) }
        val innerProtectedSha256 = resource.sha256("innerProtectedSha256")
        val innerContextDigestSha256 = resource.sha256("innerContextDigestSha256")
        val outerCipherLength = resource.positiveLong("outerCipherLength").also { require(it <= 1_310_720L) }
        val outerHex = resource.sha256("outerCipherSha256Hex")
        val mimeType = resource.string("mimeType").also {
            require(it == "application/vnd.yizhe.segmented-media-manifest+json")
        }
        val independentRandomAccess = resource.boolean("independentRandomAccess").also { require(it) }
        val matrixEncryptedFile = validateMatrixEncryptedFile(resource.objectMember("matrixEncryptedFile"), outerHex)
        // The manifest core digest is carried by the immutable root marker; the descriptor authenticates the envelope.
        require(expectedManifestDigest.matches(SHA256_HEX))
        return NextSegmentedMediaManifestDescriptor(
            contract = contract,
            role = role,
            resourceId = resourceId,
            contentId = contentId,
            index = index,
            startUs = startUs,
            durationUs = durationUs,
            plainLength = plainLength,
            innerProtectedLength = innerProtectedLength,
            innerProtectedSha256 = innerProtectedSha256,
            innerContextDigestSha256 = innerContextDigestSha256,
            outerCipherLength = outerCipherLength,
            outerCipherSha256Hex = outerHex,
            matrixEncryptedFile = matrixEncryptedFile,
            mimeType = mimeType,
            independentRandomAccess = independentRandomAccess,
        )
    }

    private fun validateMatrixEncryptedFile(
        value: JsonObject,
        expectedOuterHex: String,
    ): NextMatrixEncryptedFileDescriptor {
        value.requireExactKeys("url", "key", "iv", "hashes", "v")
        val version = value.string("v").also { require(it == "v2") }
        val url = value.string("url").also { require(it.isStrictMxc()) }
        val iv = value.string("iv").also { require(it.decodeCanonicalBase64(padded = false).size == 16) }
        val hashes = value.objectMember("hashes")
        hashes.requireExactKeys("sha256")
        val sha256Base64 = hashes.string("sha256")
        val hashBytes = sha256Base64.decodeCanonicalBase64(padded = false)
        require(hashBytes.size == 32 && hashBytes.toHex() == expectedOuterHex)
        val key = value.objectMember("key")
        key.requireExactKeys("kty", "key_ops", "alg", "k", "ext")
        val kty = key.string("kty")
        val algorithm = key.string("alg")
        val extractable = key.boolean("ext")
        require(kty == "oct" && algorithm == "A256CTR" && extractable)
        val operations = key.array("key_ops").values.map { it.stringValue("key operation") }
        require(operations == listOf("encrypt", "decrypt"))
        val keyBase64Url = key.string("k").also { require(it.decodeCanonicalBase64Url().size == 32) }
        return NextMatrixEncryptedFileDescriptor(
            url = url,
            key = NextMatrixEncryptedFileKeyDescriptor(kty, operations, algorithm, keyBase64Url, extractable),
            ivBase64 = iv,
            sha256Base64 = sha256Base64,
            version = version,
        )
    }

    private fun requireAuthenticatedSnapshotShape(snapshot: AuthenticatedNextSegmentedMediaPreparedSnapshot) {
        require(snapshot.journalRevision > 0L)
        require(snapshot.integrityReceiptSha256.matches(SHA256_HEX))
        val intent = snapshot.intent
        val historical = snapshot.journalRecord.prepared
        require(listOf(intent.operationId, intent.requestId, intent.transactionId, intent.assetId, intent.contentContractId)
            .all(String::isInternalId))
        require(intent.roomId.isMatrixId('!'))
        require(intent.mediaVersion == 1L)
        require(listOf(
            intent.resourceSetDigestSha256,
            intent.manifestDigestSha256,
            intent.pagesDigestSha256,
            intent.accountIdHash,
            intent.eventContentDigestSha256,
            historical.accountIdHash,
            historical.policyDigestSha256,
        ).all { it.matches(SHA256_HEX) })
        require(historical.installationId.isInternalId())
        require(historical.matrixUserId.isMatrixId('@'))
        require(historical.matrixDeviceId.isMatrixDeviceId())
        require(historical.roomId.isMatrixId('!'))
        require(historical.deviceTrustGeneration > 0L && historical.roomEpoch > 0L)
        require(historical.policyDomain.isNotEmpty())
        require(historical.policyRevisionId.isInternalId() && historical.policyVersion > 0L)
        require(historical.kmsFenceGeneration > 0L)
        require(historical.resourceRootGeneration > 0L)
    }

    private fun validateRegistry(registry: VerifiedNextMediaSendIssuerRegistry, trustedTime: MediaSendTrustedTime) {
        require(registry.revision > 0L)
        require(registry.issuedAtMs >= 0L && registry.issuedAtMs < registry.expiresAtMs)
        require(registry.issuedAtMs <= trustedTime.lowerBoundMillis)
        require(trustedTime.upperBoundMillis < registry.expiresAtMs)
        require(registry.keys.isNotEmpty() && registry.keys.size <= 32)
        require(registry.keys.map { it.keyId }.distinct().size == registry.keys.size)
        require(registry.keys.count { it.status == NextMediaSendIssuerKey.Status.ACTIVE } == 1)
        registry.keys.forEach { key ->
            require(key.keyId.matches(KEY_ID))
            require(key.algorithm == SIGNATURE_ALGORITHM)
            require(key.notBeforeMs >= 0L && key.notBeforeMs < key.notAfterMs)
            require(publicKeyId(parsePublicKey(key.publicKeyPem)) == key.keyId)
        }
    }

    private fun verifySignature(
        registry: VerifiedNextMediaSendIssuerRegistry,
        keyId: String,
        algorithm: String,
        signatureBase64: String,
        issuedAtMs: Long,
        trustedTime: MediaSendTrustedTime,
        domain: String,
        canonicalPayload: ByteArray,
    ) {
        require(algorithm == SIGNATURE_ALGORITHM)
        val key = requireNotNull(registry.keys.singleOrNull { it.keyId == keyId })
        require(key.status != NextMediaSendIssuerKey.Status.REVOKED)
        require(issuedAtMs in key.notBeforeMs until key.notAfterMs)
        require(key.notBeforeMs <= trustedTime.lowerBoundMillis)
        require(trustedTime.upperBoundMillis < key.notAfterMs)
        val signatureBytes = signatureBase64.decodeCanonicalBase64(padded = true)
        val verifier = Signature.getInstance(SIGNATURE_ALGORITHM)
        verifier.initVerify(parsePublicKey(key.publicKeyPem))
        verifier.update(domain.encodeToByteArray())
        verifier.update('\n'.code.toByte())
        verifier.update(canonicalPayload)
        require(verifier.verify(signatureBytes))
    }

    private data class WireSignature(val keyId: String, val algorithm: String, val signatureBase64: String)

    private fun parseSignature(value: JsonObject): WireSignature {
        value.requireExactKeys("keyId", "algorithm", "signatureBase64")
        return WireSignature(
            keyId = value.string("keyId").also { require(it.matches(KEY_ID)) },
            algorithm = value.string("algorithm"),
            signatureBase64 = value.string("signatureBase64"),
        )
    }

    private fun parsePublicKey(pem: String): PublicKey {
        val canonicalPem = pem.trim()
        require(canonicalPem.startsWith(PUBLIC_KEY_BEGIN) && canonicalPem.endsWith(PUBLIC_KEY_END))
        val encoded = canonicalPem.removePrefix(PUBLIC_KEY_BEGIN).removeSuffix(PUBLIC_KEY_END)
            .filterNot(Char::isWhitespace)
            .decodeCanonicalBase64(padded = true)
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded))
        require(key is ECPublicKey && key.params.matchesP256())
        return key
    }

    private fun publicKeyId(key: PublicKey): String = sha256Hex(key.encoded).take(16)

    companion object {
        private const val SEND_LEASE_RESPONSE = "next.segmented-media-send-lease-response.v1"
        private const val SEND_LEASE_PAYLOAD = "next.segmented-media-send-lease-payload.v1"
        private const val SEND_BOUND_ENVELOPE = "next.segmented-media-send-bound-envelope.v1"
        private const val SEND_BOUND_PAYLOAD = "next.segmented-media-send-bound.v1"
        private const val NEXT_MEDIA_CONTROL = "NEXT_MEDIA_CONTROL"
        private const val SEND_PURPOSE = "PREPARED_SEGMENTED_MEDIA_EVENT_SEND"
        private const val SEND_LEASE_DOMAIN = "YZC-NEXT-WP08-SEND-LEASE-v1"
        private const val SEND_BOUND_DOMAIN = "YZC-NEXT-WP08-SEND-BOUND-v1"
        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
        private const val PUBLIC_KEY_BEGIN = "-----BEGIN PUBLIC KEY-----"
        private const val PUBLIC_KEY_END = "-----END PUBLIC KEY-----"
        private val KEY_ID = Regex("^[0-9a-f]{16}$")
    }
}

private sealed interface StrictJson {
    fun canonical(): String

    data class ObjectValue(val members: Map<String, StrictJson>) : StrictJson {
        override fun canonical(): String = members.keys.sorted().joinToString(prefix = "{", postfix = "}", separator = ",") { key ->
            "${key.jsonString()}:${members.getValue(key).canonical()}"
        }
    }

    data class ArrayValue(val values: List<StrictJson>) : StrictJson {
        override fun canonical(): String = values.joinToString(prefix = "[", postfix = "]", separator = ",") { it.canonical() }
    }

    data class StringValue(val value: String) : StrictJson {
        override fun canonical(): String = value.jsonString()
    }

    data class IntegerValue(val value: Long) : StrictJson {
        override fun canonical(): String = value.toString()
    }

    data class BooleanValue(val value: Boolean) : StrictJson {
        override fun canonical(): String = value.toString()
    }

    data object NullValue : StrictJson {
        override fun canonical(): String = "null"
    }

    fun objectValue(label: String): ObjectValue = this as? ObjectValue ?: error("$label must be an object.")

    fun stringValue(label: String): String = (this as? StringValue)?.value ?: error("$label must be a string.")

    companion object {
        fun parse(bytes: ByteArray): StrictJson {
            require(bytes.isNotEmpty() && bytes.size <= MAX_JSON_BYTES)
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val text = decoder.decode(ByteBuffer.wrap(bytes)).toString()
            require(!text.startsWith('\uFEFF'))
            return Parser(text).parse()
        }
    }

    private class Parser(private val text: String) {
        private var index = 0

        fun parse(): StrictJson {
            skipWhitespace()
            val value = value()
            skipWhitespace()
            require(index == text.length) { "Trailing JSON bytes are forbidden." }
            return value
        }

        private fun value(): StrictJson {
            require(index < text.length)
            return when (text[index]) {
                '{' -> objectValue()
                '[' -> arrayValue()
                '"' -> StringValue(string())
                't' -> literal("true", BooleanValue(true))
                'f' -> literal("false", BooleanValue(false))
                'n' -> literal("null", NullValue)
                else -> IntegerValue(integer())
            }
        }

        private fun objectValue(): ObjectValue {
            index++
            skipWhitespace()
            val members = linkedMapOf<String, StrictJson>()
            if (take('}')) return ObjectValue(members)
            while (true) {
                require(index < text.length && text[index] == '"')
                val key = string()
                require(members[key] == null) { "Duplicate JSON key." }
                skipWhitespace()
                require(take(':'))
                skipWhitespace()
                members[key] = value()
                skipWhitespace()
                if (take('}')) return ObjectValue(members)
                require(take(','))
                skipWhitespace()
            }
        }

        private fun arrayValue(): ArrayValue {
            index++
            skipWhitespace()
            val values = mutableListOf<StrictJson>()
            if (take(']')) return ArrayValue(values)
            while (true) {
                values += value()
                skipWhitespace()
                if (take(']')) return ArrayValue(values)
                require(take(','))
                skipWhitespace()
            }
        }

        private fun string(): String {
            require(take('"'))
            val result = StringBuilder()
            while (index < text.length) {
                val character = text[index++]
                when {
                    character == '"' -> return result.toString()
                    character == '\\' -> {
                        require(index < text.length)
                        when (val escaped = text[index++]) {
                            '"', '\\', '/' -> result.append(escaped)
                            'b' -> result.append('\b')
                            'f' -> result.append('\u000C')
                            'n' -> result.append('\n')
                            'r' -> result.append('\r')
                            't' -> result.append('\t')
                            'u' -> appendUnicodeEscape(result)
                            else -> error("Invalid JSON escape.")
                        }
                    }
                    character.code < 0x20 -> error("Invalid JSON string character.")
                    character.isHighSurrogate() -> {
                        require(index < text.length && text[index].isLowSurrogate()) { "Unpaired JSON surrogate." }
                        result.append(character)
                        result.append(text[index++])
                    }
                    character.isLowSurrogate() -> error("Unpaired JSON surrogate.")
                    else -> result.append(character)
                }
            }
            error("Unterminated JSON string.")
        }

        private fun appendUnicodeEscape(result: StringBuilder) {
            val first = unicodeCodeUnit()
            when {
                first in 0xD800..0xDBFF -> {
                    require(index + 1 < text.length && text[index] == '\\' && text[index + 1] == 'u')
                    index += 2
                    val second = unicodeCodeUnit()
                    require(second in 0xDC00..0xDFFF)
                    result.append(Character.toChars(Character.toCodePoint(first.toChar(), second.toChar())))
                }
                first in 0xDC00..0xDFFF -> error("Unpaired JSON surrogate.")
                else -> result.append(first.toChar())
            }
        }

        private fun unicodeCodeUnit(): Int {
            require(index + 4 <= text.length)
            val value = text.substring(index, index + 4).toIntOrNull(16) ?: error("Invalid Unicode escape.")
            index += 4
            return value
        }

        private fun integer(): Long {
            val start = index
            if (take('-')) require(index < text.length)
            if (take('0')) {
                require(index == text.length || !text[index].isDigit())
            } else {
                require(index < text.length && text[index] in '1'..'9')
                while (index < text.length && text[index].isDigit()) index++
            }
            require(index == text.length || text[index] !in charArrayOf('.', 'e', 'E', '+'))
            return text.substring(start, index).toLong()
        }

        private fun <T : StrictJson> literal(expected: String, value: T): T {
            require(text.regionMatches(index, expected, 0, expected.length))
            index += expected.length
            return value
        }

        private fun take(expected: Char): Boolean {
            if (index >= text.length || text[index] != expected) return false
            index++
            return true
        }

        private fun skipWhitespace() {
            while (index < text.length && text[index] in charArrayOf(' ', '\t', '\r', '\n')) index++
        }
    }
}

private typealias JsonObject = StrictJson.ObjectValue
private typealias JsonArray = StrictJson.ArrayValue

private fun JsonObject.requireExactKeys(vararg expected: String) {
    require(members.keys == expected.toSet()) { "JSON object fields do not match the contract." }
}

private fun JsonObject.member(name: String): StrictJson = requireNotNull(members[name]) { "Missing $name." }
private fun JsonObject.objectMember(name: String): JsonObject = member(name).objectValue(name)
private fun JsonObject.array(name: String): JsonArray = member(name) as? JsonArray ?: error("$name must be an array.")
private fun JsonObject.string(name: String): String = member(name).stringValue(name)
private fun JsonObject.long(name: String): Long = (member(name) as? StrictJson.IntegerValue)?.value ?: error("$name must be an integer.")
private fun JsonObject.boolean(name: String): Boolean = (member(name) as? StrictJson.BooleanValue)?.value ?: error("$name must be boolean.")
private fun JsonObject.nonNegativeLong(name: String): Long = long(name).also { require(it >= 0L) }
private fun JsonObject.positiveLong(name: String): Long = long(name).also { require(it > 0L) }
private fun JsonObject.internalId(name: String): String = string(name).also { require(it.isInternalId()) }
private fun JsonObject.sha256(name: String): String = string(name).also { require(it.matches(SHA256_HEX)) }

private fun String.isInternalId(): Boolean = length in 1..160 && matches(INTERNAL_ID)
private fun String.isMatrixId(prefix: Char): Boolean {
    val separator = indexOf(':', startIndex = 1)
    return encodeToByteArray().size in 4..255 &&
        startsWith(prefix) && separator > 1 && separator < lastIndex &&
        none { it.isWhitespace() || it.code < 0x20 || it.code == 0x7F }
}

private fun ECParameterSpec.matchesP256(): Boolean {
    val parameters = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
    val expected = parameters.getParameterSpec(ECParameterSpec::class.java)
    return curve == expected.curve && generator == expected.generator && order == expected.order && cofactor == expected.cofactor
}

private fun String.isMatrixDeviceId(): Boolean =
    encodeToByteArray().size in 1..255 && none { it.isWhitespace() || it.code < 0x20 || it.code == 0x7F }

private fun String.isStrictMxc(): Boolean {
    if (encodeToByteArray().size !in 1..2048 || !startsWith("mxc://")) return false
    if (any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7F } || '?' in this || '#' in this) return false
    val remainder = removePrefix("mxc://")
    val slash = remainder.indexOf('/')
    if (slash <= 0 || slash == remainder.lastIndex) return false
    val path = remainder.substring(slash + 1)
    return "%2f" !in lowercase() && path.split('/').none { it == "." || it == ".." }
}

private fun String.decodeCanonicalBase64(padded: Boolean): ByteArray {
    require(isNotEmpty() && none(Char::isWhitespace))
    val decoded = Base64.getDecoder().decode(this)
    val encoded = if (padded) Base64.getEncoder().encodeToString(decoded) else Base64.getEncoder().withoutPadding().encodeToString(decoded)
    require(encoded == this)
    return decoded
}

private fun String.decodeCanonicalBase64Url(): ByteArray {
    require(isNotEmpty() && none(Char::isWhitespace))
    val decoded = Base64.getUrlDecoder().decode(this)
    require(Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) == this)
    return decoded
}

private fun String.jsonString(): String = buildString(length + 2) {
    append('"')
    this@jsonString.forEach { character ->
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
        }
    }
    append('"')
}

private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
private fun ByteArray.toHex(): String = joinToString(separator = "") { "%02x".format(it) }

private const val MAX_JSON_BYTES = 1_048_576
private val INTERNAL_ID = Regex("^[A-Za-z0-9._:-]{1,160}$")
private val SHA256_HEX = Regex("^[0-9a-f]{64}$")
