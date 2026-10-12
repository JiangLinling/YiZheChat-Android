/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.messages.impl.attachments.preview

import io.element.android.libraries.matrix.api.media.MediaSubmissionFact
import io.element.android.libraries.matrix.api.media.MediaSubmissionPhase
import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleClientReceipt
import io.element.android.security.dataprotection.api.LifecycleComponent
import io.element.android.security.dataprotection.api.LifecyclePhase
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Recover actual original upload facts through the existing durable receipt outbox. No Send/start/operation replacement. */
internal suspend fun recoverOriginalMediaUploadCompletion(
    coordinator: AppOperationalStateCoordinator,
    operationId: String,
    accountIdHash: String,
    deviceIdHash: String,
    roomIdHash: String,
    fact: MediaSubmissionFact,
    applyCheckpoint: suspend () -> MediaSubmissionFact,
    now: () -> Long = System::currentTimeMillis,
): Boolean {
    if (fact.phase != MediaSubmissionPhase.ACCEPTED || fact.transactionId.isNullOrBlank() ||
        fact.transportName == null || fact.eventId == null || fact.localCleanupAtEpochMillis == null || fact.uploadOperationId != operationId) return false
    val original = coordinator.operation(operationId)
    require(original.operationId == operationId && original.lifecycleId == FullAppLifecycleId.LC_16)
    require(original.subjects.accountIdHash == accountIdHash && original.subjects.matrixDeviceIdHash == deviceIdHash &&
        original.subjects.roomIdHash == roomIdHash) { "Original upload scope changed." }
    if (original.status.isTerminal) return false
    val required = original.steps.filter { it.required }.map { it.component }
    require(required.containsAll(listOf(LifecycleComponent.ANDROID_SERVICE, LifecycleComponent.ANDROID_FRONTEND)))
    val cleanupAt = requireNotNull(fact.localCleanupAtEpochMillis)
    val expectedAppliedAt = fact.clientAppliedAtEpochMillis ?: now()
    if (cleanupAt < original.createdAtEpochMillis || cleanupAt > original.deadlineEpochMillis ||
        expectedAppliedAt > original.deadlineEpochMillis) return false
    val applied = applyCheckpoint()
    require(applied.phase == MediaSubmissionPhase.ACCEPTED && applied.transportName == fact.transportName &&
        applied.transactionId == fact.transactionId && applied.eventId == fact.eventId &&
        applied.localCleanupAtEpochMillis == cleanupAt && applied.uploadOperationId == operationId &&
        applied.contentIds == fact.contentIds)
    val appliedAt = applied.clientAppliedAtEpochMillis ?: return false
    if (appliedAt > original.deadlineEpochMillis || appliedAt < cleanupAt) return false
    val receipts = listOf(LifecycleComponent.ANDROID_SERVICE to cleanupAt, LifecycleComponent.ANDROID_FRONTEND to appliedAt).map { (component, time) ->
        LifecycleClientReceipt(operationId, component, LifecyclePhase.CLIENT_APPLIED, "${component.name}_APPLIED",
            "${component.name.lowercase().replace('_', '-')}:$operationId", time)
    }
    withContext(NonCancellable) {
        // Persist both real facts before any transport. Enqueue failure does not authorize another Send.
        coordinator.persistClientReceipts(receipts)
        coordinator.enqueueClientReceipts(receipts)
    }
    return true // Local handoff only; this is not a signed server terminal receipt.
}
