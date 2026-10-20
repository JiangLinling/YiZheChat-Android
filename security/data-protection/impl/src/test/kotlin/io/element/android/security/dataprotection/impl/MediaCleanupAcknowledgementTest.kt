/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.dataprotection.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.security.dataprotection.api.LifecycleMediaObjectCleanup
import io.element.android.security.dataprotection.api.LifecycleMediaOutcome
import io.element.android.security.dataprotection.api.LifecycleMediaReservation
import io.element.android.security.dataprotection.api.LifecycleMediaResource
import io.element.android.security.dataprotection.api.LifecycleMediaResourceType
import io.element.android.security.dataprotection.api.LifecycleMediaRuntimeCleanup
import io.element.android.security.dataprotection.api.LifecycleMediaSeal
import io.element.android.security.dataprotection.api.LifecycleSubjectHashes
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64

class MediaCleanupAcknowledgementTest {
    private val key = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private val keyId = digest(key.public.encoded).take(16)
    private val configuration = mockk<ManagementControlConfiguration> {
        every { isConfigured } returns true
        every { signingKeyId } returns keyId
        every { signingPublicKeyDerBase64 } returns Base64.getEncoder().encodeToString(key.public.encoded)
    }
    private val verifier = ManagementSignatureVerifier(configuration)
    private val fact = LifecycleMediaObjectCleanup(
        "original-operation-0001",
        LifecycleSubjectHashes(
            installationId = "original-installation",
            accountIdHash = "a".repeat(64),
            matrixDeviceIdHash = "b".repeat(64)
        ),
        "e".repeat(64),
        1234,
        1235
    )
    private val request = mediaCleanupRequest(fact)
    private val reference = "android-media-object-cleanup:v1:" + digest(verifier.canonicalJson(request))
    private fun payload() = buildJsonObject {
        put("schema_version", 1)
        put("purpose", "LOCAL_MEDIA_OBJECT_CLEANUP_ACK")
        put("observation", request)
        put("receipt_reference", reference)
        put("accepted_at_ms", 4567)
        put("signing_key_id", keyId)
    }
    private fun signed(payload: JsonObject): JsonObject {
        val bytes = Signature.getInstance("SHA256withECDSA").run {
            initSign(key.private)
            update(verifier.canonicalJson(payload))
            sign()
        }
        return JsonObject(payload + ("signature_b64" to JsonPrimitive(Base64.getEncoder().encodeToString(bytes))))
    }

    @Test fun `signed exact acknowledgement covers both original cleanup times and nullable scope`() {
        assertThat(requireMediaCleanupAcknowledgement(request, signed(payload()), verifier)).isEqualTo(reference)
        assertThat(request.keys).containsAtLeast("room_id_hash", "event_id_hash", "lease_quiescent_at_ms", "confirmed_absent_at_ms")
    }

    @Test fun `valid signature for another fact is not permission to retire this fact`() {
        val others = listOf(
            fact.copy(operationId = "another-operation-0001"),
            fact.copy(confirmedAbsentAtEpochMillis = 9999),
            fact.copy(leaseQuiescentAtEpochMillis = 9999),
            fact.copy(recordSha256 = "f".repeat(64)),
            fact.copy(subjects = fact.subjects.copy(roomIdHash = "c".repeat(64)))
        )
        for (other in others) {
            val response = signed(JsonObject(payload() + ("observation" to mediaCleanupRequest(other))))
            assertThat(runCatching { requireMediaCleanupAcknowledgement(request, response, verifier) }.isFailure).isTrue()
        }
    }

    @Test fun `signed wrong acknowledgement purpose revision reference or commit time is rejected`() {
        val changes = listOf(
            "purpose" to JsonPrimitive("LIFECYCLE_SUCCESS"),
            "schema_version" to JsonPrimitive(2),
            "receipt_reference" to JsonPrimitive("android-media-object-cleanup:v1:" + "f".repeat(64)),
            "accepted_at_ms" to JsonPrimitive(0)
        )
        for (change in changes) {
            assertThat(runCatching { requireMediaCleanupAcknowledgement(request, signed(JsonObject(payload() + change)), verifier) }.isFailure).isTrue()
        }
    }

    @Test fun `unsigned tampered or wrong authority response cannot retire an object`() {
        val response = signed(payload())
        for (invalid in listOf(
                payload(),
                JsonObject(response + ("accepted_at_ms" to JsonPrimitive(9999))),
            signed(JsonObject(payload() + ("signing_key_id" to JsonPrimitive("another-authority"))))
            )) {
            assertThat(runCatching { requireMediaCleanupAcknowledgement(request, invalid, verifier) }.isFailure).isTrue()
        }
    }

    @Test fun `resource admission seal and runtime acknowledgements require exact signed original facts`() {
        val reservation = LifecycleMediaReservation(
            fact.operationId,
            fact.subjects,
            listOf(LifecycleMediaResource(LifecycleMediaResourceType.ANDROID_MEDIA_RUNTIME, fact.recordSha256))
        )
        val seal = LifecycleMediaSeal(reservation, "f".repeat(64), 5000, LifecycleMediaOutcome.SUCCEEDED)
        val runtime = LifecycleMediaRuntimeCleanup(fact.operationId, fact.subjects, fact.recordSha256, 5001, true, true, true)
        val cases = listOf(
            mediaResourceRequest(reservation) to "MEDIA_RESOURCE_RESERVATION_ACK",
            mediaSealRequest(seal) to "MEDIA_RESOURCE_SEAL_ACK",
            mediaRuntimeRequest(runtime) to "ANDROID_MEDIA_RUNTIME_CLEANUP_ACK"
        )
        for ((original, purpose) in cases) {
            val field = if (purpose == "ANDROID_MEDIA_RUNTIME_CLEANUP_ACK") "observation" else "request"
            val hash = digest(verifier.canonicalJson(original))
            val reference = when (purpose) {
                "MEDIA_RESOURCE_RESERVATION_ACK" -> null
                "MEDIA_RESOURCE_SEAL_ACK" -> "android-media-manifest:v1:$hash:SUCCEEDED:${seal.ownerProcessSha256}:5000"
                else -> "android-media-runtime-cleanup:v1:$hash"
            }
            val response = buildJsonObject {
                put("schema_version", 1)
                put("purpose", purpose)
                put(field, original)
                put("signing_key_id", keyId)
                if (reference != null) {
                    put("receipt_reference", reference)
                    put("accepted_at_ms", 6000)
                }
            }
            assertThat(requireMediaResourceAcknowledgement(original, signed(response), verifier, purpose)).isEqualTo(reference)
            val wrongScope = JsonObject(original + ("operation_id" to JsonPrimitive("another-operation-0001")))
            val wrong = signed(JsonObject(response + (field to wrongScope)))
            assertThat(runCatching { requireMediaResourceAcknowledgement(original, wrong, verifier, purpose) }.isFailure).isTrue()
            val unsigned = JsonObject(signed(response) + ("purpose" to JsonPrimitive("WRONG_PURPOSE")))
            assertThat(runCatching { requireMediaResourceAcknowledgement(original, unsigned, verifier, purpose) }.isFailure).isTrue()
            if (reference != null) {
                val badTime = signed(JsonObject(response + ("accepted_at_ms" to JsonPrimitive(0))))
                assertThat(runCatching { requireMediaResourceAcknowledgement(original, badTime, verifier, purpose) }.isFailure).isTrue()
            }
        }
    }
}
