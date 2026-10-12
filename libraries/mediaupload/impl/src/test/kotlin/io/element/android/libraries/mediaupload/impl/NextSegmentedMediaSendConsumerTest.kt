/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.libraries.mediaupload.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.TransactionId
import io.element.android.libraries.matrix.api.timeline.QueuedEncryptedRoomMessageReceipt
import io.element.android.libraries.matrix.api.timeline.QueuedEncryptedRoomMessageSender
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.mediaupload.api.PreparedSegmentedMediaSend
import io.element.android.libraries.mediaupload.api.SegmentedMediaSendOutcome
import io.element.android.security.contentprotection.api.*
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class NextSegmentedMediaSendConsumerTest {
    @Test fun `fresh authority is fenced before exact stable transaction enqueue`() = runTest {
        val fixture = SignedContractFixture(); val journal = fixture.journal(); val queue = RecordingQueue()
        val result = fixture.sender(journal, queue).send(mockk(), fixture.roomId, fixture.request)
        assertThat(result.getOrThrow()).isEqualTo(SegmentedMediaSendOutcome.SentPendingObservation(fixture.transactionId, EventId("\$event-001")))
        assertThat(queue.calls).containsExactly(fixture.transactionId to fixture.eventContent)
        assertThat(journal.states).containsExactly(
            MediaSendJournalState.PREPARED, MediaSendJournalState.ALLOW_VERIFIED,
            MediaSendJournalState.SEND_BOUND_VERIFIED, MediaSendJournalState.MATRIX_IN_FLIGHT,
            MediaSendJournalState.MATRIX_ACCEPTED,
        ).inOrder()
    }

    @Test fun `missing Matrix queue fails before authority or journal mutation`() = runTest {
        val fixture = SignedContractFixture(); val authority = RecordingAuthority(fixture.allowEnvelope, fixture.boundEnvelope)
        val journal = fixture.journal()
        val result = fixture.sender(journal, null, authority).send(mockk(), fixture.roomId, fixture.request)
        assertThat((result.exceptionOrNull() as NextSegmentedMediaSendUnavailableException).code)
            .isEqualTo(ContractBoundNextSegmentedMediaSender.MATRIX_QUEUE_UNAVAILABLE)
        assertThat(authority.calls).isEmpty(); assertThat(journal.states).containsExactly(MediaSendJournalState.PREPARED)
    }

    @Test fun `tampered ALLOW and SEND_BOUND never reach Matrix`() = runTest {
        val fixture = SignedContractFixture(); val q1 = RecordingQueue()
        val badAllow = fixture.allowEnvelope.decodeToString().replace("\"decision\":\"ALLOW\"", "\"decision\":\"DENY\"").encodeToByteArray()
        assertThat(fixture.sender(fixture.journal(), q1, RecordingAuthority(badAllow, fixture.boundEnvelope))
            .send(mockk(), fixture.roomId, fixture.request).isFailure).isTrue(); assertThat(q1.calls).isEmpty()
        val q2 = RecordingQueue(); val badBound = fixture.boundEnvelope.decodeToString().replace("txn-001", "txn-other").encodeToByteArray()
        assertThat(fixture.sender(fixture.journal(), q2, RecordingAuthority(fixture.allowEnvelope, badBound))
            .send(mockk(), fixture.roomId, fixture.request).isFailure).isTrue(); assertThat(q2.calls).isEmpty()
    }

    @Test fun `queue ambiguity persists UNCERTAIN and never retries`() = runTest {
        val fixture = SignedContractFixture(); val journal = fixture.journal()
        val queue = RecordingQueue(Result.failure(IllegalStateException("timeout")))
        val result = fixture.sender(journal, queue).send(mockk(), fixture.roomId, fixture.request).getOrThrow()
        assertThat(result).isEqualTo(SegmentedMediaSendOutcome.Uncertain(fixture.transactionId))
        assertThat(queue.calls).hasSize(1); assertThat(journal.states.last()).isEqualTo(MediaSendJournalState.MATRIX_UNCERTAIN)
    }

    @Test fun `post-fence live identity drift rejects before Matrix`() = runTest {
        val fixture = SignedContractFixture(); val queue = RecordingQueue()
        val result = fixture.sender(fixture.journal(), queue, identities = listOf(fixture.liveIdentity, fixture.liveIdentity.copy(matrixDeviceId = "DEVICE_B")))
            .send(mockk(), fixture.roomId, fixture.request)
        assertThat(result.isFailure).isTrue(); assertThat(queue.calls).isEmpty()
    }

    @Test fun `crash fenced operation is reconcile-only and performs zero enqueue`() = runTest {
        val fixture = SignedContractFixture(); val queue = RecordingQueue(); val authority = RecordingAuthority(fixture.allowEnvelope, fixture.boundEnvelope)
        val timeline = mockk<Timeline>(); every { timeline.timelineItems } returns flowOf(emptyList())
        val result = fixture.sender(fixture.journal(MediaSendJournalState.MATRIX_IN_FLIGHT), queue, authority)
            .send(timeline, fixture.roomId, fixture.request).getOrThrow()
        assertThat(result).isEqualTo(SegmentedMediaSendOutcome.Uncertain(fixture.transactionId))
        assertThat(authority.calls).containsExactly("recover"); assertThat(queue.calls).isEmpty()
    }

    @Test fun `matching transaction and event id cannot prove recovery acceptance`() = runTest {
        val fixture = SignedContractFixture(); val queue = RecordingQueue(); val journal = fixture.journal(MediaSendJournalState.MATRIX_IN_FLIGHT)
        val observed = mockk<MatrixTimelineItem.Event>()
        every { observed.transactionId } returns fixture.transactionId
        every { observed.eventId } returns EventId("\$event-001")
        val timeline = mockk<Timeline>(); every { timeline.timelineItems } returns flowOf(listOf(observed))
        val result = fixture.sender(journal, queue).send(timeline, fixture.roomId, fixture.request).getOrThrow()
        assertThat(result).isEqualTo(SegmentedMediaSendOutcome.Uncertain(fixture.transactionId))
        assertThat(journal.states).containsExactly(MediaSendJournalState.MATRIX_IN_FLIGHT)
        assertThat(queue.calls).isEmpty()
    }

    @Test fun `unavailable explicit implementation remains fail closed`() = runTest {
        val fixture = SignedContractFixture(); val result = UnavailableNextSegmentedMediaSender().send(mockk(), fixture.roomId, fixture.request)
        assertThat((result.exceptionOrNull() as NextSegmentedMediaSendUnavailableException).code)
            .isEqualTo("WP08_PRODUCTION_PREREQUISITES_UNAVAILABLE")
    }
}

internal class SignedContractFixture {
    val operationId = "op-001"; val requestId = "request-001"; val transactionId = TransactionId("txn-001")
    val roomId = "!room:example.org"; val nonce = "nonce-001"; val nowMs = 1_790_210_000_002L
    private val allowKeyPair = keyPair(); private val boundKeyPair = keyPair()
    private val allowKeyId = sha256Hex(allowKeyPair.public.encoded).take(16); private val boundKeyId = sha256Hex(boundKeyPair.public.encoded).take(16)
    val registry = VerifiedMediaSendIssuerRegistry(1, nowMs - 10_000, nowMs + 60_000, "8".repeat(64), listOf(
        issuerKey(allowKeyId, allowKeyPair, MediaSendIssuerKeyStatus.RETAINED), issuerKey(boundKeyId, boundKeyPair, MediaSendIssuerKeyStatus.ACTIVE)))
    private val binding = buildString {
        append("{\"accountIdHash\":\"${"d".repeat(64)}\",\"assetId\":\"asset-001\"")
        append(",\"authorityRevision\":{\"deviceTrustGeneration\":7,\"kmsFenceGeneration\":11,\"resourceRootGeneration\":2,\"roomEpoch\":9}")
        append(",\"contentContractId\":\"content-v3\",\"contract\":\"next.segmented-media-binding.v1\",\"deviceTrustGeneration\":7")
        append(",\"effectiveAtMs\":${nowMs - 2},\"expiresAtMs\":${nowMs + 30_000},\"installationId\":\"install-001\"")
        append(",\"manifestDigestSha256\":\"${"b".repeat(64)}\",\"matrixDeviceId\":\"DEVICE_A\",\"matrixUserId\":\"@alice:example.org\"")
        append(",\"mediaVersion\":1,\"nonce\":\"$nonce\",\"operationId\":\"$operationId\",\"pagesDigestSha256\":\"${"c".repeat(64)}\"")
        append(",\"policyRevision\":{\"digestSha256\":\"${"e".repeat(64)}\",\"domain\":\"GLOBAL_POLICY\",\"revisionId\":\"rev-001\",\"version\":4}")
        append(",\"requestId\":\"$requestId\",\"resourceSetDigestSha256\":\"${"a".repeat(64)}\",\"roomEpoch\":9")
        append(",\"roomId\":\"$roomId\",\"roomIdHash\":\"${sha256Hex(roomId.encodeToByteArray())}\"}")
    }
    private val fingerprint = sha256Hex(binding.encodeToByteArray())
    private val allowPayload = "{\"binding\":$binding,\"bindingFingerprintSha256\":\"$fingerprint\",\"contract\":\"next.segmented-media-send-lease-payload.v1\",\"decision\":\"ALLOW\",\"decisionRevision\":1,\"issuedAtMs\":${nowMs - 1},\"issuer\":\"NEXT_MEDIA_CONTROL\",\"purpose\":\"PREPARED_SEGMENTED_MEDIA_EVENT_SEND\",\"retryAfterMs\":0,\"serverNowMs\":${nowMs - 1}}"
    val allowEnvelope = signed("next.segmented-media-send-lease-response.v1", "YZC-NEXT-WP08-SEND-LEASE-v1", allowPayload, allowKeyPair, allowKeyId)
    private val outerHash = Base64.getEncoder().withoutPadding().encodeToString(ByteArray(32) { 0xff.toByte() })
    private val mediaKey = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 7 })
    val eventContent = "{\"body\":\"Protected video\",\"msgtype\":\"org.yizhechat.video.segmented.v1\",\"org.yizhechat.media_resource_root\":{\"assetId\":\"asset-001\",\"contract\":\"next.media-resource-root-binding.v1\",\"manifestDigestSha256\":\"${"b".repeat(64)}\",\"mediaVersion\":1,\"operationId\":\"$operationId\",\"resourceSetDigestSha256\":\"${"a".repeat(64)}\"},\"org.yizhechat.segmented_media_binding\":{\"bindingFingerprintSha256\":\"$fingerprint\",\"contract\":\"next.segmented-media-event-binding.v1\",\"requestId\":\"$requestId\"},\"org.yizhechat.segmented_media_manifest\":{\"contract\":\"next.segmented-media-manifest-reference.v1\",\"resource\":{\"contentId\":\"content-manifest-001\",\"contract\":\"next.segmented-media-resource.v1\",\"durationUs\":0,\"independentRandomAccess\":true,\"index\":0,\"innerContextDigestSha256\":\"${"1".repeat(64)}\",\"innerProtectedLength\":4096,\"innerProtectedSha256\":\"${"2".repeat(64)}\",\"matrixEncryptedFile\":{\"hashes\":{\"sha256\":\"$outerHash\"},\"iv\":\"AAAAAAAAAAAAAAAAAAAAAA\",\"key\":{\"alg\":\"A256CTR\",\"ext\":true,\"k\":\"$mediaKey\",\"key_ops\":[\"encrypt\",\"decrypt\"],\"kty\":\"oct\"},\"url\":\"mxc://example.org/manifest001\",\"v\":\"v2\"},\"mimeType\":\"application/vnd.yizhe.segmented-media-manifest+json\",\"outerCipherLength\":4096,\"outerCipherSha256Hex\":\"${"f".repeat(64)}\",\"plainLength\":2048,\"resourceId\":\"resource-manifest-001\",\"role\":\"MANIFEST\",\"startUs\":0}}}"
    val eventDigest = sha256Hex(eventContent.encodeToByteArray())
    private val boundPayload = "{\"bindingFingerprintSha256\":\"$fingerprint\",\"contract\":\"next.segmented-media-send-bound.v1\",\"decisionRevision\":2,\"eventContentDigestSha256\":\"$eventDigest\",\"expiresAtMs\":${nowMs + 30_000},\"issuedAtMs\":$nowMs,\"issuer\":\"NEXT_MEDIA_CONTROL\",\"nonceDigestSha256\":\"${sha256Hex(nonce.encodeToByteArray())}\",\"operationId\":\"$operationId\",\"purpose\":\"PREPARED_SEGMENTED_MEDIA_EVENT_SEND\",\"requestId\":\"$requestId\",\"transactionId\":\"${transactionId.value}\"}"
    val boundEnvelope = signed("next.segmented-media-send-bound-envelope.v1", "YZC-NEXT-WP08-SEND-BOUND-v1", boundPayload, boundKeyPair, boundKeyId)
    val request = PreparedSegmentedMediaSend(operationId, requestId, transactionId, eventContent)
    val liveIdentity = LiveMediaSendIdentity("d".repeat(64), "install-001", "@alice:example.org", "DEVICE_A", 7, roomId, sha256Hex(roomId.encodeToByteArray()), "content-v3", 4, "e".repeat(64))
    val prepared = HistoricalMediaSendPrepared(operationId, requestId, transactionId.value, roomId, "asset-001", 1,
        "a".repeat(64), "b".repeat(64), "c".repeat(64), "d".repeat(64), "install-001", "@alice:example.org", "DEVICE_A",
        7, 9, "content-v3", "GLOBAL_POLICY", "rev-001", 4, "e".repeat(64), 11, 2, eventDigest, eventContent)

    fun journal(state: MediaSendJournalState = MediaSendJournalState.PREPARED) = RecordingJournal(MediaSendJournalRecord(
        1, "9".repeat(64), state, prepared,
        allowEnvelope = allowEnvelope.takeIf { state.ordinal >= MediaSendJournalState.ALLOW_VERIFIED.ordinal },
        allowEnvelopeSha256 = sha256Hex(allowEnvelope).takeIf { state.ordinal >= MediaSendJournalState.ALLOW_VERIFIED.ordinal },
        sendBoundEnvelope = boundEnvelope.takeIf { state.ordinal >= MediaSendJournalState.SEND_BOUND_VERIFIED.ordinal },
        sendBoundEnvelopeSha256 = sha256Hex(boundEnvelope).takeIf { state.ordinal >= MediaSendJournalState.SEND_BOUND_VERIFIED.ordinal }))
    fun sender(journal: RecordingJournal, queue: QueuedEncryptedRoomMessageSender?, authority: RecordingAuthority = RecordingAuthority(allowEnvelope, boundEnvelope), identities: List<LiveMediaSendIdentity> = listOf(liveIdentity)): ContractBoundNextSegmentedMediaSender {
        var identityIndex = 0
        return ContractBoundNextSegmentedMediaSender(authority, FixedRegistryRepository(registry), FixedTrustedClock(nowMs), journal,
            object : LiveMediaSendIdentityProvider { override suspend fun current(matrixUserId: String, roomId: String, expectedAccountIdHash: String) = Result.success(identities.getOrElse(identityIndex++) { identities.last() }) },
            queueResolver = { queue })
    }
    private fun signed(contract: String, domain: String, payload: String, pair: KeyPair, id: String): ByteArray {
        val sig = Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update("$domain\n$payload".encodeToByteArray()); Base64.getEncoder().encodeToString(sign()) }
        return "{\"contract\":\"$contract\",\"payload\":$payload,\"signature\":{\"algorithm\":\"SHA256withECDSA\",\"keyId\":\"$id\",\"signatureBase64\":\"$sig\"}}".encodeToByteArray()
    }
    private fun issuerKey(id: String, pair: KeyPair, status: MediaSendIssuerKeyStatus) = MediaSendIssuerKey(id, "SHA256withECDSA", "-----BEGIN PUBLIC KEY-----\n${Base64.getMimeEncoder(64, "\n".encodeToByteArray()).encodeToString(pair.public.encoded)}\n-----END PUBLIC KEY-----\n", nowMs - 10_000, nowMs + 60_000, status)
    private fun keyPair() = KeyPairGenerator.getInstance("EC").run { initialize(ECGenParameterSpec("secp256r1")); generateKeyPair() }
}

internal class RecordingAuthority(private val allow: ByteArray, private val bound: ByteArray) : MediaSendAuthorityClient {
    val calls = mutableListOf<String>(); private fun response(bytes: ByteArray) = Result.success(MediaSendAuthorityResponse(bytes, 1, 1, 2, 1))
    override suspend fun issue(operationId: String, requestId: String) = response(allow).also { calls += "issue" }
    override suspend fun bind(request: MediaSendBindRequest) = response(bound).also { calls += "bind" }
    override suspend fun recover(operationId: String, requestId: String) = response(bound).also { calls += "recover" }
    override suspend fun fetchIssuerRegistry() = response("{}".encodeToByteArray()).also { calls += "registry" }
}
private class FixedRegistryRepository(private val registry: VerifiedMediaSendIssuerRegistry) : MediaSendIssuerRegistryRepository {
    override suspend fun installSignedRegistry(rawEnvelope: ByteArray, trustedTime: MediaSendTrustedTime) = Result.success(registry)
    override suspend fun currentVerifiedRegistry(trustedTime: MediaSendTrustedTime) = Result.success(registry)
}
private class FixedTrustedClock(private val now: Long) : MediaSendTrustedClock {
    override fun requestStarted() = MediaSendClockSample(1, 1)
    override fun acceptServerAnchor(sample: MediaSendClockSample, serverIssuedAtMillis: Long) =
        Result.success(MediaSendTrustedTime(serverIssuedAtMillis, serverIssuedAtMillis))
    override fun currentTimeMillis() = Result.success(MediaSendTrustedTime(now, now))
}
internal class RecordingJournal(private var record: MediaSendJournalRecord) : MediaSendJournal {
    val states = mutableListOf(record.state)
    override suspend fun persistPrepared(prepared: HistoricalMediaSendPrepared) = Result.failure<MediaSendJournalRecord>(UnsupportedOperationException())
    override suspend fun load(operationId: String, requestId: String, transactionId: String) = Result.success(record)
    override suspend fun compareAndSet(request: MediaSendJournalCas): Result<MediaSendJournalRecord> = runCatching {
        require(request.expectedRevision == record.revision && request.expectedRecordDigestSha256 == record.recordDigestSha256 && request.expectedState == record.state)
        record = record.copy(revision = record.revision + 1, recordDigestSha256 = sha256Hex("${record.revision + 1}:${request.nextState}".encodeToByteArray()), state = request.nextState,
            allowEnvelope = request.allowEnvelope ?: record.allowEnvelope, allowEnvelopeSha256 = request.allowEnvelope?.let(::sha256Hex) ?: record.allowEnvelopeSha256,
            sendBoundEnvelope = request.sendBoundEnvelope ?: record.sendBoundEnvelope, sendBoundEnvelopeSha256 = request.sendBoundEnvelope?.let(::sha256Hex) ?: record.sendBoundEnvelopeSha256,
            matrixEventId = request.matrixEventId ?: record.matrixEventId); states += record.state; record
    }
    override suspend fun pendingRecovery(limit: Int) = Result.success(listOf(record))
}
private class RecordingQueue(private val result: Result<QueuedEncryptedRoomMessageReceipt>? = null) : QueuedEncryptedRoomMessageSender {
    val calls = mutableListOf<Pair<TransactionId, String>>()
    override suspend fun enqueueEncryptedRoomMessage(transactionId: TransactionId, canonicalContentJson: String): Result<QueuedEncryptedRoomMessageReceipt> { calls += transactionId to canonicalContentJson; return result ?: Result.success(QueuedEncryptedRoomMessageReceipt(transactionId, EventId("\$event-001"), true)) }
}
internal fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
