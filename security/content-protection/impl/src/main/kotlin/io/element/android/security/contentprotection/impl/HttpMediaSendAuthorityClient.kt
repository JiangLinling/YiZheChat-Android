/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.contentprotection.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.sessionstorage.api.SessionData
import io.element.android.libraries.sessionstorage.api.SessionStore
import io.element.android.security.contentprotection.api.DeviceRuntimeBindingProvider
import io.element.android.security.contentprotection.api.MediaSendAuthorityClient
import io.element.android.security.contentprotection.api.MediaSendAuthorityConfiguration
import io.element.android.security.contentprotection.api.MediaSendAuthorityConfigurationProvider
import io.element.android.security.contentprotection.api.MediaSendAuthorityResponse
import io.element.android.security.contentprotection.api.MediaSendBindRequest
import io.element.android.security.contentprotection.api.MediaSendChallengeTarget
import io.element.android.security.contentprotection.api.MediaSendTrustedClock
import io.element.android.security.contentprotection.api.OfflineLicenseClock
import io.element.android.security.securestorage.api.DeviceTrustService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CertificatePinner
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class HttpMediaSendAuthorityClient(
    private val configurationProvider: MediaSendAuthorityConfigurationProvider,
    private val sessionStore: SessionStore,
    private val deviceTrustService: DeviceTrustService,
    private val runtimeBindingProvider: DeviceRuntimeBindingProvider,
    private val wallClock: OfflineLicenseClock,
    private val trustedClock: MediaSendTrustedClock,
) : MediaSendAuthorityClient {
    override suspend fun issue(operationId: String, requestId: String): Result<MediaSendAuthorityResponse> = runCatching {
        requireIds(operationId, requestId)
        val body = "{\"contract\":\"next.segmented-media-send-lease-request.v1\",\"operationId\":${operationId.quote()},\"requestId\":${requestId.quote()}}"
        executeTarget(MediaSendChallengeTarget("POST", ISSUE_PATH, operationId, requestId), body.encodeToByteArray())
    }

    override suspend fun bind(request: MediaSendBindRequest): Result<MediaSendAuthorityResponse> = runCatching {
        requireIds(request.operationId, request.requestId, request.nonce, request.transactionId)
        require(request.bindingFingerprintSha256.matches(SHA256) && request.eventContentDigestSha256.matches(SHA256))
        val path = bindPath(request.operationId, request.requestId)
        val body = buildString {
            append("{\"bindingFingerprintSha256\":${request.bindingFingerprintSha256.quote()}")
            append(",\"contract\":\"next.segmented-media-bind-send.v1\"")
            append(",\"eventContentDigestSha256\":${request.eventContentDigestSha256.quote()}")
            append(",\"nonce\":${request.nonce.quote()}")
            append(",\"operationId\":${request.operationId.quote()}")
            append(",\"requestId\":${request.requestId.quote()}")
            append(",\"transactionId\":${request.transactionId.quote()}}")
        }.encodeToByteArray()
        executeTarget(MediaSendChallengeTarget("POST", path, request.operationId, request.requestId), body)
    }

    override suspend fun recover(operationId: String, requestId: String): Result<MediaSendAuthorityResponse> = runCatching {
        requireIds(operationId, requestId)
        executeTarget(MediaSendChallengeTarget("GET", recoveryPath(operationId, requestId), operationId, requestId), ByteArray(0))
    }

    override suspend fun fetchIssuerRegistry(): Result<MediaSendAuthorityResponse> = runCatching {
        val configuration = requireNotNull(configurationProvider.configuration())
        val session = currentSession()
        val trust = deviceTrustService.current().also { require(it.remotelyTrusted && it.allowsLocalVaultAccess) }
        val started = trustedClock.requestStarted()
        val raw = execute(
            configuration, session, "GET", REGISTRY_PATH, ByteArray(0),
            genericProofHeaders("GET", REGISTRY_PATH, ByteArray(0), trust.installationId),
        )
        val issuedAt = extractPositiveTime(raw, "issuedAtMs")
        trustedClock.acceptServerAnchor(started, issuedAt).getOrThrow()
        MediaSendAuthorityResponse(raw, issuedAt, started.elapsedRealtimeMillis, wallClock.elapsedRealtimeMillis(), started.bootCount)
    }

    private suspend fun executeTarget(target: MediaSendChallengeTarget, body: ByteArray): MediaSendAuthorityResponse {
        require(target.method == "POST" || (target.method == "GET" && body.isEmpty()))
        requireCanonicalPath(target.path)
        val configuration = requireNotNull(configurationProvider.configuration())
        val session = currentSession()
        val trust = deviceTrustService.current().also { require(it.remotelyTrusted && it.allowsLocalVaultAccess) }
        val sample = trustedClock.requestStarted()
        require(sample.bootCount == runtimeBindingProvider.bootCount())
        val bootId = "boot-${sample.bootCount}"
        val challengeBody = buildString {
            append("{\"contract\":\"next.media-send-challenge-request.v1\"")
            append(",\"purpose\":\"PREPARED_SEGMENTED_MEDIA_EVENT_SEND\"")
            append(",\"targetMethod\":${target.method.quote()}")
            append(",\"targetPath\":${target.path.quote()}")
            append(",\"operationId\":${target.operationId.quote()}")
            append(",\"requestId\":${target.requestId.quote()}")
            append(",\"bootId\":${bootId.quote()}}")
        }.encodeToByteArray()
        val challengeRaw = execute(
            configuration, session, "POST", CHALLENGE_PATH, challengeBody,
            genericProofHeaders("POST", CHALLENGE_PATH, challengeBody, trust.installationId),
        )
        val challenge = parseChallenge(challengeRaw)
        val trustedNow = trustedClock.acceptServerAnchor(sample, challenge.issuedAtMillis).getOrThrow()
        require(trustedNow.upperBoundMillis < challenge.expiresAtMillis)
        val timestampInterval = trustedClock.currentTimeMillis().getOrThrow()
        require(timestampInterval.upperBoundMillis < challenge.expiresAtMillis)
        val timestamp = timestampInterval.midpointMillis
        val nonce = randomId()
        val statement = sevenLineStatement(target.method, target.path, timestamp, nonce, body, bootId, challenge.challengeId)
        val signed = deviceTrustService.signControlStatement(statement)
        require(signed.installationId == trust.installationId && signed.attestationVersion == trust.attestationVersion)
        val headers = mapOf(
            INSTALLATION to signed.installationId,
            TIMESTAMP to timestamp.toString(),
            NONCE to nonce,
            SIGNATURE to Base64.getEncoder().encodeToString(signed.signature),
            BOOT_ID to bootId,
            CHALLENGE_ID to challenge.challengeId,
        )
        val responseStarted = wallClock.elapsedRealtimeMillis()
        val raw = execute(configuration, session, target.method, target.path, body, headers)
        val responseElapsed = wallClock.elapsedRealtimeMillis()
        val issuedAt = extractPositiveTime(raw, "issuedAtMs", "serverNowMs")
        return MediaSendAuthorityResponse(raw, issuedAt, responseStarted, responseElapsed, sample.bootCount)
    }

    private suspend fun genericProofHeaders(method: String, path: String, body: ByteArray, installationId: String): Map<String, String> {
        val timestamp = wallClock.wallClockEpochMillis().also { require(it > 0) }
        val nonce = randomId()
        val statement = "$method\n$path\n$timestamp\n$nonce\n${sha256Hex(body)}".encodeToByteArray()
        val signed = deviceTrustService.signControlStatement(statement)
        require(signed.installationId == installationId)
        return mapOf(
            INSTALLATION to installationId,
            TIMESTAMP to timestamp.toString(),
            NONCE to nonce,
            SIGNATURE to Base64.getEncoder().encodeToString(signed.signature),
        )
    }

    private suspend fun execute(
        configuration: MediaSendAuthorityConfiguration,
        session: SessionData,
        method: String,
        path: String,
        body: ByteArray,
        proofHeaders: Map<String, String>,
    ): ByteArray = withContext(Dispatchers.IO) {
        requireCanonicalPath(path)
        require(proofHeaders.keys.size == proofHeaders.keys.map(String::lowercase).toSet().size)
        val base = configuration.baseUrl.toHttpUrl()
        require(base.isHttps && base.query == null && base.fragment == null)
        val url = base.newBuilder().encodedPath(path).build()
        val client = OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false)
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).writeTimeout(15, TimeUnit.SECONDS)
            .certificatePinner(CertificatePinner.Builder().apply {
                configuration.certificatePinsSha256.forEach { add(base.host, it) }
            }.build()).build()
        val requestBody = if (method == "POST") body.toRequestBody(JSON_MEDIA) else null
        val request = Request.Builder().url(url).method(method, requestBody)
            .header("Authorization", "Bearer ${session.accessToken}")
            .apply { proofHeaders.forEach { (name, value) -> requireProofValue(value); header(name, value) } }
            .build()
        client.newCall(request).await().use { response ->
            require(response.code in 200..299)
            require(response.header("Content-Encoding") == null)
            val responseBody = requireNotNull(response.body)
            val length = responseBody.contentLength()
            require(length in -1..MAX_RESPONSE_BYTES.toLong())
            val bytes = responseBody.source().readByteArray(MAX_RESPONSE_BYTES.toLong() + 1)
            require(bytes.size in 1..MAX_RESPONSE_BYTES)
            bytes
        }
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response) else response.close()
            }
        })
    }

    private suspend fun currentSession(): SessionData = requireNotNull(sessionStore.getLatestSession()).also {
        require(it.isTokenValid && it.userId.isNotBlank() && it.deviceId.isNotBlank() && it.accessToken.isNotBlank())
    }

    private data class Challenge(val challengeId: String, val issuedAtMillis: Long, val expiresAtMillis: Long)
    private fun parseChallenge(bytes: ByteArray): Challenge {
        val value = JSON.parseToJsonElement(bytes.decodeToString()).jsonObject
        require(value.keys == setOf("contract", "challengeId", "bootEpoch", "issuedAtMs", "expiresAtMs"))
        require(value.getValue("contract").jsonPrimitive.content == "next.media-send-challenge-response.v1")
        require(requireNotNull(value.getValue("bootEpoch").jsonPrimitive.longOrNull) >= 0)
        val issued = requireNotNull(value.getValue("issuedAtMs").jsonPrimitive.longOrNull)
        val expires = requireNotNull(value.getValue("expiresAtMs").jsonPrimitive.longOrNull)
        require(issued > 0 && expires > issued && expires - issued <= 30_000)
        return Challenge(value.getValue("challengeId").jsonPrimitive.content.also { require(it.matches(INTERNAL_ID)) }, issued, expires)
    }

    private fun extractPositiveTime(bytes: ByteArray, vararg names: String): Long {
        val root = JSON.parseToJsonElement(bytes.decodeToString()).jsonObject
        val candidates = sequenceOf(root) + listOfNotNull(root["payload"]?.jsonObject).asSequence()
        return candidates.flatMap { objectValue -> names.asSequence().mapNotNull { objectValue[it]?.jsonPrimitive?.longOrNull } }
            .firstOrNull { it > 0 } ?: error("signed server time missing")
    }

    private fun sevenLineStatement(method: String, path: String, timestamp: Long, nonce: String, body: ByteArray, bootId: String, challengeId: String) =
        "$method\n$path\n$timestamp\n$nonce\n${sha256Hex(body)}\n$bootId\n$challengeId".encodeToByteArray()

    private fun requireCanonicalPath(path: String) {
        require(path.startsWith('/') && '?' !in path && '%' !in path && '#' !in path && path.all { it.code in 0x21..0x7e })
        require(path == ISSUE_PATH || path == CHALLENGE_PATH || path == REGISTRY_PATH || BIND_PATH.matches(path) || RECOVERY_PATH.matches(path))
    }
    private fun requireIds(vararg ids: String) = ids.forEach { require(it.matches(INTERNAL_ID)) }
    private fun requireProofValue(value: String) = require(value.isNotEmpty() && value.all { it.code in 0x21..0x7e })
    private fun randomId(): String = ByteArray(24).also(secureRandom::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    private companion object {
        val JSON = Json { isLenient = false; ignoreUnknownKeys = false }
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        val secureRandom = SecureRandom()
        const val ISSUE_PATH = "/next/v1/media/send-leases:issue"
        const val CHALLENGE_PATH = "/next/v1/media/send-challenges:issue"
        const val REGISTRY_PATH = "/next/v1/media/send-issuer-keys"
        const val INSTALLATION = "X-Yizhe-Installation"
        const val TIMESTAMP = "X-Yizhe-Timestamp"
        const val NONCE = "X-Yizhe-Nonce"
        const val SIGNATURE = "X-Yizhe-Signature"
        const val BOOT_ID = "X-Yizhe-Boot-Id"
        const val CHALLENGE_ID = "X-Yizhe-Challenge-Id"
        const val MAX_RESPONSE_BYTES = 1_048_576
        val INTERNAL_ID = Regex("^[A-Za-z0-9._:-]{1,160}$")
        val SHA256 = Regex("^[0-9a-f]{64}$")
        val BIND_PATH = Regex("^/next/v1/media/send-leases/[A-Za-z0-9._:-]{1,160}/[A-Za-z0-9._:-]{1,160}:bind-send$")
        val RECOVERY_PATH = Regex("^/next/v1/media/send-leases/[A-Za-z0-9._:-]{1,160}/[A-Za-z0-9._:-]{1,160}$")
        fun bindPath(operationId: String, requestId: String) = "/next/v1/media/send-leases/$operationId/$requestId:bind-send"
        fun recoveryPath(operationId: String, requestId: String) = "/next/v1/media/send-leases/$operationId/$requestId"
    }
}

private fun String.quote(): String = buildString(length + 2) {
    append('"'); this@quote.forEach { c -> when (c) {
        '"' -> append("\\\""); '\\' -> append("\\\\"); '\b' -> append("\\b"); '\u000C' -> append("\\f")
        '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
        else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
    } }; append('"')
}
