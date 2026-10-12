/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.mediaviewer.impl.segmented.bridge

import io.element.android.libraries.mediaviewer.impl.segmented.prototype.SegmentedMediaAuthorizationGatePrototype
import io.element.android.libraries.mediaviewer.impl.segmented.prototype.SegmentedMediaAuthorizationDecisionPrototype
import io.element.android.libraries.mediaviewer.impl.segmented.prototype.SegmentedMediaAuthorizationPurposePrototype
import io.element.android.libraries.mediaviewer.impl.segmented.prototype.SegmentedMediaReadContextPrototype
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * D2 local-only seek replacement coordinator. It owns publication, replacement epochs and durable in-process
 * receipts; callers only receive waiters, so cancellation of a UI waiter cannot cancel replacement or cleanup.
 */
internal class SegmentedSeekReplacementCoordinator(
    initialSession: SegmentedPlaybackSession,
    private val leaseIssuer: LocalSegmentLeaseIssuer,
    private val sessionPreparer: SegmentedReplacementSessionPreparer,
    private val authorizationGate: SegmentedMediaAuthorizationGatePrototype,
    private val decoderLifecycleFactory: () -> SegmentedDecoderLifecycle,
    operationDispatcher: CoroutineDispatcher,
    cleanupDispatcher: CoroutineDispatcher,
    private val freshLeaseId: () -> String,
    initialEpoch: Long = 0L,
) {
    private val lock = ReentrantLock()
    private val workScope = CoroutineScope(SupervisorJob() + operationDispatcher)
    private val cleanupScope = CoroutineScope(SupervisorJob() + cleanupDispatcher)
    private val cleanupDispatcher = cleanupDispatcher
    private val epochs = linkedMapOf<Long, EpochRecord>()
    private val receipts = linkedMapOf<Long, SegmentedReplacementTerminalReceipt>()

    private var currentPublication: PublishedSession? = PublishedSession(
        session = initialSession,
        cursor = initialSession.cursor(initialEpoch),
    )
    private var activeEpoch: Long? = null
    private var lastEpoch = initialEpoch
    private var terminating = false
    private var terminationSignal: CompletableDeferred<SegmentedCoordinatorTerminationReceipt>? = null

    fun replace(targetTimeUs: Long): SegmentedReplacementStart {
        val accepted = lock.withLock {
            if (terminating) {
                return SegmentedReplacementStart.Rejected(SegmentedReplacementStartRejection.TERMINATED)
            }
            if (activeEpoch != null) {
                return SegmentedReplacementStart.Rejected(SegmentedReplacementStartRejection.BUSY)
            }
            val old = currentPublication
                ?: return SegmentedReplacementStart.Rejected(SegmentedReplacementStartRejection.NO_PUBLISHED_SESSION)
            val intent = try {
                old.session.planOneFragmentSeek(targetTimeUs, authorizationGate)
            } catch (failure: Throwable) {
                return SegmentedReplacementStart.Rejected(
                    SegmentedReplacementStartRejection.SEEK_REJECTED,
                    failure,
                )
            }
            val epoch = try {
                Math.addExact(lastEpoch, 1L)
            } catch (failure: ArithmeticException) {
                return SegmentedReplacementStart.Rejected(
                    SegmentedReplacementStartRejection.SEEK_REJECTED,
                    failure,
                )
            }
            val record = EpochRecord(
                epoch = epoch,
                oldSession = old.session,
                oldCursor = old.cursor,
                intent = intent,
                oldGeneration = intent.sourceContext.generation,
            )
            // This is the sole acceptance linearization point: old discovery is removed with epoch acquisition.
            currentPublication = null
            activeEpoch = epoch
            lastEpoch = epoch
            epochs[epoch] = record
            record
        }
        workScope.launch { execute(accepted) }
        val externalWaiter: Deferred<SegmentedReplacementTerminalReceipt> = workScope.async {
            accepted.terminalSignal.await()
        }
        return SegmentedReplacementStart.Accepted(accepted.epoch, externalWaiter)
    }

    fun advance(expectedCursor: SegmentedFragmentCursor): SegmentedAdvanceStart {
        val accepted = lock.withLock {
            if (terminating) {
                return SegmentedAdvanceStart.Rejected(SegmentedReplacementStartRejection.TERMINATED)
            }
            if (activeEpoch != null) {
                return SegmentedAdvanceStart.Rejected(SegmentedReplacementStartRejection.BUSY)
            }
            val old = currentPublication
                ?: return SegmentedAdvanceStart.Rejected(SegmentedReplacementStartRejection.NO_PUBLISHED_SESSION)
            if (old.cursor != expectedCursor) {
                return SegmentedAdvanceStart.Rejected(SegmentedReplacementStartRejection.STALE_SOURCE)
            }
            val intent = try {
                old.session.planNextFragment(authorizationGate) ?: return SegmentedAdvanceStart.FinalFragment
            } catch (failure: Throwable) {
                return SegmentedAdvanceStart.Rejected(
                    SegmentedReplacementStartRejection.SEEK_REJECTED,
                    failure,
                )
            }
            val epoch = try {
                Math.addExact(lastEpoch, 1L)
            } catch (failure: ArithmeticException) {
                return SegmentedAdvanceStart.Rejected(
                    SegmentedReplacementStartRejection.SEEK_REJECTED,
                    failure,
                )
            }
            val record = EpochRecord(
                epoch = epoch,
                oldSession = old.session,
                oldCursor = old.cursor,
                intent = intent,
                oldGeneration = intent.sourceContext.generation,
            )
            currentPublication = null
            activeEpoch = epoch
            lastEpoch = epoch
            epochs[epoch] = record
            record
        }
        workScope.launch { execute(accepted) }
        val externalWaiter: Deferred<SegmentedReplacementTerminalReceipt> = workScope.async {
            accepted.terminalSignal.await()
        }
        return SegmentedAdvanceStart.Accepted(accepted.epoch, externalWaiter)
    }

    fun terminalReceipt(epoch: Long): SegmentedReplacementTerminalReceipt? = lock.withLock { receipts[epoch] }

    fun auditState(epoch: Long): ReplacementEpochAuditState = lock.withLock {
        val record = checkNotNull(epochs[epoch]) { "Unknown replacement epoch $epoch." }
        ReplacementEpochAuditState(
            epoch = epoch,
            phase = record.phase,
            oldGeneration = record.oldGeneration,
            candidate = record.ownership?.auditState(),
            coordinatorOwnsUnpublishedSession = record.coordinatorCandidate != null,
            terminalReceipt = receipts[epoch],
        )
    }

    fun pendingEpochs(): Set<Long> = lock.withLock {
        epochs.keys.filterTo(linkedSetOf()) { it !in receipts }
    }

    suspend fun awaitTerminal(epoch: Long): SegmentedReplacementTerminalReceipt {
        val signal = lock.withLock {
            receipts[epoch]?.let { return it }
            checkNotNull(epochs[epoch]) { "Unknown replacement epoch $epoch." }.terminalSignal
        }
        return signal.await()
    }

    suspend fun terminateAndAwait(): SegmentedCoordinatorTerminationReceipt {
        val signal = lock.withLock {
            terminationSignal?.let { return@withLock it }
            terminating = true
            val active = activeEpoch?.let { epoch -> epochs[epoch] }
            val published = currentPublication?.session
            currentPublication = null
            CompletableDeferred<SegmentedCoordinatorTerminationReceipt>().also { created ->
                terminationSignal = created
                cleanupScope.launch {
                    val failures = mutableListOf<SegmentedReplacementFailure>()
                    val activeReceipt = active?.terminalSignal?.await()
                    val publishedReceipt = if (published != null) {
                        try {
                            published.closeAndAwaitQuiescence(SegmentedPlaybackCloseCause.OWNER_EXIT)
                        } catch (failure: Throwable) {
                            failures += SegmentedReplacementFailure(
                                SegmentedReplacementFailurePhase.OWNER_CLOSE,
                                failure,
                            )
                            null
                        }
                    } else {
                        null
                    }
                    publishedReceipt?.failures?.forEach { failure ->
                        failures += SegmentedReplacementFailure(
                            SegmentedReplacementFailurePhase.OWNER_CLOSE,
                            failure,
                        )
                    }
                    created.complete(
                        SegmentedCoordinatorTerminationReceipt(
                            activeReplacementReceipt = activeReceipt,
                            publishedSessionCloseReceipt = publishedReceipt,
                            failures = failures,
                        )
                    )
                }
            }
        }
        return signal.await()
    }

    private suspend fun execute(record: EpochRecord) {
        val failures = mutableListOf<SegmentedReplacementFailure>()
        record.updatePhase("REPLACING_QUIESCING")
        val oldReceipt = try {
            record.oldSession.closeAndAwaitQuiescence(SegmentedPlaybackCloseCause.SEEK_REPLACED)
        } catch (failure: Throwable) {
            failures += SegmentedReplacementFailure(SegmentedReplacementFailurePhase.OLD_CLOSE, failure)
            finish(record, null, null, failures)
            return
        }
        lock.withLock { record.oldCloseReceipt = oldReceipt }
        oldReceipt.failures.forEach { failure ->
            failures += SegmentedReplacementFailure(SegmentedReplacementFailurePhase.OLD_CLOSE, failure)
        }
        if (oldReceipt.cause != SegmentedPlaybackCloseCause.SEEK_REPLACED) {
            failures += SegmentedReplacementFailure(
                SegmentedReplacementFailurePhase.OLD_CLOSE_CAUSE,
                IllegalStateException("Old session closed for ${oldReceipt.cause}, not SEEK_REPLACED."),
            )
        }
        if (failures.isNotEmpty()) {
            finish(record, null, null, failures)
            return
        }

        val generation = try {
            Math.addExact(record.oldGeneration, 1L)
        } catch (failure: ArithmeticException) {
            failures += SegmentedReplacementFailure(SegmentedReplacementFailurePhase.GENERATION, failure)
            finish(record, null, null, failures)
            return
        }
        lock.withLock { record.generation = generation }
        val context = try {
            SegmentedMediaReadContextPrototype(
                ownerToken = record.intent.sourceContext.ownerToken,
                leaseId = freshLeaseId().also { check(it.isNotBlank()) { "Fresh lease id must not be blank." } },
                generation = generation,
            )
        } catch (failure: Throwable) {
            failures += SegmentedReplacementFailure(SegmentedReplacementFailurePhase.GENERATION, failure)
            finish(record, null, null, failures)
            return
        }
        val ownership = ReplacementCandidateOwnershipCell(record.epoch, cleanupDispatcher)
        // Registration precedes the first issuer instruction and therefore every possible issuer suspension.
        lock.withLock {
            record.ownership = ownership
            record.phase = "REPLACING_PREPARING_ISSUE"
        }

        val acquisitionFailure = try {
            when (val outcome = leaseIssuer.issueExactInto(ownership, record.intent, context)) {
                CandidateLeaseAcquisitionOutcome.Acquired -> null
                is CandidateLeaseAcquisitionOutcome.Rejected -> outcome.failure
            }
        } catch (failure: Throwable) {
            failure
        }
        if (acquisitionFailure != null) {
            failures += SegmentedReplacementFailure(
                SegmentedReplacementFailurePhase.LEASE_ISSUE,
                acquisitionFailure,
            )
            cleanupCellAndFinish(record, ownership, failures)
            return
        }

        record.updatePhase("REPLACING_PREPARING_D1")
        val prepareFailure = try {
            sessionPreparer.prepareInto(
                SegmentedReplacementPrepareRequest(
                    ownership = ownership,
                    intent = record.intent,
                    context = context,
                    authorizationGate = authorizationGate,
                    decoderLifecycle = decoderLifecycleFactory(),
                )
            )
            null
        } catch (failure: Throwable) {
            failure
        }
        if (prepareFailure != null) {
            failures += SegmentedReplacementFailure(SegmentedReplacementFailurePhase.PREPARE, prepareFailure)
            cleanupCellAndFinish(record, ownership, failures)
            return
        }

        if (
            authorizationGate.check(context, SegmentedMediaAuthorizationPurposePrototype.PUBLICATION) !=
            SegmentedMediaAuthorizationDecisionPrototype.AUTHORIZED
        ) {
            failures += SegmentedReplacementFailure(
                SegmentedReplacementFailurePhase.AUTHORITY_RECHECK,
                SecurityException("Current segmented-media authority rejected final publication."),
            )
            cleanupCellAndFinish(record, ownership, failures)
            return
        }

        if (lock.withLock { terminating || activeEpoch != record.epoch }) {
            cleanupCellAndFinish(record, ownership, failures)
            return
        }
        val candidate = try {
            ownership.takeSession()
        } catch (failure: Throwable) {
            failures += SegmentedReplacementFailure(
                SegmentedReplacementFailurePhase.CANDIDATE_TRANSFER,
                failure,
            )
            cleanupCellAndFinish(record, ownership, failures)
            return
        }
        lock.withLock {
            record.coordinatorCandidate = candidate
            record.phase = "REPLACING_PRE_PUBLICATION"
        }
        val candidateCursor = try {
            candidate.cursor(record.epoch)
        } catch (failure: Throwable) {
            failures += SegmentedReplacementFailure(SegmentedReplacementFailurePhase.PUBLICATION, failure)
            closeUnpublishedAndFinish(record, candidate, failures)
            return
        }

        val published = lock.withLock {
            val exactCandidate = candidateCursor.manifestIdentity == record.intent.manifestIdentity &&
                candidateCursor.segmentIndex == record.intent.segment.index &&
                candidateCursor.publicationEpoch == record.epoch &&
                candidateCursor.readGeneration == generation &&
                candidateCursor.segmentStartUs == record.intent.segment.startUs &&
                candidateCursor.segmentEndUsExclusive == Math.addExact(
                    record.intent.segment.startUs,
                    record.intent.segment.durationUs,
                ) &&
                candidateCursor.assetDurationUs == record.intent.validatedManifest.manifest.durationUs
            if (
                !terminating &&
                currentPublication == null &&
                activeEpoch == record.epoch &&
                record.coordinatorCandidate === candidate &&
                record.oldCursor.readGeneration == record.oldGeneration &&
                exactCandidate
            ) {
                val publication = SegmentedReplacementPublication(
                    cursor = candidateCursor,
                    uri = candidate.uri,
                    dataSourceFactory = candidate.dataSourceFactory(),
                    randomAccessStartUs = record.intent.randomAccessStartUs,
                )
                currentPublication = PublishedSession(candidate, candidateCursor)
                record.coordinatorCandidate = null
                val receipt = SegmentedReplacementTerminalReceipt(
                    epoch = record.epoch,
                    outcome = SegmentedReplacementOutcome.PUBLISHED,
                    generation = generation,
                    oldCloseReceipt = oldReceipt,
                    candidateCleanupReceipt = null,
                    unpublishedSessionCloseReceipt = null,
                    publication = publication,
                    failures = emptyList(),
                )
                insertTerminalLocked(record, receipt)
                receipt
            } else {
                null
            }
        }
        if (published != null) {
            record.terminalSignal.complete(published)
            return
        }
        if (!lock.withLock { terminating }) {
            failures += SegmentedReplacementFailure(
                SegmentedReplacementFailurePhase.PUBLICATION,
                IllegalStateException("Replacement epoch lost publication ownership."),
            )
        }
        closeUnpublishedAndFinish(record, candidate, failures)
    }

    private fun cleanupCellAndFinish(
        record: EpochRecord,
        ownership: ReplacementCandidateOwnershipCell,
        failures: List<SegmentedReplacementFailure>,
    ) {
        record.updatePhase("CANDIDATE_CLEANUP_UNKNOWN_PENDING")
        cleanupScope.launch {
            val cleanupReceipt = ownership.closeAndAwaitRelease()
            val allFailures = failures.toMutableList()
            cleanupReceipt.failures.forEach { failure ->
                allFailures += SegmentedReplacementFailure(
                    SegmentedReplacementFailurePhase.CANDIDATE_CLEANUP,
                    failure,
                )
            }
            cleanupReceipt.sessionCloseReceipt?.failures?.forEach { failure ->
                allFailures += SegmentedReplacementFailure(
                    SegmentedReplacementFailurePhase.CANDIDATE_CLEANUP,
                    failure,
                )
            }
            finish(record, cleanupReceipt, null, allFailures)
        }
    }

    private fun closeUnpublishedAndFinish(
        record: EpochRecord,
        candidate: SegmentedPlaybackSession,
        failures: List<SegmentedReplacementFailure>,
    ) {
        record.updatePhase("UNPUBLISHED_SESSION_CLEANUP_UNKNOWN_PENDING")
        cleanupScope.launch {
            val allFailures = failures.toMutableList()
            val closeReceipt = try {
                candidate.closeAndAwaitQuiescence(SegmentedPlaybackCloseCause.OWNER_EXIT)
            } catch (failure: Throwable) {
                allFailures += SegmentedReplacementFailure(
                    SegmentedReplacementFailurePhase.CANDIDATE_CLEANUP,
                    failure,
                )
                null
            }
            closeReceipt?.failures?.forEach { failure ->
                allFailures += SegmentedReplacementFailure(
                    SegmentedReplacementFailurePhase.CANDIDATE_CLEANUP,
                    failure,
                )
            }
            lock.withLock { record.coordinatorCandidate = null }
            finish(record, null, closeReceipt, allFailures)
        }
    }

    private fun finish(
        record: EpochRecord,
        candidateCleanupReceipt: CandidateCleanupReceipt?,
        unpublishedSessionCloseReceipt: SegmentedPlaybackCloseReceipt?,
        failures: List<SegmentedReplacementFailure>,
    ) {
        val receipt = lock.withLock {
            receipts[record.epoch] ?: SegmentedReplacementTerminalReceipt(
                epoch = record.epoch,
                outcome = if (terminating) SegmentedReplacementOutcome.TERMINATED else SegmentedReplacementOutcome.REJECTED,
                generation = record.generation,
                oldCloseReceipt = record.oldCloseReceipt,
                candidateCleanupReceipt = candidateCleanupReceipt,
                unpublishedSessionCloseReceipt = unpublishedSessionCloseReceipt,
                publication = null,
                failures = failures.toList(),
            ).also { insertTerminalLocked(record, it) }
        }
        record.terminalSignal.complete(receipt)
    }

    private fun insertTerminalLocked(record: EpochRecord, receipt: SegmentedReplacementTerminalReceipt) {
        check(record.epoch !in receipts) { "Replacement epoch ${record.epoch} already has a terminal receipt." }
        receipts[record.epoch] = receipt
        record.phase = "TERMINAL_${receipt.outcome}"
        if (activeEpoch == record.epoch) activeEpoch = null
    }

    private inner class EpochRecord(
        val epoch: Long,
        val oldSession: SegmentedPlaybackSession,
        val oldCursor: SegmentedFragmentCursor,
        val intent: SegmentedSeekIntent,
        val oldGeneration: Long,
    ) {
        val terminalSignal = CompletableDeferred<SegmentedReplacementTerminalReceipt>()
        var phase: String = "ACCEPTED"
        var generation: Long? = null
        var oldCloseReceipt: SegmentedPlaybackCloseReceipt? = null
        var ownership: ReplacementCandidateOwnershipCell? = null
        var coordinatorCandidate: SegmentedPlaybackSession? = null

        fun updatePhase(value: String) {
            lock.withLock { phase = value }
        }
    }

    private data class PublishedSession(
        val session: SegmentedPlaybackSession,
        val cursor: SegmentedFragmentCursor,
    )
}
