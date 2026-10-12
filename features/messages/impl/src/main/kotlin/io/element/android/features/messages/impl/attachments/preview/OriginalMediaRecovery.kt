/* Copyright (c) 2026 YiZheChat contributors.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.messages.impl.attachments.preview

import io.element.android.security.dataprotection.api.AppOperationalStateCoordinator
import io.element.android.security.dataprotection.api.FullAppLifecycleId
import io.element.android.security.dataprotection.api.LifecycleStepStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** Read-only CP projection. This is neither a Matrix submission fact nor a terminal receipt verification. */
data class OriginalMediaOperationObservation(
    val status: String,
    val deadlinePassed: Boolean,
    val terminal: Boolean,
    val pendingRequiredSteps: Int,
    val terminalReceiptPresent: Boolean,
)

enum class MediaSubmissionCheck { MISSING_IDENTITY, NOT_CONFIRMED, ACCEPTED, UNAVAILABLE, CANCEL_PENDING, DISCARDED }

data class MediaRecoveryItemCheck(
    val index: Int,
    val operation: OriginalMediaOperationObservation?,
    val submission: MediaSubmissionCheck,
)

internal suspend fun inspectOriginalMediaOperation(
    coordinator: AppOperationalStateCoordinator,
    operationId: String,
    accountIdHash: String,
    deviceIdHash: String,
    roomIdHash: String?,
    burnAfterRead: Boolean,
): OriginalMediaOperationObservation? = try {
    withTimeoutOrNull(10_000) {
        // operation() uses the existing installation-signed GET bound to this exact identity.
        // It does not start/resume an operation, submit a receipt, reconcile resources or authorize sending.
        val original = coordinator.operation(operationId)
        require(original.operationId == operationId &&
            original.lifecycleId == if (burnAfterRead) FullAppLifecycleId.LC_21 else FullAppLifecycleId.LC_16)
        require(!roomIdHash.isNullOrBlank() && original.subjects.accountIdHash == accountIdHash && original.subjects.matrixDeviceIdHash == deviceIdHash &&
            original.subjects.roomIdHash == roomIdHash)
        OriginalMediaOperationObservation(
            status = original.status.name,
            deadlinePassed = original.deadlineEpochMillis <= System.currentTimeMillis(),
            terminal = original.status.isTerminal,
            pendingRequiredSteps = original.steps.count { it.required && it.status !in
                setOf(LifecycleStepStatus.COMPLETE, LifecycleStepStatus.TERMINATED) },
            terminalReceiptPresent = original.terminalReceiptId != null,
        )
    }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null // Offline, access refusal, malformed or conflicting scope: no usable observation.
}
