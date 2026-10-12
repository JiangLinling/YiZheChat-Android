/*
 * Copyright (c) 2026 YiZheChat contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.mediaviewer.impl.segmented.bridge

import io.element.android.libraries.mediaviewer.impl.segmented.prototype.SegmentedMediaResourcePrototype
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The synchronous ownership ledger for one replacement candidate.
 *
 * Suspension, return and exceptions are deliberately not ownership boundaries. The issuer adopts each lease here
 * at its issuance linearization point; preparation atomically replaces the lease inventory with a D1 session; and
 * only [takeSession] transfers that session to the coordinator.
 */
internal class ReplacementCandidateOwnershipCell(
    val epoch: Long,
    private val cleanupDispatcher: CoroutineDispatcher,
) {
    private val lock = ReentrantLock()
    private val cleanupScope = CoroutineScope(SupervisorJob() + cleanupDispatcher)
    private val leases = linkedMapOf<CandidateResourceRole, SegmentedAuthorizedResourceHandle>()
    private val adoptedRoleHistory = linkedSetOf<CandidateResourceRole>()
    private var session: SegmentedPlaybackSession? = null
    private var transferred = false
    private var cleanupStatus = CandidateCleanupStatus.NOT_STARTED
    private var cleanupResult: CompletableDeferred<CandidateCleanupReceipt>? = null

    fun adoptLease(role: CandidateResourceRole, handle: SegmentedAuthorizedResourceHandle) {
        lock.withLock {
            check(cleanupStatus == CandidateCleanupStatus.NOT_STARTED) { "Candidate cleanup has already started." }
            check(session == null && !transferred) { "Candidate leases have already changed ownership." }
            check(role !in leases) { "Candidate role $role was already adopted." }
            leases[role] = handle
            adoptedRoleHistory += role
        }
    }

    internal fun handlesForPreparation(
        expectedInit: SegmentedMediaResourcePrototype,
        expectedSegment: SegmentedMediaResourcePrototype,
    ): Pair<SegmentedAuthorizedResourceHandle, SegmentedAuthorizedResourceHandle> = lock.withLock {
        check(cleanupStatus == CandidateCleanupStatus.NOT_STARTED) { "Candidate cleanup has already started." }
        check(session == null && !transferred) { "Candidate is not in the lease-owned phase." }
        val init = checkNotNull(leases[CandidateResourceRole.INIT]) { "Candidate init lease is missing." }
        val segment = checkNotNull(leases[CandidateResourceRole.SEGMENT]) { "Candidate segment lease is missing." }
        check(leases.size == CandidateResourceRole.values().size) { "Candidate has an unexpected lease inventory." }
        check(init.descriptor == expectedInit && segment.descriptor == expectedSegment) {
            "Candidate leases do not match the exact planned resources."
        }
        check(init.lease !== segment.lease) { "Candidate resources must use distinct leases." }
        init to segment
    }

    fun installSession(factory: () -> SegmentedPlaybackSession): CandidateInstallationOutcome = lock.withLock {
        if (cleanupStatus != CandidateCleanupStatus.NOT_STARTED || session != null || transferred) {
            return@withLock CandidateInstallationOutcome.Rejected(
                IllegalStateException("Candidate is not available for session installation.")
            )
        }
        if (leases.keys != CandidateResourceRole.values().toSet()) {
            return@withLock CandidateInstallationOutcome.Rejected(
                IllegalStateException("Both exact candidate leases must be adopted before session installation.")
            )
        }
        try {
            val installed = factory()
            session = installed
            leases.clear()
            CandidateInstallationOutcome.Installed
        } catch (failure: Throwable) {
            CandidateInstallationOutcome.Rejected(failure)
        }
    }

    fun takeSession(): SegmentedPlaybackSession = lock.withLock {
        check(cleanupStatus == CandidateCleanupStatus.NOT_STARTED) { "Candidate cleanup has already started." }
        check(!transferred) { "Candidate session was already transferred." }
        val value = checkNotNull(session) { "Candidate session has not been installed." }
        session = null
        transferred = true
        value
    }

    fun auditState(): CandidateOwnershipAuditState = lock.withLock {
        CandidateOwnershipAuditState(
            phase = when {
                transferred -> CandidateOwnershipPhase.TRANSFERRED
                session != null -> CandidateOwnershipPhase.SESSION
                leases.size == CandidateResourceRole.values().size -> CandidateOwnershipPhase.FULL_LEASES
                leases.isNotEmpty() -> CandidateOwnershipPhase.PARTIAL_LEASES
                else -> CandidateOwnershipPhase.EMPTY
            },
            adoptedRoles = adoptedRoleHistory.toSet(),
            ownsSession = session != null,
            cleanupStatus = cleanupStatus,
        )
    }

    suspend fun closeAndAwaitRelease(): CandidateCleanupReceipt {
        var cleanup: CleanupStart? = null
        val result = lock.withLock {
            cleanupResult ?: CompletableDeferred<CandidateCleanupReceipt>().also { created ->
                cleanupResult = created
                cleanupStatus = CandidateCleanupStatus.UNKNOWN_PENDING
                cleanup = CleanupStart(
                    result = created,
                    session = session,
                    leases = leases.toMap(),
                    adoptedRoles = adoptedRoleHistory.toSet(),
                    transferred = transferred,
                )
            }
        }
        cleanup?.let(::startCleanup)
        return result.await()
    }

    private fun startCleanup(cleanup: CleanupStart) {
        cleanupScope.launch {
            val failures = mutableListOf<Throwable>()
            var sessionReceipt: SegmentedPlaybackCloseReceipt? = null
            if (cleanup.session != null) {
                try {
                    sessionReceipt = cleanup.session.closeAndAwaitQuiescence(SegmentedPlaybackCloseCause.OWNER_EXIT)
                } catch (failure: Throwable) {
                    failures += failure
                }
            } else {
                cleanup.leases.values.forEach { handle ->
                    try {
                        handle.lease.close()
                    } catch (failure: Throwable) {
                        failures += failure
                    }
                }
            }
            val receipt = CandidateCleanupReceipt(
                adoptedRoles = cleanup.adoptedRoles,
                sessionCloseReceipt = sessionReceipt,
                failures = failures,
                transferred = cleanup.transferred,
            )
            lock.withLock {
                if (!cleanup.transferred) {
                    session = null
                    leases.clear()
                }
                cleanupStatus = CandidateCleanupStatus.COMPLETE
            }
            cleanup.result.complete(receipt)
        }
    }

    private data class CleanupStart(
        val result: CompletableDeferred<CandidateCleanupReceipt>,
        val session: SegmentedPlaybackSession?,
        val leases: Map<CandidateResourceRole, SegmentedAuthorizedResourceHandle>,
        val adoptedRoles: Set<CandidateResourceRole>,
        val transferred: Boolean,
    )
}
