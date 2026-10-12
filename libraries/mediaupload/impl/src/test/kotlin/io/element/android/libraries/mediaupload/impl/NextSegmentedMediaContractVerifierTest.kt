/*
 * Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.mediaupload.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.security.contentprotection.api.MediaSendTrustedTime
import org.junit.Assert.assertThrows
import org.junit.Test

/** Regression coverage for the normative public vectors frozen with Contract A v6. */
class NextSegmentedMediaContractVerifierTest {
    private val verifier = NextSegmentedMediaContractVerifier()

    @Test
    fun `normative signed ALLOW vector verifies`() {
        val allow = verifier.verifyAllow(
            responseBytes = allowEnvelope(),
            operationId = "op-001",
            requestId = "request-001",
            roomId = "!room:example.org",
            registry = registry(ALLOW_KEY_ID, ALLOW_PUBLIC_KEY),
            trustedTime = time(1_790_210_000_001),
        )

        assertThat(allow.bindingFingerprintSha256)
            .isEqualTo("7e5ece65383bfcb4bdd972ffdae27b2fe406c5980079651067b12a1047883a9e")
        assertThat(allow.keyId).isEqualTo(ALLOW_KEY_ID)
    }

    @Test
    fun `normative SEND_BOUND vector verifies against the same ALLOW identity`() {
        val allow = verifier.verifyAllow(
            allowEnvelope(), "op-001", "request-001", "!room:example.org",
            registry(ALLOW_KEY_ID, ALLOW_PUBLIC_KEY), time(1_790_210_000_001),
        )

        val bound = verifier.verifySendBound(
            responseBytes = BOUND_ENVELOPE.encodeToByteArray(),
            allow = allow,
            transactionId = "txn-001",
            eventContentDigestSha256 = "c7dd61fcce3404a10da205654e701f67faca6d2ffcacf3ab83cb7469de64e16f",
            registry = registry(BOUND_KEY_ID, BOUND_PUBLIC_KEY),
            trustedTime = time(1_790_210_000_002),
        )

        assertThat(bound.transactionId).isEqualTo("txn-001")
        assertThat(bound.keyId).isEqualTo(BOUND_KEY_ID)
    }

    @Test
    fun `unknown member and revoked issuer key fail closed`() {
        val unknownMember = allowEnvelope().decodeToString().replaceFirst(
            "{\"contract\":\"next.segmented-media-send-lease-response.v1\"",
            "{\"unexpected\":true,\"contract\":\"next.segmented-media-send-lease-response.v1\"",
        ).encodeToByteArray()
        assertThrows(IllegalArgumentException::class.java) {
            verifier.verifyAllow(
                unknownMember, "op-001", "request-001", "!room:example.org",
                registry(ALLOW_KEY_ID, ALLOW_PUBLIC_KEY), time(1_790_210_000_001),
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            verifier.verifyAllow(
                allowEnvelope(), "op-001", "request-001", "!room:example.org",
                registry(ALLOW_KEY_ID, ALLOW_PUBLIC_KEY, NextMediaSendIssuerKey.Status.REVOKED),
                time(1_790_210_000_001),
            )
        }
    }

    @Test
    fun `trusted interval crossing exact expiry fails closed`() {
        assertThrows(IllegalArgumentException::class.java) {
            verifier.verifyAllow(
                allowEnvelope(), "op-001", "request-001", "!room:example.org",
                registry(ALLOW_KEY_ID, ALLOW_PUBLIC_KEY),
                MediaSendTrustedTime(1_790_210_029_999, 1_790_210_030_000),
            )
        }
    }

    private fun time(value: Long) = MediaSendTrustedTime(value, value)

    private fun allowEnvelope(): ByteArray = buildString {
        append("{\"contract\":\"next.segmented-media-send-lease-response.v1\",\"payload\":")
        append(ALLOW_PAYLOAD)
        append(",\"signature\":{\"keyId\":\"$ALLOW_KEY_ID\",\"algorithm\":\"SHA256withECDSA\",\"signatureBase64\":\"$ALLOW_SIGNATURE\"}}")
    }.encodeToByteArray()

    private fun registry(
        keyId: String,
        pem: String,
        status: NextMediaSendIssuerKey.Status = NextMediaSendIssuerKey.Status.ACTIVE,
    ) = VerifiedNextMediaSendIssuerRegistry(
        revision = 1,
        issuedAtMs = 1_790_209_000_000,
        expiresAtMs = 1_790_211_000_000,
        keys = listOf(
            NextMediaSendIssuerKey(
                keyId = keyId,
                algorithm = "SHA256withECDSA",
                publicKeyPem = pem,
                notBeforeMs = 1_790_209_000_000,
                notAfterMs = 1_790_211_000_000,
                status = status,
            )
        ),
    )

    private companion object {
        const val ALLOW_KEY_ID = "b9d296385e238337"
        const val ALLOW_SIGNATURE = "MEQCIHor64L+I0CaMSREQPQSTjKYZmPCXY2mb/2ikZysoQ6+AiADeqhisDHA2HTNcJHJhzy7MNE4lz9dcWANraCFAMcTyw=="
        const val ALLOW_PUBLIC_KEY = """-----BEGIN PUBLIC KEY-----
MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEwO0NLOweTVSad4jXwB8zsgskIEgC
gfZWsVSHncn/scBqhypOKy9vAGB5RWlkglZlUC8iyPBXNGCxi2a0Qw0zvw==
-----END PUBLIC KEY-----
"""
        const val BOUND_KEY_ID = "2720cdb3de0343c9"
        const val BOUND_PUBLIC_KEY = """-----BEGIN PUBLIC KEY-----
MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEUk4NdrxahnDx3YkQs9ler0zEF1wf
5IA61f+NHJYd303SFpXrJ180DEBZ6q7q56IsMQ979jAIoc1KBV1xXma0EA==
-----END PUBLIC KEY-----
"""
        const val ALLOW_PAYLOAD = """{"contract":"next.segmented-media-send-lease-payload.v1","issuer":"NEXT_MEDIA_CONTROL","purpose":"PREPARED_SEGMENTED_MEDIA_EVENT_SEND","decision":"ALLOW","binding":{"contract":"next.segmented-media-binding.v1","operationId":"op-001","assetId":"asset-001","mediaVersion":1,"resourceSetDigestSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","manifestDigestSha256":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","pagesDigestSha256":"cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc","accountIdHash":"dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd","installationId":"install-001","matrixUserId":"@alice:example.org","matrixDeviceId":"DEVICE_A","deviceTrustGeneration":7,"roomId":"!room:example.org","roomIdHash":"03cbb71e7ec22cfce1875295478d262636197456240d09448bd8040959a9328a","roomEpoch":9,"contentContractId":"content-v3","policyRevision":{"domain":"GLOBAL_POLICY","revisionId":"rev-001","version":4,"digestSha256":"eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"},"authorityRevision":{"deviceTrustGeneration":7,"roomEpoch":9,"kmsFenceGeneration":11,"resourceRootGeneration":2},"requestId":"request-001","nonce":"nonce-001","effectiveAtMs":1790210000000,"expiresAtMs":1790210030000},"bindingFingerprintSha256":"7e5ece65383bfcb4bdd972ffdae27b2fe406c5980079651067b12a1047883a9e","issuedAtMs":1790210000001,"serverNowMs":1790210000001,"decisionRevision":1,"retryAfterMs":0}"""
        const val BOUND_ENVELOPE = """{"contract":"next.segmented-media-send-bound-envelope.v1","payload":{"contract":"next.segmented-media-send-bound.v1","issuer":"NEXT_MEDIA_CONTROL","purpose":"PREPARED_SEGMENTED_MEDIA_EVENT_SEND","operationId":"op-001","requestId":"request-001","nonceDigestSha256":"70862daf9aaf88d10db6bfc0ff845ad50aff6f29a3f5c623e9bd9390559393c9","bindingFingerprintSha256":"7e5ece65383bfcb4bdd972ffdae27b2fe406c5980079651067b12a1047883a9e","transactionId":"txn-001","eventContentDigestSha256":"c7dd61fcce3404a10da205654e701f67faca6d2ffcacf3ab83cb7469de64e16f","issuedAtMs":1790210000002,"expiresAtMs":1790210030000,"decisionRevision":2},"signature":{"keyId":"2720cdb3de0343c9","algorithm":"SHA256withECDSA","signatureBase64":"MEYCIQD1gH24j2KuBiEHkKvJ+YHrRgGuC0yJQhwW6AsJjoTLngIhAKzgaBne08L+WwNF/19UY2EyzN2FxrpeQII3l2zAHNCW"}}"""
    }
}
