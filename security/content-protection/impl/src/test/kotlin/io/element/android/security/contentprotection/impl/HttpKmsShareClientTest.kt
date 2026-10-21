/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.security.contentprotection.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.security.contentprotection.api.AcquireRoomEpochRequest
import io.element.android.security.contentprotection.api.ConfirmDeviceRecoveryRequest
import io.element.android.security.contentprotection.api.ContentId
import io.element.android.security.contentprotection.api.ContentKind
import io.element.android.security.contentprotection.api.ContentLifecycle
import io.element.android.security.contentprotection.api.ContentPolicyReference
import io.element.android.security.contentprotection.api.ContentProtectionException
import io.element.android.security.contentprotection.api.ContentProtectionFormat
import io.element.android.security.contentprotection.api.ConversationKind
import io.element.android.security.contentprotection.api.DeviceShareEnvelope
import io.element.android.security.contentprotection.api.DeviceShareKeyDescriptor
import io.element.android.security.contentprotection.api.DeviceShareKeyService
import io.element.android.security.contentprotection.api.DeviceShareRecipient
import io.element.android.security.contentprotection.api.DeviceRecoveryState
import io.element.android.security.contentprotection.api.FetchRevocationsRequest
import io.element.android.security.contentprotection.api.KmsAuthorizationProvider
import io.element.android.security.contentprotection.api.KmsClientConfiguration
import io.element.android.security.contentprotection.api.KmsClientConfigurationProvider
import io.element.android.security.contentprotection.api.KmsRequestAuthorization
import io.element.android.security.contentprotection.api.MessageLifecycleMode
import io.element.android.security.contentprotection.api.OfflineLeaseMode
import io.element.android.security.contentprotection.api.PolicyId
import io.element.android.security.contentprotection.api.ReleaseKmsShareRequest
import io.element.android.security.contentprotection.api.ReleaseRoomEpochRequest
import io.element.android.security.contentprotection.api.ResolvedRoomLifecycle
import io.element.android.security.contentprotection.api.RemoteDeviceAccessState
import io.element.android.security.contentprotection.api.RoomEpochReference
import io.element.android.security.contentprotection.api.RoomLifecycleProfileKind
import io.element.android.security.contentprotection.api.RoomLifecycleRecoveryPolicy
import io.element.android.security.contentprotection.api.StartDeviceRecoveryRequest
import io.element.android.security.contentprotection.api.StoreKmsShareRequest
import io.element.android.security.contentprotection.api.TrustedRedactionReason
import io.element.android.security.securestorage.api.HardwareSecurityLevel
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64

class HttpKmsShareClientTest {
    @Test
    fun `new share rejects retired recipient even when sender cached its device`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            for (request in listOf(storeRequest(), v3StoreRequest())) {
                for (detail in listOf("account has no approved recipient devices", "an account has no approved recipient devices",
                        "authorized device set changed; refresh recipients and retry")) {
                    server.enqueue(MockResponse().setResponseCode(409).setBody("""{"detail":"$detail"}"""))
                    val failure = runCatching { client(server, signingKey()).storeShare(request) }.exceptionOrNull()
                    assertThat(failure).isInstanceOf(ContentProtectionException.RecipientDeviceUnavailable::class.java)
                }
            }
            for (body in listOf("""{"detail":"unknown conflict"}""", "not-json", "x".repeat(4097))) {
                server.enqueue(MockResponse().setResponseCode(409).setBody(body))
                val failure = runCatching { client(server, signingKey()).storeShare(v3StoreRequest()) }.exceptionOrNull()
                assertThat(failure).isNotInstanceOf(ContentProtectionException.RecipientDeviceUnavailable::class.java)
                assertThat(failure).isNotNull()
            }
        } finally { server.shutdown() }
    }

    @Test
    fun `BUG043 unavailable epoch material never becomes content TTL expiry`() = runTest {
        val bodies = listOf(
            """{"detail":"ROOM_EPOCH_MATERIAL_UNAVAILABLE"}""",
            """{"detail":"room epoch access is unavailable"}""",
            """{"detail":"unrecognized permanent denial"}""",
            "not-json", "x".repeat(4097), "",
        )
        bodies.forEach { body ->
            val server = MockWebServer()
            server.start()
            try {
                server.enqueue(MockResponse().setResponseCode(410).setBody(body))
                val result = runCatching { client(server, signingKey()).releaseRoomEpoch(releaseRoomEpochRequest()) }
                assertThat(result.exceptionOrNull()).isInstanceOf(ContentProtectionException.RoomEpochMaterialUnavailable::class.java)
                assertThat(server.requestCount).isEqualTo(1)
            } finally { server.shutdown() }
        }
    }

    @Test
    fun `BUG043 explicit epoch retention deadline retains expiry classification`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(410).setBody("""{"detail":"ROOM_EPOCH_RETENTION_EXPIRED"}"""))
            val result = runCatching { client(server, signingKey()).releaseRoomEpoch(releaseRoomEpochRequest()) }
            assertThat(result.exceptionOrNull()).isInstanceOf(ContentProtectionException.Expired::class.java)
        } finally { server.shutdown() }
    }

    @Test
    fun `actual Python server signature vector is accepted by Android without reconstructing either signature`() = runTest {
        val vector = java.util.Properties().apply {
            requireNotNull(HttpKmsShareClientTest::class.java.getResourceAsStream("/progressive-server-v1.properties")).use(::load)
        }
        val publicKey = java.security.KeyFactory.getInstance("EC").generatePublic(
            java.security.spec.X509EncodedKeySpec(Base64.getDecoder().decode(vector.getProperty("signing_public_key"))))
        val key = KeyPair(publicKey, signingKey().private)
        val request = releaseRequest().copy(policy = releaseRequest().policy.copy(expiresAtEpochMillis = 180_000),
            progressiveVideoPlayback = true)
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(jsonResponse(Base64.getDecoder().decode(vector.getProperty("response_b64")).decodeToString()))
            val share = client(server, key, deviceShareKeyService = SuccessfulDeviceShareKeyService).releaseShare(request)
            assertThat(share.authorizationDeadlineEpochMillis).isEqualTo(9_000)
            assertThat(share.progressivePlaybackDeadlineEpochMillis).isEqualTo(180_000)
            share.kmsShare.fill(0)
        } finally { server.shutdown() }
    }


    @Test
    fun `progressive opening verifies additional signature before unwrap and retains original grant deadline`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val key = signingKey()
            val request = releaseRequest().copy(policy = releaseRequest().policy.copy(expiresAtEpochMillis = 180_000),
                progressiveVideoPlayback = true)
            val fixture = ReleaseFixture(request, OfflineLeaseMode.ONLINE_REQUIRED)
            server.enqueue(jsonResponse("[" + playbackJson(fixture, key, 180_000) + "]"))
            val share = client(server, key, deviceShareKeyService = SuccessfulDeviceShareKeyService).releaseShare(request)
            assertThat(share.authorizationDeadlineEpochMillis).isEqualTo(9_000)
            assertThat(share.expiresAtEpochMillis).isEqualTo(9_000)
            assertThat(share.progressivePlaybackDeadlineEpochMillis).isEqualTo(180_000)
            assertThat(share.kmsShare).isEqualTo(ByteArray(ContentProtectionFormat.SHARE_SIZE_BYTES) { 9 })
            share.kmsShare.fill(0)
            assertThat(server.takeRequest().body.readUtf8()).contains("\"progressive_video_playback\":\"PROGRESSIVE_VIDEO_V1\"")
            assertThat(server.requestCount).isEqualTo(1)
        } finally { server.shutdown() }
    }

    @Test
    fun `progressive missing forged bounds scope offline and unsolicited permission fail before unwrap`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val key = signingKey()
            val request = releaseRequest().copy(policy = releaseRequest().policy.copy(expiresAtEpochMillis = 7_200_000),
                progressiveVideoPlayback = true)
            val fixture = ReleaseFixture(request, OfflineLeaseMode.ONLINE_REQUIRED)
            val valid = playbackJson(fixture, key, 180_000)
            val cases = listOf(
                fixture.json(sign(key, fixture.payload())) to request,
                valid.replace("\"expires_at_ms\":180000", "\"expires_at_ms\":180001") to request,
                playbackJson(fixture, key, 8_000) to request,
                playbackJson(fixture, key, 3_601_001) to request,
                valid.replace("PROGRESSIVE_VIDEO_V1", "UNSUPPORTED") to request,
                playbackJson(ReleaseFixture(request), key, 180_000) to request,
                valid to request.copy(progressiveVideoPlayback = false),
                valid to request.copy(policy = request.policy.copy(expiresAtEpochMillis = 90_000)),
            )
            // NoOp unwrap throws a distinct error; every rejection must precede that boundary.
            for ((body, requested) in cases) {
                server.enqueue(jsonResponse("[" + body + "]"))
                assertThat(runCatching { client(server, key).releaseShare(requested) }.exceptionOrNull())
                    .isInstanceOf(ContentProtectionException::class.java)
                server.takeRequest()
            }
        } finally { server.shutdown() }
    }

    @Test
    fun `playback signature binds the original nonce even if the base release is signed again`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val key = signingKey()
            val request = releaseRequest().copy(policy = releaseRequest().policy.copy(expiresAtEpochMillis = 180_000),
                progressiveVideoPlayback = true)
            val fixture = ReleaseFixture(request, OfflineLeaseMode.ONLINE_REQUIRED)
            val changed = request.copy(requestNonce = ByteArray(request.requestNonce.size) { 5 })
            val replacement = ReleaseFixture(changed, OfflineLeaseMode.ONLINE_REQUIRED)
            val permission = playbackJson(fixture, key, 180_000).substringAfter("\"progressive_video_playback\":")
            val forged = replacement.json(sign(key, replacement.payload())).trimEnd().dropLast(1) +
                ",\"progressive_video_playback\":" + permission
            server.enqueue(jsonResponse("[" + forged + "]"))
            assertThat(runCatching { client(server, key).releaseShare(changed) }.exceptionOrNull())
                .isInstanceOf(ContentProtectionException.AuthenticationFailed::class.java)
        } finally { server.shutdown() }
    }

    private fun playbackJson(fixture: ReleaseFixture, key: KeyPair, deadline: Long): String {
        val payload = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write("YZVP0001".encodeToByteArray())
                output.write(fixture.payload())
                output.writeString("PROGRESSIVE_VIDEO_V1")
                output.writeLong(deadline)
            }
            bytes.toByteArray()
        }
        return fixture.json(sign(key, fixture.payload())).trimEnd().dropLast(1) +
            ",\"progressive_video_playback\":{\"scope\":\"PROGRESSIVE_VIDEO_V1\",\"expires_at_ms\":" + deadline +
            ",\"server_signature_b64\":\"" + sign(key, payload).base64() + "\"}}"
    }

    @Test
    fun `generic KMS denial logs only bounded fixed codes`() = runTest {
        val server = MockWebServer()
        val tree = RecordingTree()
        server.start()
        Timber.plant(tree)
        try {
            for ((body, expected) in listOf(
                """{"detail":"content binding mismatch"}""" to "CONTENT_BINDING_MISMATCH",
                """{"detail":"private-$ACCOUNT_HASH"}""" to "UNKNOWN",
                "x".repeat(4097) to "UNKNOWN",
            )) {
                val previousLogCount = tree.messages.size
                server.enqueue(MockResponse().setResponseCode(403).setHeader("Content-Type", "application/json").setBody(body))
                val failure = runCatching { client(server, signingKey()).storeShare(storeRequest()) }.exceptionOrNull()
                assertThat(failure).isInstanceOf(ContentProtectionException.AccessDenied::class.java)
                val newLogs = tree.messages.drop(previousLogCount).joinToString("\n")
                assertThat(newLogs).contains("HTTP_DETAIL_$expected")
                assertThat(newLogs).doesNotContain(body)
                assertThat(newLogs).doesNotContain(ACCOUNT_HASH)
                assertThat(newLogs).doesNotContain(INSTALLATION_ID)
            }
        } finally {
            Timber.uproot(tree)
            server.shutdown()
        }
    }

    @Test
    fun `caption version accepts complete server node and rejects unknown fields`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val operation = "media-caption-contract-test-001"
            val node = """{"content_id":"caption-root","root_content_id":"caption-root","parent_content_id":null,
                "operation_id":"$operation","room_id_sha256":"${"c".repeat(64)}",
                "owner_account_id_hash":"$ACCOUNT_HASH","sender_installation_id":"$INSTALLATION_ID",
                "content_kind":"MEDIA_CAPTION","policy_id":"test-policy","policy_version":2,
                "expires_at":1793127600000,"matrix_event_id_sha256":"${"d".repeat(64)}",
                "immutable_context_digest":"${"e".repeat(64)}","created_at":1790464994140}""".trimIndent()
            val kms = client(server, signingKey())
            val request = io.element.android.security.contentprotection.api.RegisterContentVersionRequest(
                ContentId("caption-root"), ContentId("caption-root"), null, operation, operation, "d".repeat(64),
            )
            kotlinx.coroutines.withContext(io.element.android.security.dataprotection.api.LifecycleOperationContext(
                operation, io.element.android.security.dataprotection.api.FullAppLifecycleId.LC_15,
            )) {
                val responseBody = java.util.concurrent.atomic.AtomicReference("""{"node":$node}""")
                server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                    override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                        MockResponse().setResponseCode(201).setHeader("Content-Type", "application/json")
                            .setBody(responseBody.get())
                }
                val result = kms.registerContentVersion(request)
                assertThat(result.contentId).isEqualTo(request.contentId)
                assertThat(result.expiresAtEpochMillis).isEqualTo(1793127600000L)
                responseBody.set("""{"node":${node.dropLast(1)},"unexpected_authority":true}}""")
                assertThat(runCatching { kms.registerContentVersion(request) }.isFailure).isTrue()
                responseBody.set("""{"node":${node.replace("\"policy_version\":2,", "")}}""")
                assertThat(runCatching { kms.registerContentVersion(request) }.isFailure).isTrue()
                responseBody.set("""{"node":${node.replace(operation, "wrong-operation")}}""")
                assertThat(runCatching { kms.registerContentVersion(request) }.isFailure).isTrue()
                responseBody.set("""{"node":${node.replace("d".repeat(64), "f".repeat(64))}}""")
                assertThat(runCatching { kms.registerContentVersion(request) }.isFailure).isTrue()
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `room epoch acquire 403 logs fixed denial codes without raw details`() = runTest {
        val cases = mapOf(
            "device is not eligible for protected-content access" to "DEVICE_INELIGIBLE",
            "device access ticket requires security synchronization" to "TICKET_SYNC_REQUIRED",
            "lifecycle operation belongs to another installation" to "LIFECYCLE_INSTALLATION_MISMATCH",
            "room identity hash mismatch" to "ROOM_IDENTITY_MISMATCH",
            "room epoch requesting device is not trusted" to "REQUESTING_DEVICE_UNTRUSTED",
            "room epoch account is not bound to this device" to "ACCOUNT_DEVICE_BINDING_MISMATCH",
            "account is active on another mobile installation" to "ACTIVE_MOBILE_INSTALLATION_MISMATCH",
            "device content key is unavailable" to "CONTENT_KEY_UNAVAILABLE",
            "device content key is not acceptable" to "CONTENT_KEY_UNACCEPTABLE",
            "device content key mismatch" to "CONTENT_KEY_MISMATCH",
        )

        cases.forEach { (detail, expectedCode) ->
            val server = MockWebServer()
            val tree = RecordingTree()
            val signingKey = signingKey()
            server.start()
            Timber.plant(tree)
            try {
                server.enqueue(
                    MockResponse()
                        .setResponseCode(403)
                        .setHeader("Content-Type", "application/json")
                        .setBody("""{"detail":"$detail"}""")
                )

                val result = runCatching { client(server, signingKey).acquireRoomEpoch(acquireRoomEpochRequest()) }

                assertThat(result.exceptionOrNull()).isInstanceOf(ContentProtectionException.AccessDenied::class.java)
                val logs = tree.messages.joinToString("\n")
                assertThat(logs).contains("YZCP_KMS_DENIED stage=ROOM_EPOCH_$expectedCode http_status=403")
                assertThat(logs).doesNotContain(detail)
                assertThat(logs).doesNotContain(ROOM_ID)
                assertThat(logs).doesNotContain(ACCOUNT_HASH)
                assertThat(logs).doesNotContain(INSTALLATION_ID)
            } finally {
                Timber.uproot(tree)
                server.shutdown()
            }
        }
    }

    @Test
    fun `room epoch release 403 includes only release fixed denial codes`() = runTest {
        val cases = mapOf(
            "room epoch binding mismatch" to "ROOM_EPOCH_BINDING_MISMATCH",
            "room epoch membership mismatch" to "ROOM_EPOCH_MEMBERSHIP_MISMATCH",
        )

        cases.forEach { (detail, expectedCode) ->
            val server = MockWebServer()
            val tree = RecordingTree()
            val signingKey = signingKey()
            server.start()
            Timber.plant(tree)
            try {
                server.enqueue(
                    MockResponse()
                        .setResponseCode(403)
                        .setHeader("Content-Type", "application/json")
                        .setBody("""{"detail":"$detail"}""")
                )

                val result = runCatching { client(server, signingKey).releaseRoomEpoch(releaseRoomEpochRequest()) }

                assertThat(result.exceptionOrNull()).isInstanceOf(ContentProtectionException.AccessDenied::class.java)
                val logs = tree.messages.joinToString("\n")
                assertThat(logs).contains("YZCP_KMS_DENIED stage=ROOM_EPOCH_$expectedCode http_status=403")
                assertThat(logs).doesNotContain(detail)
            } finally {
                Timber.uproot(tree)
                server.shutdown()
            }
        }
    }

    @Test
    fun `room epoch malformed unknown and oversized 403 bodies stay denied and redacted`() = runTest {
        val bodies = listOf(
            """{"detail":"unknown denial for $INSTALLATION_ID"}""",
            """{"detail":{"code":"DEVICE_INELIGIBLE"}}""",
            "not-json-$ACCOUNT_HASH",
            "",
            "x".repeat(4097),
        )

        bodies.forEach { body ->
            val server = MockWebServer()
            val tree = RecordingTree()
            val signingKey = signingKey()
            server.start()
            Timber.plant(tree)
            try {
                server.enqueue(
                    MockResponse()
                        .setResponseCode(403)
                        .setHeader("Content-Type", "application/json")
                        .setBody(body)
                )

                val result = runCatching { client(server, signingKey).acquireRoomEpoch(acquireRoomEpochRequest()) }

                assertThat(result.exceptionOrNull()).isInstanceOf(ContentProtectionException.AccessDenied::class.java)
                val logs = tree.messages.joinToString("\n")
                assertThat(logs).contains("YZCP_KMS_DENIED stage=ROOM_EPOCH_UNKNOWN http_status=403")
                if (body.isNotEmpty()) assertThat(logs).doesNotContain(body)
                assertThat(logs).doesNotContain(ACCOUNT_HASH)
                assertThat(logs).doesNotContain(INSTALLATION_ID)
            } finally {
                Timber.uproot(tree)
                server.shutdown()
            }
        }
    }

    @Test
    fun `room epoch 401 retains generic authorization denial`() = runTest {
        val server = MockWebServer()
        val tree = RecordingTree()
        val signingKey = signingKey()
        server.start()
        Timber.plant(tree)
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(401)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"detail":"device is not eligible for protected-content access"}""")
            )

            val result = runCatching { client(server, signingKey).acquireRoomEpoch(acquireRoomEpochRequest()) }

            assertThat(result.exceptionOrNull()).isInstanceOf(ContentProtectionException.AccessDenied::class.java)
            val logs = tree.messages.joinToString("\n")
            assertThat(logs).contains("YZCP_KMS_DENIED stage=HTTP_AUTHORIZATION http_status=401")
            assertThat(logs).doesNotContain("ROOM_EPOCH_")
        } finally {
            Timber.uproot(tree)
            server.shutdown()
        }
    }

    @Test
    fun `private profile exact recovery reason is exposed as device recovery required`() = runTest {
        val server = MockWebServer()
        val signingKey = signingKey()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(403)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"detail":"DEVICE_RECOVERY_REQUIRED"}""")
            )

            val result = runCatching {
                client(server, signingKey, deviceShareKeyService = PrivateProfileDeviceShareKeyService)
                    .ensurePrivateProfileKey(ACCOUNT_HASH, ByteArray(ContentProtectionFormat.SHARE_SIZE_BYTES))
            }

            assertThat(result.exceptionOrNull()).isInstanceOf(ContentProtectionException.DeviceRecoveryRequired::class.java)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `private profile unrelated authorization failures remain denied`() = runTest {
        val bodies = listOf(
            401 to """{"detail":"invalid device proof"}""",
            403 to """{"detail":"device revoked"}""",
            403 to "not-json",
            403 to """{"detail":{"code":"DEVICE_RECOVERY_REQUIRED"}}""",
        )

        bodies.forEach { (status, body) ->
            val server = MockWebServer()
            val signingKey = signingKey()
            server.start()
            try {
                server.enqueue(
                    MockResponse()
                        .setResponseCode(status)
                        .setHeader("Content-Type", "application/json")
                        .setBody(body)
                )

                val result = runCatching {
                    client(server, signingKey, deviceShareKeyService = PrivateProfileDeviceShareKeyService)
                        .ensurePrivateProfileKey(ACCOUNT_HASH, ByteArray(ContentProtectionFormat.SHARE_SIZE_BYTES))
                }

                assertThat(result.exceptionOrNull()).isInstanceOf(ContentProtectionException.AccessDenied::class.java)
            } finally {
                server.shutdown()
            }
        }
    }

    @Test
    fun `Matrix redaction confirmation is accepted only when target hashes match`() = runTest {
        val server = MockWebServer()
        val signingKey = signingKey()
        server.start()
        try {
            val roomId = "!room:yizhe.test"
            val eventId = "\$redacted-event"
            val roomHash = MessageDigest.getInstance("SHA-256").digest(roomId.encodeToByteArray()).toHex()
            val eventHash = MessageDigest.getInstance("SHA-256").digest(eventId.encodeToByteArray()).toHex()
            server.enqueue(jsonResponse("""
                {"protocol_version":1,"state":"REDACTED","room_id_sha256":"$roomHash",
                "event_id_sha256":"$eventHash","evidence_sha256":"${"a".repeat(64)}"}
            """.trimIndent()))

            assertThat(client(server, signingKey).confirmMatrixRedaction(roomId, eventId)).isTrue()
            assertThat(server.takeRequest().path).isEqualTo("/v1/message-lifecycle/redactions/confirm")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `Matrix redaction confirmation with another event fails closed`() = runTest {
        val server = MockWebServer()
        val signingKey = signingKey()
        server.start()
        try {
            val roomId = "!room:yizhe.test"
            val eventId = "\$redacted-event"
            val roomHash = MessageDigest.getInstance("SHA-256").digest(roomId.encodeToByteArray()).toHex()
            server.enqueue(jsonResponse("""
                {"protocol_version":1,"state":"REDACTED","room_id_sha256":"$roomHash",
                "event_id_sha256":"${"b".repeat(64)}","evidence_sha256":"${"a".repeat(64)}"}
            """.trimIndent()))

            val result = runCatching { client(server, signingKey).confirmMatrixRedaction(roomId, eventId) }

            assertThat(result.exceptionOrNull()).isInstanceOf(ContentProtectionException.AuthenticationFailed::class.java)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `redaction reasons are accepted only with a matching control-plane signature`() = runTest {
        val server = MockWebServer()
        val signingKey = signingKey()
        server.start()
        try {
            val eventId = "$" + "redacted-event"
            val eventHash = MessageDigest.getInstance("SHA-256").digest(eventId.encodeToByteArray()).toHex()
            val issuedAt = System.currentTimeMillis()
            val keyId = MessageDigest.getInstance("SHA-256").digest(signingKey.public.encoded).toHex().take(16)
            val payload = redactionReasonPayload(
                reason = "RETENTION_EXPIRED",
                receiptId = "receipt_0123456789012345",
                eventHash = eventHash,
                issuedAt = issuedAt,
                keyId = keyId,
            )
            server.enqueue(jsonResponse("""
                {"protocol_version":1,"receipts":[{
                  "protocol_version":1,
                  "reason_code":"RETENTION_EXPIRED",
                  "lifecycle_receipt_id":"receipt_0123456789012345",
                  "event_id_sha256":"$eventHash",
                  "issued_at_ms":$issuedAt,
                  "key_id":"$keyId",
                  "signature_b64":"${sign(signingKey, payload).base64()}"
                }]}
            """.trimIndent()))

            val result = client(server, signingKey).resolveRedactionReasons(setOf(eventHash))

            assertThat(result).containsExactly(eventHash, TrustedRedactionReason.RETENTION_EXPIRED)
            val request = server.takeRequest()
            val requestBody = request.body.readUtf8()
            assertThat(request.path).isEqualTo("/v1/message-lifecycle/redaction-reasons/resolve")
            assertThat(requestBody).contains(eventHash)
            assertThat(requestBody).doesNotContain(eventId)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `tampered redaction reason fails closed`() = runTest {
        val server = MockWebServer()
        val signingKey = signingKey()
        server.start()
        try {
            val eventHash = "c".repeat(64)
            val issuedAt = System.currentTimeMillis()
            val keyId = MessageDigest.getInstance("SHA-256").digest(signingKey.public.encoded).toHex().take(16)
            val signature = sign(
                signingKey,
                redactionReasonPayload(
                    reason = "USER_WITHDRAWN",
                    receiptId = "receipt_0123456789012345",
                    eventHash = eventHash,
                    issuedAt = issuedAt,
                    keyId = keyId,
                ),
            )
            server.enqueue(jsonResponse("""
                {"protocol_version":1,"receipts":[{
                  "protocol_version":1,
                  "reason_code":"RETENTION_EXPIRED",
                  "lifecycle_receipt_id":"receipt_0123456789012345",
                  "event_id_sha256":"$eventHash",
                  "issued_at_ms":$issuedAt,
                  "key_id":"$keyId",
                  "signature_b64":"${signature.base64()}"
                }]}
            """.trimIndent()))

            val failure = runCatching { client(server, signingKey).resolveRedactionReasons(setOf(eventHash)) }.exceptionOrNull()

            assertThat(failure).isInstanceOf(ContentProtectionException.AuthenticationFailed::class.java)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `call record registration is room bound and never enables media recording`() = runTest {
        val server = MockWebServer()
        val signingKey = signingKey()
        server.start()
        try {
            val roomId = "!calls:yizhe.test"
            val eventId = "$" + "encrypted-call-event"
            val roomHash = MessageDigest.getInstance("SHA-256").digest(roomId.encodeToByteArray()).toHex()
            val eventHash = MessageDigest.getInstance("SHA-256").digest(eventId.encodeToByteArray()).toHex()
            server.enqueue(jsonResponse("""
                {
                  "protocol_version":1,
                  "record_id":"${"a".repeat(64)}",
                  "room_id_sha256":"$roomHash",
                  "event_id_sha256":"$eventHash",
                  "profile_id":"STANDARD_30D",
                  "profile_kind":"STANDARD",
                  "scheduled_purge_at_ms":1801000000000,
                  "store_media":false,
                  "recording_enabled":false
                }
            """.trimIndent()))

            assertThat(client(server, signingKey).registerCallRecord(roomId, eventId)).isTrue()
            val request = server.takeRequest()
            assertThat(request.path).isEqualTo("/v1/message-lifecycle/call-records")
            assertThat(request.body.readUtf8()).contains("\"event_id\":\"$eventId\"")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `signed room lifecycle snapshot controls new message retention`() = runTest {
        val server = MockWebServer()
        val signingKey = signingKey()
        server.start()
        try {
            val roomId = "!confidential:yizhe.test"
            val issuedAt = System.currentTimeMillis()
            val expiresAt = issuedAt + 5 * 60_000
            val messageExpiresAt = issuedAt + 7L * 24 * 60 * 60_000 + 60 * 60_000
            val roomHash = MessageDigest.getInstance("SHA-256").digest(roomId.encodeToByteArray()).toHex()
            val contractId = "contract_012345678901234567890123"
            val profileRevision = 7L
            val signingKeyId = MessageDigest.getInstance("SHA-256").digest(signingKey.public.encoded).toHex().take(16)
            val contractSha256 = MessageDigest.getInstance("SHA-256").digest(
                roomLifecycleContractPayload(
                    contractId = contractId,
                    roomHash = roomHash,
                    profileRevision = profileRevision,
                    messageExpiresAt = messageExpiresAt,
                    issuedAt = issuedAt,
                    expiresAt = expiresAt,
                    signingKeyId = signingKeyId,
                )
            ).toHex()
            val payload = roomLifecyclePayload(
                roomHash = roomHash,
                profileRevision = profileRevision,
                messageExpiresAt = messageExpiresAt,
                issuedAt = issuedAt,
                expiresAt = expiresAt,
                contractId = contractId,
                contractSha256 = contractSha256,
                signingKeyId = signingKeyId,
            )
            val signature = sign(signingKey, payload)
            server.enqueue(
                jsonResponse(
                    roomLifecycleJson(
                        roomHash = roomHash,
                        profileRevision = profileRevision,
                        messageExpiresAt = messageExpiresAt,
                        issuedAt = issuedAt,
                        expiresAt = expiresAt,
                        contractId = contractId,
                        contractSha256 = contractSha256,
                        signingKeyId = signingKeyId,
                        signature = signature,
                    )
                )
            )

            val snapshot = client(server, signingKey).resolveRoomLifecycle(roomId)

            assertThat(snapshot.profileId).isEqualTo("CONFIDENTIAL_7D")
            assertThat(snapshot.retentionMillis).isEqualTo(7L * 24 * 60 * 60_000)
            assertThat(snapshot.maximumOfflineMillis).isEqualTo(60L * 60_000)
            assertThat(snapshot.messageExpiresAtEpochMillis).isEqualTo(messageExpiresAt)
            assertThat(snapshot.protocolVersion).isEqualTo(3)
            assertThat(snapshot.contractId).isEqualTo(contractId)
            assertThat(snapshot.profileRevision).isEqualTo(profileRevision)
            assertThat(snapshot.contractSha256?.toHex()).isEqualTo(contractSha256)
            assertThat(snapshot.signingKeyId).isEqualTo(signingKeyId)
            assertThat(server.takeRequest().path).isEqualTo("/v1/message-lifecycle/rooms/resolve")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `signed store response is accepted and authorization is sent`() = runTest {
        val server = MockWebServer()
        val signingKey = signingKey()
        server.start()
        try {
            val request = storeRequest()
            val reference = "kms-ref-1"
            val storedAt = 3_000L
            val signature = sign(signingKey, storeResponsePayload(reference, request, storedAt))
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(storeResponseJson(reference, storedAt, signature))
                    .setHeader("Content-Type", "application/json")
            )
            val client = client(server, signingKey)

            assertThat(client.storeShare(request).value).isEqualTo(reference)
            val recorded = server.takeRequest()
            assertThat(recorded.path).isEqualTo("/v1/content-shares")
            assertThat(recorded.headers["Authorization"]).isEqualTo("Bearer short-lived-token")
            val body = recorded.body.readUtf8()
            assertThat(body).contains("\"room_id\":\"!stage-four:yizhe.test\"")
            assertThat(body).contains("\"audience_account_id_hashes\":[\"$ACCOUNT_HASH\",\"$SECOND_ACCOUNT_HASH\"]")
            assertThat(body).contains("\"authorized_installation_ids\":[\"installation-0001\",\"installation-0002\"]")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `room lifecycle contract is bound to protocol three store`() = runTest {
        val server = MockWebServer()
        val signingKey = signingKey()
        server.start()
        try {
            val roomId = "!stage-four:yizhe.test"
            val expiresAt = 2_592_010_000L
            val request = storeRequest().copy(
                policy = ContentPolicyReference(PolicyId("policy-1"), 11, expiresAt),
                roomLifecycle = ResolvedRoomLifecycle(
                    roomIdSha256 = MessageDigest.getInstance("SHA-256").digest(roomId.encodeToByteArray()),
                    profileId = "STANDARD_30D",
                    profileKind = RoomLifecycleProfileKind.STANDARD,
                    retentionMillis = 30L * 24 * 60 * 60_000,
                    maximumOfflineMillis = 60L * 60_000,
                    recoveryPolicy = RoomLifecycleRecoveryPolicy.OLD_DEVICE_OR_RECOVERY_KEY,
                    messageExpiresAtEpochMillis = expiresAt,
                    issuedAtEpochMillis = 10_000,
                    expiresAtEpochMillis = 310_000,
                    protocolVersion = 3,
                    contractId = "contract_012345678901234567890123",
                    profileRevision = 4,
                    contractSha256 = ByteArray(ContentProtectionFormat.SHA256_SIZE_BYTES) { 8 },
                    signingKeyId = "0123456789abcdef",
                ),
                clientCreatedAtEpochMillis = 10_001,
                matrixTransactionIdSha256 = ByteArray(ContentProtectionFormat.SHA256_SIZE_BYTES) { 6 },
            )
            val reference = "kms-ref-v3"
            val storedAt = 10_002L
            val signature = sign(signingKey, storeResponsePayload(reference, request, storedAt, protocolVersion = 3))
            server.enqueue(jsonResponse(storeResponseJson(reference, storedAt, signature, protocolVersion = 3)))

            assertThat(client(server, signingKey).storeShare(request).value).isEqualTo(reference)

            val body = server.takeRequest().body.readUtf8()
            assertThat(body).contains("\"protocol_version\":3")
            assertThat(body).contains("\"lifecycle_contract_id\":\"contract_012345678901234567890123\"")
            assertThat(body).contains("\"lifecycle_contract_sha256\":\"${"08".repeat(32)}\"")
            assertThat(body).contains("\"client_created_at_ms\":10001")
            assertThat(body).contains("\"matrix_transaction_id_sha256\":\"${"06".repeat(32)}\"")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `expired lifecycle contract is the only store conflict that requests one full refresh`() = runTest {
        val server = MockWebServer()
        val signingKey = signingKey()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(409)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """{"detail":{"code":"ROOM_LIFECYCLE_CONTRACT_EXPIRED","retryable_after_policy_refresh":true}}"""
                    )
            )

            val result = runCatchingExceptions {
                client(server, signingKey).storeShare(v3StoreRequest())
            }

            assertThat(result.exceptionOrNull())
                .isInstanceOf(ContentProtectionException.LifecycleContractRefreshRequired::class.java)
            assertThat(server.requestCount).isEqualTo(1)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `non retryable lifecycle conflict remains a closed KMS failure`() = runTest {
        val server = MockWebServer()
        val signingKey = signingKey()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(409)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """{"detail":{"code":"ROOM_LIFECYCLE_CONTRACT_MISMATCH","retryable_after_policy_refresh":false}}"""
                    )
            )

            val result = runCatchingExceptions { client(server, signingKey).storeShare(storeRequest()) }

            assertThat(result.exceptionOrNull()).isInstanceOf(ContentProtectionException.KmsUnavailable::class.java)
            assertThat(server.requestCount).isEqualTo(1)
        } finally {
            server.shutdown()
        }
    }

    private fun v3StoreRequest(): StoreKmsShareRequest {
        val roomId = "!stage-four:yizhe.test"
        val expiresAt = 2_592_010_000L
        return storeRequest().copy(
            policy = ContentPolicyReference(PolicyId("policy-1"), 11, expiresAt),
            roomLifecycle = ResolvedRoomLifecycle(
                roomIdSha256 = MessageDigest.getInstance("SHA-256").digest(roomId.encodeToByteArray()),
                profileId = "STANDARD_30D",
                profileKind = RoomLifecycleProfileKind.STANDARD,
                retentionMillis = 30L * 24 * 60 * 60_000,
                maximumOfflineMillis = 60L * 60_000,
                recoveryPolicy = RoomLifecycleRecoveryPolicy.OLD_DEVICE_OR_RECOVERY_KEY,
                messageExpiresAtEpochMillis = expiresAt,
                issuedAtEpochMillis = 10_000,
                expiresAtEpochMillis = 310_000,
                protocolVersion = 3,
                contractId = "contract_012345678901234567890124",
                profileRevision = 4,
                contractSha256 = ByteArray(ContentProtectionFormat.SHA256_SIZE_BYTES) { 8 },
                signingKeyId = "0123456789abcdef",
            ),
            clientCreatedAtEpochMillis = 10_001,
            matrixTransactionIdSha256 = ByteArray(ContentProtectionFormat.SHA256_SIZE_BYTES) { 6 },
        )
    }

    @Test
    fun `burn after read static location is accepted by the KMS request boundary`() = runTest {
        val server = MockWebServer()
        val signingKey = signingKey()
        server.start()
        try {
            val request = storeRequest().copy(
                kind = ContentKind.LOCATION,
                lifecycle = ContentLifecycle(MessageLifecycleMode.BURN_AFTER_READ, ConversationKind.DIRECT),
            )
            val reference = "kms-ref-location"
            val storedAt = 3_050L
            val signature = sign(signingKey, storeResponsePayload(reference, request, storedAt))
            server.enqueue(jsonResponse(storeResponseJson(reference, storedAt, signature)))

            assertThat(client(server, signingKey).storeShare(request).value).isEqualTo(reference)
            val body = server.takeRequest().body.readUtf8()
            assertThat(body).contains("\"content_kind\":\"LOCATION\"")
            assertThat(body).contains("\"lifecycle_mode\":\"BURN_AFTER_READ\"")
            assertThat(body).contains("\"conversation_kind\":\"DIRECT\"")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `burn after read ignores room lifecycle contract fields at the KMS store boundary`() = runTest {
        val server = MockWebServer()
        val signingKey = signingKey()
        server.start()
        try {
            val request = v3StoreRequest().copy(
                lifecycle = ContentLifecycle(MessageLifecycleMode.BURN_AFTER_READ, ConversationKind.DIRECT),
            )
            val reference = "kms-ref-burn-with-room-context"
            val storedAt = 3_060L
            val signature = sign(signingKey, storeResponsePayload(reference, request, storedAt))
            server.enqueue(jsonResponse(storeResponseJson(reference, storedAt, signature)))

            assertThat(client(server, signingKey).storeShare(request).value).isEqualTo(reference)

            val body = server.takeRequest().body.readUtf8()
            assertThat(body).contains("\"protocol_version\":2")
            assertThat(body).contains("\"lifecycle_mode\":\"BURN_AFTER_READ\"")
            assertThat(body).doesNotContain("lifecycle_contract_id")
            assertThat(body).doesNotContain("lifecycle_contract_sha256")
            assertThat(body).doesNotContain("client_created_at_ms")
            assertThat(body).doesNotContain("matrix_transaction_id_sha256")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `transient gateway failure retries store with a fresh authorization proof`() = runTest {
        val server = MockWebServer()
        val signingKey = signingKey()
        server.start()
        try {
            val request = storeRequest()
            val reference = "kms-ref-after-failover"
            val storedAt = 3_100L
            val signature = sign(signingKey, storeResponsePayload(reference, request, storedAt))
            server.enqueue(MockResponse().setResponseCode(502))
            server.enqueue(jsonResponse(storeResponseJson(reference, storedAt, signature)))
            var authorizationAttempt = 0
            val authorizationProvider = KmsAuthorizationProvider {
                authorizationAttempt += 1
                KmsRequestAuthorization.bearer("short-lived-token-$authorizationAttempt")
            }

            val storedReference = client(server, signingKey, authorizationProvider).storeShare(request)

            assertThat(storedReference.value).isEqualTo(reference)
            assertThat(server.requestCount).isEqualTo(2)
            assertThat(server.takeRequest().headers["Authorization"]).isEqualTo("Bearer short-lived-token-1")
            assertThat(server.takeRequest().headers["Authorization"]).isEqualTo("Bearer short-lived-token-2")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `unsigned KMS response fails closed`() = runTest {
        val server = MockWebServer()
        val signingKey = signingKey()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(storeResponseJson("kms-ref-1", 3_000L, byteArrayOf(1, 2, 3)))
                    .setHeader("Content-Type", "application/json")
            )

            val result = runCatchingExceptions { client(server, signingKey).storeShare(storeRequest()) }

            assertThat(result.exceptionOrNull()).isInstanceOf(ContentProtectionException.AuthenticationFailed::class.java)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `missing KMS configuration fails before network`() = runTest {
        val client = HttpKmsShareClient(
            configurationProvider = KmsClientConfigurationProvider { null },
            authorizationProvider = KmsAuthorizationProvider { KmsRequestAuthorization.bearer("token") },
            deviceShareKeyService = NoOpDeviceShareKeyService,
        )

        val result = runCatchingExceptions { client.storeShare(storeRequest()) }

        assertThat(result.exceptionOrNull()).isInstanceOf(ContentProtectionException.KmsUnavailable::class.java)
    }

    @Test
    fun `missing batch endpoint is a retryable KMS failure rather than content expiry`() = runTest {
        val server = MockWebServer()
        val signingKey = signingKey()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(404))

            val result = runCatchingExceptions {
                client(server, signingKey).releaseShares(listOf(releaseRequest()))
            }

            assertThat(result.exceptionOrNull()).isInstanceOf(ContentProtectionException.KmsUnavailable::class.java)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `pending Matrix event binding retries release with fresh authorization and then succeeds`() = runTest {
        val server = MockWebServer()
        val signingKey = signingKey()
        server.start()
        try {
            val request = releaseRequest()
            val response = ReleaseFixture(request, OfflineLeaseMode.ONLINE_REQUIRED)
            server.enqueue(
                MockResponse()
                    .setResponseCode(409)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """{"detail":{"code":"MATRIX_EVENT_BINDING_PENDING","retryable":true,"retry_after_millis":100}}"""
                    )
            )
            server.enqueue(jsonResponse("[${response.json(sign(signingKey, response.payload()))}]"))
            var authorizationAttempt = 0
            val authorizationProvider = KmsAuthorizationProvider {
                authorizationAttempt += 1
                KmsRequestAuthorization.bearer("short-lived-token-$authorizationAttempt")
            }

            val released = client(
                server = server,
                signingKey = signingKey,
                authorizationProvider = authorizationProvider,
                deviceShareKeyService = SuccessfulDeviceShareKeyService,
            ).releaseShares(listOf(request))

            assertThat(released.single().kmsShare).hasLength(ContentProtectionFormat.SHARE_SIZE_BYTES)
            assertThat(server.requestCount).isEqualTo(2)
            assertThat(server.takeRequest().headers["Authorization"]).isEqualTo("Bearer short-lived-token-1")
            assertThat(server.takeRequest().headers["Authorization"]).isEqualTo("Bearer short-lived-token-2")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `queued Matrix event binding can become active after the former retry window`() = runTest {
        val server = MockWebServer()
        val signingKey = signingKey()
        server.start()
        try {
            val request = releaseRequest()
            val response = ReleaseFixture(request, OfflineLeaseMode.ONLINE_REQUIRED)
            repeat(6) {
                server.enqueue(
                    MockResponse()
                        .setResponseCode(409)
                        .setHeader("Content-Type", "application/json")
                        .setBody(
                            """{"detail":{"code":"MATRIX_EVENT_BINDING_PENDING","retryable":true,"retry_after_millis":100}}"""
                        )
                )
            }
            server.enqueue(jsonResponse("[${response.json(sign(signingKey, response.payload()))}]"))

            val released = client(
                server = server,
                signingKey = signingKey,
                deviceShareKeyService = SuccessfulDeviceShareKeyService,
            ).releaseShares(listOf(request))

            assertThat(released.single().kmsShare).hasLength(ContentProtectionFormat.SHARE_SIZE_BYTES)
            assertThat(server.requestCount).isEqualTo(7)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `pending Matrix event binding remains fail closed after the bounded retry window`() = runTest {
        val server = MockWebServer()
        val signingKey = signingKey()
        server.start()
        try {
            repeat(13) {
                server.enqueue(
                    MockResponse()
                        .setResponseCode(409)
                        .setHeader("Content-Type", "application/json")
                        .setBody(
                            """{"detail":{"code":"MATRIX_EVENT_BINDING_PENDING","retryable":true,"retry_after_millis":100}}"""
                        )
                )
            }

            val result = runCatchingExceptions {
                client(server, signingKey).releaseShares(listOf(releaseRequest()))
            }

            assertThat(result.exceptionOrNull()).isInstanceOf(ContentProtectionException.KmsUnavailable::class.java)
            assertThat(server.requestCount).isEqualTo(13)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `unrelated release conflict is not retried`() = runTest {
        val server = MockWebServer()
        val signingKey = signingKey()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(409)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"detail":{"code":"CONTENT_BINDING_MISMATCH","retryable":true}}""")
            )

            val result = runCatchingExceptions {
                client(server, signingKey).releaseShares(listOf(releaseRequest()))
            }

            assertThat(result.exceptionOrNull()).isInstanceOf(ContentProtectionException.KmsUnavailable::class.java)
            assertThat(server.requestCount).isEqualTo(1)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `gone content share remains an expiry failure`() = runTest {
        val server = MockWebServer()
        val signingKey = signingKey()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(410))

            val result = runCatchingExceptions {
                client(server, signingKey).releaseShares(listOf(releaseRequest()))
            }

            assertThat(result.exceptionOrNull()).isInstanceOf(ContentProtectionException.Expired::class.java)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `confidential recovery lock is not presented as a network failure`() = runTest {
        val server = MockWebServer()
        val signingKey = signingKey()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(423))

            val result = runCatchingExceptions {
                client(server, signingKey).releaseShares(listOf(releaseRequest()))
            }

            assertThat(result.exceptionOrNull()).isInstanceOf(ContentProtectionException.RecoveryApprovalRequired::class.java)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `device recovery request and old-device confirmation stay account and device bound`() = runTest {
        val server = MockWebServer()
        val signingKey = signingKey()
        server.start()
        try {
            val recoveryId = "recovery-request-0001"
            val installationId = "installation-new-0001"
            server.enqueue(jsonResponse(recoveryStatusJson(recoveryId, installationId, "STANDARD_AUTHORIZED", false)))
            server.enqueue(jsonResponse(recoveryStatusJson(recoveryId, installationId, "STANDARD_AUTHORIZED", false)))
            server.enqueue(jsonResponse(recoveryStatusJson(recoveryId, installationId, "AWAITING_ADMIN_APPROVAL", true)))
            val client = client(server, signingKey)

            val started = client.startDeviceRecovery(StartDeviceRecoveryRequest(ACCOUNT_HASH, installationId))
            val observed = client.deviceRecoveryStatus(recoveryId)
            val confirmed = client.confirmDeviceRecovery(ConfirmDeviceRecoveryRequest(recoveryId, ACCOUNT_HASH))

            assertThat(started.newInstallationId).isEqualTo(installationId)
            assertThat(observed.state).isEqualTo(DeviceRecoveryState.STANDARD_AUTHORIZED)
            assertThat(confirmed.oldDeviceConfirmed).isTrue()
            assertThat(server.takeRequest().path).isEqualTo("/v1/device-recovery/start")
            assertThat(server.takeRequest().path).isEqualTo("/v1/device-recovery/$recoveryId")
            assertThat(server.takeRequest().path).isEqualTo("/v1/device-recovery/$recoveryId/confirm-old-device")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `device recovery rejects impossible terminal authorization flags`() = runTest {
        val server = MockWebServer()
        val signingKey = signingKey()
        server.start()
        try {
            val recoveryId = "recovery-request-0002"
            val installationId = "installation-new-0002"
            server.enqueue(
                jsonResponse(
                    recoveryStatusJson(recoveryId, installationId, "EXPIRED", true)
                        .replace("\"standard_authorized\":true", "\"standard_authorized\":false")
                )
            )

            val result = runCatching { client(server, signingKey).deviceRecoveryStatus(recoveryId) }

            assertThat(result.exceptionOrNull()).isInstanceOf(ContentProtectionException.AuthenticationFailed::class.java)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `device recovery fails closed when server claims erased key material is recoverable`() = runTest {
        val server = MockWebServer()
        val signingKey = signingKey()
        server.start()
        try {
            val recoveryId = "recovery-request-0003"
            val installationId = "installation-new-0003"
            server.enqueue(
                jsonResponse(
                    recoveryStatusJson(recoveryId, installationId, "STANDARD_AUTHORIZED", false)
                        .replace("\"restores_old_device_keys\":false", "\"restores_old_device_keys\":true")
                )
            )

            val result = runCatching { client(server, signingKey).deviceRecoveryStatus(recoveryId) }

            assertThat(result.exceptionOrNull()).isInstanceOf(ContentProtectionException.AuthenticationFailed::class.java)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `signed bounded offline lease remains device wrapped`() = runTest {
        val server = MockWebServer()
        val signingKey = signingKey()
        server.start()
        try {
            val request = releaseRequest()
            val response = ReleaseFixture(request)
            server.enqueue(jsonResponse(response.json(sign(signingKey, response.payload()))))

            val lease = client(server, signingKey).issueOfflineLease(request)

            assertThat(lease.mode).isEqualTo(OfflineLeaseMode.BOUNDED_OFFLINE)
            assertThat(lease.maximumUses).isEqualTo(3)
            assertThat(lease.encryptedKmsShare).isEqualTo(response.encryptedShare)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `signed revocation snapshot binds request nonce and permanent tombstones`() = runTest {
        val server = MockWebServer()
        val signingKey = signingKey()
        server.start()
        try {
            val request = FetchRevocationsRequest(
                accountIdHash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                installationId = "installation-1",
                knownSequence = 8,
                requestNonce = ByteArray(ContentProtectionFormat.REQUEST_NONCE_MIN_SIZE_BYTES) { 5 },
            )
            val response = RevocationFixture(request)
            server.enqueue(jsonResponse(response.json(sign(signingKey, response.payload()))))

            val snapshot = client(server, signingKey).fetchRevocations(request)

            assertThat(snapshot.sequence).isEqualTo(9)
            assertThat(snapshot.deviceAccessState).isEqualTo(RemoteDeviceAccessState.ACTIVE)
            assertThat(snapshot.revokedContentIds.map { it.value }).containsExactly("content-revoked")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `private resource rejects signed responses for another account nonce resource or expired time`() = runTest {
        val time = System.currentTimeMillis()
        val valid = """{"protocol_version":1,"account_id_hash":"$ACCOUNT_HASH","request_nonce_b64":"test-nonce","resource_id":"resource-test-0001","server_time_ms":$time,"revision":0,"deleted":false}"""
        val cases = listOf(
            valid to true,
            valid.replace(ACCOUNT_HASH, SECOND_ACCOUNT_HASH) to false,
            valid.replace("test-nonce", "other-nonce") to false,
            valid.replace("resource-test-0001", "resource-test-0002") to false,
            valid.replace(time.toString(), "1") to false,
        )
        for ((payloadText, accepted) in cases) {
            val server = MockWebServer()
            val key = signingKey()
            server.start()
            try {
                val payload = payloadText.encodeToByteArray()
                val keyId = MessageDigest.getInstance("SHA-256").digest(key.public.encoded).toHex().take(16)
                server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
                    """{"key_id":"$keyId","payload_b64":"${payload.base64()}","signature_b64":"${sign(key, payload).base64()}"}"""
                ))
                val result = runCatching { client(server, key).requestPrivateResource("fetch",
                    """{"account_id_hash":"$ACCOUNT_HASH","request_nonce_b64":"test-nonce","resource_id":"resource-test-0001"}""") }
                if (accepted) result.getOrThrow()
                assertThat(result.isSuccess).isEqualTo(accepted)
                if (!accepted) assertThat(result.exceptionOrNull()).isInstanceOf(ContentProtectionException.AuthenticationFailed::class.java)
            } finally { server.shutdown() }
        }
    }

    @Test
    fun `private resource rejects a signature from a different authority`() = runTest {
        val server = MockWebServer()
        val key = signingKey()
        val impostor = signingKey()
        server.start()
        try {
            val payload = "{}".encodeToByteArray()
            val keyId = MessageDigest.getInstance("SHA-256").digest(key.public.encoded).toHex().take(16)
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"key_id":"$keyId","payload_b64":"${payload.base64()}","signature_b64":"${sign(impostor, payload).base64()}"}"""
            ))
            val result = runCatching { client(server, key).requestPrivateResource("fetch", "{}") }
            assertThat(result.exceptionOrNull()).isInstanceOf(ContentProtectionException.AuthenticationFailed::class.java)
        } finally { server.shutdown() }
    }

    @Test
    fun `room epoch conflicts preserve membership reason without key release or transport retry`() = runTest {
        val cases = mapOf(
            "room membership changed; refresh recipients and retry" to ContentProtectionException.RoomEpochConflict.Reason.MEMBERSHIP_CHANGED,
            "trusted room device set changed; refresh recipients and retry" to ContentProtectionException.RoomEpochConflict.Reason.TRUSTED_DEVICES_CHANGED,
        )
        cases.forEach { (detail, expectedReason) ->
            val server = MockWebServer()
            val tree = RecordingTree()
            server.start()
            Timber.plant(tree)
            try {
                server.enqueue(MockResponse().setResponseCode(409).setHeader("Content-Type", "application/json")
                    .setBody("""{"detail":"$detail"}"""))
                val failure = runCatching { client(server, signingKey()).acquireRoomEpoch(acquireRoomEpochRequest()) }.exceptionOrNull()
                assertThat(failure).isInstanceOf(ContentProtectionException.RoomEpochConflict::class.java)
                assertThat((failure as ContentProtectionException.RoomEpochConflict).reason).isEqualTo(expectedReason)
                assertThat(server.requestCount).isEqualTo(1)
                val logs = tree.messages.joinToString("\n")
                assertThat(logs).contains("ROOM_EPOCH_${expectedReason.name} http_status=409")
                assertThat(logs).doesNotContain(detail)
                assertThat(logs).doesNotContain(ROOM_ID)
                assertThat(logs).doesNotContain(ACCOUNT_HASH)
            } finally {
                Timber.uproot(tree)
                server.shutdown()
            }
        }
    }

    @Test
    fun `unknown malformed and oversized epoch conflicts stay closed and redacted`() = runTest {
        val bodies = listOf(
            """{"detail":"private unknown conflict $ACCOUNT_HASH"}""",
            """{"detail":{"code":"ROOM_ACCESS_NOT_READY"}}""",
            "not-json-$INSTALLATION_ID",
            "",
            "x".repeat(4097),
        )
        bodies.forEach { body ->
            val server = MockWebServer()
            val tree = RecordingTree()
            server.start()
            Timber.plant(tree)
            try {
                server.enqueue(MockResponse().setResponseCode(409).setHeader("Content-Type", "application/json").setBody(body))
                val failure = runCatching { client(server, signingKey()).releaseRoomEpoch(releaseRoomEpochRequest()) }.exceptionOrNull()
                assertThat(failure).isInstanceOf(ContentProtectionException.RoomEpochConflict::class.java)
                assertThat((failure as ContentProtectionException.RoomEpochConflict).reason)
                    .isEqualTo(ContentProtectionException.RoomEpochConflict.Reason.UNKNOWN)
                assertThat(server.requestCount).isEqualTo(1)
                val logs = tree.messages.joinToString("\n")
                assertThat(logs).contains("ROOM_EPOCH_UNKNOWN http_status=409")
                if (body.isNotEmpty()) assertThat(logs).doesNotContain(body)
                assertThat(logs).doesNotContain(ACCOUNT_HASH)
                assertThat(logs).doesNotContain(INSTALLATION_ID)
            } finally {
                Timber.uproot(tree)
                server.shutdown()
            }
        }
    }

    @Test
    fun `combined private read verifies recipient and common-only five minute policy`() = runTest {
        for (variant in listOf("valid", "recipient", "installation", "favourite", "prepare", "overlong", "policy")) {
            val server = MockWebServer(); server.start()
            val key = signingKey()
            try {
                val time = System.currentTimeMillis()
                val kind = if (variant == "favourite") "FAVOURITE_REFERENCE" else "COMMON_INDEX"
                val duration = if (variant == "overlong") 300_001L else 300_000L
                val installation = if (variant == "installation") "foreign-installation-0001" else "installation-private-profile-0001"
                val recipient = if (variant == "recipient") "foreign-key-0001" else "private-profile-key-0001"
                val policy = if (variant == "policy") "unrecognized-policy" else "account-private-common-online-v2"
                val payload = """{"protocol_version":1,"account_id_hash":"$ACCOUNT_HASH","request_nonce_b64":"test-nonce","resource_id":"resource-test-0001","server_time_ms":$time,"revision":1,"deleted":false,"version_id":"resource-version-0001","resource_kind":"$kind","installation_id":"$installation","recipient_key_id":"$recipient","grant_issued_at_ms":$time,"expires_at_ms":${time + duration},"policy_id":"$policy","encrypted_key_b64":"dGVzdA=="}""".encodeToByteArray()
                val keyId = MessageDigest.getInstance("SHA-256").digest(key.public.encoded).toHex().take(16)
                server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
                    """{"key_id":"$keyId","payload_b64":"${payload.base64()}","signature_b64":"${sign(key, payload).base64()}"}"""))
                val action = if (variant == "prepare") "prepare" else "open"
                val result = runCatching { client(server, key, deviceShareKeyService = PrivateProfileDeviceShareKeyService).requestPrivateResource(action,
                    """{"account_id_hash":"$ACCOUNT_HASH","request_nonce_b64":"test-nonce","resource_id":"resource-test-0001","resource_kind":"$kind"}""") }
                assertThat(result.isSuccess).isEqualTo(variant == "valid")
            } finally { server.shutdown() }
        }
    }

    @Test
    fun `private resource recovery denial keeps the existing recovery failure type`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(403).setBody("""{"detail":"DEVICE_RECOVERY_REQUIRED"}"""))
            val result = runCatching { client(server, signingKey()).requestPrivateResource("fetch", "{}") }
            assertThat(result.exceptionOrNull()).isInstanceOf(ContentProtectionException.DeviceRecoveryRequired::class.java)
        } finally { server.shutdown() }
    }

    @Test
    fun `existing profile release verifies signature nonce installation and key binding`() = runTest {
        listOf("valid", "nonce", "installation", "key", "account", "time", "signature").forEach { variant ->
            val server = MockWebServer()
            val signingKey = signingKey()
            var unwraps = 0
            val shareService = object : DeviceShareKeyService by PrivateProfileDeviceShareKeyService {
                override suspend fun descriptor() = PrivateProfileDeviceShareKeyService.descriptor().copy(keyId = "c".repeat(64))
                override suspend fun unwrapShare(envelope: DeviceShareEnvelope, contentId: ContentId,
                    immutableContextSha256: ByteArray): ByteArray {
                    unwraps += 1
                    return ByteArray(32) { 8 }
                }
            }
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    assertThat(request.path).isEqualTo("/v1/private-profile/key/release")
                    val fields = kotlinx.serialization.json.Json.parseToJsonElement(request.body.readUtf8())
                        as kotlinx.serialization.json.JsonObject
                    assertThat(fields.containsKey("proposed_key_b64")).isFalse()
                    val nonce = fields.getValue("request_nonce_b64").toString().trim('"')
                    return jsonResponse(existingProfileReleaseJson(signingKey, nonce, variant))
                }
            }
            server.start()
            try {
                val result = runCatching { client(server, signingKey, deviceShareKeyService = shareService)
                    .releasePrivateProfileKey(ACCOUNT_HASH) }
                if (variant == "valid") {
                    assertThat(result.getOrThrow().keyVersion).isEqualTo(4L)
                    result.getOrThrow().keyMaterial.fill(0)
                    assertThat(unwraps).isEqualTo(1)
                } else {
                    assertThat(result.exceptionOrNull()).isInstanceOf(ContentProtectionException.AuthenticationFailed::class.java)
                    assertThat(unwraps).isEqualTo(0)
                }
            } finally { server.shutdown() }
        }
    }

    @Test
    fun `only exact 403 recovery detail enters release recovery`() = runTest {
        listOf(403 to """{"detail":"DEVICE_RECOVERY_REQUIRED"}""",
            401 to """{"detail":"DEVICE_RECOVERY_REQUIRED"}""",
            403 to """{"detail":"device revoked"}""",
            403 to """{"detail":{"code":"DEVICE_RECOVERY_REQUIRED"}}""",
            403 to "not-json", 404 to """{"detail":"private profile key does not exist"}""").forEach { (status, body) ->
            val server = MockWebServer()
            server.start()
            try {
                server.enqueue(MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body))
                val result = runCatching { client(server, signingKey(), deviceShareKeyService = PrivateProfileDeviceShareKeyService)
                    .releasePrivateProfileKey(ACCOUNT_HASH) }
                val expected = when {
                    status == 403 && body == """{"detail":"DEVICE_RECOVERY_REQUIRED"}""" -> ContentProtectionException.DeviceRecoveryRequired::class.java
                    status == 404 -> ContentProtectionException.KmsUnavailable::class.java
                    else -> ContentProtectionException.AccessDenied::class.java
                }
                assertThat(result.exceptionOrNull()).isInstanceOf(expected)
                assertThat(server.requestCount).isEqualTo(1)
            } finally { server.shutdown() }
        }
    }

    @Test
    fun `only exact bounded release conflict is a revalidation hint and never retried by HTTP client`() = runTest {
        val code = "RECOVERY_RELEASE_AUTHORITY_CHANGED"
        listOf(409 to """{"detail":"$code"}""", 403 to """{"detail":"$code"}""",
            409 to """{"detail":"RECOVERY_RELEASE_GENERATION_CHANGED"}""", 409 to "not-json",
            409 to """{"detail":{"code":"$code"}}""", 409 to ("""{"detail":"$code"}""" + " ".repeat(20_000)))
            .forEachIndexed { index, (status, body) ->
                val server = MockWebServer()
                server.start()
                try {
                    server.enqueue(MockResponse().setResponseCode(status).setBody(body))
                    val result = runCatching { client(server, signingKey(), deviceShareKeyService = PrivateProfileDeviceShareKeyService)
                        .releasePrivateProfileKey(ACCOUNT_HASH) }
                    if (index == 0) assertThat(result.exceptionOrNull()).isInstanceOf(
                        io.element.android.security.contentprotection.api.RecoveryReleaseAuthorityChanged::class.java)
                    else assertThat(result.exceptionOrNull()).isInstanceOf(ContentProtectionException::class.java)
                    assertThat(server.requestCount).isEqualTo(1)
                } finally { server.shutdown() }
            }
    }

    private fun existingProfileReleaseJson(signingKey: KeyPair, requestNonce: String, variant: String): String {
        val account = if (variant == "account") "f".repeat(64) else ACCOUNT_HASH
        val installation = if (variant == "installation") "another-installation-0001" else "installation-private-profile-0001"
        val keyId = if (variant == "key") "d".repeat(64) else "c".repeat(64)
        val nonce = if (variant == "nonce") ByteArray(24) { 1 }.base64() else requestNonce
        val serverTime = System.currentTimeMillis() - if (variant == "time") 600_000L else 0L
        val hashBytes = ByteArray(32) { 0xaa.toByte() }
        val context = MessageDigest.getInstance("SHA-256").digest(
            "yizhe-account-private-profile-v1\u0000".encodeToByteArray() + hashBytes +
                java.nio.ByteBuffer.allocate(8).putLong(4).array(),
        ).toHex()
        val encrypted = byteArrayOf(1, 2, 3).base64()
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.write("YZPKR001".encodeToByteArray())
            out.writeInt(1)
            out.writeString(account)
            out.writeLong(4)
            out.writeString(installation)
            out.writeString(keyId)
            out.writeString(ContentProtectionFormat.DEVICE_SHARE_ALGORITHM)
            out.writeString(context)
            out.writeString(nonce)
            out.writeString(encrypted)
            out.writeLong(serverTime)
        }
        val signature = sign(signingKey, bytes.toByteArray()).also { if (variant == "signature") it[0] = 0 }.base64()
        return """{"protocol_version":1,"account_id_hash":"$account","key_version":4,
            "installation_id":"$installation","recipient_key_id":"$keyId",
            "wrapping_algorithm":"${ContentProtectionFormat.DEVICE_SHARE_ALGORITHM}",
            "immutable_context_sha256":"$context","request_nonce_b64":"$nonce",
            "encrypted_profile_key_b64":"$encrypted","server_time_ms":$serverTime,
            "server_signature_b64":"$signature"}"""
    }

    private fun client(
        server: MockWebServer,
        signingKey: KeyPair,
        authorizationProvider: KmsAuthorizationProvider = KmsAuthorizationProvider {
            KmsRequestAuthorization.bearer("short-lived-token")
        },
        deviceShareKeyService: DeviceShareKeyService = NoOpDeviceShareKeyService,
    ): HttpKmsShareClient = HttpKmsShareClient(
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
        authorizationProvider = authorizationProvider,
        deviceShareKeyService = deviceShareKeyService,
    )

    private fun storeRequest() = StoreKmsShareRequest(
        contentId = ContentId("content-1"),
        roomId = "!stage-four:yizhe.test",
        kind = ContentKind.TEXT,
        policy = ContentPolicyReference(PolicyId("policy-1"), 1, 10_000),
        accountIdHash = ACCOUNT_HASH,
        senderInstallationId = "installation-0001",
        audienceAccountIdHashes = setOf(SECOND_ACCOUNT_HASH, ACCOUNT_HASH),
        authorizedInstallationIds = setOf("installation-0002", "installation-0001"),
        immutableContextSha256 = ByteArray(ContentProtectionFormat.SHA256_SIZE_BYTES) { 7 },
        kmsShare = ByteArray(ContentProtectionFormat.SHARE_SIZE_BYTES) { 9 },
        leaseMode = OfflineLeaseMode.ONLINE_REQUIRED,
    )

    private fun acquireRoomEpochRequest() = AcquireRoomEpochRequest(
        roomId = ROOM_ID,
        roomIdSha256 = ByteArray(ContentProtectionFormat.SHA256_SIZE_BYTES) { 1 },
        accountIdHash = ACCOUNT_HASH,
        audienceAccountIdHashes = setOf(ACCOUNT_HASH),
        authorizedInstallationIds = setOf(INSTALLATION_ID),
        requestingInstallationId = INSTALLATION_ID,
        requestingKeyId = "room-epoch-key-1",
        requestNonce = ByteArray(ContentProtectionFormat.REQUEST_NONCE_MIN_SIZE_BYTES) { 2 },
    )

    private fun releaseRoomEpochRequest() = ReleaseRoomEpochRequest(
        reference = RoomEpochReference(
            epochId = "room-epoch-1",
            generation = 1,
            audienceDigestSha256 = ByteArray(ContentProtectionFormat.SHA256_SIZE_BYTES) { 3 },
        ),
        roomIdSha256 = ByteArray(ContentProtectionFormat.SHA256_SIZE_BYTES) { 1 },
        accountIdHash = ACCOUNT_HASH,
        requestingInstallationId = INSTALLATION_ID,
        requestingKeyId = "room-epoch-key-1",
        requestNonce = ByteArray(ContentProtectionFormat.REQUEST_NONCE_MIN_SIZE_BYTES) { 2 },
    )

    private fun releaseRequest() = ReleaseKmsShareRequest(
        reference = io.element.android.security.contentprotection.api.KmsShareReference("kms-ref-1"),
        contentId = ContentId("content-1"),
        policy = ContentPolicyReference(PolicyId("policy-1"), 1, 10_000),
        accountIdHash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        requestingInstallationId = "installation-1",
        requestingKeyId = "key-1",
        immutableContextSha256 = ByteArray(ContentProtectionFormat.SHA256_SIZE_BYTES) { 7 },
        requestNonce = ByteArray(ContentProtectionFormat.REQUEST_NONCE_MIN_SIZE_BYTES) { 4 },
        bootCount = 7,
    )

    private fun storeResponseJson(reference: String, storedAt: Long, signature: ByteArray, protocolVersion: Int = 2): String =
        """{"protocol_version":$protocolVersion,"reference":"$reference","stored_at_ms":$storedAt,"server_signature_b64":"${signature.base64()}"}"""

    private fun roomLifecyclePayload(
        roomHash: String,
        profileRevision: Long,
        messageExpiresAt: Long,
        issuedAt: Long,
        expiresAt: Long,
        contractId: String,
        contractSha256: String,
        signingKeyId: String,
    ): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write("YZRL0003".encodeToByteArray())
                output.writeInt(3)
                output.writeString(roomHash)
                output.writeString("CONFIDENTIAL_7D")
                output.writeString("CONFIDENTIAL")
                output.writeLong(7L * 24 * 60 * 60_000)
                output.writeLong(60L * 60_000)
                output.writeString("OLD_DEVICE_AND_ADMIN_APPROVAL")
                output.writeLong(messageExpiresAt)
                output.writeLong(issuedAt)
                output.writeLong(expiresAt)
                output.writeString(contractId)
                output.writeLong(profileRevision)
                output.writeString(contractSha256)
                output.writeString(signingKeyId)
            }
            bytes.toByteArray()
        }

    private fun redactionReasonPayload(
        reason: String,
        receiptId: String,
        eventHash: String,
        issuedAt: Long,
        keyId: String,
    ): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.write("YZREASON1\u0000".encodeToByteArray())
            output.writeInt(1)
            output.writeString(reason)
            output.writeString(receiptId)
            output.writeString(eventHash)
            output.writeLong(issuedAt)
            output.writeString(keyId)
        }
        bytes.toByteArray()
    }

    private fun roomLifecycleContractPayload(
        contractId: String,
        roomHash: String,
        profileRevision: Long,
        messageExpiresAt: Long,
        issuedAt: Long,
        expiresAt: Long,
        signingKeyId: String,
    ): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.write("YZRC0003".encodeToByteArray())
            output.writeString(contractId)
            output.writeString(roomHash)
            output.writeString("CONFIDENTIAL_7D")
            output.writeString("CONFIDENTIAL")
            output.writeLong(profileRevision)
            output.writeLong(7L * 24 * 60 * 60_000)
            output.writeLong(60L * 60_000)
            output.writeString("OLD_DEVICE_AND_ADMIN_APPROVAL")
            output.writeLong(messageExpiresAt)
            output.writeLong(issuedAt)
            output.writeLong(expiresAt)
            output.writeString(signingKeyId)
        }
        bytes.toByteArray()
    }

    private fun roomLifecycleJson(
        roomHash: String,
        profileRevision: Long,
        messageExpiresAt: Long,
        issuedAt: Long,
        expiresAt: Long,
        contractId: String,
        contractSha256: String,
        signingKeyId: String,
        signature: ByteArray,
    ): String = """{
        "protocol_version":3,
        "room_id_sha256":"$roomHash",
        "profile_id":"CONFIDENTIAL_7D",
        "profile_kind":"CONFIDENTIAL",
        "retention_millis":${7L * 24 * 60 * 60_000},
        "maximum_offline_millis":${60L * 60_000},
        "recovery_policy":"OLD_DEVICE_AND_ADMIN_APPROVAL",
        "message_expires_at_ms":$messageExpiresAt,
        "issued_at_ms":$issuedAt,
        "expires_at_ms":$expiresAt,
        "contract_id":"$contractId",
        "profile_revision":$profileRevision,
        "contract_sha256":"$contractSha256",
        "signing_key_id":"$signingKeyId",
        "server_signature_b64":"${signature.base64()}"
    }""".trimIndent()

    private fun recoveryStatusJson(
        recoveryId: String,
        installationId: String,
        state: String,
        oldDeviceConfirmed: Boolean,
    ): String = """{
        "recovery_id":"$recoveryId",
        "account_id_hash":"$ACCOUNT_HASH",
        "new_installation_id":"$installationId",
        "state":"$state",
        "standard_authorized":true,
        "old_device_confirmed":$oldDeviceConfirmed,
        "admin_approved":false,
        "confidential_authorized":false,
        "request_expires_at_ms":900000,
        "restores_old_device_keys":false,
        "burn_after_read_recoverable":false
    }""".trimIndent()

    private fun storeResponsePayload(
        reference: String,
        request: StoreKmsShareRequest,
        storedAt: Long,
        protocolVersion: Int = 2,
    ): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write("YZKS0001".encodeToByteArray())
                output.writeInt(protocolVersion)
                output.writeString(reference)
                output.writeString(request.contentId.value)
                output.writeInt(request.immutableContextSha256.size)
                output.write(request.immutableContextSha256)
                output.writeLong(storedAt)
            }
            bytes.toByteArray()
        }

    private fun DataOutputStream.writeString(value: String) {
        val encoded = value.encodeToByteArray()
        writeInt(encoded.size)
        write(encoded)
    }

    private fun DataOutputStream.writeStrings(values: List<String>) {
        writeInt(values.size)
        values.sorted().forEach { writeString(it) }
    }

    private fun jsonResponse(body: String) = MockResponse()
        .setResponseCode(200)
        .setBody(body)
        .setHeader("Content-Type", "application/json")

    private inner class ReleaseFixture(
        val request: ReleaseKmsShareRequest,
        val mode: OfflineLeaseMode = OfflineLeaseMode.BOUNDED_OFFLINE,
    ) {
        val encryptedShare = ByteArray(384) { (it % 251).toByte() }

        fun payload(): ByteArray = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write("YZKR0001".encodeToByteArray())
                output.writeInt(2)
                output.writeString("lease-1")
                output.writeString(request.reference.value)
                output.writeString(request.contentId.value)
                output.writeString(request.accountIdHash)
                output.writeLong(request.policy.policyVersion)
                output.writeString(request.requestingInstallationId)
                output.writeString(request.requestingKeyId)
                output.writeString(ContentProtectionFormat.DEVICE_SHARE_ALGORITHM)
                output.writeString(request.immutableContextSha256.base64())
                output.writeString(request.requestNonce.base64())
                output.writeString(mode.name)
                output.writeLong(request.bootCount)
                output.writeLong(1_000)
                output.writeLong(9_000)
                output.writeLong(2_000)
                output.writeLong(if (mode == OfflineLeaseMode.ONLINE_REQUIRED) 9_000 else 8_000)
                output.writeLong(5)
                output.writeInt(if (mode == OfflineLeaseMode.ONLINE_REQUIRED) 1 else 3)
                output.writeString(encryptedShare.base64())
            }
            bytes.toByteArray()
        }

        fun json(signature: ByteArray): String = """{
            "protocol_version":2,
            "grant_id":"lease-1",
            "reference":"${request.reference.value}",
            "content_id":"${request.contentId.value}",
            "account_id_hash":"${request.accountIdHash}",
            "policy_version":${request.policy.policyVersion},
            "installation_id":"${request.requestingInstallationId}",
            "recipient_key_id":"${request.requestingKeyId}",
            "wrapping_algorithm":"${ContentProtectionFormat.DEVICE_SHARE_ALGORITHM}",
            "context_sha256":"${request.immutableContextSha256.base64()}",
            "request_nonce_b64":"${request.requestNonce.base64()}",
            "lease_mode":"${mode.name}",
            "boot_count":${request.bootCount},
            "issued_at_ms":1000,
            "expires_at_ms":9000,
            "server_time_ms":2000,
            "refresh_after_ms":${if (mode == OfflineLeaseMode.ONLINE_REQUIRED) 9000 else 8000},
            "revocation_sequence":5,
            "maximum_uses":${if (mode == OfflineLeaseMode.ONLINE_REQUIRED) 1 else 3},
            "encrypted_kms_share_b64":"${encryptedShare.base64()}",
            "server_signature_b64":"${signature.base64()}"
        }""".trimIndent()
    }

    private inner class RevocationFixture(val request: FetchRevocationsRequest) {
        private val revokedLeases = listOf("lease-revoked")
        private val revokedContent = listOf("content-revoked")

        fun payload(): ByteArray = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write("YZKV0001".encodeToByteArray())
                output.writeInt(2)
                output.writeString(request.accountIdHash)
                output.writeString(request.installationId)
                output.writeLong(9)
                output.writeLong(2_000)
                output.writeLong(3_000)
                output.writeLong(4_000)
                output.writeString(request.requestNonce.base64())
                output.writeString(RemoteDeviceAccessState.ACTIVE.name)
                output.writeStrings(revokedLeases)
                output.writeStrings(revokedContent)
            }
            bytes.toByteArray()
        }

        fun json(signature: ByteArray): String = """{
            "protocol_version":2,
            "account_id_hash":"${request.accountIdHash}",
            "installation_id":"${request.installationId}",
            "sequence":9,
            "issued_at_ms":2000,
            "refresh_after_ms":3000,
            "expires_at_ms":4000,
            "request_nonce_b64":"${request.requestNonce.base64()}",
            "device_access_state":"${RemoteDeviceAccessState.ACTIVE.name}",
            "revoked_lease_ids":["lease-revoked"],
            "revoked_content_ids":["content-revoked"],
            "server_signature_b64":"${signature.base64()}"
        }""".trimIndent()
    }

    private fun sign(keyPair: KeyPair, payload: ByteArray): ByteArray = Signature.getInstance(ContentProtectionFormat.KMS_SIGNATURE_ALGORITHM).run {
        initSign(keyPair.private)
        update(payload)
        sign()
    }

    private fun signingKey(): KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(256)
        generateKeyPair()
    }

    private fun ByteArray.base64(): String = Base64.getEncoder().encodeToString(this)

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }

    private data object NoOpDeviceShareKeyService : DeviceShareKeyService {
        override suspend fun descriptor(): DeviceShareKeyDescriptor = error("Not used")

        override suspend fun wrapShare(
            recipient: DeviceShareRecipient,
            contentId: ContentId,
            immutableContextSha256: ByteArray,
            share: ByteArray,
        ): DeviceShareEnvelope = error("Not used")

        override suspend fun unwrapShare(
            envelope: DeviceShareEnvelope,
            contentId: ContentId,
            immutableContextSha256: ByteArray,
        ): ByteArray = error("Not used")

        override suspend fun destroy(): Boolean = true
    }

    private data object SuccessfulDeviceShareKeyService : DeviceShareKeyService {
        override suspend fun descriptor(): DeviceShareKeyDescriptor = error("Not used")

        override suspend fun wrapShare(
            recipient: DeviceShareRecipient,
            contentId: ContentId,
            immutableContextSha256: ByteArray,
            share: ByteArray,
        ): DeviceShareEnvelope = error("Not used")

        override suspend fun unwrapShare(
            envelope: DeviceShareEnvelope,
            contentId: ContentId,
            immutableContextSha256: ByteArray,
        ): ByteArray = ByteArray(ContentProtectionFormat.SHARE_SIZE_BYTES) { 9 }

        override suspend fun destroy(): Boolean = true
    }

    private data object PrivateProfileDeviceShareKeyService : DeviceShareKeyService {
        override suspend fun descriptor(): DeviceShareKeyDescriptor = DeviceShareKeyDescriptor(
            installationId = "installation-private-profile-0001",
            keyId = "private-profile-key-0001",
            algorithm = ContentProtectionFormat.DEVICE_SHARE_ALGORITHM,
            publicKeyDer = byteArrayOf(1),
            hardwareSecurityLevel = HardwareSecurityLevel.STRONGBOX,
        )

        override suspend fun wrapShare(
            recipient: DeviceShareRecipient,
            contentId: ContentId,
            immutableContextSha256: ByteArray,
            share: ByteArray,
        ): DeviceShareEnvelope = error("Not used")

        override suspend fun unwrapShare(
            envelope: DeviceShareEnvelope,
            contentId: ContentId,
            immutableContextSha256: ByteArray,
        ): ByteArray = error("Not used")

        override suspend fun destroy(): Boolean = true
    }

    private class RecordingTree : Timber.Tree() {
        val messages = mutableListOf<String>()

        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            messages += message
        }
    }

    private companion object {
        const val ROOM_ID = "!room-denial:yizhe.test"
        const val INSTALLATION_ID = "installation-0001"
        const val ACCOUNT_HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val SECOND_ACCOUNT_HASH = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
