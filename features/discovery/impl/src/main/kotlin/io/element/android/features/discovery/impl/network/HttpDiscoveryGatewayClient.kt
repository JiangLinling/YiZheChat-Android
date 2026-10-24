/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.network

import io.element.android.features.discovery.impl.session.DiscoveryClock
import io.element.android.features.discovery.impl.session.DiscoveryCode
import io.element.android.features.discovery.impl.session.DiscoveryGatewayClient
import io.element.android.features.discovery.impl.session.DiscoverySecret
import io.element.android.features.discovery.impl.session.DiscoverySession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

internal class HttpDiscoveryGatewayClient(private val transport: DiscoveryTransport, private val clock: DiscoveryClock) : DiscoveryGatewayClient {
    override suspend fun allowed(code: DiscoveryCode): Boolean {
        val result = handoff("/v1/discovery/capabilities", code)
        require(result.keys == setOf("allowed", "global_policy_version", "account_policy_version"))
        val allowed = result.getValue("allowed").jsonPrimitive.boolean
        require(result.getValue("global_policy_version").jsonPrimitive.long >= if (allowed) 1 else 0)
        require(result.getValue("account_policy_version").jsonPrimitive.long >= if (allowed) 1 else 0)
        return allowed
    }

    override suspend fun exchange(code: DiscoveryCode): DiscoverySession {
        val result = handoff("/v1/discovery/sessions/exchange", code)
        require(result.keys == setOf("session", "expires_at_ms", "exchange_id"))
        val secret = result.getValue("session").jsonPrimitive.content
        val exchangeId = result.getValue("exchange_id").jsonPrimitive.content
        val expires = result.getValue("expires_at_ms").jsonPrimitive.long
        require(exchangeId == code.exchangeId && secret.matches(Regex("dsc2\\.$exchangeId\\.[A-Za-z0-9_-]{43}")))
        require(expires <= code.authorizationExpiresAtMillis)
        val elapsed = clock.elapsedMillis() - code.startedAtElapsedMillis
        require(elapsed in 0 until 60_000)
        val remaining = minOf(expires - clock.wallMillis(), 60_000 - elapsed)
        require(remaining > 0)
        return DiscoverySession(DiscoverySecret(secret), exchangeId, expires, clock.elapsedMillis() + remaining)
    }

    override suspend fun revoke(session: DiscoverySession) {
        transport.post(DiscoveryTarget.GATEWAY, "/v1/discovery/sessions/revoke", ByteArray(0),
            mapOf("Authorization" to "Bearer ${session.secret.value}"))
    }

    private suspend fun handoff(path: String, code: DiscoveryCode): JsonObject {
        require(clock.wallMillis() < code.codeExpiresAtMillis)
        val body = buildJsonObject {
            put("code", code.secret.value)
            put("exchange_id", code.exchangeId)
            put("code_verifier", code.verifier.value)
        }.toString().encodeToByteArray()
        return try {
            Json.parseToJsonElement(transport.post(DiscoveryTarget.GATEWAY, path, body).decodeToString()).jsonObject
        } finally {
            body.fill(0)
        }
    }
}
