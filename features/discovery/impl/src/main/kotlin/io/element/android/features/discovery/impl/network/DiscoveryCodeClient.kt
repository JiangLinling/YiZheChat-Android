/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.network

import dev.zacsweers.metro.Inject
import io.element.android.features.discovery.impl.session.AndroidDiscoveryClock
import io.element.android.features.discovery.impl.session.DiscoveryAccountScope
import io.element.android.features.discovery.impl.session.DiscoveryAuthorityClient
import io.element.android.features.discovery.impl.session.DiscoveryActionTarget
import io.element.android.features.discovery.impl.session.DiscoveryCode
import io.element.android.features.discovery.impl.session.DiscoverySecret
import io.element.android.features.discovery.impl.session.sha256
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.security.securestorage.api.DeviceTrustService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

@Inject
internal class DiscoveryCodeClient(
    private val client: MatrixClient,
    private val trust: DeviceTrustService,
    private val transport: PinnedDiscoveryTransport,
    private val clock: AndroidDiscoveryClock,
) : DiscoveryAuthorityClient {
    private val random = SecureRandom()

    override suspend fun issue(scope: DiscoveryAccountScope): DiscoveryCode = issueProof(scope, null)

    override suspend fun issueAction(scope: DiscoveryAccountScope, target: DiscoveryActionTarget): DiscoveryCode = issueProof(scope, target)

    private suspend fun issueProof(scope: DiscoveryAccountScope, target: DiscoveryActionTarget?): DiscoveryCode {
        require(scope.accountHash == sha256(client.sessionId.value) && scope.deviceHash == sha256(client.deviceId.value))
        val verifier = ByteArray(32).also(random::nextBytes).urlBase64()
        val challenge = MessageDigest.getInstance("SHA-256").digest(verifier.encodeToByteArray()).urlBase64()
        val exchangeId = UUID.randomUUID().toString()
        val started = clock.elapsedMillis()
        // Existing SDK accessor validates its account/device/stored session. No change to the SDK/login.
        val token = client.recoverySessionAccessToken().getOrThrow()
        val body = buildJsonObject {
            put("schema_version", 2)
            put("app_id", "mindorigin")
            put("purpose", if (target == null) "LOGIN" else "ACTION")
            put("exchange_id", exchangeId)
            put("code_challenge", challenge)
            put("account_id_hash", scope.accountHash)
            put("matrix_device_id_hash", scope.deviceHash)
            put("matrix_access_token", token)
            if (target != null) put("target", buildJsonObject {
                put("method", "POST")
                put("raw_path", target.path)
                put("raw_query", "")
                put("body_sha256", target.bodySha256)
                put("operation_id", target.operationId)
            })
        }.toString().encodeToByteArray()
        try {
            val timestamp = clock.wallMillis().toString()
            val nonce = ByteArray(32).also(random::nextBytes).urlBase64()
            val statement = "POST\n$PATH\n$timestamp\n$nonce\n${sha256(body)}".encodeToByteArray()
            val signed = trust.signControlStatement(statement)
            require(signed.installationId == scope.installationId && signed.attestationVersion == scope.attestationVersion)
            val raw = transport.post(DiscoveryTarget.HOST, PATH, body, mapOf(
                "X-Yizhe-Installation" to signed.installationId,
                "X-Yizhe-Timestamp" to timestamp,
                "X-Yizhe-Nonce" to nonce,
                "X-Yizhe-Signature" to Base64.getEncoder().encodeToString(signed.signature),
            ))
            val value = Json.parseToJsonElement(raw.decodeToString()).jsonObject
            require(value.keys == setOf("code", "code_expires_at_ms", "authorization_expires_at_ms"))
            val code = value.getValue("code").jsonPrimitive.content
            val codeExpires = value.getValue("code_expires_at_ms").jsonPrimitive.long
            val authExpires = value.getValue("authorization_expires_at_ms").jsonPrimitive.long
            val window = if (target == null) 30_000L else 5_000L
            require(code.matches(Regex("[A-Za-z0-9_-]{1,8192}")) && codeExpires > clock.wallMillis() &&
                authExpires >= codeExpires && clock.elapsedMillis() - started in 0 until window)
            if (target != null) require(authExpires == codeExpires)
            return DiscoveryCode(DiscoverySecret(code), exchangeId, DiscoverySecret(verifier), codeExpires, authExpires, started)
        } finally {
            body.fill(0)
        }
    }

    private fun ByteArray.urlBase64(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(this)

    private companion object {
        const val PATH = "/v1/discovery/login-codes"
    }
}
