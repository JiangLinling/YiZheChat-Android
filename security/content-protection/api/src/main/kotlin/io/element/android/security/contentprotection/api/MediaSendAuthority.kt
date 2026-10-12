/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.security.contentprotection.api

data class MediaSendAuthorityConfiguration(
    val baseUrl: String,
    val certificatePinsSha256: Set<String>,
    val releaseTrustRootPublicKeyDer: ByteArray,
    val maximumClockUncertaintyMillis: Long,
)

fun interface MediaSendAuthorityConfigurationProvider {
    fun configuration(): MediaSendAuthorityConfiguration?
}
data class MediaSendChallengeTarget(
    val method: String,
    val path: String,
    val operationId: String,
    val requestId: String,
)

data class MediaSendBindRequest(
    val operationId: String,
    val requestId: String,
    val nonce: String,
    val bindingFingerprintSha256: String,
    val transactionId: String,
    val eventContentDigestSha256: String,
)

data class MediaSendAuthorityResponse(
    val rawBody: ByteArray,
    val serverIssuedAtMillis: Long,
    val requestStartedElapsedRealtimeMillis: Long,
    val responseReceivedElapsedRealtimeMillis: Long,
    val bootCount: Long,
)

/** Closed, purpose-specific Contract A transport. It intentionally exposes no arbitrary request method. */
interface MediaSendAuthorityClient {
    suspend fun issue(operationId: String, requestId: String): Result<MediaSendAuthorityResponse>
    suspend fun bind(request: MediaSendBindRequest): Result<MediaSendAuthorityResponse>
    suspend fun recover(operationId: String, requestId: String): Result<MediaSendAuthorityResponse>
    suspend fun fetchIssuerRegistry(): Result<MediaSendAuthorityResponse>
}

enum class MediaSendIssuerKeyStatus { ACTIVE, RETAINED, REVOKED }

data class MediaSendIssuerKey(
    val keyId: String,
    val algorithm: String,
    val publicKeyPem: String,
    val notBeforeMillis: Long,
    val notAfterMillis: Long,
    val status: MediaSendIssuerKeyStatus,
)

data class VerifiedMediaSendIssuerRegistry(
    val revision: Long,
    val issuedAtMillis: Long,
    val expiresAtMillis: Long,
    val signedEnvelopeSha256: String,
    val keys: List<MediaSendIssuerKey>,
)

interface MediaSendIssuerRegistryRepository {
    suspend fun installSignedRegistry(rawEnvelope: ByteArray, trustedTime: MediaSendTrustedTime): Result<VerifiedMediaSendIssuerRegistry>
    suspend fun currentVerifiedRegistry(trustedTime: MediaSendTrustedTime): Result<VerifiedMediaSendIssuerRegistry>
}

interface MediaSendTrustedClock {
    fun requestStarted(): MediaSendClockSample
    fun acceptServerAnchor(sample: MediaSendClockSample, serverIssuedAtMillis: Long): Result<MediaSendTrustedTime>
    fun currentTimeMillis(): Result<MediaSendTrustedTime>
}

/** Closed trusted-time interval. Security boundaries must reject when this interval crosses them. */
data class MediaSendTrustedTime(
    val lowerBoundMillis: Long,
    val upperBoundMillis: Long,
) {
    init {
        require(lowerBoundMillis > 0 && upperBoundMillis >= lowerBoundMillis)
    }

    val midpointMillis: Long
        get() = Math.addExact(lowerBoundMillis, (upperBoundMillis - lowerBoundMillis) / 2)
}

data class MediaSendClockSample(
    val bootCount: Long,
    val elapsedRealtimeMillis: Long,
)

/** Values read from live production owners. No authority generation is derived locally. */
data class LiveMediaSendIdentity(
    val accountIdHash: String,
    val installationId: String,
    val matrixUserId: String,
    val matrixDeviceId: String,
    val attestationVersion: Long,
    val roomId: String,
    val roomIdSha256: String,
    val contentContractId: String?,
    val profileRevision: Long?,
    val contractSha256: String?,
)

interface LiveMediaSendIdentityProvider {
    suspend fun current(
        matrixUserId: String,
        roomId: String,
        expectedAccountIdHash: String,
    ): Result<LiveMediaSendIdentity>
}
