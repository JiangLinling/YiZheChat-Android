/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.security.contentprotection.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.security.contentprotection.api.ContentProtectionFormat
import io.element.android.security.contentprotection.api.ContentProtectionException
import io.element.android.security.contentprotection.api.KmsAuthorizationProvider
import io.element.android.security.contentprotection.api.KmsClientConfiguration
import io.element.android.security.contentprotection.api.KmsClientConfigurationProvider
import io.element.android.security.contentprotection.api.KmsRequestAuthorization
import io.element.android.services.toolbox.api.systemclock.SystemClock
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64

class HttpDeviceRecipientRegistryTest {
    @Test
    fun `cold multi-account lookup uses one signed batch and then the cache`() = runTest {
        val server = MockWebServer()
        val signingKey = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        server.start()
        try {
            val first = registryResponse(signingKey, FIRST_ACCOUNT_HASH, "recipient-installation-0001")
            val second = registryResponse(signingKey, SECOND_ACCOUNT_HASH, "recipient-installation-0002")
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("[$first,$second]")
            )
            val registry = registry(server, signingKey)

            val resolved = registry.approvedRecipients(setOf(SECOND_ACCOUNT_HASH, FIRST_ACCOUNT_HASH))
            val cached = registry.approvedRecipients(setOf(FIRST_ACCOUNT_HASH, SECOND_ACCOUNT_HASH))

            assertThat(resolved.keys).containsExactly(FIRST_ACCOUNT_HASH, SECOND_ACCOUNT_HASH)
            assertThat(resolved[FIRST_ACCOUNT_HASH]?.recipients?.single()?.installationId)
                .isEqualTo("recipient-installation-0001")
            assertThat(resolved[FIRST_ACCOUNT_HASH]?.recipients?.single()?.allowsBoundedOffline).isTrue()
            assertThat(cached[SECOND_ACCOUNT_HASH]?.recipients?.single()?.installationId)
                .isEqualTo("recipient-installation-0002")
            assertThat(server.requestCount).isEqualTo(1)
            val request = server.takeRequest()
            assertThat(request.path).isEqualTo("/v1/device-registry/batch")
            assertThat(request.headers["Authorization"]).isEqualTo("Bearer registry-ticket")
            assertThat(request.body.readUtf8()).contains(
                "\"account_id_hashes\":[\"$FIRST_ACCOUNT_HASH\",\"$SECOND_ACCOUNT_HASH\"]"
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `managed recipient is signed and decoded as online only`() = runTest {
        val server = MockWebServer()
        val signingKey = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(registryResponse(signingKey, FIRST_ACCOUNT_HASH, "recipient-installation-managed", "MANAGED_REQUESTED"))
            )

            val recipient = registry(server, signingKey)
                .approvedRecipients(setOf(FIRST_ACCOUNT_HASH))
                .getValue(FIRST_ACCOUNT_HASH)
                .recipients
                .single()

            assertThat(recipient.allowsBoundedOffline).isFalse()
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `recipient absence and service outage retain distinct failure classifications`() = runTest {
        val server = MockWebServer()
        val key = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        server.start()
        try {
            val registry = registry(server, key)
            server.enqueue(MockResponse().setResponseCode(409).setBody("""{"detail":"an account has no approved recipient devices"}"""))
            assertThat(runCatching { registry.approvedRecipients(setOf(FIRST_ACCOUNT_HASH, SECOND_ACCOUNT_HASH)) }.exceptionOrNull())
                .isInstanceOf(ContentProtectionException.RecipientDeviceUnavailable::class.java)
            server.enqueue(MockResponse().setResponseCode(503))
            assertThat(runCatching { registry.approvedRecipients(setOf(FIRST_ACCOUNT_HASH)) }.exceptionOrNull())
                .isInstanceOf(ContentProtectionException.KmsUnavailable::class.java)
            server.enqueue(MockResponse().setResponseCode(409).setBody("""{"detail":"authority projection pending"}"""))
            assertThat(runCatching { registry.approvedRecipients(setOf(FIRST_ACCOUNT_HASH)) }.exceptionOrNull())
                .isInstanceOf(ContentProtectionException.KmsUnavailable::class.java)
            assertThat(server.requestCount).isEqualTo(3)
        } finally {
            server.shutdown()
        }
    }

    private fun registry(server: MockWebServer, signingKey: KeyPair) = HttpDeviceRecipientRegistry(
        configurationProvider = KmsClientConfigurationProvider {
            KmsClientConfiguration(
                baseUrl = server.url("/").toString(),
                signingPublicKeyDer = signingKey.public.encoded,
                maximumOfflineLeaseMillis = 60_000,
                maximumOfflineLicenseUses = 10,
                clockRollbackToleranceMillis = 1_000,
                allowCleartextForTests = true,
            )
        },
        authorizationProvider = KmsAuthorizationProvider { KmsRequestAuthorization.bearer("registry-ticket") },
        clock = SystemClock { NOW },
    )

    private fun registryResponse(
        signingKey: KeyPair,
        accountIdHash: String,
        installationId: String,
        attestationState: String = "TEST_ONLY_VERIFIED",
    ): String {
        val publicKey = "public-key-$installationId".encodeToByteArray()
        val keyId = MessageDigest.getInstance("SHA-256").digest(publicKey).toHex()
        val signature = Signature.getInstance(ContentProtectionFormat.KMS_SIGNATURE_ALGORITHM).run {
            initSign(signingKey.private)
            update(signaturePayload(accountIdHash, installationId, keyId, publicKey, attestationState))
            sign()
        }
        return """
            {
              "protocol_version": 1,
              "account_id_hash": "$accountIdHash",
              "issued_at_ms": 900,
              "expires_at_ms": 2000,
              "recipients": [{
                "installation_id": "$installationId",
                "key_id": "$keyId",
                "algorithm": "${ContentProtectionFormat.DEVICE_SHARE_ALGORITHM}",
                "public_key_der_b64": "${publicKey.base64()}",
                "hardware_security_level": "TEE",
                "attestation_state": "$attestationState"
              }],
              "server_signature_b64": "${signature.base64()}"
            }
        """.trimIndent()
    }

    private fun signaturePayload(
        accountIdHash: String,
        installationId: String,
        keyId: String,
        publicKey: ByteArray,
        attestationState: String,
    ): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write("YZDR0001".encodeToByteArray())
                output.writeString(accountIdHash)
                output.writeLong(900)
                output.writeLong(2_000)
                output.writeInt(1)
                output.writeString(installationId)
                output.writeString(keyId)
                output.writeString(ContentProtectionFormat.DEVICE_SHARE_ALGORITHM)
                output.writeByteArray(publicKey)
                output.writeString("TEE")
                output.writeString(attestationState)
            }
            bytes.toByteArray()
        }

    private fun DataOutputStream.writeString(value: String) = writeByteArray(value.encodeToByteArray())

    private fun DataOutputStream.writeByteArray(value: ByteArray) {
        writeInt(value.size)
        write(value)
    }

    private fun ByteArray.base64(): String = Base64.getEncoder().encodeToString(this)
    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        const val NOW = 1_000L
        const val FIRST_ACCOUNT_HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val SECOND_ACCOUNT_HASH = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
