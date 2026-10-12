/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.mediaupload.impl

import dev.zacsweers.metro.ContributesBinding
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.QueuedEncryptedRoomMessageSender
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.mediaupload.api.PreparedSegmentedMediaSend
import io.element.android.libraries.mediaupload.api.SegmentedMediaSendOutcome
import io.element.android.security.contentprotection.api.LiveMediaSendIdentityProvider
import io.element.android.security.contentprotection.api.MediaSendAuthorityClient
import io.element.android.security.contentprotection.api.MediaSendBindRequest
import io.element.android.security.contentprotection.api.MediaSendIssuerRegistryRepository
import io.element.android.security.contentprotection.api.MediaSendJournal
import io.element.android.security.contentprotection.api.MediaSendJournalCas
import io.element.android.security.contentprotection.api.MediaSendJournalRecord
import io.element.android.security.contentprotection.api.MediaSendJournalState
import io.element.android.security.contentprotection.api.MediaSendTrustedClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.security.MessageDigest

interface NextSegmentedMediaSender {
    suspend fun send(
        timeline: Timeline,
        roomId: String,
        request: PreparedSegmentedMediaSend,
    ): Result<SegmentedMediaSendOutcome>
}

/**
 * The one bounded WP08 product sender candidate.
 *
 * This class is a caller, not another preparer. It consumes a fully PREPARED operation, verifies
 * signed Contract A ALLOW and SEND_BOUND envelopes against a CP-verified issuer registry, and then
 * invokes only Contract B's caller-transaction queued E2EE boundary. It never calls sendVideo or
 * sendRaw and contains no local enable flag.
 */
@ContributesBinding(SessionScope::class)
class ContractBoundNextSegmentedMediaSender(
    private val authorityClient: MediaSendAuthorityClient,
    private val registryRepository: MediaSendIssuerRegistryRepository,
    private val trustedClock: MediaSendTrustedClock,
    private val journal: MediaSendJournal,
    private val liveIdentityProvider: LiveMediaSendIdentityProvider,
) : NextSegmentedMediaSender {
    private var verifier = NextSegmentedMediaContractVerifier()
    private var queueResolver: (Timeline) -> QueuedEncryptedRoomMessageSender? = { it as? QueuedEncryptedRoomMessageSender }

    internal constructor(
        authorityClient: MediaSendAuthorityClient,
        registryRepository: MediaSendIssuerRegistryRepository,
        trustedClock: MediaSendTrustedClock,
        journal: MediaSendJournal,
        liveIdentityProvider: LiveMediaSendIdentityProvider,
        verifier: NextSegmentedMediaContractVerifier = NextSegmentedMediaContractVerifier(),
        queueResolver: (Timeline) -> QueuedEncryptedRoomMessageSender? = { it as? QueuedEncryptedRoomMessageSender },
    ) : this(authorityClient, registryRepository, trustedClock, journal, liveIdentityProvider) {
        this.verifier = verifier
        this.queueResolver = queueResolver
    }

    override suspend fun send(
        timeline: Timeline,
        roomId: String,
        request: PreparedSegmentedMediaSend,
    ): Result<SegmentedMediaSendOutcome> = try {
        Result.success(sendOrThrow(timeline, roomId, request))
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        Result.failure(failure)
    }

    private suspend fun sendOrThrow(
        timeline: Timeline,
        roomId: String,
        request: PreparedSegmentedMediaSend,
    ): SegmentedMediaSendOutcome {
        validateRequest(request)
        val queue = queueResolver(timeline) ?: throw NextSegmentedMediaSendUnavailableException(MATRIX_QUEUE_UNAVAILABLE)

        val loaded = journal.load(
            request.operationId,
            request.requestId,
            request.transactionId.value,
        ).getOrThrow()
        if (loaded.state == MediaSendJournalState.MATRIX_IN_FLIGHT || loaded.state == MediaSendJournalState.MATRIX_UNCERTAIN) {
            return reconcileOnly(timeline, request, loaded)
        }
        if (loaded.state == MediaSendJournalState.MATRIX_ACCEPTED) {
            return SegmentedMediaSendOutcome.SentPendingObservation(
                request.transactionId,
                EventId(requireNotNull(loaded.matrixEventId)),
            )
        }
        require(loaded.state == MediaSendJournalState.PREPARED)
        val prepared = loaded.toNextSnapshot()
        verifier.requirePreparedRequestMatches(
            snapshot = prepared,
            operationId = request.operationId,
            requestId = request.requestId,
            transactionId = request.transactionId.value,
            roomId = roomId,
            eventContentDigestSha256 = request.canonicalEventContentJson.sha256Hex(),
        )
        require(request.canonicalEventContentJson == loaded.prepared.canonicalEventContentJson)
        val initialLive = liveIdentityProvider.current(
            loaded.prepared.matrixUserId, roomId, loaded.prepared.accountIdHash,
        ).getOrThrow()
        val registry = refreshRegistry()
        val issueTime = trustedClock.currentTimeMillis().getOrThrow()
        val allowBytes = authorityClient.issue(request.operationId, request.requestId).getOrThrow().rawBody
        val allow = verifier.verifyAllow(
            responseBytes = allowBytes,
            operationId = request.operationId,
            requestId = request.requestId,
            roomId = roomId,
            registry = registry,
            trustedTime = issueTime,
        )
        verifier.requireAllowMatchesPrepared(allow, prepared, initialLive, roomId)
        val event = verifier.validateEventContent(request.canonicalEventContentJson, allow)
        verifier.requireEventMatchesPrepared(event, prepared)
        val allowRecord = cas(
            loaded, MediaSendJournalState.ALLOW_VERIFIED, allowEnvelope = allowBytes,
        )

        val boundBytes = authorityClient.bind(
            MediaSendBindRequest(
                operationId = request.operationId,
                requestId = request.requestId,
                nonce = allow.binding.nonce,
                bindingFingerprintSha256 = allow.bindingFingerprintSha256,
                transactionId = request.transactionId.value,
                eventContentDigestSha256 = event.eventContentDigestSha256,
            )
        ).getOrThrow().rawBody
        val boundRegistry = currentRegistry()
        val boundTime = trustedClock.currentTimeMillis().getOrThrow()
        val bound = verifier.verifySendBound(
            responseBytes = boundBytes,
            allow = allow,
            transactionId = request.transactionId.value,
            eventContentDigestSha256 = event.eventContentDigestSha256,
            registry = boundRegistry,
            trustedTime = boundTime,
        )
        val boundRecord = cas(
            allowRecord, MediaSendJournalState.SEND_BOUND_VERIFIED, sendBoundEnvelope = boundBytes,
        )

        // Durable cross-process fence is committed and fsynced before any Matrix side effect.
        val fencedRecord = cas(boundRecord, MediaSendJournalState.MATRIX_IN_FLIGHT)

        // All remaining mutable/blocking reads happen before the final pure predicate.
        val current = journal.load(
            request.operationId,
            request.requestId,
            request.transactionId.value,
        ).getOrThrow().toNextSnapshot()
        require(current.journalRecord.revision == fencedRecord.revision)
        val finalLive = liveIdentityProvider.current(
            loaded.prepared.matrixUserId, roomId, loaded.prepared.accountIdHash,
        ).getOrThrow()
        val queueRegistry = currentRegistry()
        val queueTime = trustedClock.currentTimeMillis().getOrThrow()
        val queueAuthority = verifier.revalidateQueueAuthority(
            allowResponseBytes = allowBytes,
            boundResponseBytes = boundBytes,
            initial = prepared,
            current = current,
            initialLiveIdentity = initialLive,
            currentLiveIdentity = finalLive,
            operationId = request.operationId,
            requestId = request.requestId,
            transactionId = request.transactionId.value,
            roomId = roomId,
            canonicalEventContentJson = request.canonicalEventContentJson,
            registry = queueRegistry,
            trustedTime = queueTime,
            minimumRegistryRevision = maxOf(registry.revision, boundRegistry.revision),
            minimumTrustedLowerBoundMillis = maxOf(issueTime.lowerBoundMillis, boundTime.lowerBoundMillis),
        )

        // No suspend/blocking authority call may be inserted between the predicate above and this queue call.
        val queuedResult = try {
            queue.enqueueEncryptedRoomMessage(
                transactionId = request.transactionId,
                canonicalContentJson = queueAuthority.event.canonicalContentJson,
            )
        } catch (cancellation: CancellationException) {
            withContext(NonCancellable) { markUncertain(fencedRecord) }
            throw cancellation
        } catch (_: Throwable) {
            markUncertain(fencedRecord)
            return SegmentedMediaSendOutcome.Uncertain(request.transactionId)
        }
        val queued = queuedResult.getOrElse {
            markUncertain(fencedRecord)
            return SegmentedMediaSendOutcome.Uncertain(request.transactionId)
        }
        if (
            queued.transactionId != request.transactionId ||
            !queued.localEchoObserved ||
            !queued.eventId.value.isContractBEventId()
        ) {
            markUncertain(fencedRecord)
            return SegmentedMediaSendOutcome.Uncertain(request.transactionId)
        }
        runCatching {
            cas(fencedRecord, MediaSendJournalState.MATRIX_ACCEPTED, matrixEventId = queued.eventId.value)
        }.getOrElse { return SegmentedMediaSendOutcome.Uncertain(request.transactionId) }
        return SegmentedMediaSendOutcome.SentPendingObservation(request.transactionId, EventId(queued.eventId.value))
    }

    /** Recovery is observation-only. It never calls the Matrix enqueue boundary. */
    private suspend fun reconcileOnly(
        timeline: Timeline,
        request: PreparedSegmentedMediaSend,
        record: MediaSendJournalRecord,
    ): SegmentedMediaSendOutcome {
        val recovery = runCatching { authorityClient.recover(request.operationId, request.requestId).getOrThrow() }
            .getOrElse { return SegmentedMediaSendOutcome.Uncertain(request.transactionId) }
        if (recovery.rawBody.isEmpty()) return SegmentedMediaSendOutcome.Uncertain(request.transactionId)
        val observed = timeline.timelineItems.first().asSequence()
            .filterIsInstance<MatrixTimelineItem.Event>()
            .firstOrNull { it.transactionId == request.transactionId && it.eventId?.value?.isContractBEventId() == true }
            ?: return SegmentedMediaSendOutcome.Uncertain(request.transactionId)
        require(record.state == MediaSendJournalState.MATRIX_IN_FLIGHT || record.state == MediaSendJournalState.MATRIX_UNCERTAIN)
        requireNotNull(observed.eventId)

        // The current typed Timeline surface exposes transaction/event IDs and sender metadata, but
        // not the exact room identity plus canonical raw event content needed to prove the bound
        // digest. Contract A recovery also has no frozen, locally verified acceptance envelope yet.
        // A matching transaction/event ID is therefore only a hint and can never advance the
        // durable journal. Recovery remains deliberately open and fail-closed until both proofs
        // exist; in particular this path contains no enqueue and no MATRIX_ACCEPTED CAS.
        return SegmentedMediaSendOutcome.Uncertain(request.transactionId)
    }

    private suspend fun refreshRegistry(): VerifiedNextMediaSendIssuerRegistry {
        val response = authorityClient.fetchIssuerRegistry().getOrThrow()
        val now = trustedClock.currentTimeMillis().getOrThrow()
        return registryRepository.installSignedRegistry(response.rawBody, now).getOrThrow().toNextRegistry()
    }

    private suspend fun currentRegistry(): VerifiedNextMediaSendIssuerRegistry {
        val now = trustedClock.currentTimeMillis().getOrThrow()
        return registryRepository.currentVerifiedRegistry(now).getOrThrow().toNextRegistry()
    }

    private suspend fun cas(
        current: MediaSendJournalRecord,
        nextState: MediaSendJournalState,
        allowEnvelope: ByteArray? = null,
        sendBoundEnvelope: ByteArray? = null,
        matrixEventId: String? = null,
    ): MediaSendJournalRecord = journal.compareAndSet(
        MediaSendJournalCas(
            operationId = current.prepared.operationId,
            requestId = current.prepared.requestId,
            transactionId = current.prepared.transactionId,
            expectedRevision = current.revision,
            expectedRecordDigestSha256 = current.recordDigestSha256,
            expectedState = current.state,
            nextState = nextState,
            allowEnvelope = allowEnvelope,
            sendBoundEnvelope = sendBoundEnvelope,
            matrixEventId = matrixEventId,
        )
    ).getOrThrow()

    private suspend fun markUncertain(fenced: MediaSendJournalRecord) {
        runCatching { cas(fenced, MediaSendJournalState.MATRIX_UNCERTAIN) }
    }

    private fun validateRequest(request: PreparedSegmentedMediaSend) {
        require(request.operationId.isInternalId())
        require(request.requestId.isInternalId())
        require(request.transactionId.value.isInternalId())
        require(request.canonicalEventContentJson.encodeToByteArray().size in 1..MAX_EVENT_CONTENT_BYTES)
    }

    companion object {
        internal const val ISSUE_PATH = "/next/v1/media/send-leases:issue"
        internal const val ISSUER_REGISTRY_PATH = "/next/v1/media/send-issuer-keys"
        internal const val CHALLENGE_PATH = "/next/v1/media/send-challenges:issue"
        internal const val MATRIX_QUEUE_UNAVAILABLE = "WP08_MATRIX_CALLER_TRANSACTION_QUEUE_UNAVAILABLE"

        internal fun bindPath(operationId: String, requestId: String): String {
            require(operationId.isInternalId() && requestId.isInternalId())
            return "/next/v1/media/send-leases/$operationId/$requestId:bind-send"
        }

        internal fun recoveryPath(operationId: String, requestId: String): String {
            require(operationId.isInternalId() && requestId.isInternalId())
            return "/next/v1/media/send-leases/$operationId/$requestId"
        }

        internal fun issueBody(operationId: String, requestId: String): String {
            require(operationId.isInternalId() && requestId.isInternalId())
            return "{\"contract\":\"next.segmented-media-send-lease-request.v1\",\"operationId\":${operationId.jsonString()}," +
                "\"requestId\":${requestId.jsonString()}}"
        }

        internal fun bindBody(
            operationId: String,
            requestId: String,
            nonce: String,
            fingerprint: String,
            transactionId: String,
            eventContentDigest: String,
        ): String {
            require(listOf(operationId, requestId, nonce, transactionId).all(String::isInternalId))
            require(fingerprint.isSha256() && eventContentDigest.isSha256())
            return buildString {
                append("{\"bindingFingerprintSha256\":${fingerprint.jsonString()}")
                append(",\"contract\":\"next.segmented-media-bind-send.v1\"")
                append(",\"eventContentDigestSha256\":${eventContentDigest.jsonString()}")
                append(",\"nonce\":${nonce.jsonString()}")
                append(",\"operationId\":${operationId.jsonString()}")
                append(",\"requestId\":${requestId.jsonString()}")
                append(",\"transactionId\":${transactionId.jsonString()}}")
            }
        }
    }
}

/** Explicit fail-closed implementation retained for tests/diagnostics; production DI binds the contract sender above. */
internal class UnavailableNextSegmentedMediaSender : NextSegmentedMediaSender {
    override suspend fun send(
        timeline: Timeline,
        roomId: String,
        request: PreparedSegmentedMediaSend,
    ): Result<SegmentedMediaSendOutcome> = Result.failure(
        NextSegmentedMediaSendUnavailableException("WP08_PRODUCTION_PREREQUISITES_UNAVAILABLE"),
    )
}

internal class NextSegmentedMediaSendUnavailableException(val code: String) : SecurityException(code)

private fun String.isInternalId(): Boolean = length in 1..160 && matches(INTERNAL_ID)
private fun String.isSha256(): Boolean = matches(SHA256_HEX)
private fun String.sha256Hex(): String = MessageDigest.getInstance("SHA-256")
    .digest(encodeToByteArray())
    .joinToString("") { "%02x".format(it) }
private fun String.isContractBEventId(): Boolean =
    encodeToByteArray().size in 2..255 && startsWith('$') && none { it.isWhitespace() || it.code < 0x20 || it.code == 0x7F }

private fun String.jsonString(): String = buildString(length + 2) {
    append('"')
    this@jsonString.forEach { character ->
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
        }
    }
    append('"')
}

private const val MAX_EVENT_CONTENT_BYTES = 1_048_576
private val INTERNAL_ID = Regex("^[A-Za-z0-9._:-]{1,160}$")
private val SHA256_HEX = Regex("^[0-9a-f]{64}$")
