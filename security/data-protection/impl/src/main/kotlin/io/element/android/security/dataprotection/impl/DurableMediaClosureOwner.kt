/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.security.dataprotection.impl

import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleMediaOutcome
import io.element.android.security.dataprotection.api.LifecycleMediaReservation
import io.element.android.security.dataprotection.api.LifecycleMediaResourceType
import io.element.android.security.dataprotection.api.LifecycleMediaRuntimeCleanup
import io.element.android.security.dataprotection.api.LifecycleMediaSeal
import io.element.android.security.dataprotection.api.LifecycleOperation
import io.element.android.security.dataprotection.api.LifecycleOperationOwnerDescriptor
import io.element.android.security.dataprotection.api.LifecycleOperationOwnerPhase
import io.element.android.security.dataprotection.api.LifecycleOperationOwnerRole
import io.element.android.security.dataprotection.api.MEDIA_RESOURCE_CLOSURE_PROTOCOL
import io.element.android.security.dataprotection.api.ManagementControlService
import io.element.android.security.dataprotection.api.NON_TERMINAL_RESUMABLE_STATUSES
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/** Lifecycle coordinator helper. Persistence and revisions belong to its existing owner store. */
internal class DurableMediaClosureOwner(
    private val service: ManagementControlService,
    private val store: LifecycleOperationOwnerStateStore,
    private val mutation: Mutex,
    private val process: ProcessInstanceIdProvider,
    private val clock: () -> Long,
    private val retireRecoveredExternalOwner: suspend (String) -> Unit = {},
    private val operation: suspend (String) -> LifecycleOperation,
) {
    private val flush = Mutex()
    private class DeliveryLock(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val deliveries = ConcurrentHashMap<String, DeliveryLock>()
    private var afterKey: String? = null
    private var roundPending = false

    suspend fun reserve(supplied: LifecycleMediaReservation) {
        val request = supplied.copy(resources = supplied.resources.toList())
        val original = operation(request.operationId)
        check((original.lifecycleId == FullAppLifecycleId.LC_16 || original.lifecycleId == FullAppLifecycleId.LC_17) && original.subjects == request.subjects)
        check(original.authorityRevisions["media_cleanup_protocol"] == MEDIA_RESOURCE_CLOSURE_PROTOCOL)
        check(original.status in NON_TERMINAL_RESUMABLE_STATUSES && original.deadlineEpochMillis > clock())
        mutation.withLock {
            val previous = store.read(request.operationId)
            val retained = previous?.mediaClosure?.let(StoredMediaClosure::decode)
            check(retained == null || retained.reservation.subjects == request.subjects &&
                retained.reservation.operationId == request.operationId && retained.producerProcessSha256 == process.sha256)
            check(retained?.seal == null) { "Media producer is already sealed." }
            check(request.resources.none { it.recordSha256 in retained?.runtimeFacts.orEmpty() }) { "Released runtime cannot be reused." }
            val resources = (retained?.reservation?.resources.orEmpty() + request.resources)
                .distinct().sortedWith(compareBy({ it.type.name }, { it.recordSha256 }))
            val next = retained?.copy(reservation = request.copy(resources = resources))
                ?: StoredMediaClosure(request.copy(resources = resources), process.sha256)
            val record = previous ?: StoredLifecycleOperationOwnerState(
                descriptor = LifecycleOperationOwnerDescriptor(
                    original.operationId,
                    original.lifecycleId,
                    null,
                    LifecycleOperationOwnerPhase.AVAILABLE,
                    0,
                    original.updatedAtEpochMillis
                ),
                processInstanceIdSha256 = process.sha256,
                currentCapabilityIdSha256 = null,
                storeRevision = 0,
                transitions = emptyList(),
            )
            save(record, previous?.storeRevision, next)
        }
        // No creation permission escapes if either local durability or verified
        // server admission fails. A lost response leaves the same intent pending.
        service.reserveMediaResources(request)
        mutation.withLock {
            val record = requireNotNull(store.read(request.operationId))
            val retained = requireNotNull(record.mediaClosure).let(StoredMediaClosure::decode)
            check(retained.reservation.subjects == request.subjects && retained.reservation.resources.containsAll(request.resources))
            save(record, record.storeRevision, retained.copy(admitted = retained.admitted + request.resources))
            check(retained.seal == null) { "Media producer sealed while admission was in flight." }
        }
    }

    suspend fun seal(operationId: String, outcome: LifecycleMediaOutcome) = mutation.withLock {
        val record = requireNotNull(store.read(operationId))
        val retained = requireNotNull(record.mediaClosure).let(StoredMediaClosure::decode)
        check(retained.reservation.operationId == operationId)
        if (retained.seal != null) {
            check(retained.seal.outcome == outcome) { "Original media outcome is immutable." }
            return@withLock
        }
        check(outcome != LifecycleMediaOutcome.SUCCEEDED || retained.producerProcessSha256 == process.sha256) {
            "A replacement process cannot infer original product success."
        }
        save(record, record.storeRevision, retained.copy(seal = LifecycleMediaSeal(
            retained.reservation,
            retained.producerProcessSha256,
            clock(),
            outcome,
        )))
    }

    suspend fun retain(fact: LifecycleMediaRuntimeCleanup) = mutation.withLock {
        val record = requireNotNull(store.read(fact.operationId))
        val retained = requireNotNull(record.mediaClosure).let(StoredMediaClosure::decode)
        val previous = retained.runtimeFacts[fact.recordSha256]
        check(previous == null || previous == fact) { "Original runtime release fact is immutable." }
        if (previous == null) {
            save(
                record,
                record.storeRevision,
            retained.copy(runtimeFacts = retained.runtimeFacts + (fact.recordSha256 to fact))
            )
        }
    }

    data class FlushResult(val hasMore: Boolean, val needsRetry: Boolean)

    /** A local seal is pending until the exact signed acknowledgements have been retained. */
    suspend fun awaitSealAcknowledgement(operationId: String) = withDeliveryLock(operationId) {
        deliverRetained(operationId)
        val current = readClosure(operationId)
        check(
            current.seal != null && current.sealAcknowledgement != null &&
            current.runtimeAcknowledgements.keys.containsAll(current.runtimeFacts.keys)
        ) {
            "Original media closure acknowledgements are still pending."
        }
    }

    suspend fun flushBatch(): FlushResult = flush.withLock {
        val scan = mutation.withLock { store.scanMediaOwners(afterKey, 32) }
        afterKey = scan.nextAfterKey.takeIf { scan.hasMore }
        if (scan.unreadableRecords > 0) {
            Timber.tag("YiZheLifecycle").w("YZMEDIA_OWNER_RECORD_UNREADABLE")
            roundPending = true
        }
        for (record in scan.records) {
            try {
                recoverExternalRead(record)
                withDeliveryLock(record.descriptor.operationId) { deliverRetained(record.descriptor.operationId) }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                roundPending = true
                reportPending(record.descriptor.operationId, failure)
            }
        }
        FlushResult(scan.hasMore, roundPending).also { if (!scan.hasMore) roundPending = false }
    }

    /** A process-bound capability cannot be resumed by its replacement process. No reader is relaunched. */
    private suspend fun recoverExternalRead(record: StoredLifecycleOperationOwnerState) {
        if (record.descriptor.role != LifecycleOperationOwnerRole.EXTERNAL_READ ||
            record.processInstanceIdSha256 == process.sha256) {
                return
            }
        val closure = record.mediaClosure?.let(StoredMediaClosure::decode) ?: return
        if (closure.producerProcessSha256 == process.sha256) return
        val original = operation(record.descriptor.operationId)
        check(
            original.authorityRevisions["external_read_handoff"] == "uid-bound-v1" &&
            original.subjects == closure.reservation.subjects
        )
        // Only our departed process's capabilities, callbacks and memory buffers have ceased.
        // The encrypted object journal separately proves file removal; third-party memory is not covered.
        for (resource in closure.reservation.resources.filter { it.type == LifecycleMediaResourceType.ANDROID_MEDIA_RUNTIME }) {
            if (resource.recordSha256 !in closure.runtimeFacts) {
                retain(LifecycleMediaRuntimeCleanup(
                    original.operationId,
                original.subjects,
                    resource.recordSha256,
                    clock(),
                    true,
                    true,
                    true
                ))
            }
        }
        if (closure.seal == null) seal(original.operationId, LifecycleMediaOutcome.CANCELLED)
        retireRecoveredExternalOwner(original.operationId)
    }

    private suspend fun deliverRetained(operationId: String) {
        var failure: Exception? = null
        val retained = readClosure(operationId)
        for ((identity, fact) in retained.runtimeFacts) {
            if (identity in retained.runtimeAcknowledgements) continue
            try {
                val acknowledgement = service.recordMediaRuntimeCleanup(fact)
                update(operationId) { current ->
                    check(current.runtimeFacts[identity] == fact)
                    current.copy(runtimeAcknowledgements = current.runtimeAcknowledgements + (identity to acknowledgement))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (pending: Exception) {
                failure = pending
                reportPending(operationId, pending)
            }
        }
        // Sealing may have been retained while runtime delivery was in flight.
        // Read the current durable value, never the old scan snapshot.
        val current = readClosure(operationId)
        val sealed = current.seal
        if (sealed != null && current.sealAcknowledgement == null) {
            try {
                val acknowledgement = service.sealMediaResources(sealed)
                update(operationId) { latest ->
                    check(latest.seal == sealed)
                    latest.copy(sealAcknowledgement = acknowledgement)
                }
                Timber.tag("YiZheLifecycle").i("YZMEDIA_SEAL_ACKNOWLEDGED operation=%s", operationId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (pending: Exception) {
                failure = pending
                reportPending(operationId, pending)
            }
        }
        failure?.let { throw it }
    }

    private suspend fun readClosure(operationId: String): StoredMediaClosure = mutation.withLock {
        val record = requireNotNull(store.read(operationId))
        requireNotNull(record.mediaClosure).let(StoredMediaClosure::decode).also {
            check(it.reservation.operationId == operationId)
        }
    }

    private suspend fun <T> withDeliveryLock(operationId: String, block: suspend () -> T): T {
        val entry = checkNotNull(deliveries.compute(operationId) { _, current ->
            (current ?: DeliveryLock()).also { it.users++ }
        })
        return try {
            entry.mutex.withLock { block() }
        } finally {
            deliveries.compute(operationId) { _, current ->
                check(current === entry)
                entry.users--
                entry.takeIf { it.users > 0 }
            }
        }
    }

    private fun reportPending(operationId: String, failure: Exception) {
        val delivery = failure as? MediaClosureDeliveryFailure
        val reason = delivery?.reasonCode ?: managementSyncFailureReasonCode(failure) ?: "RETAINED_CLOSURE_REJECTED"
        Timber.tag("YiZheLifecycle").w(
            "YZMEDIA_CLOSURE_PENDING operation=%s purpose=%s reason=%s http=%s",
            operationId,
            delivery?.purpose ?: "RETAINED_CLOSURE",
            reason,
            delivery?.httpStatusCode ?: "none"
        )
    }

    private suspend fun update(operationId: String, transform: (StoredMediaClosure) -> StoredMediaClosure) = mutation.withLock {
        val record = requireNotNull(store.read(operationId))
        val current = requireNotNull(record.mediaClosure).let(StoredMediaClosure::decode)
        check(current.reservation.operationId == operationId)
        save(record, record.storeRevision, transform(current))
    }

    private fun save(record: StoredLifecycleOperationOwnerState, expectedRevision: Long?, closure: StoredMediaClosure) {
        check(record.descriptor.operationId == closure.reservation.operationId)
        check(store.compareAndSet(
            record.descriptor.operationId,
            expectedRevision,
            record.copy(storeRevision = (expectedRevision ?: 0) + 1, mediaClosure = closure.encode())
        )) {
            "Original media owner revision was not retained."
        }
    }
}
