/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl.network

import io.element.android.features.discovery.impl.session.AndroidDiscoveryClock
import io.element.android.features.discovery.impl.session.DiscoveryAccountScope
import io.element.android.features.discovery.impl.session.DiscoveryCode
import io.element.android.features.discovery.impl.session.DiscoveryActionTarget
import io.element.android.features.discovery.impl.session.DiscoverySecret
import io.element.android.features.discovery.impl.session.sha256
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.api.core.DeviceId
import io.element.android.security.securestorage.api.DeviceTrustService
import io.element.android.security.securestorage.api.DeviceControlRequestSignature
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

internal class DiscoveryClientsTest {
    private fun clock() = mockk<AndroidDiscoveryClock> {
        every { wallMillis() } returns 105_000L
        every { elapsedMillis() } returns 5_000L
    }

    @Test
    fun `host proof and PKCE are bound while Matrix token never reaches gateway`() = runTest {
        val matrix = mockk<MatrixClient> {
            every { sessionId } returns SessionId("@test:host.test")
            every { deviceId } returns DeviceId("DEVICE")
            coEvery { recoverySessionAccessToken() } returns Result.success("MATRIX-ONLY-TEST-SECRET")
        }
        val trust = mockk<DeviceTrustService>()
        var statement = ""
        coEvery { trust.signControlStatement(any()) } answers {
            statement = firstArg<ByteArray>().decodeToString()
            DeviceControlRequestSignature("installation", 1, byteArrayOf(1, 2, 3))
        }
        val transport = mockk<PinnedDiscoveryTransport>()
        var hostBody = ""
        var hostHeaders = emptyMap<String, String>()
        coEvery { transport.post(DiscoveryTarget.HOST, "/v1/discovery/login-codes", any(), any()) } answers {
            hostBody = arg<ByteArray>(2).decodeToString()
            hostHeaders = arg(3)
            """{"code":"signed_code","code_expires_at_ms":130000,"authorization_expires_at_ms":160000}""".encodeToByteArray()
        }
        val code = DiscoveryCodeClient(matrix, trust, transport, clock()).issue(
            DiscoveryAccountScope(sha256(matrix.sessionId.value), sha256(matrix.deviceId.value), "installation", 1, 1),
        )
        val payload = Json.parseToJsonElement(hostBody).jsonObject
        assertEquals("MATRIX-ONLY-TEST-SECRET", payload.getValue("matrix_access_token").jsonPrimitive.content)
        assertEquals(5, statement.lines().size)
        assertEquals(sha256(hostBody), statement.lines().last())
        assertEquals(hostHeaders["X-Yizhe-Nonce"], statement.lines()[3])
        val expectedChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(code.verifier.value.encodeToByteArray()),
        )
        assertEquals(expectedChallenge, payload.getValue("code_challenge").jsonPrimitive.content)
        var guestBody = ""
        coEvery { transport.post(DiscoveryTarget.GATEWAY, "/v1/discovery/capabilities", any(), any()) } answers {
            guestBody = arg<ByteArray>(2).decodeToString()
            """{"allowed":true,"global_policy_version":2,"account_policy_version":2}""".encodeToByteArray()
        }
        assertTrue(HttpDiscoveryGatewayClient(transport, clock()).allowed(code))
        assertFalse(guestBody.contains("MATRIX-ONLY"))
        assertEquals(setOf("code", "code_verifier", "exchange_id"), Json.parseToJsonElement(guestBody).jsonObject.keys)
        coVerify(exactly = 1) { transport.post(DiscoveryTarget.HOST, any(), any(), any()) }
    }

    @Test
    fun `gateway deadline cannot extend code authority or switch exchange`() = runTest {
        val id = UUID.randomUUID().toString()
        val code = DiscoveryCode(DiscoverySecret("code"), id, DiscoverySecret("v".repeat(43)), 130_000, 160_000, 0)
        val transport = mockk<PinnedDiscoveryTransport>()
        fun reply(exchange: String, expires: Long) =
            """{"session":"dsc2.$exchange.${"s".repeat(43)}","exchange_id":"$exchange","expires_at_ms":$expires}""".encodeToByteArray()
        coEvery { transport.post(DiscoveryTarget.GATEWAY, any(), any(), any()) } returns reply(id, 160_000)
        val client = HttpDiscoveryGatewayClient(transport, clock())
        assertEquals(60_000L, client.exchange(code).expiresAtElapsedMillis)
        coEvery { transport.post(DiscoveryTarget.GATEWAY, any(), any(), any()) } returns reply(id, 160_001)
        assertTrue(runCatching { client.exchange(code) }.isFailure)
        coEvery { transport.post(DiscoveryTarget.GATEWAY, any(), any(), any()) } returns reply(UUID.randomUUID().toString(), 160_000)
        assertTrue(runCatching { client.exchange(code) }.isFailure)
    }

    @Test
    fun `action uses same host issuer with exact operation body binding and no login purpose`() = runTest {
        val matrix = mockk<MatrixClient> {
            every { sessionId } returns SessionId("@test:host.test")
            every { deviceId } returns DeviceId("DEVICE")
            coEvery { recoverySessionAccessToken() } returns Result.success("HOST-ONLY-ACTION-TOKEN")
        }
        val trust = mockk<DeviceTrustService>()
        var statement = ""
        coEvery { trust.signControlStatement(any()) } answers {
            statement = firstArg<ByteArray>().decodeToString()
            DeviceControlRequestSignature("installation", 1, byteArrayOf(1))
        }
        val transport = mockk<PinnedDiscoveryTransport>()
        var hostBody = ""
        coEvery { transport.post(DiscoveryTarget.HOST, "/v1/discovery/login-codes", any(), any()) } answers {
            hostBody = arg<ByteArray>(2).decodeToString()
            """{"code":"action_code","code_expires_at_ms":110000,"authorization_expires_at_ms":110000}""".encodeToByteArray()
        }
        val target = DiscoveryActionTarget(UUID.randomUUID().toString(), sha256("persisted exact business body"))
        val scope = DiscoveryAccountScope(sha256(matrix.sessionId.value), sha256(matrix.deviceId.value), "installation", 1, 1)
        val client = DiscoveryCodeClient(matrix, trust, transport, clock())
        val code = client.issueAction(scope, target)
        val payload = Json.parseToJsonElement(hostBody).jsonObject
        assertEquals("ACTION", payload.getValue("purpose").jsonPrimitive.content)
        assertEquals("HOST-ONLY-ACTION-TOKEN", payload.getValue("matrix_access_token").jsonPrimitive.content)
        val binding = payload.getValue("target").jsonObject
        assertEquals("POST", binding.getValue("method").jsonPrimitive.content)
        assertEquals(target.path, binding.getValue("raw_path").jsonPrimitive.content)
        assertEquals("", binding.getValue("raw_query").jsonPrimitive.content)
        assertEquals(target.bodySha256, binding.getValue("body_sha256").jsonPrimitive.content)
        assertEquals(target.operationId, binding.getValue("operation_id").jsonPrimitive.content)
        assertEquals(sha256(hostBody), statement.lines().last())
        assertEquals(code.codeExpiresAtMillis, code.authorizationExpiresAtMillis)
        coVerify(exactly = 0) { transport.post(DiscoveryTarget.GATEWAY, any(), any(), any()) }
        coEvery { transport.post(DiscoveryTarget.HOST, any(), any(), any()) } returns
            """{"code":"action_code","code_expires_at_ms":110000,"authorization_expires_at_ms":160000}""".encodeToByteArray()
        assertTrue(runCatching { client.issueAction(scope, target) }.isFailure)
    }
}
